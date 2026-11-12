package app.narabuspulse.feature.wifi

import android.content.Context
import app.narabuspulse.core.Json
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext

/** ブリッジとの通信で起きた問題（利用者に伝わる言葉）。 */
class BridgeError(message: String, val kind: String = "network", val status: Int = 0) : IOException(message)

/** カメラ内の1ファイル（Web: BridgeClient.normalizeFile）。 */
data class CamFile(val id: String, val index: Int, val name: String, val folder: String, val format: String, val kind: String, val isRaw: Boolean, val isJpeg: Boolean, val size: Long?, val sizeExact: Boolean,
                   val capturedAt: String?, val model: String, val pairKey: String, val port: String = "")

class DownloadResult(val file: File, val bytes: Long, val total: Long?, val sha256: String, val crc32: Long, val bridgeHash: String?, val sizeMatch: Boolean?, val sizeExact: Boolean, val resumeSupported: Boolean, val resumeUsed: Boolean, val durationMs: Long, val avgBps: Double)
class Progress(val received: Long, val total: Long?, val bps: Double, val etaSec: Double?)

private val RAW_EXT = setOf("NEF", "NRW", "CR2", "CR3", "CRW", "ARW", "SR2", "SRF", "RAF", "RW2", "RAW", "ORF", "PEF", "DNG", "RWL", "MOS", "IIQ", "X3F", "3FR", "SRW", "MDC")
private val JPEG_EXT = setOf("JPG", "JPEG", "JPE", "JFIF"); private val VIDEO_EXT = setOf("MOV", "MP4", "AVI", "MTS", "M4V")

fun formatBytes(bytes: Long?): String {
    val n = bytes ?: return "—"; if (n < 0) return "—"; if (n < 1024) return "$n B"
    var v = n / 1024.0; var i = 0; val units = listOf("KB", "MB", "GB", "TB")
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return "${if (v >= 100) "%.0f".format(v) else "%.1f".format(v)} ${units[i]}"
}
fun formatEta(sec: Double?): String {
    val s = sec?.takeIf { it.isFinite() && it > 0 } ?: return "—"
    if (s < 60) return "約${kotlin.math.ceil(s).toInt()}秒"
    val m = (s / 60).toInt(); return if (m < 60) "約${m}分${(s % 60).toInt()}秒" else "約${m / 60}時間${m % 60}分"
}

