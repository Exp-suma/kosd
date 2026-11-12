package app.narabuspulse.feature.field

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.items
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.offline.OfflineStore
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private val ZONE = ZoneId.systemDefault()

fun formatBytes(b: Long?): String = when { b == null || b <= 0 -> "0B"; b >= 1_073_741_824 -> "%.1fGB".format(b / 1_073_741_824.0); b >= 1_048_576 -> "%.1fMB".format(b / 1_048_576.0); b >= 1024 -> "${b / 1024}KB"; else -> "${b}B" }

private data class PickedPhoto(val name: String, val mime: String, val bytes: ByteArray)

/** 現地記録の作成・編集（Web: components/offline/FieldNoteForm.jsx）。位置・日時が取れない場合は空欄のままにする。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldNoteForm(record: JsonObject?, spots: List<JsonElement>, plans: List<JsonElement>, onSaved: () -> Unit, onCancel: (() -> Unit)? = null, presetPlan: Pair<String, String>? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val online by OfflineStore.online.collectAsState()
    var kind by remember(record) { mutableStateOf(record.str("kind") ?: "memo") }
    var content by remember(record) { mutableStateOf(record.str("content").orEmpty()) }
    var checklist by remember(record) { mutableStateOf(record.list("checklist").map { (it.str("label").orEmpty()) to (it.bool("done") == true) }) }
    var occurredAt by remember(record) { mutableStateOf(if (record == null) System.currentTimeMillis() else record.long("occurredAt")) }
    var lat by remember(record) { mutableStateOf(record.dbl("lat")) }
    var lon by remember(record) { mutableStateOf(record.dbl("lon")) }
    var latLonSource by remember(record) { mutableStateOf(record.str("latLonSource") ?: "none") }
    var vehicle by remember(record) { mutableStateOf(record.str("vehicle").orEmpty()) }
    var vehicleType by remember(record) { mutableStateOf(record.str("vehicleType").orEmpty()) }
    var stopName by remember(record) { mutableStateOf(record.str("stopName").orEmpty()) }
    var spotId by remember(record) { mutableStateOf(record.str("spotId").orEmpty()) }
    var spotName by remember(record) { mutableStateOf(record.str("spotName").orEmpty()) }
    var planId by remember(record) { mutableStateOf(record.str("planId") ?: presetPlan?.first.orEmpty()) }
    var planName by remember(record) { mutableStateOf(record.str("planName") ?: presetPlan?.second.orEmpty()) }
    var files by remember { mutableStateOf(listOf<PickedPhoto>()) }
    var busy by remember { mutableStateOf(false) }
    var geoBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var geoError by remember { mutableStateOf("") }
    var newItem by remember { mutableStateOf("") }
    val locate = rememberLocationRequest { loc ->
        geoBusy = false
        if (loc == null) geoError = "現在地を取得できませんでした（位置は空欄のまま保存できます）"
        else { lat = "%.6f".format(loc.lat).toDouble(); lon = "%.6f".format(loc.lon).toDouble(); latLonSource = "device_gps" }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        scope.launch {
            val add = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    val mime = context.contentResolver.getType(u) ?: "image/jpeg"
                    if (!mime.startsWith("image/")) return@mapNotNull null
                    val bytes = context.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return@mapNotNull null
                    if (bytes.size > 20 * 1024 * 1024) null else PickedPhoto(u.lastPathSegment ?: "photo.jpg", mime, bytes)
                }
            }
            files = files + add
        }
    }
    fun localDate(ms: Long?) = ms?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZONE) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SelectField(OfflineStore.KINDS, kind, { kind = it }, label = "種類")
            val d = localDate(occurredAt)
            DateField(d?.toLocalDate()?.toString().orEmpty(), { v -> val t = d?.toLocalTime() ?: java.time.LocalTime.now(); occurredAt = java.time.LocalDate.parse(v).atTime(t).atZone(ZONE).toInstant().toEpochMilli() }, label = if (d == null) "日時なし" else null)
            TimeField(d?.toLocalTime()?.toString()?.take(5).orEmpty(), { v -> val day = d?.toLocalDate() ?: java.time.LocalDate.now(); occurredAt = day.atTime(java.time.LocalTime.parse(v)).atZone(ZONE).toInstant().toEpochMilli() })
            TextButton(onClick = { occurredAt = null }) { Text("日時なし", fontSize = 11.sp) }
        }
        OutlinedTextField(vehicle, { vehicle = it }, label = { Text("車番", fontSize = 12.sp) }, placeholder = { Text("例：奈良200か1234", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(content, { content = it }, placeholder = { Text("現地で気づいたこと（例：○時頃、正面が逆光。停車位置は停留所の10m手前）", fontSize = 12.sp) }, minLines = 3, modifier = Modifier.fillMaxWidth())
        if (kind == "checklist") {
            Text("チェックリスト", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newItem, { newItem = it }, placeholder = { Text("例：三脚、レンズ拭き、予備バッテリー", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                SmallButton("追加", { if (newItem.isNotBlank()) { checklist = checklist + (newItem.trim() to false); newItem = "" } }, modifier = Modifier.padding(start = 6.dp))
            }
            checklist.forEachIndexed { i, (l, done) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(done, { v -> checklist = checklist.mapIndexed { j, p -> if (j == i) p.first to v else p } })
                    Text(l, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { checklist = checklist.filterIndexed { j, _ -> j != i } }) { Icon(Icons.Outlined.Close, "削除") }
                }
            }
        }
        OutlinedTextField(stopName, { stopName = it }, label = { Text("関連する停留所", fontSize = 12.sp) }, placeholder = { Text("例：近鉄奈良駅", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectField(listOf("" to "撮影地点：選択しない") + spots.mapNotNull { s -> s.str("id")?.let { it to (s.str("name") ?: it) } }, spotId, { v -> spotId = v; spotName = spots.firstOrNull { it.str("id") == v }?.str("name").orEmpty() })
            SelectField(listOf("" to "遠征計画：選択しない") + plans.mapNotNull { p -> p.str("id")?.let { it to (p.str("title") ?: it) } }, planId, { v -> planId = v; planName = plans.firstOrNull { it.str("id") == v }?.str("title").orEmpty() })
        }
        OutlinedTextField(vehicleType, { vehicleType = it }, label = { Text("型式・登録番号など", fontSize = 12.sp) }, placeholder = { Text("例：いすゞ エルガ", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            val la = lat; val lo = lon
            Text(if (la != null && lo != null) "位置 ${"%.5f".format(la)}, ${"%.5f".format(lo)}（${when (latLonSource) { "device_gps" -> "端末GPS"; "manual" -> "手入力"; else -> "地図" }}）" else "位置 未取得（空欄のまま保存できます）", fontSize = 11.sp, modifier = Modifier.weight(1f))
            SmallButton(if (geoBusy) "取得中…" else "端末の現在地", { geoBusy = true; geoError = ""; locate() }, enabled = !geoBusy, icon = Icons.Outlined.MyLocation)
            TextButton(onClick = { lat = null; lon = null; latLonSource = "none" }) { Text("クリア", fontSize = 11.sp) }
        }
        if (geoError.isNotBlank()) Text(geoError, fontSize = 10.sp, color = Brand.Warning)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton("写真を追加", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, icon = Icons.Outlined.CameraAlt)
            Muted("  ${files.size}枚・${formatBytes(files.sumOf { it.bytes.size.toLong() })}（1枚20MBまで）", 10)
        }
        if (files.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            files.forEachIndexed { i, f ->
                Box {
                    val bmp = remember(f) { runCatching { BitmapFactory.decodeByteArray(f.bytes, 0, f.bytes.size, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }.getOrNull() }
                    if (bmp != null) Image(bmp, f.name, Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    IconButton(onClick = { files = files.filterIndexed { j, _ -> j != i } }, modifier = Modifier.size(24.dp).align(Alignment.TopEnd)) { Icon(Icons.Outlined.Close, "写真を外す") }
                }
            }
        }
        if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = Brand.Destructive)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton(if (busy) "保存中…" else if (record != null) "変更を保存" else "端末に記録する", {
                error = ""
                val c = content.trim()
                if (c.isEmpty() && files.isEmpty() && kind != "checklist") { error = "内容を入力するか、写真を追加してください"; return@SmallButton }
                if (kind == "checklist" && checklist.isEmpty()) { error = "チェック項目を追加してください"; return@SmallButton }
                busy = true
                scope.launch {
                    try {
                        val payload = mapOf("kind" to kind, "content" to c.ifEmpty { if (kind == "photo") "写真を記録" else checklist.joinToString(" / ") { it.first } },
                            "checklist" to JsonArray(checklist.map { app.narabuspulse.core.jsonOf("label" to it.first, "done" to it.second) }), "occurredAt" to occurredAt, "lat" to lat, "lon" to lon,
                            "latLonSource" to if (lat != null && lon != null) latLonSource.takeIf { it != "none" } ?: "manual" else "none", "vehicle" to vehicle, "vehicleType" to vehicleType, "stopName" to stopName,
                            "spotId" to spotId, "spotName" to spotName, "planId" to planId, "planName" to planName, "recordedOffline" to !online, "device" to "モバイル")
                        val id = if (record != null) { OfflineStore.updateRecord(record.str("id").orEmpty(), payload); record.str("id").orEmpty() } else OfflineStore.createRecord(payload).str("id").orEmpty()
                        files.forEach { OfflineStore.addPhoto(id, it.name, it.mime, it.bytes) }
                        files = emptyList(); content = ""; checklist = emptyList(); occurredAt = System.currentTimeMillis()
                        onSaved()
                        OfflineStore.autoSync("after-record")
                    } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.message ?: "保存できませんでした" } finally { busy = false }
                }
            }, enabled = !busy, icon = Icons.Outlined.Save, primary = true)
            onCancel?.let { TextButton(onClick = it) { Text("やめる", fontSize = 12.sp) } }
        }
        Muted(if (online) "保存後、自動で同期します。" else "オフライン中：端末に保存し、通信復旧後に同期します。", 10)
    }
}

private val KIND_LABEL = mapOf("memo" to "メモ", "photo" to "写真", "visit" to "訪問", "spot" to "撮影地点", "vehicle" to "車両", "checklist" to "チェック")
private val STATE = mapOf(OfflineStore.SYNC.pending to ("同期待ち" to Brand.Warning), OfflineStore.SYNC.syncing to ("同期中" to INFO), OfflineStore.SYNC.synced to ("同期済み" to Brand.Success), OfflineStore.SYNC.failed to ("同期失敗" to Brand.Destructive), OfflineStore.SYNC.conflict to ("競合" to Brand.Destructive))

/** 端末に保存した現地記録の一覧（Web: FieldNoteList.jsx）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldNoteList(records: List<JsonObject>, onEdit: ((JsonObject) -> Unit)? = null, onDelete: ((JsonObject) -> Unit)? = null, emptyText: String = "まだ記録はありません。") {
    if (records.isEmpty()) { Muted(emptyText, 12); return }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        records.forEach { r ->
            val (sl, sc) = STATE[r.str("syncState")] ?: STATE.getValue(OfflineStore.SYNC.pending)
            val photos = r.list("photos"); val pendingPhotos = photos.count { it.bool("uploaded") != true }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Tag(KIND_LABEL[r.str("kind")] ?: "メモ", Brand.MutedFg); Tag(sl, sc)
                    Muted(r.long("occurredAt")?.let { BusFormat.dateTime(it) } ?: "日時なし", 10)
                    if (r.bool("recordedOffline") == true) Tag("オフライン記録", Brand.MutedFg)
                }
                Text(r.str("content")?.ifBlank { null } ?: "（本文なし）", fontSize = 13.sp)
                val la = r.dbl("lat"); val lo = r.dbl("lon")
                Muted(listOfNotNull(if (la != null && lo != null) "%.5f, %.5f".format(la, lo) else "位置なし", r.str("vehicle")?.takeIf { it.isNotBlank() }?.let { "車番 $it" }, r.str("stopName")?.takeIf { it.isNotBlank() }?.let { "停留所 $it" },
                    r.str("spotName")?.takeIf { it.isNotBlank() }?.let { "撮影地点 $it" }, r.str("planName")?.takeIf { it.isNotBlank() }?.let { "遠征 $it" },
                    photos.takeIf { it.isNotEmpty() }?.let { "写真${it.size}枚${if (pendingPhotos > 0) "（未送信${pendingPhotos}枚）" else ""}" },
                    r.list("checklist").takeIf { it.isNotEmpty() }?.let { c -> "チェック ${c.count { it.bool("done") == true }}/${c.size}" }).joinToString("　"), 10)
                if (!r.str("lastError").isNullOrBlank() && r.str("syncState") != OfflineStore.SYNC.synced) Text(r.str("lastError").orEmpty(), fontSize = 10.sp, color = Brand.Destructive)
                Row {
                    onEdit?.let { e -> TextButton(onClick = { e(r) }) { Icon(Icons.Outlined.Edit, null, Modifier.size(14.dp)); Text(" 編集", fontSize = 11.sp) } }
                    onDelete?.let { d -> TextButton(onClick = { d(r) }) { Icon(Icons.Outlined.Delete, null, Modifier.size(14.dp)); Text(" 削除", fontSize = 11.sp) } }
                }
            }
        }
    }
}

/** 添付写真（Web: RecordPhotoStrip.jsx）。 */
@Composable
fun RecordPhotoStrip(record: JsonObject, onRemove: ((String) -> Unit)?) {
    val photos = record.list("photos")
    if (photos.isEmpty()) return
    Muted("添付写真 ${photos.size}枚（未送信は通信復旧後にアップロードします）", 10)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        photos.forEach { p ->
            val id = p.str("id").orEmpty()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val bmp = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, id) {
                    value = withContext(Dispatchers.IO) { OfflineStore.localPhoto(id)?.let { f -> runCatching { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }.getOrNull() } }
                }.value
                if (bmp != null) Image(bmp, p.str("name"), Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                else Muted(if (p.bool("uploaded") == true) "サーバー保存済み" else "端末に無し", 9)
                onRemove?.let { r -> TextButton(onClick = { r(id) }) { Text("削除", fontSize = 10.sp, color = Brand.Destructive) } }
            }
        }
    }
}

@Suppress("unused") private fun keepItems(e: JsonElement?) = e.items()
@Suppress("unused") private val keepMat = MaterialTheme
