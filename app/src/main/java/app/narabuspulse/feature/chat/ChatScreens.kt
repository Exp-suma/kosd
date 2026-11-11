package app.narabuspulse.feature.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Export
import app.narabuspulse.core.Json
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.offline.OfflineStore
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.core.toJson
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.meter.MeterStore
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.Markdown
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val JST = ZoneId.of("Asia/Tokyo")
private val HM = DateTimeFormatter.ofPattern("HH:mm")
private val MDHM = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private val FULL = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
private fun fmt(f: DateTimeFormatter, ts: Long?) = if (ts == null || ts == 0L) "" else f.format(Instant.ofEpochMilli(ts).atZone(JST))
private fun parse(v: JsonElement?): JsonElement? = when (v) { null, is JsonNull -> null; is JsonObject, is JsonArray -> v; else -> v.text.takeIf { it.isNotBlank() }?.let { runCatching { Json.parse(it) }.getOrNull() } }

/** 会話の控え（Web: lib/chat/cache.js。通信できないときでも直近の会話を読めるようにする）。 */
private object ChatCache {
    private fun p(c: Context) = c.getSharedPreferences("logic_storage", Context.MODE_PRIVATE)
    private fun all(c: Context): MutableMap<String, JsonElement> = runCatching { (Json.parse(p(c).getString("nara-chat-cache-v1", null) ?: "{}") as JsonObject).toMutableMap() }.getOrDefault(mutableMapOf())
    fun put(c: Context, conv: JsonElement?, messages: List<JsonElement>) {
        val id = conv.str("id") ?: return
        val a = all(c)
        a[id] = jsonOf("conversation" to jsonOf("id" to id, "title" to conv.str("title"), "mode" to conv.str("mode"), "last_at" to conv.long("last_at")), "messages" to JsonArray(messages.takeLast(80)), "savedAt" to System.currentTimeMillis())
        a.keys.sortedByDescending { a[it].long("savedAt") ?: 0 }.drop(12).forEach { a.remove(it) }
        runCatching { p(c).edit().putString("nara-chat-cache-v1", JsonObject(a).toString()).apply() }
    }
    fun get(c: Context, id: String): JsonElement? = all(c)[id]
    fun list(c: Context): List<JsonElement> = all(c).values.sortedByDescending { it.long("savedAt") ?: 0 }.mapNotNull { it.objAt("conversation") }
}

/** AIに渡すアプリ内データ（Web: lib/chat/context.js buildAppContext）。存在しない項目は作らない。 */
private fun buildAppContext(): String {
    val s = MeterStore.state.value
    val last = s.objAt("last")
    fun nm(x: JsonElement?) = "${x.str("maker").orEmpty()} ${x.str("name").orEmpty()}".trim().ifBlank { x.str("name").orEmpty() }
    fun nameOf(key: String, id: String?) = s.list(key).firstOrNull { it.str("id") == id }?.str("name").orEmpty()
    val camera = s.list("cameras").firstOrNull { it.str("id") == last.str("cameraId") }
    val lens = s.list("lenses").firstOrNull { it.str("id") == last.str("lensId") }
    val preset = s.list("presets").firstOrNull { it.str("id") == last.str("presetId") }
    val live = MeterStore.readLive()
    val cal = s.objAt("calibration")
    fun ctx(limit: Boolean) = jsonOf(
        "generatedAt" to Instant.now().toString(), "timezone" to "Asia/Tokyo",
        "cameras" to s.list("cameras").take(if (limit) 8 else 999).map { c -> jsonOf("name" to nm(c), "sensor" to c.str("sensor").orEmpty(), "mount" to c.str("mount").orEmpty(), "isoRange" to listOf(c.dbl("isoMin"), c.dbl("isoMax")), "shutterRange" to listOf(c.str("shutterFast"), c.str("shutterSlow")), "metering" to c.str("metering").orEmpty(), "evBias" to (c.dbl("evBias") ?: 0.0), "custom" to c.str("custom").orEmpty(), "note" to c.str("note").orEmpty()) },
        "lenses" to s.list("lenses").take(if (limit) 8 else 999).map { l -> jsonOf("name" to nm(l), "mount" to l.str("mount").orEmpty(), "focal" to if (l.bool("zoom") == true) "${l.str("focalMin")}-${l.str("focalMax")}mm" else "${l.str("focalMin")}mm", "aperture" to listOf(l.dbl("apertureMin"), l.dbl("apertureMax")), "vr" to (l.bool("vr") == true), "vrMode" to l.str("vrMode").orEmpty(), "note" to l.str("note").orEmpty()) },
        "combos" to s.list("combos").take(if (limit) 8 else 999).map { c -> jsonOf("name" to c.str("name").orEmpty(), "camera" to nameOf("cameras", c.str("cameraId")).ifBlank { c.str("cameraName").orEmpty() }, "lens" to nameOf("lenses", c.str("lensId")).ifBlank { c.str("lensName").orEmpty() }, "adapter" to c.str("adapter").orEmpty(), "note" to c.str("note").orEmpty()) },
        "presets" to s.list("presets").take(if (limit) 12 else 999).map { p -> jsonOf("name" to p.str("name"), "mode" to p.str("mode"), "iso" to p.dbl("iso"), "shutter" to p.str("shutter"), "aperture" to p.str("aperture"), "evBias" to (p.dbl("evBias") ?: 0.0), "metering" to p.str("metering").orEmpty(), "note" to p.str("note").orEmpty()) },
        "current" to jsonOf("camera" to (camera?.let { nm(it) } ?: "未選択"), "lens" to (lens?.let { nm(it) } ?: "未選択"), "preset" to (preset.str("name") ?: "未選択"), "mode" to last.str("mode").orEmpty(), "metering" to last.str("metering").orEmpty(), "iso" to last.dbl("iso"), "shutter" to last.str("shutter").orEmpty(), "aperture" to last.str("aperture"), "evComp" to (last.dbl("evComp") ?: 0.0)),
        "calibration" to jsonOf("global" to (cal.dbl("global") ?: 0.0), "sensitivity" to (cal.dbl("sensitivity") ?: 1.0), "isoFactor" to (cal.dbl("isoFactor") ?: 1.0), "byCamera" to (cal?.get("byCamera") ?: JsonObject(emptyMap())), "byLens" to (cal?.get("byLens") ?: JsonObject(emptyMap())), "byMode" to (cal?.get("byMode") ?: JsonObject(emptyMap()))),
        "lastMeasurement" to live?.let { l -> jsonOf("note" to "露出計画面でいま測った値（引き継ぎ）", "ev" to l.dbl("ev"), "iso" to l.dbl("iso"), "shutter" to l.str("shutter").orEmpty(), "aperture" to l.str("aperture"), "metering" to l.str("metering").orEmpty(), "mode" to l.str("mode").orEmpty(), "evComp" to (l.dbl("evComp") ?: 0.0), "camera" to l.str("camera").orEmpty(), "lens" to l.str("lens").orEmpty(), "preset" to l.str("preset").orEmpty(), "luma255" to l.dbl("luma"), "at" to l.long("at")) },
        "savedMeasurements" to s.list("history").sortedByDescending { it.long("at") ?: 0 }.take(if (limit) 5 else 10).map { r -> jsonOf("at" to Instant.ofEpochMilli(r.long("at") ?: 0).toString(), "ev" to r.dbl("ev"), "iso" to r.dbl("iso"), "shutter" to r.str("shutter").orEmpty(), "aperture" to r.str("aperture"), "mode" to r.str("mode").orEmpty(), "metering" to r.str("metering").orEmpty(), "evComp" to (r.dbl("evComp") ?: 0.0), "camera" to r.str("cameraName").orEmpty(), "lens" to r.str("lensName").orEmpty(), "luma255" to r.dbl("luma"), "note" to r.str("note").orEmpty(), "favorite" to (r.bool("favorite") == true)) },
        "counts" to jsonOf("cameras" to s.list("cameras").size, "lenses" to s.list("lenses").size, "combos" to s.list("combos").size, "presets" to s.list("presets").size, "savedMeasurements" to s.list("history").size),
    ).toString()
    val full = ctx(false)
    return if (full.length <= 15000) full else ctx(true).take(15500)
}

