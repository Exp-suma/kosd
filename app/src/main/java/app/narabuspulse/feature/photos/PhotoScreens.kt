package app.narabuspulse.feature.photos

import android.media.ExifInterface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Json
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.KpiRow
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.DateRange
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.RangePicker
import app.narabuspulse.feature.data.addDays
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayInputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

private val TABS = listOf("/photos" to "撮影データベース", "/photos/learning" to "設定学習・地点実績")
private val RATINGS = listOf("favorite" to "お気に入り", "success" to "成功", "failed" to "失敗", "none" to "未評価")
private val RATING_COLOR = mapOf("favorite" to Brand.Warning, "success" to Brand.Success, "failed" to Brand.Destructive, "none" to Brand.MutedFg)
private val DIRECTION = listOf("front" to "正面", "side" to "側面", "rear" to "後面", "other" to "その他")
private val STYLE = listOf("static" to "止め", "panning" to "流し撮り", "night" to "夜景", "other" to "その他")

@Composable
private fun PhotosScaffold(current: String, title: String, description: String, content: LazyListScope.() -> Unit) {
    val nav = LocalNav.current
    ScreenList {
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ChipRow(TABS, current, { if (it != current) nav.go(it) }); Heading(title, description) } }
        content()
    }
}

/** JPEG の EXIF（Web: lib/photo/exif.js と同じ項目）。 */
private class Exif(val found: Boolean, val reason: String = "", val capturedAt: String? = null, val camera: String? = null, val lens: String? = null, val focal: Double? = null, val shutter: String? = null, val shutterSeconds: Double? = null,
                   val aperture: Double? = null, val iso: Int? = null, val bias: Double? = null, val lat: Double? = null, val lon: Double? = null)

