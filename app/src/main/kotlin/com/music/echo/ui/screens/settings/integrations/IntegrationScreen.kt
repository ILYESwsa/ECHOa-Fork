package iad1tya.echo.music.ui.screens.settings.integrations

import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import iad1tya.echo.music.LocalPlayerAwareWindowInsets
import iad1tya.echo.music.R
import iad1tya.echo.music.constants.DiscordActivityNameKey
import iad1tya.echo.music.constants.DiscordActivityTypeKey
import iad1tya.echo.music.constants.DiscordAdvancedModeKey
import iad1tya.echo.music.constants.DiscordButton1TextKey
import iad1tya.echo.music.constants.DiscordButton1VisibleKey
import iad1tya.echo.music.constants.DiscordButton2TextKey
import iad1tya.echo.music.constants.DiscordButton2VisibleKey
import iad1tya.echo.music.constants.DiscordPkceVerifierKey
import iad1tya.echo.music.constants.DiscordRefreshTokenKey
import iad1tya.echo.music.constants.DiscordStatusKey
import iad1tya.echo.music.constants.DiscordTokenKey
import iad1tya.echo.music.constants.DiscordUseDetailsKey
import iad1tya.echo.music.constants.DiscordUsernameKey
import iad1tya.echo.music.constants.EnableDiscordRPCKey
import iad1tya.echo.music.ui.component.IconButton
import iad1tya.echo.music.ui.component.IntegrationCard
import iad1tya.echo.music.ui.component.IntegrationCardItem
import iad1tya.echo.music.ui.utils.backToMain
import iad1tya.echo.music.utils.DiscordRPC
import iad1tya.echo.music.utils.dataStore
import iad1tya.echo.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── Preferences ───────────────────────────────────────────────────────────
    var enabled      by rememberPreference(EnableDiscordRPCKey,      defaultValue = false)
    var accessToken  by rememberPreference(DiscordTokenKey,          defaultValue = "")
    var username     by rememberPreference(DiscordUsernameKey,       defaultValue = "")
    var useDetails   by rememberPreference(DiscordUseDetailsKey,     defaultValue = false)
    var status       by rememberPreference(DiscordStatusKey,         defaultValue = "online")
    var btn1Text     by rememberPreference(DiscordButton1TextKey,    defaultValue = "Listen on Echo")
    var btn1Visible  by rememberPreference(DiscordButton1VisibleKey, defaultValue = true)
    var btn2Text     by rememberPreference(DiscordButton2TextKey,    defaultValue = "")
    var btn2Visible  by rememberPreference(DiscordButton2VisibleKey, defaultValue = false)
    var activityType by rememberPreference(DiscordActivityTypeKey,   defaultValue = "listening")
    var activityName by rememberPreference(DiscordActivityNameKey,   defaultValue = "")
    var advancedMode by rememberPreference(DiscordAdvancedModeKey,   defaultValue = false)

    val isConnected = accessToken.isNotBlank()

    // ── Dialog visibility ─────────────────────────────────────────────────────
    var showStatusDialog   by remember { mutableStateOf(false) }
    var showActivityDialog by remember { mutableStateOf(false) }

    // ── OAuth helpers ─────────────────────────────────────────────────────────
    fun launchOAuth() {
        val verifier = DiscordRPC.generateVerifier()
        scope.launch(Dispatchers.IO) {
            context.dataStore.edit { it[DiscordPkceVerifierKey] = verifier }
        }
        CustomTabsIntent.Builder().setShowTitle(true).build()
            .launchUrl(context, DiscordRPC.buildAuthUri(verifier))
    }

    fun disconnect() {
        scope.launch {
            withContext(Dispatchers.IO) {
                context.dataStore.edit { prefs ->
                    prefs[DiscordTokenKey]        = ""
                    prefs[DiscordRefreshTokenKey] = ""
                    prefs[DiscordUsernameKey]     = ""
                }
            }
            accessToken = ""
            username    = ""
            enabled     = false
            Toast.makeText(context, "Disconnected from Discord", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Status Picker Dialog ──────────────────────────────────────────────────
    if (showStatusDialog) {
        val options = listOf("online" to "🟢 Online", "idle" to "🌙 Idle",
                             "dnd" to "⛔ Do Not Disturb", "invisible" to "⚫ Invisible")
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            title = { Text("Discord Status") },
            text  = {
                Column {
                    options.forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = status == value,
                                onClick  = { status = value },
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatusDialog = false }) { Text("Done") }
            },
        )
    }

    // ── Activity Type Dialog ──────────────────────────────────────────────────
    if (showActivityDialog) {
        val options = listOf("listening" to "🎵 Listening to",
                             "playing"   to "🎮 Playing",
                             "watching"  to "📺 Watching",
                             "competing" to "🏆 Competing in")
        AlertDialog(
            onDismissRequest = { showActivityDialog = false },
            title = { Text("Activity Type") },
            text  = {
                Column {
                    options.forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = activityType == value,
                                onClick  = { activityType = value },
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showActivityDialog = false }) { Text("Done") }
            },
        )
    }

    // ── Screen layout ─────────────────────────────────────────────────────────
    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ── Main Discord RPC card ──────────────────────────────────────────────
        IntegrationCard(
            title = "Discord Rich Presence",
            items = listOf(
                IntegrationCardItem(
                    icon  = painterResource(R.drawable.ic_discord_new),
                    title = { Text("Discord Rich Presence") },
                    description = {
                        Text(
                            when {
                                !isConnected          -> "Connect your Discord account to show what you're listening to"
                                username.isNotBlank() -> "Connected as $username"
                                else                  -> "Connected"
                            }
                        )
                    },
                    trailingContent = {
                        if (isConnected) {
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                    },
                ),
                IntegrationCardItem(
                    icon  = painterResource(R.drawable.discord),
                    title = { Text(if (isConnected) "Account" else "Connect Discord") },
                    description = {
                        Text(
                            if (isConnected) "Tap Disconnect to remove your account"
                            else "Opens Discord in your browser — no password needed"
                        )
                    },
                    trailingContent = {
                        if (isConnected) {
                            TextButton(onClick = { disconnect() }) { Text("Disconnect") }
                        } else {
                            Button(onClick = { launchOAuth() }) { Text("Connect") }
                        }
                    },
                ),
            ),
        )

        // ── Presence options (shown when token saved) ─────────────────────────
        AnimatedVisibility(
            visible = isConnected,
            enter   = expandVertically(),
            exit    = shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                IntegrationCard(
                    title = "Presence Options",
                    items = listOf(
                        // Status
                        IntegrationCardItem(
                            icon  = painterResource(R.drawable.discord),
                            title = { Text("Status") },
                            description = {
                                val label = when (status) {
                                    "online"    -> "🟢 Online"
                                    "idle"      -> "🌙 Idle"
                                    "dnd"       -> "⛔ Do Not Disturb"
                                    "invisible" -> "⚫ Invisible"
                                    else        -> status
                                }
                                Text(label)
                            },
                            onClick = { showStatusDialog = true },
                        ),
                        // Activity type
                        IntegrationCardItem(
                            icon  = painterResource(R.drawable.discord),
                            title = { Text("Activity Type") },
                            description = {
                                val label = when (activityType) {
                                    "playing"   -> "🎮 Playing"
                                    "watching"  -> "📺 Watching"
                                    "competing" -> "🏆 Competing in"
                                    else        -> "🎵 Listening to"
                                }
                                Text(label)
                            },
                            onClick = { showActivityDialog = true },
                        ),
                        // Use details
                        IntegrationCardItem(
                            icon  = painterResource(R.drawable.discord),
                            title = { Text("Show title in details field") },
                            description = { Text("Uses 'details' + 'state' instead of one combined line") },
                            trailingContent = {
                                Switch(checked = useDetails, onCheckedChange = { useDetails = it })
                            },
                        ),
                    ),
                )

                // ── Button options ─────────────────────────────────────────────
                Spacer(Modifier.height(8.dp))
                IntegrationCard(
                    title = "Buttons",
                    items = listOf(
                        IntegrationCardItem(
                            icon  = painterResource(R.drawable.discord),
                            title = { Text("Button 1") },
                            description = {
                                Column {
                                    OutlinedTextField(
                                        value         = btn1Text,
                                        onValueChange = { btn1Text = it },
                                        label         = { Text("Label — supports {title}, {artist}") },
                                        singleLine    = true,
                                        modifier      = Modifier.fillMaxWidth(),
                                    )
                                }
                            },
                            trailingContent = {
                                Switch(checked = btn1Visible, onCheckedChange = { btn1Visible = it })
                            },
                        ),
                        IntegrationCardItem(
                            icon  = painterResource(R.drawable.discord),
                            title = { Text("Button 2") },
                            description = {
                                Column {
                                    OutlinedTextField(
                                        value         = btn2Text,
                                        onValueChange = { btn2Text = it },
                                        label         = { Text("Label — leave blank to hide") },
                                        singleLine    = true,
                                        modifier      = Modifier.fillMaxWidth(),
                                    )
                                }
                            },
                            trailingContent = {
                                Switch(checked = btn2Visible, onCheckedChange = { btn2Visible = it })
                            },
                        ),
                    ),
                )

                // ── Advanced options ───────────────────────────────────────────
                Spacer(Modifier.height(8.dp))
                IntegrationCard(
                    title = "Advanced",
                    items = listOf(
                        IntegrationCardItem(
                            icon  = painterResource(R.drawable.discord),
                            title = { Text("Advanced Mode") },
                            description = { Text("Custom activity name (supports {title}, {artist}, {album})") },
                            trailingContent = {
                                Switch(checked = advancedMode, onCheckedChange = { advancedMode = it })
                            },
                        ),
                    ) + if (advancedMode) listOf(
                        IntegrationCardItem(
                            icon  = painterResource(R.drawable.discord),
                            title = { Text("Activity Name") },
                            description = {
                                OutlinedTextField(
                                    value         = activityName,
                                    onValueChange = { activityName = it },
                                    label         = { Text("e.g. {title} — {artist}") },
                                    singleLine    = true,
                                    modifier      = Modifier.fillMaxWidth(),
                                )
                            },
                        ),
                    ) else emptyList(),
                )

                Spacer(Modifier.height(16.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    TopAppBar(
        title          = { Text(stringResource(R.string.integrations)) },
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(
                onClick     = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}