private fun formatAperture(n: Double?): String {
    if (n == null || n <= 0) return "—"
    val r = Math.round(n * 10) / 10.0
    return "F" + if (r % 1.0 == 0.0) r.toLong().toString() else r.toString()
}

private val MODES = listOf("general" to "一般質問", "shooting" to "撮影アドバイス")
private val STYLES = listOf("concise" to "簡潔", "normal" to "標準", "detailed" to "詳しい")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatSettingsBar(mode: String, style: String, onMode: (String) -> Unit, onStyle: (String) -> Unit) {
    val s by MeterStore.state.collectAsState()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Muted("モード", 10); MODES.forEach { (k, l) -> FilterChip(mode == k, { onMode(k) }, label = { Text(l, fontSize = 10.sp) }) }
        Muted("回答", 10); STYLES.forEach { (k, l) -> FilterChip(style == k, { onStyle(k) }, label = { Text(l, fontSize = 10.sp) }) }
        Muted("📷 カメラ ${s.list("cameras").size}台・レンズ ${s.list("lenses").size}本　◎ 測定履歴 ${s.list("history").size}件", 10)
        if (MeterStore.readLive() != null) Tag("露出計の計測値を引き継ぎ", INFO)
    }
}

private data class ChatImage(val uri: String, val url: String, val name: String)