private fun r1(v: Double) = Math.round(v * 10) / 10.0
private fun readExif(bytes: ByteArray): Exif {
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return Exif(false, "JPEG画像ではないためEXIFを読み取れません")
    return try {
        val e = ExifInterface(ByteArrayInputStream(bytes))
        fun s(tag: String) = e.getAttribute(tag)?.trim()?.takeIf { it.isNotBlank() }
        fun d(tag: String) = e.getAttributeDouble(tag, Double.NaN).takeIf { it.isFinite() && e.getAttribute(tag) != null }
        val camera = listOfNotNull(s(ExifInterface.TAG_MAKE), s(ExifInterface.TAG_MODEL)).distinct().joinToString(" ").ifBlank { null }
        val captured = (s(ExifInterface.TAG_DATETIME_ORIGINAL) ?: s(ExifInterface.TAG_DATETIME_DIGITIZED) ?: s(ExifInterface.TAG_DATETIME))?.let { Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""").find(it) }?.destructured?.let { (y, mo, da, h, mi, se) -> "$y-$mo-${da}T$h:$mi:$se" }
        val t = d(ExifInterface.TAG_EXPOSURE_TIME)?.takeIf { it > 0 }
        val f = d(ExifInterface.TAG_F_NUMBER); val iso = e.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0).takeIf { it > 0 }
        val ll = FloatArray(2).takeIf { e.getLatLong(it) }
        val found = captured != null || camera != null || f != null || iso != null
        Exif(found, if (found) "" else "撮影日時・機材・露出の情報が見つかりませんでした", captured, camera, s("LensModel"), d(ExifInterface.TAG_FOCAL_LENGTH)?.let(::r1),
            t?.let { if (it >= 1) "${if (it % 1.0 == 0.0) it.toInt().toString() else r1(it).toString()}s" else "1/${(1 / it).roundToInt()}" }, t, f?.let(::r1), iso, d(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.let(::r1),
            ll?.get(0)?.toDouble()?.let { Math.round(it * 1e6) / 1e6 }, ll?.get(1)?.toDouble()?.let { Math.round(it * 1e6) / 1e6 })
    } catch (e: Throwable) { Exif(false, e.message ?: "EXIF情報を読み取れませんでした") }
}

private fun nowJst() = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))

@Composable private fun F(label: String, value: String, modifier: Modifier = Modifier, placeholder: String = "", number: Boolean = false, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 10.sp) }, placeholder = { Text(placeholder, fontSize = 12.sp) }, singleLine = true, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text))

/** 写真を登録（Web: PhotoUploadCard.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoUploadCard(onSaved: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }; var exif by remember { mutableStateOf<Exif?>(null) }
    var date by remember { mutableStateOf(nowJst().toLocalDate().toString()) }; var time by remember { mutableStateOf("%02d:%02d".format(nowJst().hour, nowJst().minute)) }
    var form by remember { mutableStateOf(mapOf("shot_direction" to "front", "shooting_style" to "static")) }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf<Pair<String, Color>?>(null) }
    fun v(k: String) = form[k].orEmpty()
    fun patch(k: String, value: String) { form = form + (k to value) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() } ?: return@launch
            file = (context.contentResolver.getType(uri) ?: "image/jpeg") to bytes; message = null
            val info = withContext(Dispatchers.Default) { readExif(bytes) }; exif = info
            info.capturedAt?.let { date = it.take(10); time = it.substring(11, 16) }
        }
    }
    Panel(title = "写真を登録") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            SmallButton(if (file != null) "写真を選び直す（${file!!.second.size / 1024}KB）" else "写真（JPEG）を選ぶ", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, icon = Icons.Outlined.Image)
            DateField(date, { date = it }, label = "撮影日"); TimeField(time, { time = it }, label = "時刻（JST）")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { F("撮影地点", v("place_name"), Modifier.weight(1f), "例 奈良駅前") { patch("place_name", it) }; F("車両番号", v("vehicle"), Modifier.weight(1f), "例 奈良200か115") { patch("vehicle", it) } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { F("系統", v("route"), Modifier.weight(1f), "例 1") { patch("route", it) }; F("行先", v("destination"), Modifier.weight(1f)) { patch("destination", it) } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { F("天気（手入力）", v("weather"), Modifier.weight(1f), "例 晴れ / 雪") { patch("weather", it) }; F("気温（℃・手入力）", v("temperature"), Modifier.weight(1f), number = true) { patch("temperature", it) } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SelectField(DIRECTION, v("shot_direction"), { patch("shot_direction", it) }, label = "撮影方向"); SelectField(STYLE, v("shooting_style"), { patch("shooting_style", it) }, label = "撮影スタイル") }
        F("メモ", v("notes"), Modifier.fillMaxWidth()) { patch("notes", it) }
        exif?.let { x ->
            Text(if (x.found) "EXIFから取得: ${listOfNotNull(x.camera, x.lens, x.focal?.let { "${it}mm" }, x.shutter, x.aperture?.let { "F$it" }, x.iso?.let { "ISO$it" }, x.bias?.let { "${it}EV" }, if (x.lat != null && x.lon != null) "GPSあり" else null).joinToString(" / ")}"
                else "EXIFを取得できませんでした（${x.reason}）。日時・車両・地点を入力してください。", fontSize = 11.sp, color = if (x.found) MaterialTheme.colorScheme.onSurfaceVariant else Brand.Warning)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallButton(if (busy) "登録中…" else "登録する", {
                val f = file; if (f == null) { message = "写真を選択してください" to Brand.Destructive; return@SmallButton }
                busy = true; message = null
                scope.launch {
                    try {
                        val uri = Api.uploadPrivateFile("photo.${f.first.substringAfter('/').replace("jpeg", "jpg")}", f.first, f.second); val x = exif?.takeIf { it.found }
                        val result = Api.rpc("photo/server-fns#savePhotoRecord", jsonOf("photo_uri" to uri, "captured_at" to "${date}T$time", "exif_found" to (x != null), "exif_source" to (if (x != null) "EXIFから自動取得" else exif?.reason?.take(300) ?: "EXIF無し"),
                            "vehicle" to v("vehicle").ifBlank { null }, "route" to v("route").ifBlank { null }, "destination" to v("destination").ifBlank { null }, "place_name" to v("place_name").ifBlank { null }, "weather" to v("weather").ifBlank { null },
                            "temperature" to v("temperature").toDoubleOrNull(), "shot_direction" to v("shot_direction"), "shooting_style" to v("shooting_style"), "notes" to v("notes").ifBlank { null }, "rating" to "none",
                            "camera" to x?.camera, "lens" to x?.lens, "focal_length" to x?.focal, "shutter_speed" to x?.shutter, "shutter_seconds" to x?.shutterSeconds?.takeIf { it <= 60 }, "aperture" to x?.aperture, "iso" to x?.iso, "exposure_compensation" to x?.bias, "lat" to x?.lat, "lon" to x?.lon))
                        if (result.bool("duplicate") == true) message = "この写真は既に登録されています" to Brand.Warning
                        else { message = "写真を登録しました" to Brand.Success; file = null; exif = null; form = mapOf("shot_direction" to "front", "shooting_style" to "static"); onSaved() }
                    } catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "登録できませんでした" } to Brand.Destructive } finally { busy = false }
                }
            }, enabled = !busy, icon = Icons.Outlined.Upload, primary = true)
            message?.let { Text("  ${it.first}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = it.second) }
        }
        Muted("写真は非公開ストレージに保存され、アプリの利用者のみ表示できます。EXIFに無い情報（天気・気温など）は推測で埋めません。", 10)
    }
}

/** 撮影データベース（Web: photos.index.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotosScreen() {
    val nav = LocalNav.current
    var range by remember { val t = jstTodayKey(); mutableStateOf(DateRange(addDays(t, -89), t)) }
    var rating by remember { mutableStateOf("") }; var place by remember { mutableStateOf("") }; var reload by remember { mutableIntStateOf(0) }
    val load = rememberLoad(range, rating, place, reload) { Api.rpc("photo/server-fns#listPhotoRecords", jsonOf("from" to range.from, "to" to range.to, "rating" to rating.ifBlank { null }, "place" to place.ifBlank { null }, "limit" to 60)) }
    val rows = load.data.items("rows")
    PhotosScaffold("/photos", "撮影データベース", "撮影した写真と撮影条件を記録します。JPEGのEXIFがあれば撮影日時・機材・露出・GPSを自動で取り込みます。") {
        item { PhotoUploadCard { reload++ } }
        item {
            RangePicker(range, { range = it }) {
                SelectField(listOf("" to "すべて") + RATINGS, rating, { rating = it }, label = "評価")
                OutlinedTextField(place, { place = it }, placeholder = { Text("地点名を含む", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(0.5f))
            }
            Muted("該当 ${load.data.str("total") ?: "—"} 枚（表示 ${rows.size} 枚）", 11)
        }
        if (load.loading && load.data == null) item { Muted("読み込んでいます…", 12) }
        load.error?.let { item { Text("⚠ $it", fontSize = 12.sp, color = Brand.Destructive) } }
        if (!load.loading && load.error == null && rows.isEmpty()) item { Panel { Muted("この条件の撮影記録はありません。上のフォームから登録できます。", 12) } }
        rows.chunked(2).forEach { pair ->
            item(key = "p-${pair.first().str("id")}") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { photo ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).clickable { nav.go("/photos/${photo.str("id")}") }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                val url = photo.str("signed_url")
                                if (!url.isNullOrBlank()) AsyncImage(url, photo.str("place_name") ?: "撮影記録", Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentScale = ContentScale.Crop) else Muted("画像を表示できません", 10)
                                if (photo.str("rating") == "favorite") Text("★", color = Brand.Warning, fontSize = 16.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                            }
                            Column(Modifier.padding(8.dp)) {
                                Muted(photo.str("captured_at").orEmpty().replace("T", " ").take(16), 10); Text(photo.str("place_name")?.ifBlank { null } ?: "地点未設定", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("vehicle", "route", "weather").forEach { k -> photo.str(k)?.takeIf { it.isNotBlank() }?.let { Tag(it, Brand.MutedFg) } } }
                            }
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
        if (load.data.bool("hasMore") == true) item { Muted("新しい60枚を表示しています。期間を狭めるか、地点・評価で絞り込んでください。", 11) }
    }
}

/** 撮影記録の詳細（Web: photos.$photoId.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoDetailScreen(photoId: String) {
    val nav = LocalNav.current; val scope = rememberCoroutineScope(); val confirm = rememberConfirmer()
    val load = rememberLoad(photoId) { Api.rpc("photo/server-fns#getPhotoRecord", jsonOf("id" to photoId)) }
    var busy by remember { mutableStateOf("") }; var notes by remember { mutableStateOf<String?>(null) }; var message by remember { mutableStateOf("") }
    val record = load.data.objAt("record")
    val analysis = record.str("ai_analysis")?.takeIf { it.isNotBlank() }?.let { t -> runCatching { Json.parse(t) }.getOrElse { jsonOf("summary" to t) } }
    fun act(key: String, fallback: String = "", block: suspend () -> Unit) { busy = key; scope.launch { try { block() } catch (e: Throwable) { if (e is CancellationException) throw e; if (fallback.isNotBlank()) message = e.userMessage().ifBlank { fallback } } finally { busy = "" } } }
    PhotosScaffold("/photos", "撮影記録の詳細", "撮影条件の確認・評価・AIによる技術分析を行います。") {
        if (load.loading && load.data == null) item { Muted("読み込んでいます…", 12) }
        load.error?.let { item { Text("⚠ $it", fontSize = 12.sp, color = Brand.Destructive) } }
        if (!load.loading && record == null) item { Panel { Muted("この撮影記録は存在しないか、閲覧できません。", 12) } }
        if (record != null) {
            item {
                val url = load.data.str("signed_url")
                if (!url.isNullOrBlank()) AsyncImage(url, record.str("place_name") ?: "撮影記録", Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.FillWidth) else Panel { Muted("画像を表示できません（署名URLの発行に失敗）", 12) }
            }
            item {
                Panel(title = "評価とメモ") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { RATINGS.forEach { (v, l) -> SmallButton(if (v == "favorite") "★ $l" else l, { act("rating") { Api.rpc("photo/server-fns#updatePhotoRecord", jsonOf("id" to photoId, "rating" to v)); load.reload() } }, enabled = busy != "rating", primary = record.str("rating") == v) } }
                    OutlinedTextField(notes ?: record.str("notes").orEmpty(), { notes = it }, placeholder = { Text("撮影のメモ", fontSize = 12.sp) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton("メモを保存", { act("notes") { Api.rpc("photo/server-fns#updatePhotoRecord", jsonOf("id" to photoId, "notes" to (notes ?: record.str("notes").orEmpty()))); notes = null; load.reload() } }, enabled = busy != "notes" && notes != null)
                        SmallButton("削除", { confirm.ask("この撮影記録を削除します。よろしいですか？（写真ファイルはストレージに残ります）") { act("delete") { Api.rpc("photo/server-fns#deletePhotoRecord", jsonOf("id" to photoId)); nav.go("/photos") } } }, enabled = busy != "delete", icon = Icons.Outlined.Delete)
                    }
                }
            }
            item {
                Panel(title = "AIによる技術分析", action = { SmallButton(if (busy == "ai") "分析中…" else if (analysis != null) "再分析する" else "分析する", { message = ""; act("ai", "AI分析を実行できませんでした") { Api.rpc("photo/server-fns#analyzePhoto", jsonOf("id" to photoId)); load.reload() } }, enabled = busy != "ai", icon = Icons.Outlined.AutoAwesome) }) {
                    record.long("ai_analyzed_at")?.let { Muted("分析 ${BusFormat.dateTime(it)}", 10) }
                    if (message.isNotBlank()) Text(message, fontSize = 11.sp, color = Brand.Destructive)
                    if (analysis == null) Muted("写真に写っている内容だけをもとに、ピント・ブレ・露出・構図を技術的な観点で整理します。作品の価値を判定するものではありません。", 11)
                    else {
                        analysis.str("summary")?.let { Text(it, fontSize = 12.sp, lineHeight = 18.sp) }
                        listOf("明瞭さ" to "sharpness", "ピント" to "focus", "ブレ" to "blur", "露出" to "exposure", "明暗差" to "contrast", "色かぶり" to "color_cast", "構図" to "composition", "背景との分離" to "separation", "車両の見え方" to "vehicle_visibility", "光の当たり方" to "lighting")
                            .forEach { (l, k) -> analysis.str(k)?.takeIf { it.isNotBlank() }?.let { KeyValue(l, it) } }
                        analysis.list("improvements").takeIf { it.isNotEmpty() }?.let { imp -> Text("改善のヒント", fontSize = 12.sp, fontWeight = FontWeight.Bold); imp.forEach { Muted("・${it.text}", 11) } }
                        analysis.objAt("suggested_settings")?.let { s ->
                            Text("次回試せる設定（参考）", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Muted(listOf("シャッター" to "shutter_speed", "絞り" to "aperture", "ISO" to "iso", "露出補正" to "exposure_compensation", "焦点距離" to "focal_length").mapNotNull { (l, k) -> s.str(k)?.takeIf { it.isNotBlank() }?.let { "$l $it" } }.joinToString(" / ").ifBlank { "提案なし" }, 11)
                        }
                        analysis.list("caveats").takeIf { it.isNotEmpty() }?.let { c -> Muted("注意: ${c.joinToString(" / ") { it.text }}", 10) }
                        analysis.str("confidence")?.let { Muted("AIの確信度: $it", 10) }
                    }
                }
            }
            item {
                Panel(title = "撮影情報") {
                    fun lab(list: List<Pair<String, String>>, v: String?) = list.firstOrNull { it.first == v }?.second
                    KeyValue("撮影日時", record.str("captured_at")?.replace("T", " ")); KeyValue("撮影地点", record.str("place_name")); KeyValue("車両", record.str("vehicle"))
                    KeyValue("系統 / 行先", listOfNotNull(record.str("route")?.ifBlank { null }, record.str("destination")?.ifBlank { null }).joinToString(" / "))
                    KeyValue("撮影方向", lab(DIRECTION, record.str("shot_direction"))); KeyValue("スタイル", lab(STYLE, record.str("shooting_style"))); KeyValue("カメラ", record.str("camera")); KeyValue("レンズ", record.str("lens"))
                    KeyValue("焦点距離", record.str("focal_length")?.let { "${it}mm" }); KeyValue("シャッター", record.str("shutter_speed")); KeyValue("絞り", record.str("aperture")?.let { "F$it" }); KeyValue("ISO", record.str("iso"))
                    KeyValue("露出補正", record.str("exposure_compensation")?.let { "${it}EV" }); KeyValue("天気 / 気温", listOfNotNull(record.str("weather")?.ifBlank { null }, record.str("temperature")?.let { "$it℃" }).joinToString(" / "))
                    KeyValue("太陽方位 / 高度", record.str("sun_azimuth")?.let { "$it° / ${record.str("sun_altitude")}°" }); KeyValue("座標", record.dbl("lat")?.let { la -> record.dbl("lon")?.let { "$la, $it" } })
                    Muted("${if (record.bool("exif_found") == true) "EXIF情報から自動取得した項目を含みます。" else "EXIF情報はありません（${record.str("exif_source") ?: "理由不明"}）。"}空欄は取得できなかった項目で、推測値は入れていません。", 10)
                }
            }
        }
    }
}

/** 撮影設定の学習・地点実績（Web: photos.learning.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoLearningScreen() {
    val scope = rememberCoroutineScope()
    var range by remember { val t = jstTodayKey(); mutableStateOf(DateRange(addDays(t, -365), t)) }
    val load = rememberLoad(range) { Api.rpc("photo/server-fns#getPhotoLearning", jsonOf("from" to range.from, "to" to range.to)) }
    var gear by remember { mutableStateOf(mapOf<String, String>()) }; var busy by remember { mutableStateOf("") }
    val data = load.data
    PhotosScaffold("/photos/learning", "撮影設定の学習・地点実績", "登録した写真と評価から、状況別の設定傾向と撮影地点ごとの実績を集計します。") {
        item { RangePicker(range, { range = it }) }
        if (load.loading && data == null) item { Muted("集計しています…", 12) }
        load.error?.let { item { Text("⚠ $it", fontSize = 12.sp, color = Brand.Destructive) } }
        if (data != null) {
            item {
                KpiRow(Triple("登録写真", "${data.int("totals.photos") ?: 0}", null), Triple("評価あり", "${data.int("totals.rated") ?: 0}", Brand.Success), Triple("失敗として評価", "${data.int("totals.failed") ?: 0}", Brand.Destructive), Triple("EXIFあり", "${data.int("totals.withExif") ?: 0}", null), units = listOf("枚", "枚", "枚", "枚"))
                data.str("note")?.let { Muted(it, 10) }
            }
            item {
                Panel(title = "状況別の設定傾向（中央値）") {
                    DataTable(listOf(Col("シーン", 110.dp), Col("枚数", 48.dp, true), Col("成功", 48.dp, true), Col("シャッター", 76.dp, true), Col("絞り", 52.dp, true), Col("ISO", 60.dp, true), Col("焦点距離", 70.dp, true)),
                        data.items("scenes").map { s -> listOf(s.str("label").orEmpty(), s.str("count").orEmpty(), s.str("successCount").orEmpty(), s.str("settings.shutterLabel") ?: "—", s.str("settings.aperture")?.let { "F$it" } ?: "—", s.str("settings.iso") ?: "—", s.str("settings.focal")?.let { "${it}mm" } ?: "—") })
                }
            }
            item {
                Panel(title = "次回の撮影で試す設定（機材の範囲内）") {
                    val sug = data.items("suggestions")
                    if (sug.isEmpty()) Muted("写真と設定を登録すると提案が表示されます。", 11)
                    sug.forEach { s ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(s.str("scene").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(listOfNotNull(s.str("shutter")?.let { "シャッター $it" }, s.str("aperture")?.let { "絞り F$it" }, s.str("iso")?.let { "ISO $it" }, s.str("focal")?.let { "${it}mm" }, s.str("exposureComp")?.let { "露出補正 ${it}EV" }).joinToString(" / ").ifBlank { "設定の記録が不足しています" }, fontSize = 12.sp)
                            Muted("根拠: ${s.str("basedOn").orEmpty()}${if (s.bool("limitedByGear") == true) "（登録機材の対応範囲に調整）" else ""}", 10)
                        }
                    }
                }
            }
            item {
                Panel(title = "撮影地点ごとの実績") {
                    val locs = data.items("locations")
                    if (locs.isEmpty()) Muted("撮影地点の記録がありません。", 11)
                    locs.forEach { row ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Text(row.str("name").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold); Muted("${row.int("count") ?: 0}枚", 11); row.str("successRate")?.let { Muted("成功率 $it%", 11) }; Muted("最終 ${row.str("lastDate") ?: "—"}", 11) }
                            fun l(k: String) = row.list(k).map { it.text }
                            Muted("車両 ${l("vehicles").size}台（${l("vehicles").take(4).joinToString("・").ifBlank { "—" }}）${l("routes").takeIf { it.isNotEmpty() }?.let { " / 系統 ${it.joinToString("・")}" } ?: ""}${l("weather").takeIf { it.isNotEmpty() }?.let { " / ${it.joinToString("・")}" } ?: ""}${l("seasons").takeIf { it.isNotEmpty() }?.let { " / ${it.joinToString("・")}" } ?: ""}", 10)
                        }
                    }
                }
            }
            item {
                Panel(title = "登録機材") {
                    fun g(k: String) = gear[k].orEmpty()
                    @Composable fun G(label: String, key: String, ph: String, m: Modifier, number: Boolean = false) = F(label, g(key), m, ph, number) { gear = gear + (key to it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { G("カメラ名", "name", "α7 IV", Modifier.weight(1f)); G("レンズ", "lens", "70-200mm F2.8", Modifier.weight(1f)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { G("センサー", "sensor", "フルサイズ", Modifier.weight(1f)); G("焦点距離 下限", "focal_min", "24", Modifier.weight(1f), true); G("焦点距離 上限", "focal_max", "200", Modifier.weight(1f), true) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { G("開放F値", "aperture_min", "2.8", Modifier.weight(1f), true); G("常用ISO上限", "iso_max", "12800", Modifier.weight(1f), true); G("最短シャッター", "shutter_min", "1/4000", Modifier.weight(1f)) }
                    SmallButton("機材を追加", {
                        if (g("name").isBlank()) return@SmallButton
                        busy = "add"
                        scope.launch {
                            try { Api.rpc("photo/server-fns#saveCameraGear", jsonOf("name" to g("name"), "lens" to g("lens").ifBlank { null }, "sensor" to g("sensor").ifBlank { null }, "focal_min" to g("focal_min").toDoubleOrNull(), "focal_max" to g("focal_max").toDoubleOrNull(),
                                "aperture_min" to g("aperture_min").toDoubleOrNull(), "iso_max" to g("iso_max").toDoubleOrNull(), "shutter_min" to g("shutter_min").ifBlank { null })); gear = emptyMap(); load.reload() }
                            catch (e: Throwable) { if (e is CancellationException) throw e } finally { busy = "" }
                        }
                    }, enabled = busy != "add", icon = Icons.Outlined.Add)
                    data.items("gear").forEach { x ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("${x.str("name")}　${x.str("lens").orEmpty()}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Muted(listOfNotNull(x.str("focal_min")?.let { a -> x.str("focal_max")?.let { "$a-${it}mm" } }, x.str("aperture_min")?.let { "F$it" }, x.str("iso_max")?.let { "ISO≤$it" }).joinToString(" / "), 10) }
                            SmallButton("削除", { busy = x.str("id").orEmpty(); scope.launch { try { Api.rpc("photo/server-fns#deleteCameraGear", jsonOf("id" to x.str("id"))); load.reload() } catch (e: Throwable) { if (e is CancellationException) throw e } finally { busy = "" } } }, enabled = busy != x.str("id"), icon = Icons.Outlined.Delete)
                        }
                    }
                }
            }
        }
    }
}
