package iad1tya.echo.music.utils

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.datastore.preferences.core.edit
import iad1tya.echo.music.constants.DiscordPkceVerifierKey
import iad1tya.echo.music.constants.DiscordRefreshTokenKey
import iad1tya.echo.music.constants.DiscordTokenKey
import iad1tya.echo.music.constants.DiscordUsernameKey
import iad1tya.echo.music.db.entities.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch

/**
 * Discord Rich Presence via OAuth2 PKCE + Gateway WebSocket.
 *
 * Auth flow:
 *  1. buildAuthUri(verifier) → Chrome Custom Tab → Discord consent page
 *  2. User approves → deep link echomusic://discord/callback?code=...
 *  3. exchangeCode(code, verifier) → POST /oauth2/token (no client secret needed)
 *     → access token stored in DataStore, never exposed in UI
 *  4. Gateway Identify uses "Bearer <accessToken>" → OP 3 Presence updates
 */
class DiscordRPC(
    private val context: Context,
    private val accessToken: String,   // OAuth2 Bearer token, never a raw user token
) {
    companion object {
        private const val TAG        = "DiscordRPC"
        const val CLIENT_ID          = "1517729881885114368"   // ← replace with your app ID
        private const val REDIRECT   = "echomusic://discord/callback"
        private const val AUTH_URL   = "https://discord.com/oauth2/authorize"
        private const val TOKEN_URL  = "https://discord.com/api/oauth2/token"
        private const val API_BASE   = "https://discord.com/api/v10"
        private const val WS_URL     = "wss://gateway.discord.gg/?v=10&encoding=json"
        private const val SCOPES = "identify gateway.connect activities.write"

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

        /** Exchange auth code for tokens. Called from MainActivity.onNewIntent. */
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

            // Store — never logged, never shown in UI
            context.dataStore.edit { prefs ->
                prefs[DiscordTokenKey]        = accessToken
                prefs[DiscordRefreshTokenKey] = refreshToken
                prefs[DiscordPkceVerifierKey] = ""   // single-use, clear immediately
            }

            // Fetch display name
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

        /** Refresh an expired access token. Returns new token or null on failure. */
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

        // Activity type IDs
        private const val ACTIVITY_LISTENING = 2
        private const val ACTIVITY_PLAYING   = 0
        private const val ACTIVITY_WATCHING  = 3
        private const val ACTIVITY_COMPETING = 5

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
    private var wsThread: Thread? = null
    private var wsWriter: OutputStreamWriter? = null
    @Volatile private var running = false
    @Volatile private var heartbeatInterval = 41250L
    private var sessionId: String? = null
    private var lastSequence: Int? = null

    // ── Cached user info ──────────────────────────────────────────────────────
    private var cachedUsername: String = ""
    private var cachedAvatar: String   = ""

    // ── Last pushed presence (for re-push on reconnect) ──────────────────────
    private var lastPresencePayload: JSONObject? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun isRpcRunning(): Boolean = running && wsThread?.isAlive == true

    /**
     * Push a presence update. Starts the WS if not already running.
     */
    suspend fun updateSong(
        song: Song,
        currentPlaybackTimeMillis: Long,
        playbackSpeed: Float = 1.0f,
        useDetails: Boolean = false,
        status: String = "online",
        button1Text: String = "",
        button1Visible: Boolean = true,
        button2Text: String = "",
        button2Visible: Boolean = true,
        activityType: String = "listening",
        activityName: String = "",
    ): Result<Unit> = runCatching {
        val payload = buildPresencePayload(
            song, currentPlaybackTimeMillis, playbackSpeed,
            useDetails, status, button1Text, button1Visible,
            button2Text, button2Visible, activityType, activityName,
        )
        lastPresencePayload = payload

        if (!isRpcRunning()) {
            startWebSocket(payload)
        } else {
            sendWsMessage(payload)
        }
    }

    /** Temporarily clear the presence (pause) */
    fun close() {
        try { sendWsMessage(buildClearPresencePayload()) } catch (_: Exception) {}
    }

    /** Fully disconnect and stop the WebSocket thread */
    fun closeRPC() {
        running = false
        try { sendWsMessage(buildClearPresencePayload()) } catch (_: Exception) {}
        wsThread?.interrupt()
        wsThread = null
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun startWebSocket(initialPresence: JSONObject) {
        running = true
        wsThread = Thread {
            try {
                runWebSocket(initialPresence)
            } catch (e: InterruptedException) {
                Log.d(TAG, "WS thread interrupted")
            } catch (e: Exception) {
                Log.e(TAG, "WS error: ${e.message}")
            } finally {
                running = false
            }
        }.also {
            it.isDaemon = true
            it.name = "DiscordRPC-WS"
            it.start()
        }
    }

    private fun runWebSocket(initialPresence: JSONObject) {
        // Simple HTTP upgrade to WebSocket
        val wsConn = URL(WS_URL).openConnection() as HttpURLConnection
        wsConn.apply {
            requestMethod = "GET"
            setRequestProperty("Connection",           "Upgrade")
            setRequestProperty("Upgrade",              "websocket")
            setRequestProperty("Sec-WebSocket-Key",    "dGhlIHNhbXBsZSBub25jZQ==")
            setRequestProperty("Sec-WebSocket-Version","13")
            setRequestProperty("User-Agent",           SuperProperties.userAgent)
            connectTimeout = 10_000
            readTimeout    = 0 // keep-alive
        }

        // For the actual gateway we use OkHttp-style via java.net.URI + threads
        // Since we can't import OkHttp directly here without dependency issues,
        // use the discord4j-compatible manual approach via raw socket frames.
        // In practice, Echo already has OkHttp in the project, so we delegate.
        runGatewayWithOkHttp(initialPresence)
    }

    /**
     * Uses a raw OkHttp WebSocket (already a dependency via media3/coil).
     * Handles OP 10 Hello → OP 2 Identify → OP 3 Presence → heartbeat loop.
     */
    private fun runGatewayWithOkHttp(initialPresence: JSONObject) {
        val client = okhttp3.OkHttpClient.Builder()
            .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url(WS_URL)
            .header("User-Agent", SuperProperties.userAgent)
            .build()

        val latch = java.util.concurrent.CountDownLatch(1)

        val wsListener = object : okhttp3.WebSocketListener() {
            lateinit var ws: okhttp3.WebSocket

            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                ws = webSocket
                Log.d(TAG, "Gateway connected")
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                ws = webSocket
                handleGatewayMessage(ws, text, initialPresence)
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "Gateway failure: ${t.message}")
                running = false
                latch.countDown()
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gateway closed: $code $reason")
                running = false
                latch.countDown()
            }
        }

        val webSocket = client.newWebSocket(request, wsListener)

        // Store reference so we can write from updateSong
        wsWriterHolder = webSocket

        // Block this thread until the socket closes
        latch.await()
        client.dispatcher.executorService.shutdown()
    }

    @Volatile private var wsWriterHolder: okhttp3.WebSocket? = null

    private fun sendWsMessage(payload: JSONObject) {
        wsWriterHolder?.send(payload.toString())
            ?: Log.w(TAG, "sendWsMessage: no active WebSocket")
    }

    private var heartbeatJob: Thread? = null

    private fun handleGatewayMessage(
        ws: okhttp3.WebSocket,
        text: String,
        initialPresence: JSONObject,
    ) {
        val json = JSONObject(text)
        val op  = json.optInt("op", -1)
        val seq = if (json.isNull("s")) null else json.optInt("s")
        if (seq != null) lastSequence = seq

        when (op) {
            10 -> {
                // Hello — start heartbeat
                heartbeatInterval = json.getJSONObject("d").getLong("heartbeat_interval")
                startHeartbeat(ws)
                // Identify
                ws.send(buildIdentifyPayload().toString())
            }
            0  -> {
                // Dispatch
                val t = json.optString("t")
                if (t == "READY") {
                    sessionId = json.getJSONObject("d").optString("session_id")
                    Log.d(TAG, "Gateway READY, session=$sessionId")
                    // Send the initial presence right after READY
                    ws.send(initialPresence.toString())
                }
            }
            1  -> {
                // Heartbeat request
                ws.send(buildHeartbeatPayload().toString())
            }
            7  -> {
                // Reconnect
                ws.close(4000, "reconnect requested")
            }
            9  -> {
                // Invalid session
                Log.w(TAG, "Invalid session — re-identifying")
                Thread.sleep(2000)
                ws.send(buildIdentifyPayload().toString())
            }
        }
    }

    private fun startHeartbeat(ws: okhttp3.WebSocket) {
        heartbeatJob?.interrupt()
        heartbeatJob = Thread {
            try {
                // Initial jitter
                Thread.sleep((heartbeatInterval * Math.random()).toLong())
                while (running) {
                    ws.send(buildHeartbeatPayload().toString())
                    Thread.sleep(heartbeatInterval)
                }
            } catch (_: InterruptedException) {}
        }.also {
            it.isDaemon = true
            it.name = "DiscordRPC-HB"
            it.start()
        }
    }

    // ── Payload builders ──────────────────────────────────────────────────────

    private fun buildIdentifyPayload(): JSONObject = JSONObject().apply {
        put("op", 2)
        put("d", JSONObject().apply {
            put("token", "Bearer $accessToken")   // OAuth2 — never a raw user token
            put("capabilities", 16381)
            put("properties", SuperProperties.superProperties)
            put("presence", JSONObject().apply {
                put("status",     "online")
                put("since",      0)
                put("activities", JSONArray())
                put("afk",        false)
            })
            put("compress", false)
            put("client_state", JSONObject().apply {
                put("guild_versions", JSONObject())
            })
        })
    }

    private fun buildHeartbeatPayload(): JSONObject = JSONObject().apply {
        put("op", 1)
        put("d",  lastSequence ?: JSONObject.NULL)
    }

    private fun buildClearPresencePayload(): JSONObject = JSONObject().apply {
        put("op", 3)
        put("d", JSONObject().apply {
            put("status",     "online")
            put("since",      0)
            put("activities", JSONArray())
            put("afk",        false)
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
        val artist = song.artists.joinToString(", ") { it.name }
        val songUrl = "https://music.youtube.com/watch?v=${song.id}"

        val activityTypeId = when (activityType.lowercase()) {
            "playing"    -> ACTIVITY_PLAYING
            "watching"   -> ACTIVITY_WATCHING
            "competing"  -> ACTIVITY_COMPETING
            else         -> ACTIVITY_LISTENING
        }

        val resolvedName = when {
            activityName.isNotBlank() -> resolveVariables(activityName, song)
            activityTypeId == ACTIVITY_LISTENING -> "Echo Music"
            else -> song.title
        }

        val activity = JSONObject().apply {
            put("name",  resolvedName)
            put("type",  activityTypeId)
            put("flags", 1)

            // Details + state
            if (useDetails) {
                put("details", resolveVariables("{title}", song))
                put("state",   resolveVariables("{artist}", song))
            } else {
                put("details", song.title)
                put("state",   artist)
            }

            // Timestamps — show elapsed
            val nowMs = System.currentTimeMillis()
            val startMs = nowMs - currentPlaybackTimeMillis.coerceAtLeast(0L)
            put("timestamps", JSONObject().apply {
                put("start", startMs)
            })

            // Assets
            put("assets", JSONObject().apply {
                val artUrl = song.thumbnailUrl?.takeIf { it.isNotBlank() } ?: ""
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

            // Buttons
            val buttons     = JSONArray()
            val buttonLinks = JSONArray()

            if (button1Visible && button1Text.isNotBlank()) {
                val label = resolveVariables(button1Text, song)
                buttons.put(JSONObject().apply {
                    put("label", label.take(32))
                    put("url",   songUrl)
                })
                buttonLinks.put(songUrl)
            }
            if (button2Visible && button2Text.isNotBlank()) {
                val label = resolveVariables(button2Text, song)
                val url2  = "https://echo.music.app"
                buttons.put(JSONObject().apply {
                    put("label", label.take(32))
                    put("url",   url2)
                })
                buttonLinks.put(url2)
            }
            if (buttons.length() > 0) {
                put("buttons",     buttons)
                put("metadata", JSONObject().apply {
                    put("button_urls", buttonLinks)
                })
            }
        }

        return JSONObject().apply {
            put("op", 3)
            put("d", JSONObject().apply {
                put("status",     status.takeIf { it in listOf("online","idle","dnd","invisible") } ?: "online")
                put("since",      0)
                put("activities", JSONArray().put(activity))
                put("afk",        false)
            })
        }
    }
}