/** 入力欄（画像添付・音声入力つき。Web: ChatComposer.jsx）。 */
@Composable
private fun ChatComposer(onSend: suspend (String, List<ChatImage>) -> Unit, busy: Boolean, placeholder: String = "何でも聞いてください（例: 夜のバスを撮る設定は？）") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val online by OfflineStore.online.collectAsState()
    var text by remember { mutableStateOf("") }
    var images by remember { mutableStateOf(listOf<ChatImage>()) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val disabled = busy || !online || uploading
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        val pick = uris.take(3 - images.size)
        if (pick.isEmpty()) return@rememberLauncherForActivityResult
        uploading = true; error = ""
        scope.launch {
            try {
                for (u in pick) {
                    val mime = context.contentResolver.getType(u) ?: "image/jpeg"
                    val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(u)?.use { it.readBytes() } } ?: continue
                    val uri = Api.uploadPrivateFile("chat.${mime.substringAfter('/')}", mime, bytes)
                    val url = runCatching { Api.signedUrl(uri) }.getOrDefault("")
                    images = (images + ChatImage(uri, url, u.lastPathSegment.orEmpty())).take(3)
                }
            } catch (e: Throwable) { if (e is CancellationException) throw e; error = "画像を送信できませんでした: ${e.userMessage()}" } finally { uploading = false }
        }
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!t.isNullOrBlank()) text = if (text.isBlank()) t else "$text $t"
    }
    val voiceSupported = remember { android.speech.SpeechRecognizer.isRecognitionAvailable(context) }
    Panel {
        if (images.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            images.forEachIndexed { i, im ->
                Box {
                    if (im.url.isNotBlank()) AsyncImage(im.url, im.name, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop) else Box(Modifier.size(56.dp).background(Brand.MutedFg.copy(alpha = 0.2f)))
                    IconButton(onClick = { images = images.filterIndexed { j, _ -> j != i } }, modifier = Modifier.size(20.dp).align(Alignment.TopEnd)) { Icon(Icons.Outlined.Close, "画像を外す", Modifier.size(14.dp)) }
                }
            }
        }
        OutlinedTextField(text, { text = it }, placeholder = { Text(if (!online) "オフラインのため送信できません" else placeholder, fontSize = 12.sp) }, enabled = online, minLines = 2, maxLines = 8, modifier = Modifier.fillMaxWidth())
        if (!online) Text("オフラインです。新しい回答は生成できません（保存済みの会話は読めます）。", fontSize = 10.sp, color = Brand.Destructive, fontWeight = FontWeight.Bold)
        if (error.isNotBlank()) Text(error, fontSize = 10.sp, color = Brand.Destructive)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton(if (uploading) "送信中…" else "画像", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !disabled && images.size < 3, icon = Icons.Outlined.Image)
            SmallButton("音声", { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")) }, enabled = voiceSupported && online, icon = Icons.Outlined.Mic)
            Box(Modifier.weight(1f))
            SmallButton(if (busy) "生成中" else "送信", {
                val body = text.trim(); if (body.isEmpty()) return@SmallButton
                val imgs = images; text = ""; images = emptyList(); error = ""
                scope.launch { onSend(body, imgs) }
            }, enabled = !disabled && text.isNotBlank(), icon = Icons.Outlined.Send, primary = true)
        }
        if (!voiceSupported) Muted("この端末は音声入力に対応していません。", 10)
    }
}

@Composable
private fun MessageImage(uri: String) {
    val context = LocalContext.current
    val url = produceState("", uri) { value = runCatching { Api.signedUrl(uri) }.getOrDefault("") }.value
    if (url.isBlank()) Box(Modifier.size(80.dp).clip(RoundedCornerShape(6.dp)).background(Brand.MutedFg.copy(alpha = 0.15f)))
    else AsyncImage(url, "添付画像", Modifier.size(80.dp).clip(RoundedCornerShape(6.dp)).clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }, contentScale = ContentScale.Crop)
}

@Composable
private fun ChatQuestion(m: JsonElement) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(m.str("content").orEmpty(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimary)
            val att = parse((m as? JsonObject)?.get("attachments")).items()
            if (att.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { att.forEach { a -> a.str("file_uri")?.let { MessageImage(it) } } }
        }
    }
}

/** AIの回答1件（Markdown・コピー・再生成・評価・露出設定の反映）。Web: ChatAnswer.jsx */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatAnswer(m: JsonElement, busy: Boolean, onRegenerate: () -> Unit, onRate: (String) -> Unit, onFollowup: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }
    val o = m as? JsonObject
    val settings = parse(o?.get("settings")) as? JsonObject
    val followups = parse(o?.get("followups")).items().map { it.text }
    val uncertainty = parse(o?.get("uncertainty")).items().map { it.text }
    val att = parse(o?.get("attachments")).items()
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Tag("AI", Brand.Orange); if (m.bool("used_context") == true) Tag("アプリ内データを参照", Brand.MutedFg); if (m.bool("had_images") == true) Tag("画像を解析", Brand.MutedFg)
            m.long("at")?.let { Muted(fmt(HM, it), 10) }
        }
        Markdown(m.str("content").orEmpty())
        if (settings != null) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Brand.Orange.copy(alpha = 0.08f)).padding(8.dp)) {
            Text("⚙ 提案された露出設定", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("ISO ${settings.str("iso")} ・ ${settings.str("shutter")} 秒 ・ ${formatAperture(settings.dbl("aperture"))}${settings.str("mode")?.let { " ・ ${it}モード" } ?: ""}${settings.dbl("ev_comp")?.takeIf { it != 0.0 }?.let { " ・ 補正 ${if (it > 0) "+" else ""}${it}EV" } ?: ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            settings.str("reason")?.let { Muted(it, 10) }
            when {
                applied -> Text("✓ 露出計に反映しました（露出計画面を開くと反映されています）", fontSize = 10.sp, color = Brand.Success, fontWeight = FontWeight.Bold)
                confirming -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("この設定を露出計に反映しますか？", 10)
                    TextButton(onClick = {
                        val v = mutableListOf<Pair<String, Any?>>()
                        settings.dbl("iso")?.takeIf { it > 0 }?.let { v += "iso" to it }
                        settings.str("shutter")?.takeIf { it.isNotBlank() }?.let { v += "shutter" to it }
                        settings.dbl("aperture")?.takeIf { it > 0 }?.let { v += "aperture" to it }
                        settings.str("mode")?.takeIf { it in setOf("M", "S", "A", "ISO") }?.let { v += "mode" to it }
                        settings.dbl("ev_comp")?.let { v += "evComp" to it }
                        MeterStore.patchLast(*v.toTypedArray()); confirming = false; applied = true
                    }) { Text("反映する", fontSize = 11.sp) }
                    TextButton(onClick = { confirming = false }) { Text("やめる", fontSize = 11.sp) }
                }
                else -> SmallButton("露出計に反映", { confirming = true })
            }
        }
        if (uncertainty.isNotEmpty()) Column(Modifier.fillMaxWidth().background(Brand.Warning.copy(alpha = 0.1f)).padding(6.dp)) { Text("⚠ 不確かな点・未確認", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Brand.Warning); uncertainty.forEach { Muted("・$it", 10) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(m.str("content").orEmpty())); copied = true }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(14.dp)); Text(if (copied) " コピーしました" else " コピー", fontSize = 10.sp) }
            TextButton(onClick = onRegenerate, enabled = !busy) { Icon(Icons.Outlined.Refresh, null, Modifier.size(14.dp)); Text(" 再生成", fontSize = 10.sp) }
            IconButton(onClick = { onRate(if (m.str("rating") == "good") "none" else "good") }) { Icon(Icons.Outlined.ThumbUp, "役に立った", tint = if (m.str("rating") == "good") Brand.Success else Brand.MutedFg, modifier = Modifier.size(16.dp)) }
            IconButton(onClick = { onRate(if (m.str("rating") == "bad") "none" else "bad") }) { Icon(Icons.Outlined.ThumbDown, "役に立たなかった", tint = if (m.str("rating") == "bad") Brand.Destructive else Brand.MutedFg, modifier = Modifier.size(16.dp)) }
            m.dbl("duration_ms")?.takeIf { it > 0 }?.let { Muted("%.1f秒".format(it / 1000), 10) }
        }
        if (followups.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { followups.forEach { f -> FilterChip(false, { onFollowup(f) }, label = { Text(f, fontSize = 10.sp) }) } }
        if (att.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { att.forEach { a -> a.str("file_uri")?.let { MessageImage(it) } } }
    }
}

