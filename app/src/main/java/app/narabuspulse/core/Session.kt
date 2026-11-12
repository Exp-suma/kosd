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

    private lateinit var prefs: SharedPreferences

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token

    private val _user = MutableStateFlow<JsonObject?>(null)
    val user: StateFlow<JsonObject?> = _user

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _token.value = prefs.getString(KEY_TOKEN, null)
        _user.value = prefs.getString(KEY_USER, null)?.let {
            runCatching { Json.parse(it).jsonObject }.getOrNull()
        }
    }

    val isAdmin: Boolean get() = _user.value?.str("role") == "admin"

    fun setToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
        _token.value = token
    }

    fun setUser(user: JsonObject?) {
        prefs.edit().apply {
            if (user == null) remove(KEY_USER) else putString(KEY_USER, user.toString())
        }.apply()
        _user.value = user
    }

    fun clear() {
        prefs.edit().clear().apply()
        _token.value = null
        _user.value = null
    }

}
