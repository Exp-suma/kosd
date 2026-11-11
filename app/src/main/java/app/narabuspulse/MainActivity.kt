package app.narabuspulse

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.narabuspulse.core.Api
import app.narabuspulse.core.Session
import app.narabuspulse.feature.auth.AuthFlow
import app.narabuspulse.nav.AppShell
import app.narabuspulse.ui.theme.NaraTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var authError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        Api.onUnauthorized = { runOnUiThread { Session.clear() } }

        setContent {
            NaraTheme {
                val token by Session.token.collectAsState()
                if (token.isNullOrBlank()) {
                    AuthFlow(onSignedIn = {})
                } else {
                    LaunchedEffect(token) {
                        // 利用者情報を最新にする（失敗しても表示は続ける）
                        runCatching { Session.setUser(Api.me()) }
                    }
                    AppShell(onLogout = { Session.clear() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** narabuspulse://auth#access_token=… （Google などのログインからの戻り） */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "narabuspulse" || data.host != "auth") return
        val params = (data.fragment ?: data.query ?: "").split('&').associate {
            it.substringBefore('=') to android.net.Uri.decode(it.substringAfter('=', ""))
        }
        val token = params["access_token"]
        if (!token.isNullOrBlank()) {
            Session.setToken(token)
            lifecycleScope.launch { runCatching { Session.setUser(Api.me()) } }
        } else {
            authError = "ログインできませんでした"
        }
    }
}
