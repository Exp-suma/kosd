package app.narabuspulse.core

/** 接続先の Base44 アプリ。 */
object Config {
    const val APP_ID = "6ae730b58237afe77f487d24"
    const val BASE_URL = "https://nara-bus-pulse.base44.app"

    /** 既存のサーバー関数を JSON で呼ぶための API（Base44 アプリ側の src/routes/api/native/rpc.js）。 */
    const val RPC_URL = "$BASE_URL/api/native/rpc"

    /** Google ログイン後に Base44 が戻ってくる URL（同 auth-callback.js）。 */
    const val AUTH_CALLBACK_URL = "$BASE_URL/api/native/auth-callback"

    val apiBase = "$BASE_URL/api/apps/$APP_ID"

    fun googleLoginUrl(): String =
        "$BASE_URL/api/apps/auth/login?app_id=$APP_ID&from_url=" +
            java.net.URLEncoder.encode(AUTH_CALLBACK_URL, "UTF-8")
}
