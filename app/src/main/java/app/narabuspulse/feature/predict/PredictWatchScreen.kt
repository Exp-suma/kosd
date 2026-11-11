package app.narabuspulse.feature.predict

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAlert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Session
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.obj
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.push.PushStatusCard
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val FIELDS = listOf("name", "vehicle_number", "vehicle_type", "route", "garage", "area_name", "radius_m", "notify_start", "notify_route", "notify_area", "notify_end", "notify_missing", "quiet_from", "quiet_to", "enabled", "lat", "lon")
private val NUMERIC = setOf("radius_m", "quiet_from", "quiet_to", "lat", "lon")

/** Web の pickFields：保存済みの監視設定から更新に使う項目だけを取り出す。 */
private fun pickFields(watch: JsonElement, patch: Map<String, Any?>): JsonObject {
    val o = watch.obj ?: JsonObject(emptyMap())
    val out = LinkedHashMap<String, JsonElement>()
    o["id"]?.let { out["id"] = it }
    FIELDS.forEach { k ->
        val v = o[k] ?: return@forEach
        if (v is JsonNull) return@forEach
        if (k in NUMERIC && v is JsonPrimitive && v.isString && v.content.isEmpty()) return@forEach
        out[k] = v
    }
    patch.forEach { (k, v) -> out[k] = app.narabuspulse.core.toJson(v) }
    return JsonObject(out)
}

/** 車両出現通知（Web: predict.watch.jsx） */
@Composable
fun PredictWatchScreen() {
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val q = rememberLoad("appearance-setup") { Api.rpc("predict/fns#listAppearanceSetup", jsonOf()) }
    var editing by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var actionError by remember { mutableStateOf("") }
    val watches = q.data.items("watches"); val notifications = q.data.items("notifications")

    fun act(fallback: String, block: suspend () -> Unit) {
        busy = true; actionError = ""
        scope.launch {
            try { block(); q.reload() } catch (e: Throwable) { if (e is CancellationException) throw e; actionError = e.userMessage().ifBlank { fallback } } finally { busy = false }
        }
    }

    ScreenList {
        item {
            MiniHeader("車両出現通知", "指定した車番・車種・系統・エリアの運行開始や接近を監視し、端末に通知で知らせます。位置情報が古い場合は通知しません。") {
                SmallButton("監視を追加", { editing = JsonObject(emptyMap()) }, icon = Icons.Outlined.AddAlert, primary = true)
            }
        }
        item { PredictNav("/predict/watch") }
        editing?.let { w ->
            item {
                WatchForm(w.takeIf { it.str("id") != null }, busy, onCancel = { editing = null }) { payload ->
                    act("保存できませんでした") {
                        Api.rpc("predict/fns#saveAppearanceWatch", payload)
                        message = if (payload["id"] != null) "監視設定を更新しました" else "監視設定を保存しました"
                        editing = null
                    }
                }
            }
        }
        if (message.isNotBlank()) item { Text(message, fontSize = 12.sp, color = Brand.Success) }
        if (actionError.isNotBlank()) item { ErrorText(actionError) }
        q.error?.let { item { ErrorText("設定を読み込めませんでした: $it") } }
        if (q.loading && q.data == null) item { Muted("監視設定を確認しています…", 12) }
        else item {
            WatchList(watches, busy,
                onEdit = { editing = it },
                onToggle = { w -> act("変更できませんでした") { Api.rpc("predict/fns#saveAppearanceWatch", pickFields(w, mapOf("enabled" to (w.bool("enabled") == false)))) } },
                onDelete = { w -> confirm.ask("「${w.str("name")}」の監視設定を削除します。よろしいですか？") { act("削除できませんでした") { Api.rpc("predict/fns#deleteAppearanceWatch", jsonOf("id" to w.str("id"))) } } },
            )
        }
        item {
            AppearanceNotificationList(notifications, busy, canRun = Session.isAdmin,
                onMarkRead = { ids -> act("既読にできませんでした") { Api.rpc("predict/fns#markAppearanceRead", jsonOf("ids" to ids)) } },
                onRun = {
                    act("判定できませんでした") {
                        val r = Api.rpc("predict/fns#runAppearanceCheck", jsonOf())
                        message = "判定しました（監視 ${r.int("watches") ?: 0}件 / 新しい通知 ${r.int("events") ?: 0}件 / 送信 ${r.int("delivered") ?: 0}台）"
                    }
                })
        }
        item { PushStatusCard() }
        item {
            Panel(title = "通知の条件") {
                listOf("同じ車両・同じ運行では1回だけ通知します。", "位置情報が15分以上古い場合は、開始・系統・エリアの通知を行いません。", "通知しない時間帯を設定すると、その間は記録も送信もしません。", "定期判定は約10分間隔の実行を想定しています（外部からの定期実行で駆動）。").forEach { Muted("・$it", 11) }
            }
        }
    }
}

