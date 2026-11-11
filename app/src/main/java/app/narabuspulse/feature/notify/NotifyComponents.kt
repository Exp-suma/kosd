package app.narabuspulse.feature.notify

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AddAlert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.obj
import app.narabuspulse.core.objAt
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val INFO = Color(0xFF2F6FB0)

fun toneColor(tone: String?): Color = when (tone) {
    "success" -> Brand.Success; "info" -> INFO; "warning" -> Brand.Warning; "danger" -> Brand.Destructive; "primary" -> Brand.Orange; else -> Brand.MutedFg
}

private val VN_TABS = listOf("/vehicle-notify" to "監視の設定", "/vehicle-notify/place" to "地点を指定", "/vehicle-notify/history" to "通知履歴")

/** 車両通知のタブ（Web: components/notify/VehicleNotifyNav.jsx） */
@Composable
fun VehicleNotifyNav(current: String) {
    val nav = LocalNav.current
    ChipRow(VN_TABS, current, { if (it != current) nav.go(it) })
}

/** notify/vehicleLabels.js の表示用ラベル。 */
@Composable
fun rememberVehicleLabels(): Pair<JsonElement?, JsonElement?> {
    val types = produceState<JsonElement?>(null) { value = Logic.constant("notify/vehicleLabels", "NOTIFY_TYPES") }
    val send = produceState<JsonElement?>(null) { value = Logic.constant("notify/vehicleLabels", "SEND_STATUS") }
    return types.value to send.value
}

@Composable
fun logicText(module: String, fn: String, vararg args: Any?): String =
    produceState("", *args) { value = Logic.call(module, fn, *args).text }.value

private val RADII = listOf("100", "300", "500", "800", "1000", "2000", "3000")
private val NOTIFY_KEYS = listOf(
    "notify_start" to "運行開始", "notify_route" to "指定系統へ", "notify_area" to "エリア接近", "notify_end" to "運行終了",
    "notify_missing" to "一定時間未確認", "notify_stop" to "停留所に接近", "notify_pass" to "停留所を通過", "notify_exit" to "エリアから退出",
    "notify_destination" to "行先の変更", "notify_resume" to "位置情報の復帰",
)
private val NOTIFY_DEFAULTS = mapOf("notify_start" to true, "notify_route" to true, "notify_area" to true, "notify_end" to true, "notify_missing" to true)

