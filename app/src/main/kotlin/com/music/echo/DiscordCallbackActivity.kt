package iad1tya.echo.music

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import iad1tya.echo.music.constants.DiscordPkceVerifierKey
import iad1tya.echo.music.constants.EnableDiscordRPCKey
import iad1tya.echo.music.utils.DiscordRPC
import iad1tya.echo.music.utils.dataStore
import iad1tya.echo.music.utils.get
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscordCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent?.data?.getQueryParameter("code")
        if (code.isNullOrBlank()) { finish(); return }
        CoroutineScope(Dispatchers.Main).launch {
            val verifier = dataStore[DiscordPkceVerifierKey] ?: ""
            if (verifier.isBlank()) {
                Toast.makeText(this@DiscordCallbackActivity, "Discord auth error: session expired", Toast.LENGTH_LONG).show()
                finish(); return@launch
            }
            runCatching {
                val (_, uname) = withContext(Dispatchers.IO) {
                    DiscordRPC.exchangeCode(this@DiscordCallbackActivity, code, verifier)
                }
                dataStore.edit { it[EnableDiscordRPCKey] = true }
                Toast.makeText(this@DiscordCallbackActivity, "Discord connected as $uname", Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                Toast.makeText(this@DiscordCallbackActivity, "Discord connect failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}