/** 会話履歴（Web: chat/ConversationList.jsx）。 */
@Composable
private fun ConversationList(activeId: String?, reloadKey: Int) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(listOf<JsonElement>()) }
    var note by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmId by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(activeId == null) }
    LaunchedEffect(activeId, reloadKey) {
        try { rows = Api.rpc("chat/server-fns#listChatConversations", jsonOf()).items(); note = "" }
        catch (e: Throwable) { if (e is CancellationException) throw e; rows = ChatCache.list(context); note = "端末に残っている会話を表示しています（通信できません）" }
    }
    val filtered = rows.filter { r -> query.isBlank() || listOfNotNull(r.str("title"), r.str("last_excerpt")).any { it.lowercase().contains(query.trim().lowercase()) } }
    Panel(title = "会話履歴（${rows.size}）", action = { Row { SmallButton("新しいチャット", { nav.go("/assistant") }, icon = Icons.Outlined.AddComment, primary = true); TextButton(onClick = { open = !open }) { Text(if (open) "閉じる" else "開く", fontSize = 11.sp) } } }) {
        if (!open) return@Panel
        OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("会話を検索", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (note.isNotBlank()) Text(note, fontSize = 10.sp, color = Brand.Warning)
        filtered.take(60).forEach { r ->
            val id = r.str("id").orEmpty()
            val ed = editing
            if (ed != null && ed.first == id) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ed.second, { editing = id to it }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val t = ed.second.trim(); editing = null
                    if (t.isNotBlank()) scope.launch { runCatching { Api.rpc("chat/server-fns#renameChatConversation", jsonOf("id" to id, "title" to t)) }; rows = rows.map { if (it.str("id") == id) JsonObject((it as JsonObject) + ("title" to toJson(t))) else it } }
                }) { Text("保存", fontSize = 11.sp) }
            } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (id == activeId) Brand.Orange.copy(alpha = 0.1f) else Color.Transparent)) {
                Column(Modifier.weight(1f).clickable { nav.go("/assistant/$id") }.padding(6.dp)) {
                    Text(r.str("title")?.ifBlank { null } ?: "無題の会話", fontSize = 12.sp, fontWeight = if (id == activeId) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    Muted("${fmt(MDHM, r.long("last_at"))}${r.str("last_excerpt")?.takeIf { it.isNotBlank() }?.let { " ・ $it" } ?: ""}", 9)
                }
                IconButton(onClick = { editing = id to r.str("title").orEmpty() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.Edit, "名前を変更", Modifier.size(14.dp)) }
                if (confirmId == id) {
                    TextButton(onClick = { scope.launch { runCatching { Api.rpc("chat/server-fns#deleteChatConversation", jsonOf("id" to id)) }; rows = rows.filter { it.str("id") != id }; confirmId = ""; if (activeId == id) nav.go("/assistant") } }) { Text("削除", fontSize = 10.sp, color = Brand.Destructive) }
                    TextButton(onClick = { confirmId = "" }) { Text("戻す", fontSize = 10.sp) }
                } else IconButton(onClick = { confirmId = id }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.Delete, "削除", Modifier.size(14.dp)) }
            }
        }
        if (filtered.isEmpty()) Muted(if (query.isNotBlank()) "該当する会話がありません。" else "会話履歴はまだありません。", 10)
    }
}

