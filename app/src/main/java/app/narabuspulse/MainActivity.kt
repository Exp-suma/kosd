package app.narabuspulse

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.narabuspulse.core.Api
import app.narabuspulse.core.Session
import app.narabuspulse.feature.auth.AuthFlow
import app.narabuspulse.nav.AppShell
import app.narabuspulse.nav.DevRoute
import app.narabuspulse.ui.theme.NaraTheme
import kotlinx.coroutines.launch

/**
 * ログインせずに使う：起動時にサーバーからゲスト用トークンを受け取り、そのまま使い始める。
 * サーバーがゲストに対応していない（窓口が無い・ゲスト未設定）ときは、従来のログイン画面を出す。
 */
@androidx.compose.runtime.Composable
private fun GuestGate() {
    var state by androidx.compose.runtime.remember { mutableStateOf("trying") }
    var attempt by androidx.compose.runtime.remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        state = "trying"
        state = try {
            val t = Api.guestToken()
            if (t != null) { Session.setToken(t, guest = true); "ok" } else "unsupported"
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { "offline" }
    }
    when (state) {
        "unsupported" -> AuthFlow(onSignedIn = {})
        "offline" -> androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Text("サーバーに接続できません。通信状態を確認してください。")
            androidx.compose.material3.TextButton({ attempt++ }) { androidx.compose.material3.Text("再試行") }
            androidx.compose.material3.TextButton({ Session.requestLogin(true) }) { androidx.compose.material3.Text("ログインする") }
        }
        else -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
    }
}

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
                val wantsLogin by Session.wantsLogin.collectAsState()
                if (token.isNullOrBlank()) {
                    if (wantsLogin) AuthFlow(onSignedIn = {}, onSkip = { Session.requestLogin(false) })
                    else GuestGate()
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
        // 開発用（デバッグ版のみ）：adb から --es dev_token <token> でログイン状態にする
        if (BuildConfig.DEBUG) intent?.getStringExtra("dev_token")?.takeIf { it.isNotBlank() }?.let { t ->
            Session.setToken(t, guest = intent.getBooleanExtra("dev_guest", false))
            lifecycleScope.launch { runCatching { Session.setUser(Api.me()) } }
        }
        // 通知を押したとき：通知が指定した画面を開く
        intent?.getStringExtra("route")?.let { DevRoute.pending = it }
        // 開発用（デバッグ版のみ）：--es dev_route /board などで画面を開く
        if (BuildConfig.DEBUG) intent?.getStringExtra("dev_route")?.let { DevRoute.pending = it }
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