/** 保存済みのブリッジ設定（Web の localStorage "wifi-bridge-v1" と同じ）。 */
object BridgeConfig {
    private const val KEY = "wifi-bridge-v1"
    val state = MutableStateFlow("http://127.0.0.1:8765" to "")
    fun init(context: Context) {
        val raw = context.getSharedPreferences("logic_storage", Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching { Json.parse(raw) }.getOrNull()?.let { state.value = (it.str("url")?.ifBlank { null } ?: "http://127.0.0.1:8765") to it.str("token").orEmpty() }
    }
    fun save(context: Context, url: String, token: String) {
        state.value = url.trim() to token
        context.getSharedPreferences("logic_storage", Context.MODE_PRIVATE).edit().putString(KEY, jsonOf("url" to url.trim(), "token" to token).toString()).apply()
    }
}

/**
 * ローカルブリッジ（同じWi-Fi上のPC・端末で動く中継）との通信（Web: lib/wifi/bridge.js）。
 * ファイルの中身には手を加えず、受け取ったバイト列をそのまま保存する。
 */
class BridgeClient(url: String, private val token: String) {
    val baseUrl = url.trim().trimEnd('/')
    val configured get() = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    private fun open(path: String, params: Map<String, String?> = emptyMap(), post: Boolean = false, range: Long = 0): Response {
        if (!configured) throw BridgeError("ブリッジのURLが未設定です（設定タブで入力してください）", "config")
        val url = "$baseUrl$path".toHttpUrlOrNull()?.newBuilder()?.apply { params.forEach { (k, v) -> if (!v.isNullOrEmpty()) addQueryParameter(k, v) } }?.build() ?: throw BridgeError("ブリッジのURLが正しくありません", "config")
        val req = Request.Builder().url(url).header("Cache-Control", "no-store").apply { if (token.isNotEmpty()) header("x-bridge-token", token); if (range > 0) header("Range", "bytes=$range-"); if (post) post(ByteArray(0).toRequestBody()) }.build()
        val res = try { http.newCall(req).execute() } catch (e: IOException) { throw BridgeError("ブリッジに接続できません。PC側でブリッジが起動しているか、URLと許可設定を確認してください") }
        if (res.code == 401) { res.close(); throw BridgeError("ブリッジのトークンが一致しません", "auth", 401) }
        if (!res.isSuccessful) {
            val body = runCatching { res.body?.string() }.getOrNull(); res.close()
            val msg = body?.let { runCatching { Json.parse(it).str("error") }.getOrNull() }
            throw BridgeError(msg?.ifBlank { null } ?: "ブリッジがエラーを返しました（${res.code}）", "http", res.code)
        }
        return res
    }
    private suspend fun json(path: String, params: Map<String, String?> = emptyMap(), post: Boolean = false): JsonElement = withContext(Dispatchers.IO) {
        open(path, params, post).use { r -> runCatching { Json.parse(r.body?.string().orEmpty()) }.getOrDefault(jsonOf()) }
    }
    private suspend fun optional(path: String, params: Map<String, String?> = emptyMap()): JsonElement =
        try { json(path, params) } catch (e: BridgeError) { if (e.status == 404) throw BridgeError("ブリッジがこの機能に対応していません（ブリッジを更新してください）", "unsupported") else throw e }

    suspend fun health(): JsonElement = json("/health").also { if (it.bool("ok") != true) throw BridgeError("ブリッジが応答しましたが準備ができていません", "health") }
    suspend fun netinfo(): JsonElement = optional("/netinfo")
    suspend fun discover(protocol: String, candidates: List<String>): JsonElement = optional("/discover", mapOf("protocol" to protocol, "candidates" to candidates.take(4).joinToString(",")))
    suspend fun probe(port: String): JsonElement = optional("/camera/probe", mapOf("port" to port)).also { if (it.bool("ok") != true) throw BridgeError(it.str("error") ?: "カメラから応答がありません", "camera") }
    suspend fun detect(): List<JsonElement> = json("/camera/detect").items("cameras")
    suspend fun status(port: String): JsonElement = json("/camera/status", mapOf("port" to port))
    suspend fun config(port: String): List<JsonElement> = json("/camera/config", mapOf("port" to port)).items("settings")
    suspend fun setConfig(port: String, name: String, value: String): JsonElement = json("/camera/set-config", mapOf("port" to port, "name" to name, "value" to value), post = true).also { if (it.bool("ok") != true) throw BridgeError(it.str("error") ?: "設定を変更できませんでした", "camera") }
    suspend fun capture(port: String): JsonElement = json("/camera/capture", mapOf("port" to port), post = true).also { if (it.bool("ok") != true) throw BridgeError(it.str("error") ?: "撮影できませんでした", "camera") }
    suspend fun preview(port: String, file: CamFile): ByteArray? = withContext(Dispatchers.IO) { runCatching { open("/preview", mapOf("port" to port, "name" to file.name, "folder" to file.folder)).use { it.body?.bytes() } }.getOrNull()?.takeIf { it.isNotEmpty() } }