private val EXAMPLES = listOf("こんにちは", "夜のバスを撮影する設定を教えて", "F2.8、ISO3200、1/125秒の露出値を計算して", "登録しているカメラとレンズを教えて", "今の測定結果から適正な設定を提案して", "バス撮影で背景を流すコツは？")

/** AIアシスタント（Web: assistant.jsx + assistant.index.jsx + assistant.$conversationId.jsx）。conversationId が null なら新しいチャット。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantScreen(conversationId: String?) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var conversation by remember(conversationId) { mutableStateOf<JsonElement?>(null) }
    var messages by remember(conversationId) { mutableStateOf(listOf<JsonElement>()) }
    var loading by remember(conversationId) { mutableStateOf(conversationId != null) }
    var note by remember(conversationId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var mode by remember(conversationId) { mutableStateOf("general") }
    var style by remember(conversationId) { mutableStateOf("normal") }
    var listKey by remember { mutableStateOf(0) }
    LaunchedEffect(conversationId) {
        if (conversationId == null) return@LaunchedEffect
        fun fallback(reason: String) {
            val c = ChatCache.get(context, conversationId)
            if (c?.objAt("conversation") != null) { conversation = c.objAt("conversation"); messages = c.list("messages"); note = reason } else note = "この会話を読み込めませんでした（通信できず、端末にも控えがありません）。"
        }
        try {
            val r = Api.rpc("chat/server-fns#getChatConversation", jsonOf("id" to conversationId))
            val conv = r.objAt("conversation")
            if (conv == null) fallback("この会話は見つかりませんでした。端末に残っている控えを表示しています。")
            else { conversation = conv; messages = r.items("messages"); conv.str("mode")?.let { mode = it }; conv.str("style")?.let { style = it }; note = ""; ChatCache.put(context, conv, messages) }
        } catch (e: Throwable) { if (e is CancellationException) throw e; fallback("通信できないため、端末に保存された会話を表示しています。") } finally { loading = false }
    }
    fun isLocal(m: JsonElement) = m.bool("pending") == true || m.str("id").orEmpty().startsWith("local-")
    suspend fun send(text: String, images: List<ChatImage> = emptyList(), replaceId: String? = null): JsonElement? {
        if (busy || text.isBlank()) return null
        busy = true; error = ""
        val pendingId = "pending-${System.currentTimeMillis()}"
        val optimistic = if (replaceId != null) emptyList() else listOf(jsonOf("id" to "local-${System.currentTimeMillis()}", "role" to "user", "content" to text, "attachments" to if (images.isNotEmpty()) JsonArray(images.map { jsonOf("file_uri" to it.uri) }).toString() else "", "at" to System.currentTimeMillis()))
        messages = messages.filter { !isLocal(it) && it.str("id") != replaceId } + optimistic + jsonOf("id" to pendingId, "role" to "assistant", "content" to "", "pending" to true)
        return try {
            val r = Api.rpc("chat/server-fns#askAssistant", jsonOf("conversation_id" to conversationId, "question" to text, "mode" to mode, "style" to style, "app_context" to buildAppContext(),
                "image_urls" to images.map { it.url }.filter { it.isNotBlank() }, "image_uris" to images.map { it.uri }, "replace_message_id" to replaceId))
            messages = messages.filter { !isLocal(it) && it.str("id") != replaceId && it.str("id") != pendingId } + listOfNotNull(r.objAt("userMessage"), r.objAt("answerMessage"))
            r.objAt("conversation")?.let { conversation = it; ChatCache.put(context, it, messages) }
            listKey++
            r
        } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "回答を生成できませんでした" }; messages = messages.filter { it.str("id") != pendingId }; null } finally { busy = false }
    }
    fun rate(id: String, rating: String) {
        messages = messages.map { if (it.str("id") == id) JsonObject((it as JsonObject) + ("rating" to toJson(rating))) else it }
        if (!id.startsWith("local-") && !id.startsWith("pending-")) scope.launch { runCatching { Api.rpc("chat/server-fns#rateChatMessage", jsonOf("id" to id, "rating" to rating)) } }
    }
    suspend fun sendNew(text: String, images: List<ChatImage>) {
        val r = send(text, images) ?: return
        r.objAt("conversation")?.str("id")?.let { id -> if (conversationId == null) { ChatCache.put(context, r.objAt("conversation"), listOfNotNull(r.objAt("userMessage"), r.objAt("answerMessage"))); nav.go("/assistant/$id") } }
    }
    ScreenList {
        item { Heading("AIアシスタント", "自由な質問に答える汎用AIです。登録しているカメラ・レンズ・露出計の測定値を参照して、撮影の相談にも乗ります。") }
        item { ConversationList(conversationId, listKey) }
        item { ChatSettingsBar(mode, style, { mode = it }, { style = it }) }
        if (conversationId == null) {
            item {
                Panel {
                    Text("✨ 何でも聞いてください", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Muted("撮影・露出の相談から、文章の作成、計算、調べものまで自由に質問できます。登録している機材と露出計の測定値を参照して答えます。", 11)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { EXAMPLES.forEach { ex -> FilterChip(false, { scope.launch { sendNew(ex, emptyList()) } }, enabled = !busy, label = { Text(ex, fontSize = 11.sp) }) } }
                }
            }
            if (busy) item { Muted("回答を生成しています…", 11) }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(conversation.str("title") ?: "AIアシスタント", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    Muted("${messages.size}件", 10)
                    SmallButton("書き出し", {
                        val done = messages.filter { it.bool("pending") != true }
                        val title = (conversation.str("title") ?: "AIアシスタント").replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        val md = buildString {
                            appendLine("# ${conversation.str("title") ?: "AIアシスタントの会話"}"); appendLine(); appendLine("- 書き出し日時: ${fmt(FULL, System.currentTimeMillis())}"); appendLine("- メッセージ数: ${done.size}"); appendLine()
                            done.forEach { m -> appendLine("## ${if (m.str("role") == "user") "質問" else "AI"}（${fmt(FULL, m.long("at"))}）"); appendLine(); appendLine(m.str("content").orEmpty()); appendLine() }
                        }
                        scope.launch { runCatching { snackbar.showSnackbar("${Export.text(context, "$title.md", "text/markdown", md)} に保存しました") }.onFailure { snackbar.showSnackbar(it.userMessage()) } }
                    }, enabled = messages.isNotEmpty(), icon = Icons.Outlined.Download, modifier = Modifier.padding(start = 6.dp))
                }
                if (note.isNotBlank()) Text(note, fontSize = 10.sp, color = Brand.Warning)
            }
            if (loading) item { Muted("会話を読み込んでいます…", 11) }
            else if (messages.isEmpty()) item { Muted("まだメッセージがありません。下の入力欄から質問してください。", 11) }
            messages.forEach { m ->
                item(key = m.str("id")) {
                    when {
                        m.bool("pending") == true -> Muted("回答を生成しています…", 11)
                        m.str("role") == "user" -> ChatQuestion(m)
                        else -> ChatAnswer(m, busy, onRegenerate = {
                            val idx = messages.indexOfFirst { it.str("id") == m.str("id") }
                            val lastUser = (if (idx >= 0) messages.take(idx) else messages).lastOrNull { it.str("role") == "user" }
                            if (lastUser == null) error = "再生成する質問が見つかりませんでした。" else scope.launch { send(lastUser.str("content").orEmpty(), replaceId = m.str("id")) }
                        }, onRate = { rate(m.str("id").orEmpty(), it) }, onFollowup = { f -> scope.launch { send(f) } })
                    }
                }
            }
        }
        if (error.isNotBlank()) item { Text(error, fontSize = 11.sp, color = Brand.Destructive, fontWeight = FontWeight.Bold) }
        item { ChatComposer({ t, imgs -> if (conversationId == null) sendNew(t, imgs) else send(t, imgs) }, busy) }
        item { Muted("回答はAIが生成します。誤りを含む場合があるため、大事な判断はご自身で確認してください。画像を添付したときだけ、その画像がAIへ送信されます（画像は非公開で保存）。", 10) }
    }
}

// ------------------------------ 総合相談AI（expert） ------------------------------

private val EXPERT_MODES = listOf("auto" to "通常回答（自動判断）", "web" to "Web検索", "db" to "DB検索", "full" to "総合調査", "technical" to "技術調査", "latest" to "最新情報", "deep" to "詳細調査")
private val MODE_HINT = mapOf("auto" to "質問に応じて、Web検索が必要かを自動で判断します。", "web" to "インターネットの情報を中心に調査します（検索できない場合は失敗として表示します）。",
    "db" to "アプリ内の車両・現在状態・運行履歴・収集状況だけを根拠にします。", "full" to "Web・DB・GPS・運行履歴を横断して調べます。", "technical" to "Base44・GAS・API・コードの公式ドキュメントを優先して調べます。",
    "latest" to "新しい公式情報・ニュースを優先します。", "deep" to "複数の情報源を比較し、相違点も明記したレポートにします。")
private val CONFIDENCE = mapOf("measured" to ("実測" to Brand.Success), "reported" to ("公開情報" to INFO), "estimated" to ("推定" to Brand.Warning), "unknown" to ("不明" to Brand.MutedFg))
private val KIND_LABEL = mapOf("official" to "公式", "public" to "公的", "media" to "報道", "personal" to "個人", "sns" to "SNS", "db" to "アプリ内DB")

/** 実データによるバスロケ診断（Web: expert/DiagnosticsCard.jsx）。 */
@Composable
private fun DiagnosticsCard() {
    var data by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var key by remember { mutableStateOf(0) }
    LaunchedEffect(key) {
        busy = true; error = ""
        try { data = Api.rpc("expert/server-fns#getExpertDiagnostics", jsonOf()) } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "診断できませんでした" } } finally { busy = false }
    }
    Panel(title = "バスロケ診断（実データ）", action = { SmallButton(if (busy) "確認中…" else "再確認", { key++ }, enabled = !busy, icon = Icons.Outlined.Refresh) }) {
        if (error.isNotBlank()) Text(error, fontSize = 11.sp, color = Brand.Destructive)
        val d = data
        if (d == null && error.isBlank()) Muted("読み込み中…", 11)
        d.items("items").forEach { item ->
            val (icon, c) = when (item.str("level")) { "ok" -> "✓" to Brand.Success; "warn" -> "⚠" to Brand.Warning; else -> "ℹ" to INFO }
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(icon, color = c, fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                Column { Text(item.str("label").orEmpty(), fontSize = 11.sp, fontWeight = FontWeight.Bold); Muted(item.str("detail").orEmpty(), 10) }
            }
        }
        if (d != null) Muted("${fmt(FULL, d.long("generatedAt") ?: runCatching { Instant.parse(d.str("generatedAt")).toEpochMilli() }.getOrNull())} 時点の記録にもとづく診断です。原因は確定したものと候補を区別して表示しています。", 10)
    }
}