/** 出現通知の設定フォーム（Web: components/predict/WatchForm.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchForm(watch: JsonElement?, onSubmit: (JsonObject) -> Unit, onCancel: (() -> Unit)?, busy: Boolean) {
    fun s(k: String) = watch.str(k).orEmpty()
    var name by remember { mutableStateOf(s("name")) }
    var vehicleNumber by remember { mutableStateOf(s("vehicle_number")) }
    var vehicleType by remember { mutableStateOf(s("vehicle_type")) }
    var route by remember { mutableStateOf(s("route")) }
    var garage by remember { mutableStateOf(s("garage")) }
    var areaName by remember { mutableStateOf(s("area_name")) }
    var stopName by remember { mutableStateOf(s("stop_name")) }
    var lat by remember { mutableStateOf(s("lat")) }
    var lon by remember { mutableStateOf(s("lon")) }
    var radius by remember { mutableStateOf(watch.str("radius_m") ?: "800") }
    var quietFrom by remember { mutableStateOf(s("quiet_from")) }
    var quietTo by remember { mutableStateOf(s("quiet_to")) }
    var enabled by remember { mutableStateOf(watch.bool("enabled") != false) }
    val flags = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply { NOTIFY_KEYS.forEach { (k, _) -> put(k, watch.bool(k) ?: (NOTIFY_DEFAULTS[k] ?: false)) } } }
    var error by remember { mutableStateOf("") }
    var locating by remember { mutableStateOf(false) }
    val locate = rememberLocationRequest { loc ->
        locating = false
        if (loc == null) error = "位置情報を取得できませんでした。座標を直接入力してください"
        else { lat = "%.6f".format(loc.lat); lon = "%.6f".format(loc.lon); if (areaName.isBlank()) areaName = "現在地周辺" }
    }

    Panel(title = if (watch?.str("id") != null) "監視設定を編集" else "監視設定を追加", action = { if (onCancel != null) SmallButton("閉じる", onCancel, icon = Icons.Outlined.Close) }) {
        @Composable fun F(label: String, v: String, set: (String) -> Unit, ph: String = "", num: Boolean = false, m: Modifier = Modifier.fillMaxWidth()) =
            OutlinedTextField(v, set, label = { Text(label, fontSize = 12.sp) }, placeholder = { Text(ph, fontSize = 12.sp) }, singleLine = true, modifier = m,
                keyboardOptions = if (num) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default)
        F("監視の名前 *", name, { name = it }, "例 5006号車の朝の運用")
        F("車番", vehicleNumber, { vehicleNumber = it }, "例 12-5006")
        F("車種", vehicleType, { vehicleType = it }, "例 大型")
        F("系統", route, { route = it }, "例 12")
        F("営業所", garage, { garage = it }, "例 奈良営業所")
        F("通知対象エリアの名前", areaName, { areaName = it }, "例 奈良駅周辺")
        F("通知対象の停留所名", stopName, { stopName = it }, "例 近鉄奈良駅")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            F("緯度", lat, { lat = it }, "34.680", true, Modifier.weight(1f))
            F("経度", lon, { lon = it }, "135.818", true, Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            SmallButton("現在地をエリアにする", { locating = true; locate() }, enabled = !locating, icon = Icons.Outlined.GpsFixed)
            SelectField(((if (radius !in RADII) listOf(radius) else emptyList()) + RADII).map { it to if ((it.toIntOrNull() ?: 0) >= 1000) "${it.toInt() / 1000}km" else "${it}m" }, radius, { radius = it }, label = "半径")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            F("通知しない開始", quietFrom, { quietFrom = it.filter(Char::isDigit) }, "23", true, Modifier.weight(1f))
            F("通知しない終了", quietTo, { quietTo = it.filter(Char::isDigit) }, "6", true, Modifier.weight(1f))
        }
        Text("通知する内容", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        FlowRow {
            NOTIFY_KEYS.forEach { (k, label) -> CheckRow(label, flags[k] == true) { flags[k] = it } }
            CheckRow("この監視を有効にする", enabled) { enabled = it }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        SmallButton("保存", {
            error = ""
            if (name.isBlank()) { error = "監視の名前を入力してください"; return@SmallButton }
            val hasPlace = stopName.isNotBlank() || (lat.isNotBlank() && lon.isNotBlank())
            if (vehicleNumber.isBlank() && vehicleType.isBlank() && route.isBlank() && garage.isBlank() && !hasPlace) {
                error = "車番・車種・系統・営業所・停留所・地点のいずれかを指定してください"; return@SmallButton
            }
            val payload = mutableMapOf<String, Any?>(
                "id" to watch?.str("id"), "name" to name.trim(), "vehicle_number" to vehicleNumber.trim(), "vehicle_type" to vehicleType.trim(),
                "route" to route.trim(), "garage" to garage.trim(), "area_name" to areaName.trim(), "radius_m" to (radius.toIntOrNull() ?: 800),
                "stop_name" to stopName.trim(), "enabled" to enabled,
            )
            NOTIFY_KEYS.forEach { (k, _) -> payload[k] = flags[k] == true }
            if (lat.isNotBlank() && lon.isNotBlank()) { payload["lat"] = lat.toDoubleOrNull(); payload["lon"] = lon.toDoubleOrNull() }
            quietFrom.toIntOrNull()?.let { payload["quiet_from"] = it }
            quietTo.toIntOrNull()?.let { payload["quiet_to"] = it }
            onSubmit(jsonOf(*payload.entries.map { it.key to it.value }.toTypedArray()))
        }, enabled = !busy, icon = Icons.Outlined.Save, primary = true, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onChange(!checked) }.padding(end = 8.dp)) {
        Checkbox(checked, onChange, modifier = Modifier.size(36.dp))
        Text(label, fontSize = 12.sp)
    }
}

/** 車両の検索と一括監視登録（Web: components/notify/NotifyVehicleSearch.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotifyVehicleSearch(onRegistered: () -> Unit) {
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    var q by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var garage by remember { mutableStateOf("") }
    var route by remember { mutableStateOf("") }
    var runningOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(q) { delay(400); debounced = q }
    val query = rememberLoad("notify-search", debounced, garage, route, runningOnly) {
        Api.rpc("notify/vehicle-fns#searchNotifyVehicles", jsonOf("q" to debounced, "garage" to garage, "route" to route, "runningOnly" to runningOnly))
    }
    val data = query.data
    val items = data.items("items")
    fun register(numbers: List<String>, all: Boolean) {
        busy = true; error = ""; message = ""
        scope.launch {
            try {
                val res = Api.rpc("notify/vehicle-fns#bulkWatchVehicles", if (all) jsonOf("all" to true) else jsonOf("vehicleNumbers" to numbers))
                message = "${res.int("created") ?: 0}台を監視対象に追加しました" + ((res.int("skipped") ?: 0).takeIf { it > 0 }?.let { "（登録済み ${it}台）" } ?: "")
                selected = emptyList(); query.reload(); onRegistered()
            } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage().ifBlank { "登録できませんでした" } } finally { busy = false }
        }
    }
    Panel(title = "車両を探して監視する", subtitle = "全 ${data.int("total") ?: 0} 台（運行中 ${data.int("running") ?: 0} 台）") {
        OutlinedTextField(q, { q = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("車番・車両名・車種・営業所・系統・行先で検索", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            SelectField(listOf("" to "営業所：すべて") + data.list("garages").map { it.text to it.text }, garage, { garage = it })
            SelectField(listOf("" to "系統：すべて") + data.list("routes").map { it.text to it.text }, route, { route = it })
        }
        CheckRow("現在運行中の車両だけを表示", runningOnly) { runningOnly = it }
        if (message.isNotBlank()) Text(message, fontSize = 12.sp, color = Brand.Success)
        if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SmallButton("選択した ${selected.size} 台を監視", { confirm.ask("${selected.size}台を監視対象に追加します。よろしいですか？") { register(selected, false) } }, enabled = selected.isNotEmpty() && !busy, icon = Icons.Outlined.AddAlert, primary = true)
            SmallButton("全車両を一括監視", { confirm.ask("全車両をまとめて監視対象に追加します（登録済みは除く）。よろしいですか？") { register(emptyList(), true) } }, enabled = (data.int("total") ?: 0) > 0 && !busy, icon = Icons.Outlined.SelectAll)
            if (selected.isNotEmpty()) SmallButton("選択を解除", { selected = emptyList() })
        }
        if (items.isEmpty() && !query.loading) Text("条件に合う車両がありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        if (query.loading && items.isEmpty()) Text("検索しています…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEachIndexed { i, item ->
            if (i > 0) RowDivider()
            val number = item.str("vehicle_number").orEmpty()
            val watched = item.bool("watched") == true
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Checkbox(number in selected, { selected = if (number in selected) selected - number else selected + number }, enabled = !watched, modifier = Modifier.size(36.dp))
                Column(Modifier.weight(1f)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(number, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        if (item.bool("running") == true) Tag("運行中", Brand.Success) else Tag("停止中", Brand.MutedFg)
                        if (watched) Tag("監視中", INFO)
                    }
                    Text(listOfNotNull(item.str("garage"), item.str("vehicle_type")).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "所属・車種は未登録" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (item.bool("running") == true) "系統 ${item.str("route")?.ifBlank { null } ?: "—"}／${item.str("destination")?.ifBlank { null } ?: "行先不明"}${item.str("stop")?.takeIf { it.isNotBlank() }?.let { "／$it" } ?: ""}" else "現在の位置情報はありません", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 登録済みの監視（Web: components/notify/NotifyWatchList.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotifyWatchList(watches: List<JsonElement>, onEdit: (JsonElement) -> Unit, onToggle: (JsonElement) -> Unit, onDelete: (JsonElement) -> Unit, busy: Boolean, onAdd: (() -> Unit)?) {
    if (watches.isEmpty()) {
        Panel {
            EmptyState("監視中の車両はありません", description = "上の検索から車番を選ぶか、地点指定からエリア監視を追加できます。", actionLabel = if (onAdd != null) "監視を追加" else null, onAction = onAdd)
        }
        return
    }
    Panel(title = "登録済みの監視（${watches.size}）", action = { if (onAdd != null) SmallButton("追加", onAdd) }) {
        watches.forEachIndexed { i, w ->
            if (i > 0) RowDivider()
            val off = w.bool("enabled") == false
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.str("name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Tag(if (off) "停止中" else "監視中", if (off) Brand.MutedFg else Brand.Success)
                }
                Text(logicText("notify/vehicleLabels", "conditionText", w), fontSize = 12.sp)
                Text("📍 " + logicText("notify/vehicleLabels", "eventText", w).ifBlank { "通知する内容が未選択" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val qf = w.int("quiet_from"); val qt = w.int("quiet_to")
                if (qf != null && qt != null) Text("🕒 ${qf}〜${qt}時は通知しない", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    SmallButton(if (off) "再開" else "停止", { onToggle(w) }, enabled = !busy, icon = if (off) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff)
                    SmallButton("条件", { onEdit(w) }, enabled = !busy, icon = Icons.Outlined.Edit)
                    SmallButton("", { onDelete(w) }, enabled = !busy, icon = Icons.Outlined.Delete)
                }
            }
        }
    }
}

/** 最新の通知（Web: components/notify/NotifyLatestList.jsx） */
@Composable
fun NotifyLatestList(notifications: List<JsonElement>, unread: Int) {
    val nav = LocalNav.current
    val (types, send) = rememberVehicleLabels()
    val rows = notifications.take(6)
    Panel(title = "最新の通知", action = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (unread > 0) Tag("${unread}件未読", Brand.Warning)
            SmallButton("履歴へ", { nav.go("/vehicle-notify/history") }, icon = Icons.AutoMirrored.Filled.KeyboardArrowRight)
        }
    }) {
        if (rows.isEmpty()) EmptyState("通知はまだありません", description = "監視を有効にすると、条件に合ったときにここへ届きます。")
        rows.forEachIndexed { i, row -> if (i > 0) RowDivider(); NotifyRow(row, types, send) }
    }
}

