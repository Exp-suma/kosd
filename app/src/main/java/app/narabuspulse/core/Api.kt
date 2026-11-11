package app.narabuspulse.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, message: String) : IOException(message) {
    val isUnauthorized get() = status == 401
}

/**
 * Base44 アプリへの通信。
 * - rpc(): 既存のサーバー関数を名前で呼ぶ（/api/native/rpc）
 * - auth / entities / upload: Base44 の標準 API（SDK と同じエンドポイント）
 */
object Api {
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val b = chain.request().newBuilder()
                .header("X-App-Id", Config.APP_ID)
                .header("Accept-Language", "ja")
            if (chain.request().tag(NoAuth::class.java) == null) {
                Session.token.value?.let { b.header("Authorization", "Bearer $it") }
            }
            chain.proceed(b.build())
        }
        .build()

    /** 認証切れを画面に知らせる（ログイン画面へ戻すため）。 */
    @Volatile var onUnauthorized: (() -> Unit)? = null

    private fun Response.readJson(): JsonElement {
        val text = body.string()
        if (!isSuccessful) {
            val parsed = runCatching { Json.parse(text) }.getOrNull()
            val msg = parsed.str("error") ?: parsed.str("message") ?: parsed.str("detail")
                ?: parsed.str("detail.0.msg") ?: "通信エラー（$code）"
            if (code == 401) onUnauthorized?.invoke()
            throw ApiException(code, msg)
        }
        if (text.isBlank()) return JsonNull
        return runCatching { Json.parse(text) }.getOrElse { throw ApiException(code, "応答を読み取れませんでした") }
    }

    private suspend fun call(req: Request): JsonElement = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val path = req.url.encodedPath
        val entity = Regex("/entities/([^/]+)").find(path)?.groupValues?.get(1)
        try {
            val out = http.newCall(req).execute().use { it.readJson() }
            record(req, path, entity, true, System.currentTimeMillis() - started, 200, null)
            out
        } catch (e: Throwable) {
            record(req, path, entity, false, System.currentTimeMillis() - started, (e as? ApiException)?.status ?: 0, e)
            throw e
        }
    }

    /** 端末内の通信記録（システム状態モニター用）。 */
    private fun record(req: Request, path: String, entity: String?, ok: Boolean, ms: Long, status: Int, e: Throwable?) {
        runCatching {
            if (entity != null) Telemetry.recordDb(entity, req.method != "GET", ok, ms, e)
            val label = when {
                path.contains("/api/native/rpc") -> Telemetry.APP
                entity != null -> "Base44 エンティティ"
                path.contains("/auth") -> "Base44 認証"
                path.contains("integration-endpoints") -> "Base44 ファイル"
                else -> "Base44 API"
            }
            Telemetry.recordApi(label, ok, ms, status, e, entity ?: path.substringAfterLast('/'))
        }
    }

    private fun jsonBody(data: JsonElement) = data.toString().toRequestBody(JSON_TYPE)

    // ---------------- サーバー関数 ----------------

    /** サーバー関数を呼び、`result` を返す。例: rpc("board/server-fns#getBoardFeed", jsonOf("limit" to 20)) */
    suspend fun rpc(fn: String, data: JsonElement? = null): JsonElement {
        val body = JsonObject(mapOf("fn" to toJson(fn), "data" to (data ?: JsonNull)))
        val res = call(Request.Builder().url(Config.RPC_URL).post(jsonBody(body)).build())
        return (res as? JsonObject)?.get("result") ?: JsonNull
    }

    /** ストリームで返すサーバー関数（AIチャットなど）を 1 行ずつ受け取る。 */
    fun rpcStream(fn: String, data: JsonElement? = null): Flow<JsonElement> = flow {
        val body = JsonObject(mapOf("fn" to toJson(fn), "data" to (data ?: JsonNull)))
        val req = Request.Builder().url(Config.RPC_URL).post(jsonBody(body)).build()
        http.newCall(req).execute().use { res ->
            val type = res.header("content-type").orEmpty()
            if (!res.isSuccessful || !type.contains("ndjson")) {
                val json = res.readJson()
                emit((json as? JsonObject)?.get("result") ?: json)
                return@use
            }
            val source = res.body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isNotBlank()) emit(Json.parse(line))
            }
        }
    }.flowOn(Dispatchers.IO)

    // ---------------- 認証 ----------------

    suspend fun login(email: String, password: String): JsonElement =
        call(post("${Config.apiBase}/auth/login", jsonOf("email" to email, "password" to password), auth = false))

    suspend fun register(email: String, password: String, fullName: String?): JsonElement =
        call(post("${Config.apiBase}/auth/register", jsonOf("email" to email, "password" to password, "full_name" to fullName), auth = false))

    suspend fun verifyOtp(email: String, code: String): JsonElement =
        call(post("${Config.apiBase}/auth/verify-otp", jsonOf("email" to email, "otp_code" to code), auth = false))

    suspend fun resendOtp(email: String): JsonElement =
        call(post("${Config.apiBase}/auth/resend-otp", jsonOf("email" to email), auth = false))

    suspend fun resetPasswordRequest(email: String): JsonElement =
        call(post("${Config.apiBase}/auth/reset-password-request", jsonOf("email" to email), auth = false))

    suspend fun resetPassword(token: String, newPassword: String): JsonElement =
        call(post("${Config.apiBase}/auth/reset-password", jsonOf("reset_token" to token, "new_password" to newPassword), auth = false))

    suspend fun changePassword(userId: String, current: String, new: String): JsonElement =
        call(post("${Config.apiBase}/auth/change-password", jsonOf("user_id" to userId, "current_password" to current, "new_password" to new)))

    suspend fun me(): JsonObject =
        call(Request.Builder().url("${Config.apiBase}/entities/User/me").get().build()) as? JsonObject
            ?: throw ApiException(500, "利用者情報を取得できませんでした")

    suspend fun updateMe(data: JsonObject): JsonElement =
        call(Request.Builder().url("${Config.apiBase}/entities/User/me").put(jsonBody(data)).build())

    // ---------------- エンティティ（SDK の base44.entities.X と同じ） ----------------

    suspend fun entityList(entity: String, query: JsonObject? = null, sort: String? = null, limit: Int? = null, skip: Int? = null): List<JsonElement> {
        val url = "${Config.apiBase}/entities/$entity".toHttpUrl().newBuilder().apply {
            query?.let { addQueryParameter("q", it.toString()) }
            sort?.let { addQueryParameter("sort", it) }
            limit?.let { addQueryParameter("limit", it.toString()) }
            skip?.let { addQueryParameter("skip", it.toString()) }
        }.build()
        return call(Request.Builder().url(url).get().build()).items()
    }

    suspend fun entityGet(entity: String, id: String): JsonElement =
        call(Request.Builder().url("${Config.apiBase}/entities/$entity/$id").get().build())

    suspend fun entityCreate(entity: String, data: JsonObject): JsonElement =
        call(post("${Config.apiBase}/entities/$entity", data))

    suspend fun entityUpdate(entity: String, id: String, data: JsonObject): JsonElement =
        call(Request.Builder().url("${Config.apiBase}/entities/$entity/$id").put(jsonBody(data)).build())

    suspend fun entityDelete(entity: String, id: String): JsonElement =
        call(Request.Builder().url("${Config.apiBase}/entities/$entity/$id").delete().build())

    // ---------------- ファイル ----------------

    /** 画像などをアップロードして URL を返す（SDK の integrations.Core.UploadFile と同じ）。 */
    suspend fun uploadFile(fileName: String, mime: String, bytes: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
            .build()
        val res = call(Request.Builder().url("${Config.apiBase}/integration-endpoints/Core/UploadFile").post(body).build())
        return res.str("file_url") ?: res.str("url") ?: throw ApiException(500, "アップロードに失敗しました")
    }

    /** 非公開ファイルとしてアップロードして file_uri を返す（SDK の integrations.Core.UploadPrivateFile と同じ）。 */
    suspend fun uploadPrivateFile(fileName: String, mime: String, bytes: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
            .build()
        val res = call(Request.Builder().url("${Config.apiBase}/integration-endpoints/Core/UploadPrivateFile").post(body).build())
        return res.str("file_uri") ?: throw ApiException(500, "アップロードに失敗しました")
    }

    /** 非公開ファイルの一時URL（SDK の integrations.Core.CreateFileSignedUrl と同じ）。 */
    suspend fun signedUrl(fileUri: String): String =
        call(post("${Config.apiBase}/integration-endpoints/Core/CreateFileSignedUrl", JsonObject(mapOf("file_uri" to toJson(fileUri))))).str("signed_url").orEmpty()

    private fun post(url: String, data: JsonElement, auth: Boolean = true): Request =
        Request.Builder().url(url).post(jsonBody(data)).apply {
            if (!auth) tag(NoAuth::class.java, NoAuth)
        }.build()

    private object NoAuth
}