/** AIの回答（Markdown）と、出典・未確認項目・信頼度（Web: expert/MessageBubble.jsx）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(m: JsonElement, onFollowup: ((String) -> Unit)?) {
    val context = LocalContext.current
    val nav = LocalNav.current
    if (m.str("role") == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(m.str("content").orEmpty(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 8.dp))
        }
        return
    }
    val o = m as? JsonObject
    val sources = parse(o?.get("sources")).items(); val missing = parse(o?.get("missing")).items().map { it.text }; val related = parse(o?.get("related")).items()
    Panel {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            CONFIDENCE[m.str("confidence")]?.let { (l, c) -> Tag(l, c) }
            if (m.bool("used_web") == true) Tag("🌐 Web検索を使用", INFO) else Tag("アプリ内データのみ", Brand.MutedFg)
            (m.long("at") ?: runCatching { Instant.parse(m.str("updated_date")).toEpochMilli() }.getOrNull())?.let { Muted(fmt(FULL, it), 10) }
        }
        Markdown(m.str("content").orEmpty())
        if (sources.isNotEmpty()) {
            Text("出典", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg, modifier = Modifier.padding(top = 4.dp))
            sources.forEach { s ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(if (s.bool("reachable") == false) "⚠" else "✓", color = if (s.bool("reachable") == false) Brand.Warning else Brand.Success, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                    Column {
                        val url = s.str("url")
                        Text(s.str("title") ?: url ?: "（URL不明）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (url != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = if (url != null) Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } else Modifier)
                        Muted("${if (s.bool("official") == true) "公式・公的" else KIND_LABEL[s.str("kind")] ?: "参考"}　${s.str("publisher") ?: "発行元不明"}${s.str("published_at")?.let { " / 公開 $it" } ?: ""}${if (s.bool("reachable") == false) " / ページを確認できませんでした" else if (s.str("checked_at") != null) " / 到達確認済み" else ""}", 10)
                        s.str("used_for")?.let { Muted("用途: $it", 10) }
                    }
                }
            }
        }
        if (missing.isNotEmpty()) Column(Modifier.fillMaxWidth().background(Brand.Warning.copy(alpha = 0.1f)).padding(6.dp)) { Text("未確認・取得できなかったこと", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Brand.Warning); missing.forEach { Muted("・$it", 10) } }
        if (related.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            related.forEach { r ->
                val t = r.str("type")
                val label = "${when (t) { "vehicle" -> "車両"; "route" -> "系統"; "stop" -> "停留所"; else -> "データ" }}: ${r.str("value")}"
                FilterChip(false, { when (t) { "vehicle" -> nav.go("/vehicles"); "stop" -> nav.go("/nara/stops"); else -> {} } }, label = { Text(label, fontSize = 10.sp) })
            }
        }
        val f = m.list("followups").map { it.text }
        if (onFollowup != null && f.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { f.forEach { x -> FilterChip(false, { onFollowup(x) }, label = { Text(x, fontSize = 10.sp) }) } }
    }
}

/** 奈良交通・バスロケ総合相談AI（Web: expert.index.jsx） */
@Composable
fun ExpertHomeScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<JsonElement>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var question by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("auto") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var key by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<JsonElement?>(null) }
    LaunchedEffect(key) { loading = true; try { rows = Api.rpc("expert/server-fns#listExpertConversations", jsonOf()).items() } catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "会話履歴を取得できませんでした" } } finally { loading = false } }
    confirm?.let { r -> androidx.compose.material3.AlertDialog(onDismissRequest = { confirm = null }, text = { Text("「${r.str("title")}」を削除しますか？") },
        confirmButton = { TextButton(onClick = { confirm = null; scope.launch { runCatching { Api.rpc("expert/server-fns#deleteExpertConversation", jsonOf("id" to r.str("id"))) }; key++ } }) { Text("削除") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("やめる") } }) }
    ScreenList {
        item { Heading("奈良交通・バスロケ総合相談AI", "車両・路線・運行・バスロケ・Base44・GAS・API について自由に質問できます。必要に応じてWeb検索とアプリ内データ（GPS・運行履歴・収集状況）を組み合わせ、出典と未確認項目を明示して回答します。") }
        if (error.isNotBlank()) item { Text(error, fontSize = 12.sp, color = Brand.Destructive) }
        item {
            Panel(title = "✨ 新しい相談") {
                app.narabuspulse.ui.kit.SelectField(EXPERT_MODES, mode, { mode = it }, label = "調査モード")
                Muted(MODE_HINT[mode].orEmpty(), 10)
                OutlinedTextField(question, { question = it }, placeholder = { Text("例: 奈良交通の最新のダイヤ改正は？ / 今運行している車両は？ / バスロケが更新されない原因を調べて", fontSize = 12.sp) }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("🌐 出典URLは到達確認し、確認できない場合は明記します", 10); Box(Modifier.weight(1f))
                    SmallButton(if (busy) "調査中…" else "質問する", {
                        busy = true; error = ""
                        scope.launch {
                            try { val r = Api.rpc("expert/server-fns#askExpert", jsonOf("question" to question.trim(), "mode" to mode)); question = ""; r.str("conversation.id")?.let { nav.go("/expert/$it") } }
                            catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "回答を取得できませんでした" } } finally { busy = false }
                        }
                    }, enabled = !busy && question.isNotBlank(), icon = Icons.Outlined.Send, primary = true)
                }
            }
        }
        item { DiagnosticsCard() }
        item {
            Panel(title = "会話履歴") {
                OutlinedTextField(q, { q = it }, placeholder = { Text("過去の質問を検索", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val list = rows.orEmpty().filter { q.isBlank() || "${it.str("title")} ${it.str("last_question")}".contains(q.trim()) }
                if (loading) Muted("読み込み中…", 11)
                else if (list.isEmpty()) Muted("まだ会話がありません。上の入力欄から質問すると、会話が保存されます。", 11)
                list.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { nav.go("/expert/${r.str("id")}") }.padding(vertical = 6.dp)) {
                            Text("💬 ${r.str("title")?.ifBlank { null } ?: "無題の相談"}", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Muted(r.str("last_question") ?: "—", 10)
                            Muted("${fmt(FULL, r.long("last_at")).ifBlank { "—" }} / ${EXPERT_MODES.toMap()[r.str("mode")] ?: r.str("mode").orEmpty()} / ${r.int("message_count") ?: 0}件", 10)
                        }
                        IconButton(onClick = { confirm = r }) { Icon(Icons.Outlined.Delete, "会話を削除") }
                    }
                }
            }
        }
    }
}