    class Listing(val resume: Boolean, val model: String, val firmware: String, val files: List<CamFile>)
    suspend fun listFiles(port: String): Listing {
        val data = json("/files", mapOf("port" to port))
        return Listing(data.bool("resume") == true, data.str("model").orEmpty(), data.str("firmware").orEmpty(), data.items("files").mapIndexed { i, raw ->
            val name = (raw.str("name") ?: raw.str("path").orEmpty()).substringAfterLast('/'); val ext = name.substringAfterLast('.', "").uppercase()
            val isRaw = ext in RAW_EXT; val isJpeg = ext in JPEG_EXT
            CamFile(raw.str("id") ?: raw.str("index") ?: "$i", raw.dbl("index")?.toInt() ?: (i + 1), name, raw.str("folder").orEmpty(), ext.ifBlank { "—" }, if (isRaw) "RAW" else if (isJpeg) "JPEG" else if (ext in VIDEO_EXT) "動画" else "その他", isRaw, isJpeg,
                raw.long("size"), raw.bool("sizeExact") == true, raw.str("capturedAt") ?: raw.str("shotAt"), raw.str("model").orEmpty(), name.substringBeforeLast('.').uppercase(), port)
        })
    }

    /** 1ファイルを一時ファイルへ取得する。途切れたら Range で再開し、サイズと SHA-256 を検証する（Web: lib/wifi/transfer.js）。 */
    suspend fun download(file: CamFile, dir: File, onProgress: (Progress) -> Unit): DownloadResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis(); val out = File(dir, "dl-${System.nanoTime()}.part")
        var sha = MessageDigest.getInstance("SHA-256"); var crc = CRC32(); var received = 0L
        var total: Long? = file.size?.takeIf { file.sizeExact && it > 0 }; var resumeSupported = true; var resumeUsed = false; var attempts = 0; var serverHash: String? = null
        try {
            while (attempts < 3) {
                attempts++
                coroutineContext.ensureActive()
                val res = try { open("/download", mapOf("port" to file.port, "name" to file.name, "folder" to file.folder), range = received) } catch (e: IOException) { if (attempts >= 3) throw e; delay(700L * attempts); continue }
                try {
                    if (received > 0 && res.code == 200) { resumeSupported = false; received = 0; out.delete(); sha = MessageDigest.getInstance("SHA-256"); crc = CRC32() } else if (received > 0) resumeUsed = true
                    res.header("x-sha256")?.lowercase()?.takeIf { it.isNotBlank() }?.let { serverHash = it }
                    res.header("content-length")?.toLongOrNull()?.takeIf { it > 0 }?.let { total = it + received }
                    res.header("x-original-name")?.let { if (it != file.name) throw IllegalStateException("取得元のファイル名が一覧と一致しません（安全のため中止しました）") }
                    val body = res.body ?: throw IOException("ブリッジからデータが返りませんでした")
                    val base = System.currentTimeMillis(); val baseReceived = received; var lastTick = 0L
                    java.io.FileOutputStream(out, true).use { fos ->
                        val buf = ByteArray(64 * 1024); val input = body.byteStream()
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf); if (n < 0) break
                            fos.write(buf, 0, n); sha.update(buf, 0, n); crc.update(buf, 0, n); received += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 180) { lastTick = now; val el = (now - base) / 1000.0; val bps = if (el > 0.2) (received - baseReceived) / el else 0.0; onProgress(Progress(received, total, bps, total?.let { t -> if (bps > 0) (t - received) / bps else null })) }
                        }
                    }
                    break
                } catch (e: IOException) { if (attempts >= 3) throw e; delay(700L * attempts) } finally { res.close() }
            }
            val expected = file.size?.takeIf { file.sizeExact && it > 0 } ?: total
            if (expected != null && received != expected) throw IOException("サイズが一致しません（取得元 $expected バイト / 受信 $received バイト）。欠けたデータは完成扱いにしません")
            val ms = System.currentTimeMillis() - started
            DownloadResult(out, received, expected, sha.digest().joinToString("") { "%02x".format(it) }, crc.value, serverHash, expected?.let { received == it }, file.sizeExact && (file.size ?: 0) > 0, resumeSupported, resumeUsed, ms, received / maxOf(0.2, ms / 1000.0))
        } catch (e: Throwable) { out.delete(); throw e }
    }
}