@Composable
fun NotifyRow(row: JsonElement, types: JsonElement?, send: JsonElement?) {
    val type = types.objAt(row.str("type").orEmpty())
    val st = send.objAt(row.str("send_status").orEmpty()) ?: send.objAt("stored")
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(type.str("label") ?: row.str("type").orEmpty(), toneColor(type.str("tone")))
            row.str("vehicle")?.takeIf { it.isNotBlank() }?.let { Text("  $it", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Text("  " + logicText("notify/vehicleLabels", "notifyTimeText", row.long("at")), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(row.str("title").orEmpty(), fontSize = 12.sp)
        Text(listOf(logicText("notify/vehicleLabels", "channelsText", row), st.str("label").orEmpty(), row.str("place_name").orEmpty()).filter { it.isNotBlank() }.joinToString("・"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val FREQUENCY = listOf("instant" to "即時に通知", "m15" to "15分に1回まで", "h1" to "1時間に1回まで")

/** 通知方法・頻度・一時停止（Web: components/notify/NotifyChannelSettings.jsx） */
@Composable
fun NotifyChannelSettings(prefs: JsonElement?, pushDevices: Int, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var inapp by remember(prefs) { mutableStateOf(prefs.bool("vehicle_inapp") != false) }
    var push by remember(prefs) { mutableStateOf(prefs.bool("vehicle_push") != false) }
    var email by remember(prefs) { mutableStateOf(prefs.bool("vehicle_email") == true) }
    var paused by remember(prefs) { mutableStateOf(prefs.bool("vehicle_paused") == true) }
    var frequency by remember(prefs) { mutableStateOf(prefs.str("vehicle_frequency") ?: "instant") }
    var quietFrom by remember(prefs) { mutableStateOf(prefs.int("quiet_from")?.toString().orEmpty()) }
    var quietTo by remember(prefs) { mutableStateOf(prefs.int("quiet_to")?.toString().orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Panel(title = "通知設定", action = { if (paused) Tag("一時停止中", Brand.Warning) }) {
        CheckRow("アプリ内の通知履歴に残す（このページと通知履歴に表示します）", inapp) { inapp = it }
        CheckRow("端末への通知（アプリを閉じていても届く）" + if (pushDevices > 0) "　登録 ${pushDevices} 台" else "", push) { push = it }
        CheckRow("メールでも通知する（登録済みのメールアドレス宛）", email) { email = it }
        CheckRow("通知を一時停止する（判定結果は保存し、通知は送りません）", paused) { paused = it }
        SelectField(FREQUENCY, frequency, { frequency = it }, label = "通知頻度")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(quietFrom, { quietFrom = it.filter(Char::isDigit) }, label = { Text("通知しない開始（時）", fontSize = 11.sp) }, placeholder = { Text("23") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(quietTo, { quietTo = it.filter(Char::isDigit) }, label = { Text("通知しない終了（時）", fontSize = 11.sp) }, placeholder = { Text("6") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        if (message.isNotBlank()) Text(message, fontSize = 12.sp, color = Brand.Success)
        if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            SmallButton("テストメールを送信", {
                busy = true; error = ""; message = ""
                scope.launch {
                    try {
                        val res = Api.rpc("notify/vehicle-fns#sendNotifyTestEmail", jsonOf())
                        if (res.bool("ok") == true) message = "テストメールを ${res.str("to")} 宛に送信しました" else error = res.str("error") ?: "メールを送信できませんでした"
                    } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage() } finally { busy = false }
                }
            }, enabled = !busy, icon = Icons.Outlined.Email)
            SmallButton("保存", {
                busy = true; error = ""; message = ""
                scope.launch {
                    try {
                        Api.rpc("notify/fns#saveNotifyPrefs", jsonOf(
                            "vehicle_inapp" to inapp, "vehicle_push" to push, "vehicle_email" to email, "vehicle_paused" to paused,
                            "vehicle_frequency" to frequency, "quiet_from" to quietFrom.toIntOrNull(), "quiet_to" to quietTo.toIntOrNull(),
                        ).let { o -> JsonObject(o + mapOf("quiet_from" to (quietFrom.toIntOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull), "quiet_to" to (quietTo.toIntOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull))) })
                        message = "通知設定を保存しました"; onSaved()
                    } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = false }
                }
            }, enabled = !busy, icon = Icons.Outlined.Save, primary = true)
        }
    }
}

/** 管理者向けの監視処理パネル（Web: components/notify/NotifyAdminPanel.jsx） */
@Composable
fun NotifyAdminPanel(admin: JsonElement?, onUpdated: () -> Unit) {
    if (admin?.obj == null) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val control = admin.objAt("control")
    val runs = admin.list("runs")
    val paused = control.bool("paused") == true
    fun clock(ts: Long?) = if (ts == null) "—" else "${app.narabuspulse.feature.bus.BusFormat.shortDate(ts)} ${app.narabuspulse.feature.bus.BusFormat.time(ts)}"
    Panel(title = "監視処理（管理者）", action = { Tag(if (paused) "一時停止中" else "稼働中", if (paused) Brand.Warning else Brand.Success) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("手動実行", {
                busy = "run"; error = ""; message = ""
                scope.launch {
                    try {
                        val res = Api.rpc("predict/fns#runAppearanceCheck", jsonOf())
                        when {
                            res.bool("paused") == true -> message = "通知処理は一時停止中です"
                            !res.str("error").isNullOrBlank() -> error = res.str("error")!!
                            else -> message = "判定しました（監視 ${res.int("watches")}件 / 新しい通知 ${res.int("events")}件 / 送信 ${res.int("delivered")}件・失敗 ${res.int("failed") ?: 0}件）"
                        }
                        onUpdated()
                    } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage().ifBlank { "実行できませんでした" } } finally { busy = "" }
                }
            }, enabled = busy.isEmpty(), icon = Icons.Outlined.Refresh)
            SmallButton(if (paused) "再開" else "一時停止", {
                busy = "pause"; error = ""; message = ""
                scope.launch {
                    try {
                        Api.rpc("notify/vehicle-fns#setVehicleNotifyPaused", jsonOf("paused" to !paused))
                        message = if (paused) "通知処理を再開しました" else "通知処理を一時停止しました"; onUpdated()
                    } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage().ifBlank { "変更できませんでした" } } finally { busy = "" }
                }
            }, enabled = busy.isEmpty(), icon = if (paused) Icons.Outlined.PlayCircle else Icons.Outlined.PauseCircle)
        }
        KeyValue("監視中の車両", "${admin.int("totals.watches") ?: 0}件")
        KeyValue("登録ユーザー", "${admin.int("totals.users") ?: 0}人")
        KeyValue("通知の累計", "${admin.int("totals.events") ?: 0}件")
        KeyValue("最終データ取得", clock(control.long("last_success_at")))
        KeyValue("最終通知処理", clock(control.long("last_run_at")))
        KeyValue("送信成功", "${control.int("total_sent") ?: 0}件")
        KeyValue("送信失敗", "${control.int("total_failed") ?: 0}件")
        KeyValue("前回の通知", "${runs.firstOrNull().int("events") ?: 0}件")
        val errors = runs.filter { !it.str("error").isNullOrBlank() }.take(4)
        if (!control.str("last_error").isNullOrBlank() || errors.isNotEmpty()) {
            Text("エラーログ", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand.Destructive)
            control.str("last_error")?.takeIf { it.isNotBlank() }?.let { Text("直近: $it", fontSize = 11.sp) }
            errors.forEach { Text("${clock(it.long("at"))} ${it.str("error")}", fontSize = 11.sp) }
        }
        if (message.isNotBlank()) Text(message, fontSize = 12.sp, color = Brand.Success)
        if (error.isNotBlank()) Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        Text("判定は定期実行（外部からの /api/predict-check）で行われます。手動実行は全利用者の設定を対象にします。取得に失敗した回は状態を更新せず、通知も送りません。最終処理 ${logicText("notify/vehicleLabels", "notifyTimeText", runs.firstOrNull().long("at"))}。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
