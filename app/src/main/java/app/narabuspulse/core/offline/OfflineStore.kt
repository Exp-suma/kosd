package app.narabuspulse.core.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.narabuspulse.core.Api
import app.narabuspulse.core.ApiException
import app.narabuspulse.core.Json
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.obj
import app.narabuspulse.core.str
import app.narabuspulse.core.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/**
 * オフライン現地活動モードの端末内データ（Web の lib/offline/records.js・sync.js・conflicts.js・settings.js と同じ仕組み）。
 *
 * 現地記録（メモ・写真・訪問・チェックリスト）は端末に保存し、通信が戻ったら1件ずつ同期する。
 * 写真の実体はアップロードが終わるまで端末に残す。サーバー側が他の端末で変更されていれば競合として残す。
 */
object OfflineStore {
    object SYNC { const val pending = "pending"; const val syncing = "syncing"; const val synced = "synced"; const val failed = "failed"; const val conflict = "conflict" }
    val KINDS = listOf("memo" to "メモ", "photo" to "写真", "visit" to "訪問", "spot" to "撮影地点", "vehicle" to "車両メモ", "checklist" to "チェックリスト")

    data class Settings(val enabled: Boolean = false, val autoSync: Boolean = true, val paused: Boolean = false, val retentionDays: Int = 14, val tileMaxTiles: Int = 240, val warnPercent: Int = 85, val lastSyncAt: Long = 0, val lastSyncError: String = "")
    data class Stats(val pending: Int = 0, val failed: Int = 0, val conflict: Int = 0, val total: Int = 0, val pendingBytes: Long = 0)
    data class SyncState(val running: Boolean = false, val phase: String = "idle", val index: Int = 0, val total: Int = 0, val label: String = "", val lastRunAt: Long = 0, val lastResult: Map<String, Int>? = null, val lastError: String = "")

    private const val MAX_ATTEMPTS = 5
    private const val AUTO_MIN_GAP = 20_000L
    private lateinit var ctx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastAutoAt = 0L

    private val _records = MutableStateFlow<List<JsonObject>>(emptyList())
    val records: StateFlow<List<JsonObject>> = _records
    private val _conflicts = MutableStateFlow<List<JsonObject>>(emptyList())
    val conflicts: StateFlow<List<JsonObject>> = _conflicts
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    private val _sync = MutableStateFlow(SyncState())
    val sync: StateFlow<SyncState> = _sync
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    private val dir by lazy { File(ctx.filesDir, "offline").apply { mkdirs() } }
    private val photoDir by lazy { File(dir, "photos").apply { mkdirs() } }
    private val recordsFile get() = File(dir, "records.json")
    private val conflictsFile get() = File(dir, "conflicts.json")
    private fun prefs() = ctx.getSharedPreferences("offline", Context.MODE_PRIVATE)

    fun init(context: Context) {
        ctx = context.applicationContext
        _settings.value = readSettings()
        scope.launch {
            _records.value = readList(recordsFile)
            _conflicts.value = readList(conflictsFile)
            refreshStats()
        }
        watchConnectivity()
    }

    // ---------------- 設定 ----------------

    private fun readSettings(): Settings {
        val p = prefs()
        fun clamp(v: Int, lo: Int, hi: Int) = v.coerceIn(lo, hi)
        return Settings(p.getBoolean("enabled", false), p.getBoolean("autoSync", true), p.getBoolean("paused", false),
            clamp(p.getInt("retentionDays", 14), 1, 120), clamp(p.getInt("tileMaxTiles", 240), 24, 400), clamp(p.getInt("warnPercent", 85), 50, 95),
            p.getLong("lastSyncAt", 0), p.getString("lastSyncError", "").orEmpty())
    }

