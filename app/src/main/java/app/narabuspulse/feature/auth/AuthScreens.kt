package app.narabuspulse.feature.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Config
import app.narabuspulse.core.Session
import app.narabuspulse.core.bool
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.str
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private enum class Step { Login, Register, Otp, Forgot, Reset, Mfa }

/** ログイン～新規登録～パスワード再設定（Web の /login /register /forgot-password /reset-password）。 */
@Composable
fun AuthFlow(onSignedIn: () -> Unit, initialResetToken: String? = null, onSkip: (() -> Unit)? = null) {
    var step by remember { mutableStateOf(if (initialResetToken != null) Step.Reset else Step.Login) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var resetToken by remember { mutableStateOf(initialResetToken ?: "") }
    var mfaToken by remember { mutableStateOf<String?>(null) }
    var mfaMethods by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun run(block: suspend () -> Unit) {
        if (busy) return
        error = null; info = null; busy = true
        scope.launch {
            try { block() } catch (e: Throwable) { error = e.userMessage() } finally { busy = false }
        }
    }

    suspend fun finish(result: JsonElement) {
        val token = result.str("access_token") ?: throw IllegalStateException("ログインできませんでした")
        Session.setToken(token)
        Session.setUser(runCatching { Api.me() }.getOrNull())
        onSignedIn()
    }

    suspend fun handleLoginResult(result: JsonElement) {
        if (result.bool("mfa_required") == true || result.str("mfa_session_token") != null) {
            mfaToken = result.str("mfa_session_token")
            mfaMethods = result.list("mfa_methods").mapNotNull { it.str("method") ?: it.str("type") ?: (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            code = ""
            step = Step.Mfa
        } else finish(result)
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Outlined.DirectionsBus, null, tint = Brand.Orange, modifier = Modifier.size(48.dp))
        Text("バスロケ監視", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("奈良交通 運行管理", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val title = when (step) {
                    Step.Login -> "ログイン"
                    Step.Register -> "アカウント作成"
                    Step.Otp -> "メールの確認コード"
                    Step.Forgot -> "パスワードを忘れた場合"
                    Step.Reset -> "新しいパスワード"
                    Step.Mfa -> "本人確認"
                }
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                info?.let { Text(it, color = Brand.Success, fontSize = 13.sp) }

                when (step) {
                    Step.Login -> {
                        SocialButtons(context)
                        HorizontalDivider()
                        EmailField(email) { email = it }
                        PasswordField(password, "パスワード") { password = it }
                        SubmitButton("ログイン", busy) {
                            run { handleLoginResult(Api.login(email.trim(), password)) }
                        }
                        Row2(
                            "パスワードを忘れた", { error = null; step = Step.Forgot },
                            "アカウントを作成", { error = null; step = Step.Register },
                        )
                        if (onSkip != null) TextButton(onSkip, Modifier.fillMaxWidth()) { Text("ログインせずに使う") }
                    }
                    Step.Register -> {
                        OutlinedTextField(fullName, { fullName = it }, label = { Text("お名前（任意）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        EmailField(email) { email = it }
                        PasswordField(password, "パスワード（8文字以上）") { password = it }
                        SubmitButton("登録して確認コードを送る", busy) {
                            run {
                                if (password.length < 8) throw IllegalArgumentException("パスワードは8文字以上にしてください")
                                Api.register(email.trim(), password, fullName.trim().ifBlank { null })
                                code = ""; step = Step.Otp; info = "確認コードをメールで送りました"
                            }
                        }
                        TextButton({ error = null; step = Step.Login }) { Text("ログインに戻る") }
                    }
                    Step.Otp -> {
                        Text("$email に届いた確認コードを入力してください。", fontSize = 13.sp)
                        CodeField(code) { code = it }
                        SubmitButton("確認してログイン", busy) { run { finish(Api.verifyOtp(email.trim(), code.trim())) } }
                        TextButton({ run { Api.resendOtp(email.trim()); info = "確認コードを再送しました" } }) { Text("コードを再送") }
                    }
                    Step.Forgot -> {
                        Text("登録したメールアドレスに再設定用のリンク（またはコード）を送ります。", fontSize = 13.sp)
                        EmailField(email) { email = it }
                        SubmitButton("再設定メールを送る", busy) {
                            run { Api.resetPasswordRequest(email.trim()); info = "メールを送りました。届いたリンクのコードを次の画面で入力してください。"; step = Step.Reset }
                        }
                        TextButton({ error = null; step = Step.Login }) { Text("ログインに戻る") }
                    }
                    Step.Reset -> {
                        OutlinedTextField(resetToken, { resetToken = it.trim() }, label = { Text("再設定コード（メールのリンクの token）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        PasswordField(password, "新しいパスワード（8文字以上）") { password = it }
                        SubmitButton("パスワードを変更", busy) {
                            run {
                                Api.resetPassword(resetToken, password)
                                info = "パスワードを変更しました。ログインしてください。"; step = Step.Login
                            }
                        }
                        TextButton({ error = null; step = Step.Login }) { Text("ログインに戻る") }
                    }
                    Step.Mfa -> {
                        val method = if (mfaMethods.contains("totp") || mfaMethods.isEmpty()) "totp" else mfaMethods.first()
                        Text(if (method == "sms") "SMS で届いたコードを入力してください。" else "認証アプリに表示されているコードを入力してください。", fontSize = 13.sp)
                        CodeField(code) { code = it }
                        SubmitButton("確認", busy) {
                            run { finish(mfaCall("mfa/verify", mfaToken, jsonOf("code" to code.trim(), "method" to method, "remember_device" to true))) }
                        }
                        if (method == "sms") TextButton({ run { mfaCall("mfa/resend", mfaToken, null); info = "コードを再送しました" } }) { Text("コードを再送") }
                        TextButton({ step = Step.Login }) { Text("ログインに戻る") }
                    }
                }
            }
        }
    }
}

/** MFA の API（/api/apps/{id}/auth/mfa/...）。認証はチャレンジのトークンで行う。 */
private suspend fun mfaCall(suffix: String, token: String?, body: JsonObject?): JsonElement = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val req = Request.Builder().url("${Config.apiBase}/auth/$suffix")
        .post((body ?: JsonObject(emptyMap())).toString().toRequestBody("application/json".toMediaType()))
        .apply { if (token != null) header("Authorization", "Bearer $token") }
        .build()
    okhttp3.OkHttpClient().newCall(req).execute().use { res ->
        val text = res.body.string()
        val json = runCatching { app.narabuspulse.core.Json.parse(text) }.getOrNull()
        if (!res.isSuccessful) throw app.narabuspulse.core.ApiException(res.code, json.str("error.message") ?: json.str("detail") ?: "確認できませんでした")
        json ?: JsonObject(emptyMap())
    }
}

/** Google などの外部アカウントでのログイン（ブラウザで開き、narabuspulse://auth に戻る）。 */
@Composable
private fun SocialButtons(context: Context) {
    val providers = listOf("google" to "Google でログイン", "microsoft" to "Microsoft でログイン", "apple" to "Apple でログイン", "facebook" to "Facebook でログイン")
    providers.forEach { (id, label) ->
        OutlinedButton(onClick = { openProviderLogin(context, id) }, modifier = Modifier.fillMaxWidth().height(46.dp)) { Text(label) }
    }
}

fun openProviderLogin(context: Context, provider: String) {
    val path = if (provider == "google") "" else "/$provider"
    val url = "${Config.BASE_URL}/api/apps/auth$path/login?app_id=${Config.APP_ID}&from_url=" +
        Uri.encode(Config.AUTH_CALLBACK_URL)
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
}

@Composable
private fun EmailField(value: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text("メールアドレス") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())

@Composable
private fun CodeField(value: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, { onChange(it.filter(Char::isLetterOrDigit).take(8)) }, label = { Text("コード") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())

@Composable
private fun SubmitButton(label: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(label)
    }
}

@Composable
private fun Row2(a: String, onA: () -> Unit, b: String, onB: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onA) { Text(a, fontSize = 13.sp) }
        TextButton(onB) { Text(b, fontSize = 13.sp) }
    }
}
