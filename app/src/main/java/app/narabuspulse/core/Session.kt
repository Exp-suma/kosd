package app.narabuspulse.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** ログイン状態（アクセストークンと利用者情報）。 */
object Session {
    private const val PREFS = "session"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_USER = "user"
    private const val KEY_GUEST = "guest"
    private const val KEY_WANTS_LOGIN = "wants_login"

    private lateinit var prefs: SharedPreferences

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token

    private val _user = MutableStateFlow<JsonObject?>(null)
    val user: StateFlow<JsonObject?> = _user

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _token.value = prefs.getString(KEY_TOKEN, null)
        _guest.value = prefs.getBoolean(KEY_GUEST, false)
        _wantsLogin.value = prefs.getBoolean(KEY_WANTS_LOGIN, false)
        _user.value = prefs.getString(KEY_USER, null)?.let {
            runCatching { Json.parse(it).jsonObject }.getOrNull()
        }
    }

    /** ゲスト（ログインせずに使っている状態）。サーバーの共有ゲストアカウントで動く。 */
    private val _guest = MutableStateFlow(false)
    val guest: StateFlow<Boolean> = _guest

    /** 利用者が自分のアカウントでログインしたいとき（ゲストに戻らずログイン画面を出す）。 */
    private val _wantsLogin = MutableStateFlow(false)
    val wantsLogin: StateFlow<Boolean> = _wantsLogin

    fun requestLogin(want: Boolean) {
        prefs.edit().putBoolean(KEY_WANTS_LOGIN, want).apply()
        _wantsLogin.value = want
        if (want && _guest.value) clear(keepPreference = true)
    }

    val isAdmin: Boolean get() = _user.value?.str("role") == "admin"

    fun setToken(token: String, guest: Boolean = false) {
        prefs.edit().putString(KEY_TOKEN, token).putBoolean(KEY_GUEST, guest).apply()
        if (!guest) { prefs.edit().putBoolean(KEY_WANTS_LOGIN, false).apply(); _wantsLogin.value = false }
        _guest.value = guest
        _token.value = token
    }

    fun setUser(user: JsonObject?) {
        prefs.edit().apply {
            if (user == null) remove(KEY_USER) else putString(KEY_USER, user.toString())
        }.apply()
        _user.value = user
    }

    fun clear(keepPreference: Boolean = false) {
        val wants = _wantsLogin.value
        prefs.edit().clear().apply()
        if (keepPreference && wants) prefs.edit().putBoolean(KEY_WANTS_LOGIN, true).apply() else _wantsLogin.value = false
        _token.value = null
        _user.value = null
        _guest.value = false
    }

}
