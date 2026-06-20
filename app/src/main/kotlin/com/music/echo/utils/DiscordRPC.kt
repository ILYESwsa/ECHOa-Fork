package iad1tya.echo.music.utils

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import iad1tya.echo.music.constants.DiscordPkceVerifierKey
import iad1tya.echo.music.constants.DiscordLastErrorKey
import iad1tya.echo.music.constants.DiscordRefreshTokenKey
import iad1tya.echo.music.constants.DiscordTokenKey
import iad1tya.echo.music.constants.DiscordUsernameKey
import iad1tya.echo.music.db.entities.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Discord Rich Presence via OAuth2 PKCE + Gateway WebSocket.
 *
 * Auth flow:
 *  1. buildAuthUri(verifier) → Chrome Custom Tab → Discord consent page
 *  2. User approves → deep link echomusic://discord/callback?code=...
 *  3. exchangeCode(code, verifier) → POST /oauth2/token (no client secret)
 *     → tokens stored in DataStore, never exposed in UI
 *  4. Gateway Identify uses "Bearer <accessToken>" → OP 3 Presence updates
 *
 * NOT a selfbot — uses OAuth2 Bearer token, not a raw user token.
 */
class DiscordRPC(
    private val context: Context,
    private val accessToken: String,
) {
    companion object {
        private const val TAG       = "DiscordRPC"
        const val CLIENT_ID         = "1517729881885114368"   // ← replace
        private const val REDIRECT  = "echomusic://discord/callback"
        private const val AUTH_URL  = "https://discord.com/oauth2/authorize"
        private const val TOKEN_URL = "https://discord.com/api/oauth2/token"
        private const val API_BASE  = "https://discord.com/api/v10"
        private const val WS_URL    = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val SCOPES    = "identify"

        private const val OP_DISPATCH        = 0
        private const val OP_HEARTBEAT       = 1
        private const val OP_IDENTIFY        = 2
        private const val OP_PRESENCE        = 3
        private const val OP_RECONNECT       = 7
        private const val OP_INVALID_SESSION = 9
        private const val OP_HELLO           = 10

        private const val ACTIVITY_PLAYING   = 0
        private const val ACTIVITY_LISTENING = 2
        private const val ACTIVITY_WATCHING  = 3
        private const val ACTIVITY_COMPETING = 5

        // ── PKCE ──────────────────────────────────────────────────────────────

        fun generateVerifier(): String =
            ByteArray(32).also { SecureRandom().nextBytes(it) }
                .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

        fun buildAuthUri(verifier: String): Uri {
            val challenge = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            return Uri.parse(AUTH_URL).buildUpon()
                .appendQueryParameter("client_id",             CLIENT_ID)
                .appendQueryParameter("redirect_uri",          REDIRECT)
                .appendQueryParameter("response_type",         "code")
                .appendQueryParameter("scope",                 SCOPES)
                .appendQueryParameter("code_challenge",        challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
        }

        /** Called from MainActivity.onNewIntent after OAuth callback. */
        suspend fun exchangeCode(
            context: Context,
            code: String,
            verifier: String,
        ): Pair<String, String> = withContext(Dispatchers.IO) {
            val client = OkHttpClient()
            val body = FormBody.Builder()
                .add("client_id",     CLIENT_ID)
                .add("grant_type",    "authorization_code")
                .add("code",          code)
                .add("redirect_uri",  REDIRECT)
                .add("code_verifier", verifier)
                .build()
            val resp = client.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute()
            val text = resp.body?.string() ?: error("Empty token response")
            if (!resp.isSuccessful) error("Token exchange failed ${resp.code}: $text")

            val json         = JSONObject(text)
            val accessToken  = json.getString("access_token")
            val refreshToken = json.optString("refresh_token", "")

            context.dataStore.edit { prefs ->
                prefs[DiscordTokenKey]        = accessToken
                prefs[DiscordRefreshTokenKey] = refreshToken
                prefs[DiscordPkceVerifierKey] = ""
            }

            val meResp = client.newCall(
                Request.Builder().url("$API_BASE/users/@me")
                    .header("Authorization", "Bearer $accessToken").build()
            ).execute()
            val me       = JSONObject(meResp.body?.string() ?: "{}")
            val username = me.optString("global_name").takeIf { it.isNotBlank() }
                ?: me.optString("username", "Unknown")
            context.dataStore.edit { it[DiscordUsernameKey] = username }

            Pair(accessToken, username)
        }

        /** Refresh an expired access token. Returns new token or null. */
        suspend fun refreshToken(context: Context): String? = withContext(Dispatchers.IO) {
            val refreshTok = context.dataStore[DiscordRefreshTokenKey]
            if (refreshTok.isNullOrBlank()) return@withContext null
            runCatching {
                val client = OkHttpClient()
                val body = FormBody.Builder()
                    .add("client_id",     CLIENT_ID)
                    .add("grant_type",    "refresh_token")
                    .add("refresh_token", refreshTok)
                    .build()
                val resp = client.newCall(Request.Builder().url(TOKEN_URL).post(body).build()).execute()
                val text = resp.body?.string() ?: return@runCatching null
                if (!resp.isSuccessful) return@runCatching null
                val json       = JSONObject(text)
                val newAccess  = json.getString("access_token")
                val newRefresh = json.optString("refresh_token", refreshTok)
                context.dataStore.edit { prefs ->
                    prefs[DiscordTokenKey]        = newAccess
                    prefs[DiscordRefreshTokenKey] = newRefresh
                }
                newAccess
            }.getOrNull()
        }

        fun resolveVariables(text: String, song: Song): String {
            val artist = song.artists.joinToString(", ") { it.name }
            return text
                .replace("{title}",  song.title)
                .replace("{artist}", artist)
                .replace("{album}",  song.album?.title ?: "")
                .replace("{id}",     song.id)
        }
    }

    // ── WebSocket state ───────────────────────────────────────────────────────

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var running = false
    private var heartbeatInterval = 41250L
    private var lastSequence: Int? = null
    private var heartbeatThread: Thread? = null
    private var lastPresencePayload: JSONObject? = null

    private fun recordStatus(message: String) {
        Timber.tag(TAG).d(message)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                context.dataStore.edit { it[DiscordLastErrorKey] = message }
            }
        }
    }

    private fun clearStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                context.dataStore.edit { it[DiscordLastErrorKey] = "" }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun isRpcRunning(): Boolean = running && webSocket != null

    suspend fun updateSong(
        song: Song,
        currentPlaybackTimeMillis: Long,
        playbackSpeed: Float    = 1.0f,
        useDetails: Boolean     = false,
        status: String          = "online",
        button1Text: String     = "",
        button1Visible: Boolean = true,
        button2Text: String     = "",
        button2Visible: Boolean = true,
        activityType: String    = "listening",
        activityName: String    = "",
    ): Result<Unit> = runCatching {
        val payload = buildPresencePayload(
            song, currentPlaybackTimeMillis, playbackSpeed,
            useDetails, status, button1Text, button1Visible,
            button2Text, button2Visible, activityType, activityName,
        )
        lastPresencePayload = payload
        if (!isRpcRunning()) {
            recordStatus("Connecting to Discord Gateway…")
            connectGateway(payload)
        } else {
            val sent = webSocket?.send(payload.toString()) == true
            if (sent) clearStatus() else recordStatus("Discord Gateway send failed")
        }
    }

    fun close() {
        runCatching { webSocket?.send(buildClearPresencePayload().toString()) }
    }

    fun closeRPC() {
        running = false
        runCatching { webSocket?.send(buildClearPresencePayload().toString()) }
        heartbeatThread?.interrupt()
        webSocket?.close(1000, "closeRPC")
        webSocket = null
    }

    // ── Gateway ───────────────────────────────────────────────────────────────

    private fun connectGateway(initialPresence: JSONObject) {
        running = true
        val request = Request.Builder()
            .url(WS_URL)
            .header("User-Agent", SuperProperties.userAgent)
            .build()

        httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                webSocket = ws
                Log.d(TAG, "Gateway connected")
                recordStatus("Discord Gateway connected; waiting for READY…")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(ws, text, initialPresence)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                val message = "Discord Gateway failure: ${t.message ?: "unknown error"}"
                Log.e(TAG, message)
                recordStatus(message)
                running = false
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                val message = "Discord Gateway closed $code: $reason"
                Log.d(TAG, message)
                if (code != 1000) recordStatus(message)
                running = false
            }
        })
    }

    private fun handleMessage(ws: WebSocket, text: String, initialPresence: JSONObject) {
        val json = JSONObject(text)
        val op   = json.optInt("op", -1)
        val seq  = if (json.isNull("s")) null else json.optInt("s")
        if (seq != null) lastSequence = seq

        when (op) {
            OP_HELLO -> {
                heartbeatInterval = json.getJSONObject("d").getLong("heartbeat_interval")
                startHeartbeat(ws)
                ws.send(buildIdentifyPayload().toString())
            }
            OP_DISPATCH -> {
                if (json.optString("t") == "READY") {
                    Log.d(TAG, "Gateway READY")
                    val sent = ws.send(initialPresence.toString())
                    if (sent) clearStatus() else recordStatus("Discord Gateway READY but presence send failed")
                }
            }
            OP_HEARTBEAT       -> ws.send(buildHeartbeatPayload().toString())
            OP_RECONNECT       -> ws.close(4000, "reconnect")
            OP_INVALID_SESSION -> {
                Log.w(TAG, "Invalid session")
                recordStatus("Discord Gateway invalid session; retrying identify")
                Thread.sleep(2000)
                ws.send(buildIdentifyPayload().toString())
            }
        }
    }

    private fun startHeartbeat(ws: WebSocket) {
        heartbeatThread?.interrupt()
        heartbeatThread = Thread {
            try {
                Thread.sleep((heartbeatInterval * Math.random()).toLong())
                while (running) {
                    ws.send(buildHeartbeatPayload().toString())
                    Thread.sleep(heartbeatInterval)
                }
            } catch (_: InterruptedException) {}
        }.also { it.isDaemon = true; it.name = "DiscordRPC-HB"; it.start() }
    }

    // ── Payload builders ──────────────────────────────────────────────────────

    private fun buildIdentifyPayload() = JSONObject().apply {
        put("op", OP_IDENTIFY)
        put("d", JSONObject().apply {
            put("token", "Bearer $accessToken")   // OAuth2 — not a selfbot token
            put("capabilities", 16381)
            put("properties", SuperProperties.superProperties)
            put("presence", JSONObject().apply {
                put("status", "online")
                put("since", 0)
                put("activities", JSONArray())
                put("afk", false)
            })
            put("compress", false)
            put("client_state", JSONObject().apply {
                put("guild_versions", JSONObject())
            })
        })
    }

    private fun buildHeartbeatPayload() = JSONObject().apply {
        put("op", OP_HEARTBEAT)
        put("d", lastSequence ?: JSONObject.NULL)
    }

    private fun buildClearPresencePayload() = JSONObject().apply {
        put("op", OP_PRESENCE)
        put("d", JSONObject().apply {
            put("status", "online")
            put("since", 0)
            put("activities", JSONArray())
            put("afk", false)
        })
    }

    private fun buildPresencePayload(
        song: Song,
        currentPlaybackTimeMillis: Long,
        playbackSpeed: Float,
        useDetails: Boolean,
        status: String,
        button1Text: String,
        button1Visible: Boolean,
        button2Text: String,
        button2Visible: Boolean,
        activityType: String,
        activityName: String,
    ): JSONObject {
        val artist   = song.artists.joinToString(", ") { it.name }
        val songUrl  = "https://music.youtube.com/watch?v=${song.id}"
        val typeId   = when (activityType.lowercase()) {
            "playing"   -> ACTIVITY_PLAYING
            "watching"  -> ACTIVITY_WATCHING
            "competing" -> ACTIVITY_COMPETING
            else        -> ACTIVITY_LISTENING
        }
        val resolvedName = when {
            activityName.isNotBlank() -> resolveVariables(activityName, song)
            typeId == ACTIVITY_LISTENING -> "Echo Music"
            else -> song.title
        }

        val activity = JSONObject().apply {
            put("name",    resolvedName)
            put("type",    typeId)
            put("flags",   1)
            put("details", if (useDetails) song.title else artist)
            put("state",   if (useDetails) artist else song.title)

            val startMs = System.currentTimeMillis() - currentPlaybackTimeMillis.coerceAtLeast(0L)
            put("timestamps", JSONObject().apply { put("start", startMs) })

            val artUrl = song.thumbnailUrl?.takeIf { it.isNotBlank() } ?: ""
            put("assets", JSONObject().apply {
                if (artUrl.isNotBlank()) {
                    put("large_image", artUrl)
                    put("large_text",  song.title)
                } else {
                    put("large_image", "echo_music_logo")
                    put("large_text",  "Echo Music")
                }
                put("small_image", "echo_music_logo")
                put("small_text",  "Echo Music")
            })

            val buttons     = JSONArray()
            val buttonLinks = JSONArray()
            if (button1Visible && button1Text.isNotBlank()) {
                val label = resolveVariables(button1Text, song)
                buttons.put(JSONObject().apply { put("label", label.take(32)); put("url", songUrl) })
                buttonLinks.put(songUrl)
            }
            if (button2Visible && button2Text.isNotBlank()) {
                val label = resolveVariables(button2Text, song)
                val url2  = "https://echo.music.app"
                buttons.put(JSONObject().apply { put("label", label.take(32)); put("url", url2) })
                buttonLinks.put(url2)
            }
            if (buttons.length() > 0) {
                put("buttons",  buttons)
                put("metadata", JSONObject().apply { put("button_urls", buttonLinks) })
            }
        }

        return JSONObject().apply {
            put("op", OP_PRESENCE)
            put("d", JSONObject().apply {
                put("status", status.takeIf { it in setOf("online","idle","dnd","invisible") } ?: "online")
                put("since", 0)
                put("activities", JSONArray().put(activity))
                put("afk", false)
            })
        }
    }
}