    fun saveSettings(s: Settings) {
        val v = s.copy(retentionDays = s.retentionDays.coerceIn(1, 120), tileMaxTiles = s.tileMaxTiles.coerceIn(24, 400), warnPercent = s.warnPercent.coerceIn(50, 95))
        prefs().edit().putBoolean("enabled", v.enabled).putBoolean("autoSync", v.autoSync).putBoolean("paused", v.paused).putInt("retentionDays", v.retentionDays)
            .putInt("tileMaxTiles", v.tileMaxTiles).putInt("warnPercent", v.warnPercent).putLong("lastSyncAt", v.lastSyncAt).putString("lastSyncError", v.lastSyncError).apply()
        _settings.value = v
    }

    fun setPaused(paused: Boolean) { saveSettings(_settings.value.copy(paused = paused)); _sync.value = _sync.value.copy(lastError = if (paused) "同期を一時停止中です" else "") }

    // ---------------- 通信状態 ----------------

    private fun watchConnectivity() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        fun current() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        _online.value = current()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { val was = _online.value; _online.value = true; if (!was) scope.launch { delay(2000); autoSync("reconnect") } }
                override fun onLost(network: Network) { _online.value = current() }
            })
        }
    }

    fun isOnline() = _online.value

    // ---------------- 保存 ----------------

    private fun readList(f: File): List<JsonObject> = runCatching { (Json.parse(f.readText()) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty() }.getOrDefault(emptyList())
    private fun writeList(f: File, rows: List<JsonObject>) { val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(JsonArray(rows).toString()); tmp.renameTo(f) }

    private suspend fun mutate(block: (MutableList<JsonObject>) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock { val list = _records.value.toMutableList(); block(list); writeList(recordsFile, list); _records.value = list }
        refreshStats()
    }

    private fun refreshStats() {
        var pending = 0; var failed = 0; var conflict = 0; var total = 0; var bytes = 0L
        _records.value.forEach { r ->
            val st = r.str("syncState")
            if (r.bool("deleted") == true && st == SYNC.synced) return@forEach
            total++
            when (st) { SYNC.conflict -> conflict++; SYNC.failed -> failed++; SYNC.synced -> {}; else -> pending++ }
            if (st != SYNC.synced) bytes += r.list("photos").sumOf { p -> if (p.bool("uploaded") == true) 0L else p.long("size") ?: 0L }
        }
        _stats.value = Stats(pending, failed, conflict, total, bytes)
    }

    private fun uuid() = UUID.randomUUID().toString()
    fun newClientKey() = "fn-${uuid()}"
    private fun JsonObject.with(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(this + pairs.map { it.first to toJson(it.second) })

    fun getRecord(id: String): JsonObject? = _records.value.firstOrNull { it.str("id") == id }
    fun listRecords(): List<JsonObject> = _records.value.sortedByDescending { it.long("createdAt") ?: 0 }

    private suspend fun putRecord(r: JsonObject) = mutate { list -> val i = list.indexOfFirst { it.str("id") == r.str("id") }; if (i >= 0) list[i] = r else list.add(r) }
    private suspend fun removeRecordHard(id: String) {
        getRecord(id)?.list("photos")?.forEach { p -> p.str("id")?.let { File(photoDir, it).delete() } }
        mutate { list -> list.removeAll { it.str("id") == id } }
    }

    /** 新しい記録（Web の newRecord と同じ項目）。 */
    suspend fun createRecord(partial: Map<String, Any?>): JsonObject {
        val now = System.currentTimeMillis()
        val base = mutableMapOf<String, Any?>(
            "id" to "local-${uuid()}", "clientKey" to newClientKey(), "kind" to "memo", "content" to "", "checklist" to JsonArray(emptyList()), "occurredAt" to now,
            "lat" to null, "lon" to null, "latLonSource" to "none", "vehicle" to "", "vehicleType" to "", "stopName" to "", "spotId" to "", "spotName" to "",
            "planId" to "", "planName" to "", "photos" to JsonArray(emptyList()), "createdAt" to now, "updatedAt" to now, "syncState" to SYNC.pending, "attempts" to 0,
            "nextAttemptAt" to 0, "lastError" to "", "serverId" to null, "serverUpdatedAt" to null, "syncedAt" to null, "deleted" to false, "recordedOffline" to false,
            "device" to "Android",
        )
        base.putAll(partial); base["syncState"] = SYNC.pending
        val r = JsonObject(base.mapValues { toJson(it.value) })
        putRecord(r)
        return r
    }

    /** 記録を編集する（未同期に戻し、再試行待ちの時刻をリセットする）。 */
    suspend fun updateRecord(id: String, patch: Map<String, Any?>): JsonObject {
        val cur = getRecord(id) ?: throw IllegalStateException("記録が見つかりません")
        val next = JsonObject(cur + patch.mapValues { toJson(it.value) }).with("updatedAt" to System.currentTimeMillis(), "syncState" to SYNC.pending, "attempts" to 0, "nextAttemptAt" to 0, "lastError" to "")
        putRecord(next); return next
    }

    /** 削除する。サーバーに保存済みの記録は「削除待ち」として残し、同期でサーバー側も消す。 */
    suspend fun deleteRecord(id: String): String {
        val cur = getRecord(id) ?: return "missing"
        if (cur.str("serverId") == null) { removeRecordHard(id); return "local" }
        putRecord(cur.with("deleted" to true, "updatedAt" to System.currentTimeMillis(), "syncState" to SYNC.pending, "attempts" to 0, "nextAttemptAt" to 0, "lastError" to ""))
        return "queued"
    }

    /** 写真を端末に保存して記録に付ける（アップロードは同期のとき）。 */
    suspend fun addPhoto(recordId: String, name: String, mime: String, bytes: ByteArray): JsonObject {
        val r = getRecord(recordId) ?: throw IllegalStateException("記録が見つかりません")
        val id = "photo-${uuid()}"
        withContext(Dispatchers.IO) { File(photoDir, id).writeBytes(bytes) }
        val now = System.currentTimeMillis()
        val meta = jsonOf("id" to id, "name" to name, "mime" to mime, "size" to bytes.size, "uploaded" to false, "fileUri" to "", "addedAt" to now)
        val next = r.with("photos" to JsonArray(r.list("photos") + meta), "updatedAt" to now, "syncState" to SYNC.pending, "attempts" to 0, "nextAttemptAt" to 0)
        putRecord(next); return next
    }

    suspend fun removePhoto(recordId: String, photoId: String) {
        val r = getRecord(recordId) ?: return
        withContext(Dispatchers.IO) { File(photoDir, photoId).delete() }
        putRecord(r.with("photos" to JsonArray(r.list("photos").filter { it.str("id") != photoId }), "updatedAt" to System.currentTimeMillis()))
    }

    /** 端末に残っている写真の実体（表示用）。 */
    fun localPhoto(photoId: String): File? = File(photoDir, photoId).takeIf { it.exists() && it.length() > 0 }

    fun photoBytesTotal(): Long = photoDir.listFiles()?.sumOf { it.length() } ?: 0

    // ---------------- 同期 ----------------

    private fun payloadOf(r: JsonObject): JsonObject {
        val photos = r.list("photos").filter { !it.str("fileUri").isNullOrBlank() }
        val m = mutableMapOf<String, Any?>(
            "client_key" to r.str("clientKey"), "kind" to (r.str("kind") ?: "memo"), "content" to r.str("content").orEmpty(), "lat_lon_source" to (r.str("latLonSource") ?: "none"),
            "vehicle" to r.str("vehicle").orEmpty(), "vehicle_type" to r.str("vehicleType").orEmpty(), "stop_name" to r.str("stopName").orEmpty(), "spot_id" to r.str("spotId").orEmpty(),
            "spot_name" to r.str("spotName").orEmpty(), "plan_id" to r.str("planId").orEmpty(), "plan_name" to r.str("planName").orEmpty(),
            "checklist" to (r.list("checklist").takeIf { it.isNotEmpty() }?.let { JsonArray(it).toString() } ?: ""),
            "photo_uri" to (photos.firstOrNull()?.str("fileUri") ?: ""), "photo_count" to photos.size,
            "photos" to (if (photos.isNotEmpty()) JsonArray(photos.map { jsonOf("file_uri" to it.str("fileUri"), "uploaded_at" to (it.long("uploadedAt") ?: System.currentTimeMillis())) }).toString() else ""),
            "device" to r.str("device").orEmpty(), "recorded_offline" to (r.bool("recordedOffline") == true), "client_updated_at" to (r.long("updatedAt") ?: System.currentTimeMillis()), "synced_at" to System.currentTimeMillis(),
        )
        r.long("occurredAt")?.let { m["occurred_at"] = it }
        r.dbl("lat")?.let { m["lat"] = it }; r.dbl("lon")?.let { m["lon"] = it }
        return JsonObject(m.mapValues { toJson(it.value) })
    }

    private suspend fun fetchServer(id: String?): JsonElement? = if (id == null) null else Api.rpc("offline/note-fns#noteGet", jsonOf("id" to id)).takeIf { it !is JsonNull }
    private suspend fun stampOf(row: JsonElement?): String = row.str("updated_date") ?: fetchServer(row.str("id")).str("updated_date").orEmpty()

    private suspend fun raiseConflict(r: JsonObject, server: JsonElement): JsonObject {
        val c = jsonOf("id" to "conflict-${r.str("id")}", "recordId" to r.str("id"), "at" to System.currentTimeMillis(), "clientKey" to r.str("clientKey"),
            "local" to localSide(r), "server" to serverSide(server), "serverUpdatedAt" to server.str("updated_date").orEmpty(), "serverRaw" to server)
        withContext(Dispatchers.IO) { mutex.withLock { val list = _conflicts.value.filter { it.str("id") != c.str("id") } + c; writeList(conflictsFile, list); _conflicts.value = list } }
        val next = r.with("syncState" to SYNC.conflict, "lastError" to "別の端末で変更されています（どちらを残すか選んでください）")
        putRecord(next); return next
    }

    fun localSide(r: JsonObject) = jsonOf("content" to r.str("content").orEmpty(), "kind" to (r.str("kind") ?: "memo"), "occurredAt" to r.long("occurredAt"), "lat" to r.dbl("lat"), "lon" to r.dbl("lon"),
        "vehicle" to r.str("vehicle").orEmpty(), "stopName" to r.str("stopName").orEmpty(), "spotName" to r.str("spotName").orEmpty(), "planName" to r.str("planName").orEmpty(),
        "checklist" to JsonArray(r.list("checklist")), "photoCount" to r.list("photos").size, "updatedAt" to r.long("updatedAt"))

    fun serverSide(row: JsonElement) = jsonOf("content" to row.str("content").orEmpty(), "kind" to (row.str("kind") ?: "memo"), "occurredAt" to row.long("occurred_at"), "lat" to row.dbl("lat"), "lon" to row.dbl("lon"),
        "vehicle" to row.str("vehicle").orEmpty(), "stopName" to row.str("stop_name").orEmpty(), "spotName" to row.str("spot_name").orEmpty(), "planName" to row.str("plan_name").orEmpty(),
        "checklist" to runCatching { Json.parse(row.str("checklist") ?: "[]") }.getOrDefault(JsonArray(emptyList())), "photoCount" to (row.int("photo_count") ?: 0),
        "updatedAt" to row.str("updated_date")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }, "updatedText" to row.str("updated_date").orEmpty())

    private suspend fun uploadOnePhoto(r: JsonObject): JsonObject {
        val p = r.list("photos").firstOrNull { it.bool("uploaded") != true } ?: return r
        val id = p.str("id") ?: return r
        val f = localPhoto(id) ?: return r
        val uri = Api.uploadPrivateFile(p.str("name") ?: "photo.jpg", p.str("mime") ?: "image/jpeg", withContext(Dispatchers.IO) { f.readBytes() })
        val photos = r.list("photos").map { if (it.str("id") == id) (it as JsonObject).with("uploaded" to true, "fileUri" to uri, "uploadedAt" to System.currentTimeMillis()) else it }
        val next = r.with("photos" to JsonArray(photos))
        putRecord(next)
        withContext(Dispatchers.IO) { f.delete() } // アップロードが終わった原本は端末から消して容量を空ける
        return next
    }

    private suspend fun pushOne(input: JsonObject): Pair<String, Int> {
        var r = getRecord(input.str("id").orEmpty()) ?: input
        var photos = 0
        if (r.list("photos").any { it.bool("uploaded") != true }) {
            val before = r.list("photos").count { it.bool("uploaded") == true }
            r = uploadOnePhoto(r)
            photos = maxOf(0, r.list("photos").count { it.bool("uploaded") == true } - before)
        }
        val now = System.currentTimeMillis()
        fun synced(rec: JsonObject, extra: Array<Pair<String, Any?>>) = rec.with(*extra, "syncState" to SYNC.synced, "syncedAt" to now, "attempts" to 0, "nextAttemptAt" to 0, "lastError" to "")
        if (r.bool("deleted") == true) {
            val sid = r.str("serverId") ?: run { removeRecordHard(r.str("id")!!); return "deleted" to photos }
            val server = fetchServer(sid)
            if (server != null && r.str("serverUpdatedAt") != null && server.str("updated_date") != null && server.str("updated_date") != r.str("serverUpdatedAt")) { raiseConflict(r, server); return "conflict" to photos }
            if (server != null) Api.rpc("offline/note-fns#noteDelete", jsonOf("id" to sid))
            removeRecordHard(r.str("id")!!); return "deleted" to photos
        }
        if (r.str("serverId") == null) {
            val existing = Api.rpc("offline/note-fns#noteFindByKey", jsonOf("clientKey" to r.str("clientKey"))).takeIf { it !is JsonNull }
            if (existing != null) { r = r.with("serverId" to existing.str("id"), "serverUpdatedAt" to existing.str("updated_date")); putRecord(r) }
            else {
                val created = Api.rpc("offline/note-fns#noteCreate", jsonOf("payload" to payloadOf(r)))
                putRecord(synced(r, arrayOf("serverId" to created.str("id"), "serverUpdatedAt" to stampOf(created)))); return "created" to photos
            }
        }
        val server = fetchServer(r.str("serverId"))
        if (server == null) {
            val created = Api.rpc("offline/note-fns#noteCreate", jsonOf("payload" to payloadOf(r)))
            putRecord(synced(r, arrayOf("serverId" to created.str("id"), "serverUpdatedAt" to stampOf(created)))); return "created" to photos
        }
        if (r.str("serverUpdatedAt") != null && server.str("updated_date") != null && server.str("updated_date") != r.str("serverUpdatedAt")) { raiseConflict(r, server); return "conflict" to photos }
        val updated = Api.rpc("offline/note-fns#noteUpdate", jsonOf("id" to r.str("serverId"), "payload" to payloadOf(r)))
        putRecord(synced(r, arrayOf("serverUpdatedAt" to (stampOf(updated).ifBlank { server.str("updated_date").orEmpty() }))))
        return "updated" to photos
    }

    private suspend fun failRecord(r: JsonObject, e: Throwable) {
        val status = (e as? ApiException)?.status ?: 0
        val attempts = (r.int("attempts") ?: 0) + 1
        val base = if (status == 429 || status == 522) 30_000.0 else 5_000.0
        val delayMs = (minOf(base * Math.pow(2.0, minOf(attempts, 6).toDouble()), 600_000.0) * (0.7 + Math.random() * 0.6)).toLong()
        putRecord(r.with("attempts" to attempts, "nextAttemptAt" to System.currentTimeMillis() + delayMs, "lastError" to (e.message ?: "同期に失敗しました"), "syncState" to if (attempts >= MAX_ATTEMPTS) SYNC.failed else SYNC.pending))
    }

    /** 同期を実行する（Web の syncNow と同じ流れ）。 */
    suspend fun syncNow(force: Boolean = false): Map<String, Int> {
        if (_sync.value.running) return mapOf("skipped" to 1)
        if (_settings.value.paused && !force) return mapOf("skipped" to 1)
        if (!isOnline()) { _sync.value = _sync.value.copy(lastError = "オフラインのため同期できません（記録は端末に残っています）"); return mapOf("skipped" to 1) }
        val queue = _records.value.filter { it.str("syncState") != SYNC.synced && it.str("syncState") != SYNC.conflict }.sortedBy { it.long("updatedAt") ?: 0 }
        if (queue.isEmpty()) { _sync.value = _sync.value.copy(lastRunAt = System.currentTimeMillis(), lastError = "", phase = "done"); return emptyMap() }
        _sync.value = _sync.value.copy(running = true, phase = "running", lastError = "", index = 0, total = queue.size, label = "")
        val result = mutableMapOf("created" to 0, "updated" to 0, "deleted" to 0, "photos" to 0, "conflicts" to 0, "failed" to 0)
        for ((i, q) in queue.withIndex()) {
            if (_settings.value.paused && !force) break
            if (!isOnline()) break
            val r = getRecord(q.str("id").orEmpty()) ?: continue
            if (r.str("syncState") == SYNC.synced || r.str("syncState") == SYNC.conflict) continue
            if (!force && (r.long("nextAttemptAt") ?: 0) > System.currentTimeMillis()) continue
            _sync.value = _sync.value.copy(index = i + 1, total = queue.size, label = (r.str("content")?.ifBlank { null } ?: r.str("kind") ?: "記録").take(30))
            try {
                val (outcome, ph) = pushOne(r)
                result["photos"] = result["photos"]!! + ph
                when (outcome) { "created" -> result["created"] = result["created"]!! + 1; "updated" -> result["updated"] = result["updated"]!! + 1; "deleted" -> result["deleted"] = result["deleted"]!! + 1; "conflict" -> result["conflicts"] = result["conflicts"]!! + 1 }
                delay(300)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                result["failed"] = result["failed"]!! + 1
                failRecord(getRecord(r.str("id").orEmpty()) ?: r, e)
                delay(500)
            }
        }
        val failed = result["failed"]!!; val conflicts = result["conflicts"]!!
        val err = when { failed > 0 -> "${failed}件を送信できませんでした（端末に残しています）"; conflicts > 0 -> "${conflicts}件が他の端末の変更と競合しています"; else -> "" }
        val now = System.currentTimeMillis()
        _sync.value = SyncState(running = false, phase = if (err.isNotBlank()) "error" else "done", lastRunAt = now, lastResult = result, lastError = err)
        saveSettings(if (err.isBlank()) _settings.value.copy(lastSyncAt = now, lastSyncError = "") else _settings.value.copy(lastSyncError = err))
        return result
    }

    /** 通信が戻ったとき・記録の直後の自動同期（短時間に何度も走らせない）。 */
    fun autoSync(reason: String = "reconnect") {
        val s = _settings.value
        if (!s.enabled || !s.autoSync || s.paused || !isOnline() || _sync.value.running) return
        if (System.currentTimeMillis() - lastAutoAt < AUTO_MIN_GAP) return
        lastAutoAt = System.currentTimeMillis()
        scope.launch { runCatching { syncNow() } }
    }

    /** 競合を解決する。choice: local=端末側 / server=サーバー側 / both=両方を残す */
    suspend fun resolveConflict(conflictId: String, choice: String) {
        val c = _conflicts.value.firstOrNull { it.str("id") == conflictId } ?: throw IllegalStateException("競合が見つかりません")
        if (!isOnline()) throw IllegalStateException("オンラインに戻ってから選んでください")
        val r = getRecord(c.str("recordId").orEmpty())
        if (r != null) {
            val server = fetchServer(r.str("serverId"))
            val raw = c["serverRaw"] ?: JsonObject(emptyMap())
            fun withServer(rec: JsonObject): JsonObject {
                val srvPhotos = runCatching { (Json.parse(raw.str("photos") ?: "[]") as JsonArray).filter { !it.str("file_uri").isNullOrBlank() }.mapIndexed { i, p -> jsonOf("id" to "srv-$i-${System.currentTimeMillis()}", "name" to "サーバー側の写真", "size" to 0, "uploaded" to true, "fileUri" to p.str("file_uri"), "uploadedAt" to p.long("uploaded_at")) } }.getOrDefault(emptyList())
                val merged = rec.with("content" to (raw.str("content") ?: rec.str("content")), "kind" to (raw.str("kind") ?: rec.str("kind")), "occurredAt" to raw.long("occurred_at"), "lat" to raw.dbl("lat"), "lon" to raw.dbl("lon"),
                    "vehicle" to raw.str("vehicle").orEmpty(), "stopName" to raw.str("stop_name").orEmpty(), "spotName" to raw.str("spot_name").orEmpty(), "spotId" to raw.str("spot_id").orEmpty(),
                    "planId" to raw.str("plan_id").orEmpty(), "planName" to raw.str("plan_name").orEmpty(), "checklist" to runCatching { Json.parse(raw.str("checklist") ?: "[]") }.getOrDefault(JsonArray(emptyList())),
                    "photos" to JsonArray(srvPhotos + rec.list("photos")))
                val needs = merged.list("photos").any { it.bool("uploaded") != true }
                return merged.with("serverUpdatedAt" to (c.str("serverUpdatedAt")?.ifBlank { null } ?: server.str("updated_date") ?: rec.str("serverUpdatedAt")), "syncState" to if (needs) SYNC.pending else SYNC.synced,
                    "syncedAt" to if (needs) null else System.currentTimeMillis(), "attempts" to 0, "nextAttemptAt" to 0, "lastError" to "")
            }
            when (choice) {
                "server" -> putRecord(withServer(r))
                "local" -> {
                    if (server == null) { val created = Api.rpc("offline/note-fns#noteCreate", jsonOf("payload" to payloadOf(r))); putRecord(r.with("serverId" to created.str("id"), "serverUpdatedAt" to stampOf(created), "syncState" to SYNC.synced, "syncedAt" to System.currentTimeMillis(), "lastError" to "")) }
                    else { val updated = Api.rpc("offline/note-fns#noteUpdate", jsonOf("id" to r.str("serverId"), "payload" to payloadOf(r))); putRecord(r.with("serverUpdatedAt" to stampOf(updated).ifBlank { server.str("updated_date").orEmpty() }, "syncState" to SYNC.synced, "syncedAt" to System.currentTimeMillis(), "attempts" to 0, "nextAttemptAt" to 0, "lastError" to "")) }
                }
                "both" -> {
                    val now = System.currentTimeMillis()
                    putRecord(r.with("id" to "local-${uuid()}", "clientKey" to newClientKey(), "serverId" to null, "serverUpdatedAt" to null, "createdAt" to now, "updatedAt" to now, "syncState" to SYNC.pending, "syncedAt" to null,
                        "attempts" to 0, "nextAttemptAt" to 0, "lastError" to "", "deleted" to false, "content" to (r.str("content")?.trim()?.ifBlank { null } ?: "(端末側の記録)")))
                    putRecord(withServer(r))
                }
                else -> throw IllegalArgumentException("どちらを残すか選んでください")
            }
        }
        withContext(Dispatchers.IO) { mutex.withLock { val list = _conflicts.value.filter { it.str("id") != conflictId }; writeList(conflictsFile, list); _conflicts.value = list } }
        _sync.value = _sync.value.copy(lastError = "")
    }

    /** 保存期間を過ぎた同期済みの記録を整理する（未同期は消さない）。 */
    suspend fun retentionCleanup(days: Int): Int {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        var n = 0
        mutate { list -> val keep = list.filter { !(it.str("syncState") == SYNC.synced && (it.long("syncedAt") ?: 0) < cutoff) }; n = list.size - keep.size; list.clear(); list.addAll(keep) }
        return n
    }

    /** バックアップ（端末の記録をそのまま JSON にする）。 */
    fun backupJson(): String = jsonOf("at" to System.currentTimeMillis(), "records" to JsonArray(_records.value), "conflicts" to JsonArray(_conflicts.value)).toString()

}