/** 相談AIの会話（Web: expert.$conversationId.jsx） */
@Composable
fun ExpertConversationScreen(conversationId: String) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var conversation by remember(conversationId) { mutableStateOf<JsonElement?>(null) }
    var messages by remember(conversationId) { mutableStateOf(listOf<JsonElement>()) }
    var loading by remember(conversationId) { mutableStateOf(true) }
    var mode by remember(conversationId) { mutableStateOf("auto") }
    var question by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var diag by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(conversationId) {
        loading = true
        try { val r = Api.rpc("expert/server-fns#getExpertConversation", jsonOf("id" to conversationId)); conversation = r.objAt("conversation"); messages = r.items("messages"); r.str("conversation.mode")?.let { mode = it } }
        catch (e: Throwable) { if (e is CancellationException) throw e; error = e.userMessage().ifBlank { "会話を読み込めませんでした" } } finally { loading = false }
    }
    fun send(text: String? = null) {
        val body = (text ?: question).trim(); if (body.isEmpty() || busy) return
        busy = true; error = ""
        val tmp = jsonOf("id" to "tmp-${System.currentTimeMillis()}", "role" to "user", "content" to body, "at" to System.currentTimeMillis())
        messages = messages + tmp; question = ""
        scope.launch {
            try {
                val r = Api.rpc("expert/server-fns#askExpert", jsonOf("question" to body, "conversation_id" to conversationId, "mode" to mode))
                messages = messages.filter { it.str("id") != tmp.str("id") } + listOfNotNull(r.objAt("userMessage")?.let { JsonObject(it + ("followups" to JsonArray(emptyList()))) }, r.objAt("answerMessage")?.let { JsonObject(it + ("followups" to ((r as? JsonObject)?.get("followups") ?: JsonArray(emptyList())))) })
                conversation = r.objAt("conversation") ?: conversation
            } catch (e: Throwable) { if (e is CancellationException) throw e; messages = messages.filter { it.str("id") != tmp.str("id") }; error = e.userMessage().ifBlank { "回答を取得できませんでした" } } finally { busy = false }
        }
    }
    if (confirm) androidx.compose.material3.AlertDialog(onDismissRequest = { confirm = false }, text = { Text("この会話を削除しますか？") },
        confirmButton = { TextButton(onClick = { confirm = false; scope.launch { runCatching { Api.rpc("expert/server-fns#deleteExpertConversation", jsonOf("id" to conversationId)) }; nav.go("/expert") } }) { Text("削除") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("やめる") } })
    val lastAssistant = messages.lastOrNull { it.str("role") == "assistant" }
    ScreenList {
        item {
            Heading(conversation.str("title") ?: "相談AI", conversation.str("last_question")?.let { "直近の質問: $it" } ?: "")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("会話一覧", { nav.go("/expert") }); SmallButton("診断", { diag = !diag }); SmallButton("削除", { confirm = true }, icon = Icons.Outlined.Delete)
            }
        }
        if (error.isNotBlank()) item { Text(error, fontSize = 12.sp, color = Brand.Destructive) }
        if (diag) item { DiagnosticsCard() }
        if (loading) item { Muted("読み込み中…", 12) }
        if (!loading && messages.isEmpty()) item { Panel { Muted("この会話にはまだメッセージがありません。下の入力欄から質問してください。", 12) } }
        messages.forEach { m -> item(key = m.str("id")) { MessageBubble(m, if (m.str("id") == lastAssistant?.str("id")) { t -> send(t) } else null) } }
        if (busy) item { Muted("Web検索とアプリ内データを調べています（モード: ${EXPERT_MODES.toMap()[mode]}）…", 11) }
        item {
            Panel {
                app.narabuspulse.ui.kit.SelectField(EXPERT_MODES, mode, { mode = it }, label = "調査モード"); Muted(MODE_HINT[mode].orEmpty(), 10)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(question, { question = it }, placeholder = { Text("質問を入力", fontSize = 12.sp) }, minLines = 2, modifier = Modifier.weight(1f))
                    SmallButton("送信", { send() }, enabled = !busy && question.isNotBlank(), icon = Icons.Outlined.Send, primary = true, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}