private val RADII = listOf("100", "300", "500", "800", "1000", "2000", "3000")
private val TOGGLES = listOf("notify_start" to "運行開始", "notify_route" to "指定系統へ", "notify_area" to "エリア接近", "notify_end" to "運行終了", "notify_missing" to "一定時間未確認", "notify_stop" to "停留所に接近", "notify_pass" to "停留所を通過", "notify_exit" to "エリアから退出", "notify_destination" to "行先の変更", "notify_resume" to "位置情報の復帰", "enabled" to "この監視を有効にする")
private val TOGGLE_DEFAULT = mapOf("notify_start" to true, "notify_route" to true, "notify_area" to true, "notify_end" to true, "notify_missing" to true, "enabled" to true)

/** 出現通知の設定フォーム（Web: WatchForm.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchForm(watch: JsonElement?, busy: Boolean, onCancel: () -> Unit, onSubmit: (JsonObject) -> Unit) {
    val text = remember(watch) {
        mutableStateMapOf<String, String>().apply {
            listOf("name", "vehicle_number", "vehicle_type", "route", "garage", "area_name", "stop_name").forEach { put(it, watch.str(it).orEmpty()) }
            put("radius_m", watch.long("radius_m")?.toString() ?: "800")
            put("lat", watch.dbl("lat")?.toString().orEmpty()); put("lon", watch.dbl("lon")?.toString().orEmpty())
            put("quiet_from", watch.long("quiet_from")?.toString().orEmpty()); put("quiet_to", watch.long("quiet_to")?.toString().orEmpty())
        }
    }
    val flags = remember(watch) { mutableStateMapOf<String, Boolean>().apply { TOGGLES.forEach { (k, _) -> put(k, watch.bool(k) ?: (TOGGLE_DEFAULT[k] ?: false)) } } }
    var error by remember { mutableStateOf("") }
    var locating by remember { mutableStateOf(false) }
    val locate = rememberLocationRequest { loc ->
        locating = false
        if (loc == null) error = "位置情報を取得できませんでした。座標を直接入力してください"
        else { text["lat"] = "%.6f".format(loc.lat); text["lon"] = "%.6f".format(loc.lon); if (text["area_name"].isNullOrBlank()) text["area_name"] = "現在地周辺" }
    }
    fun submit() {
        error = ""
        val t = text.mapValues { it.value.trim() }
        if (t["name"].isNullOrEmpty()) { error = "監視の名前を入力してください"; return }
        val hasPlace = t["stop_name"]!!.isNotEmpty() || (t["lat"]!!.isNotEmpty() && t["lon"]!!.isNotEmpty())
        if (listOf("vehicle_number", "vehicle_type", "route", "garage").all { t[it]!!.isEmpty() } && !hasPlace) { error = "車番・車種・系統・営業所・停留所・地点のいずれかを指定してください"; return }
        val p = LinkedHashMap<String, Any?>()
        watch.str("id")?.let { p["id"] = it }
        listOf("name", "vehicle_number", "vehicle_type", "route", "garage", "area_name").forEach { p[it] = t[it] }
        p["radius_m"] = t["radius_m"]!!.toIntOrNull()?.takeIf { it > 0 } ?: 800
        TOGGLES.filter { it.first != "enabled" }.forEach { (k, _) -> p[k] = flags[k] == true }
        p["stop_name"] = t["stop_name"]; p["enabled"] = flags["enabled"] == true
        if (t["lat"]!!.isNotEmpty() && t["lon"]!!.isNotEmpty()) { p["lat"] = t["lat"]!!.toDoubleOrNull(); p["lon"] = t["lon"]!!.toDoubleOrNull() }
        t["quiet_from"]!!.toIntOrNull()?.let { p["quiet_from"] = it }
        t["quiet_to"]!!.toIntOrNull()?.let { p["quiet_to"] = it }
        onSubmit(jsonOf(*p.entries.map { it.key to it.value }.toTypedArray()))
    }
    @Composable fun F(key: String, label: String, ph: String, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false) =
        OutlinedTextField(text[key].orEmpty(), { text[key] = it }, label = { Text(label, fontSize = 12.sp) }, placeholder = { Text(ph, fontSize = 12.sp) }, singleLine = true, modifier = modifier,
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default)
    Panel(title = if (watch != null) "監視設定を編集" else "監視設定を追加", action = { SmallButton("閉じる", onCancel, icon = Icons.Outlined.Close) }) {
        F("name", "監視の名前 *", "例 5006号車の朝の運用")
        F("vehicle_number", "車番", "例 12-5006"); F("vehicle_type", "車種", "例 大型"); F("route", "系統", "例 12")
        F("garage", "営業所", "例 奈良営業所"); F("area_name", "通知対象エリアの名前", "例 奈良駅周辺"); F("stop_name", "通知対象の停留所名", "例 近鉄奈良駅")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { F("lat", "緯度", "34.680", Modifier.weight(1f), true); F("lon", "経度", "135.818", Modifier.weight(1f), true) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            SmallButton(if (locating) "取得中…" else "現在地をエリアにする", { locating = true; locate() }, enabled = !locating, icon = Icons.Outlined.MyLocation)
            val r = text["radius_m"].orEmpty()
            SelectField((if (r !in RADII) listOf(r to "${r}m") else emptyList()) + RADII.map { v -> v to (if (v.toInt() >= 1000) "${v.toInt() / 1000}km" else "${v}m") }, r, { text["radius_m"] = it }, label = "半径")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { F("quiet_from", "通知しない開始", "23", Modifier.weight(1f), true); F("quiet_to", "通知しない終了", "6", Modifier.weight(1f), true) }
        Text("通知する内容", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        FlowRow {
            TOGGLES.forEach { (k, l) ->
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(flags[k] == true, { flags[k] = it }); Text(l, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp)) }
            }
        }
        if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = Brand.Destructive)
        SmallButton(if (busy) "保存中…" else "保存", { submit() }, enabled = !busy, icon = Icons.Outlined.Save, primary = true)
    }
}

private fun conditionText(w: JsonElement): String {
    val parts = mutableListOf<String>()
    w.str("vehicle_number")?.takeIf { it.isNotBlank() }?.let { parts += "車番 $it" }
    w.str("vehicle_type")?.takeIf { it.isNotBlank() }?.let { parts += "車種 $it" }
    w.str("route")?.takeIf { it.isNotBlank() }?.let { parts += "系統 $it" }
    w.str("garage")?.takeIf { it.isNotBlank() }?.let { parts += "営業所 $it" }
    if (w.dbl("lat") != null && w.dbl("lon") != null) parts += "エリア ${w.str("area_name")?.ifBlank { null } ?: "指定地点"}（${w.long("radius_m") ?: 800}m）"
    return parts.joinToString(" / ").ifBlank { "条件が未設定" }
}

private fun enabledLabels(w: JsonElement): String = listOfNotNull(
    "開始".takeIf { w.bool("notify_start") != false },
    "系統".takeIf { w.bool("notify_route") != false && !w.str("route").isNullOrBlank() },
    "エリア".takeIf { w.bool("notify_area") != false && w.dbl("lat") != null },
    "終了".takeIf { w.bool("notify_end") != false },
    "未確認".takeIf { w.bool("notify_missing") != false },
).joinToString("・")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchList(watches: List<JsonElement>, busy: Boolean, onEdit: (JsonElement) -> Unit, onToggle: (JsonElement) -> Unit, onDelete: (JsonElement) -> Unit) {
    if (watches.isEmpty()) { EmptyState("監視設定がありません", description = "車番や系統を指定して、運行開始・エリア接近などを通知できます。"); return }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        watches.forEach { w ->
            val off = w.bool("enabled") == false
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(w.str("name").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Tag(if (off) "停止中" else "有効", if (off) Brand.MutedFg else Brand.Success)
                }
                Text(conditionText(w), fontSize = 12.sp)
                val qf = w.long("quiet_from"); val qt = w.long("quiet_to")
                Muted("🔔 ${enabledLabels(w).ifBlank { "通知内容なし" }}　🕒 ${if (qf != null && qt != null) "${qf}時〜${qt}時は通知しない" else "通知しない時間帯の指定なし"}${w.long("last_checked_at")?.let { "　📍 最終判定 ${BusFormat.time(it)}" } ?: ""}", 10)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton(if (off) "有効化" else "停止", { onToggle(w) }, enabled = !busy, icon = if (off) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff)
                    SmallButton("編集", { onEdit(w) }, enabled = !busy, icon = Icons.Outlined.Edit)
                    SmallButton("削除", { onDelete(w) }, enabled = !busy, icon = Icons.Outlined.Delete)
                }
            }
        }
    }
}

private val TYPE = mapOf(
    "start" to ("運行開始" to Brand.Success), "route" to ("指定系統" to INFO), "area" to ("エリア接近" to INFO), "type" to ("車種" to Brand.Warning),
    "end" to ("運行終了" to Brand.MutedFg), "missing" to ("未確認" to Brand.Destructive), "stop" to ("停留所接近" to INFO), "passed" to ("停留所通過" to PURPLE),
    "exit" to ("エリア退出" to PURPLE), "destination" to ("行先変更" to Brand.Warning), "resume" to ("位置復帰" to Brand.Success),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceNotificationList(rows: List<JsonElement>, busy: Boolean, canRun: Boolean, onMarkRead: (List<String>) -> Unit, onRun: () -> Unit) {
    val unread = rows.filter { it.long("read_at") == null && it.str("read_at") == null }
    Panel(title = "🔔 通知履歴") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (unread.isNotEmpty()) { Tag("${unread.size}件未読", Brand.Warning); SmallButton("すべて既読", { onMarkRead(unread.mapNotNull { it.str("id") }) }, icon = Icons.Outlined.DoneAll) }
            if (canRun) SmallButton(if (busy) "判定中…" else "いま判定する", onRun, enabled = !busy, icon = Icons.Outlined.Refresh)
        }
        if (rows.isEmpty()) EmptyState("通知はまだありません", description = "監視設定を有効にすると、条件に合ったときにここへ記録され、端末にも通知されます。")
        rows.forEachIndexed { i, r ->
            if (i > 0) RowDivider()
            Column(Modifier.padding(vertical = 4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    val (l, c) = TYPE[r.str("type")] ?: (r.str("type").orEmpty() to Brand.MutedFg)
                    Tag(l, c); r.str("vehicle")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    Muted(BusFormat.dateTime(r.long("at")), 10)
                }
                Text(r.str("title").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                r.str("body")?.takeIf { it.isNotBlank() }?.let { Muted(it, 11) }
            }
        }
    }
}

