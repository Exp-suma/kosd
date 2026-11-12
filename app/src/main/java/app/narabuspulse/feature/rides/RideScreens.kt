package app.narabuspulse.feature.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Notifier
import app.narabuspulse.core.bool
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.rememberNotifyPermission
import app.narabuspulse.core.str
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.push.PushStatusCard
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.kit.TimeField
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement


private fun liveColor(state: String?) = when (state) {
    "at_stop", "approaching" -> Brand.Success
    "en_route" -> INFO
    "passed" -> Brand.Destructive
    "stale" -> Brand.Warning
    else -> Brand.MutedFg
}

private val ALERT_LABEL = mapOf("approach" to "接近", "departure" to "発車間近", "missed" to "乗り遅れの可能性", "not_running" to "運行情報なし", "stale" to "位置情報更新停止")
private val ALERT_COLOR = mapOf("approach" to Brand.Success, "departure" to Brand.Warning, "missed" to Brand.Destructive, "not_running" to Brand.MutedFg, "stale" to Brand.Warning)

private fun todayKey() = BusFormat.dayKey(System.currentTimeMillis())
private fun nowHm() = BusFormat.time(System.currentTimeMillis())
private fun toEpoch(date: String, hm: String): Long? = runCatching {
    val m = Regex("^(\\d{1,2}):(\\d{2})$").find(hm) ?: return null
    java.time.OffsetDateTime.parse("${date}T${m.groupValues[1].padStart(2, '0')}:${m.groupValues[2]}:00+09:00").toInstant().toEpochMilli()
}.getOrNull()

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", number: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 12.sp) }, placeholder = { Text(placeholder, fontSize = 12.sp) }, singleLine = true, modifier = modifier,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default)
}

/** 乗車予定（Web: rides.plans.jsx）。3 分ごとに運行中の実データで判定し、通知する。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RidePlansScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val perm = rememberNotifyPermission()
    var plans by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    val live = remember { mutableStateMapOf<String, JsonElement>() }
    var alerts by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var dataError by remember { mutableStateOf("") }
    var checkedAt by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf("") }

    suspend fun refreshPlans() { plans = Api.rpc("rides/server-fns#listRidePlans", jsonOf()).items() }
    suspend fun runCheck() {
        try {
            val res = Api.rpc("rides/server-fns#checkRideAlerts", jsonOf())
            dataError = res.str("dataError").orEmpty()
            checkedAt = res.long("checkedAt") ?: 0
            res.items("plans").forEach { p -> p.str("id")?.let { live[it] = p } }
            val newAlerts = res.items("alerts")
            if (newAlerts.isNotEmpty()) {
                alerts = (newAlerts + alerts).take(40)
                newAlerts.take(3).forEach { a -> Notifier.show(context, a.str("title").orEmpty(), a.str("body").orEmpty(), "/rides/plans", Notifier.CH_RIDES) }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            dataError = e.userMessage().ifBlank { "乗車予定を判定できませんでした" }
        }
    }
    LaunchedEffect(Unit) {
        try {
            refreshPlans()
            alerts = Api.rpc("rides/server-fns#listRideAlerts", jsonOf()).items()
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            dataError = e.userMessage().ifBlank { "読み込めませんでした" }
        }
        while (true) { runCheck(); delay(180_000) }
    }
    fun act(id: String, block: suspend () -> Unit) {
        busy = id
        scope.launch {
            try { block() } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; dataError = e.userMessage() } finally { busy = "" }
        }
    }

    // 入力欄（PlanForm）
    var date by remember { mutableStateOf(todayKey()) }
    var route by remember { mutableStateOf("") }
    var departure by remember { mutableStateOf(nowHm()) }
    var boarding by remember { mutableStateOf("") }
    var alighting by remember { mutableStateOf("") }
    var vehicle by remember { mutableStateOf("") }
    var remind by remember { mutableStateOf("10") }
    var walk by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf("") }

    ScreenList {
        item {
            Column {
                Text("乗車予定", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("乗車予定を登録すると、運行中の実データから接近・発車間近・乗り遅れの可能性を判定して通知します。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (dataError.isNotBlank()) item { Text("$dataError（表示中の予定は前回の判定結果です）", fontSize = 12.sp, color = Brand.Warning) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NotificationsActive, null, tint = Brand.Orange)
                Text(
                    " 通知：" + when (perm.state) { "granted" -> "端末の通知が有効"; "denied" -> "端末の通知がオフになっています"; else -> "未許可" } +
                        (if (checkedAt > 0) "　最終判定 ${BusFormat.time(checkedAt)}" else ""),
                    fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                if (perm.state != "granted") SmallButton("通知を許可する", perm.request)
            }
        }
        item { PushStatusCard() }
        item {
            Panel(title = "乗車予定を登録", subtitle = "予定時刻はご自身の目標時刻です。時刻表の定刻・運賃は取得できないため表示しません。") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DateField(date, { date = it }, label = "日付")
                    TimeField(departure, { departure = it }, label = "予定時刻")
                }
                Field("系統番号", route, { route = it }, "例 52/8")
                Field("乗車停留所 *", boarding, { boarding = it }, "例 近鉄奈良駅")
                Field("降車停留所", alighting, { alighting = it }, "例 大和八木駅（南）")
                Field("車両番号（任意）", vehicle, { vehicle = it }, "例 奈良200か48")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field("発車何分前に通知", remind, { remind = it.filter(Char::isDigit).take(3) }, number = true, modifier = Modifier.weight(1f))
                    Field("停留所までの徒歩（分）", walk, { walk = it.filter(Char::isDigit).take(3) }, number = true, modifier = Modifier.weight(1f))
                }
                Field("メモ", note, { note = it }, "任意")
                if (formError.isNotBlank()) Text(formError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                SmallButton("登録する", {
                    if (boarding.isBlank()) { formError = "乗車停留所を入力してください"; return@SmallButton }
                    formError = ""
                    act("save") {
                        try {
                            Api.rpc("rides/server-fns#saveRidePlan", jsonOf(
                                "date" to date.ifBlank { todayKey() }, "route" to route.trim(), "boarding_stop" to boarding.trim(), "alighting_stop" to alighting.trim(),
                                "departure_time" to departure, "vehicle" to vehicle.trim(), "walk_minutes" to walk.toIntOrNull(),
                                "remind_minutes" to (remind.toIntOrNull()?.coerceIn(0, 120) ?: 10), "note" to note.trim(),
                            ))
                            date = todayKey(); route = ""; departure = nowHm(); boarding = ""; alighting = ""; vehicle = ""; remind = "10"; walk = ""; note = ""
                            refreshPlans(); runCheck()
                        } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; formError = e.userMessage().ifBlank { "保存できませんでした" } }
                    }
                }, enabled = busy != "save", primary = true, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { Text("登録中の予定", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        if (plans.isEmpty()) item { Text("乗車予定はまだありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        plans.forEach { plan ->
            item {
                val id = plan.str("id").orEmpty()
                val l = live[id]
                val info = l.objAt("live")
                Panel {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text((plan.str("route")?.takeIf { it.isNotBlank() }?.let { "$it　" } ?: "") + plan.str("boarding_stop").orEmpty() + (plan.str("alighting_stop")?.takeIf { it.isNotBlank() }?.let { " → $it" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("${plan.str("date")}　${plan.str("departure_time")?.ifBlank { null } ?: "時刻未入力"} 発（目標）" + (plan.str("vehicle")?.takeIf { it.isNotBlank() }?.let { "　車両 $it" } ?: ""), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Tag(l.str("liveLabel") ?: "未判定", liveColor(info.str("state")))
                    }
                    if (info != null) {
                        KeyValue("現在地", info.str("currentStop")?.ifBlank { null } ?: "—")
                        KeyValue("乗車停留所まで", info.int("stopsToGo")?.let { "あと${maxOf(0, it)}停留所" } ?: "—")
                        KeyValue("使用車両", info.str("vehicle")?.ifBlank { null } ?: "—")
                        KeyValue("最終取得", info.long("lastCheck")?.let { BusFormat.time(it) } ?: "—")
                        if (info.bool("fresh") == false && info.long("lastCheck") != null) Text("位置情報の更新が止まっています（最終 ${BusFormat.dateTime(info.long("lastCheck"))}）。運休とは限りません。", fontSize = 11.sp, color = Brand.Warning)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        SmallButton("乗車を記録", {
                            act(id) {
                                Api.rpc("rides/server-fns#saveRideLog", jsonOf(
                                    "date" to plan.str("date"), "vehicle" to (info.str("vehicle")?.ifBlank { null } ?: plan.str("vehicle").orEmpty()),
                                    "route" to (plan.str("route")?.ifBlank { null } ?: info.str("route").orEmpty()), "boarding_stop" to plan.str("boarding_stop"),
                                    "alighting_stop" to plan.str("alighting_stop").orEmpty(), "boarding_at" to System.currentTimeMillis(), "plan_id" to id,
                                    "source" to if (!info.str("vehicle").isNullOrBlank()) "live" else "plan",
                                ))
                                refreshPlans(); runCheck()
                            }
                        }, enabled = busy != id, icon = Icons.Outlined.DoneAll)
                        SmallButton("削除", { act(id) { Api.rpc("rides/server-fns#deleteRidePlan", jsonOf("id" to id)); refreshPlans() } }, enabled = busy != id, icon = Icons.Outlined.Delete)
                        plan.long("last_alert_at")?.let { Text("通知 ${BusFormat.dateTime(it)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        item {
            Panel(title = "通知") {
                if (alerts.isEmpty()) Text("通知はまだありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                alerts.forEachIndexed { i, a ->
                    if (i > 0) RowDivider()
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val t = a.str("type")
                            Tag(ALERT_LABEL[t] ?: t.orEmpty(), ALERT_COLOR[t] ?: Brand.MutedFg)
                            Text("  ${BusFormat.dateTime(a.long("at"))}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(a.str("title").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        a.str("body")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

private val SOURCE_LABEL = mapOf("manual" to "手入力", "plan" to "乗車予定から", "live" to "車両情報から")

/** 乗車履歴（Web: rides.history.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RideHistoryScreen() {
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    suspend fun refresh() { logs = Api.rpc("rides/server-fns#listRideLogs", jsonOf()).items() }
    LaunchedEffect(Unit) { try { refresh() } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage().ifBlank { "履歴を読み込めませんでした" } } }

    var date by remember { mutableStateOf(todayKey()) }
    var time by remember { mutableStateOf(nowHm()) }
    var vehicle by remember { mutableStateOf("") }
    var route by remember { mutableStateOf("") }
    var boarding by remember { mutableStateOf("") }
    var alighting by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf("") }

    ScreenList {
        item {
            Column {
                Text("乗車履歴", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("乗車したバスを記録します。乗車予定から記録すると、運行中の車両番号・系統が自動で入ります。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (error.isNotBlank()) item { Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        item {
            Panel(title = "乗車を記録", subtitle = "車両番号・系統は、乗車予定から記録すると運行中の実データで自動入力されます。") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DateField(date, { date = it }, label = "乗車日")
                    TimeField(time, { time = it }, label = "乗車時刻")
                }
                Field("車両番号", vehicle, { vehicle = it }, "任意")
                Field("系統番号", route, { route = it }, "任意")
                Field("乗車停留所", boarding, { boarding = it }, "任意")
                Field("降車停留所", alighting, { alighting = it }, "任意")
                Field("メモ", memo, { memo = it }, "任意")
                if (formError.isNotBlank()) Text(formError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                SmallButton("記録する", {
                    busy = "save"; formError = ""
                    scope.launch {
                        try {
                            Api.rpc("rides/server-fns#saveRideLog", jsonOf(
                                "date" to date.ifBlank { todayKey() }, "vehicle" to vehicle.trim(), "route" to route.trim(), "boarding_stop" to boarding.trim(),
                                "alighting_stop" to alighting.trim(), "boarding_at" to toEpoch(date.ifBlank { todayKey() }, time), "memo" to memo.trim(), "source" to "manual",
                            ))
                            date = todayKey(); time = nowHm(); vehicle = ""; route = ""; boarding = ""; alighting = ""; memo = ""
                            refresh()
                        } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; formError = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = "" }
                    }
                }, enabled = busy != "save", primary = true, modifier = Modifier.padding(top = 6.dp))
            }
        }
        item {
            Panel(title = "記録" + if (logs.isNotEmpty()) "（${logs.size}件）" else "") {
                if (logs.isEmpty()) Text("まだ乗車記録がありません。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                logs.forEachIndexed { i, log ->
                    if (i > 0) RowDivider()
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(log.long("boarding_at")?.let { BusFormat.dateTime(it) } ?: log.str("date").orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                                Tag(log.str("route")?.ifBlank { null } ?: "系統不明", INFO)
                                log.str("vehicle")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                Text((log.str("boarding_stop")?.ifBlank { null } ?: "乗車停留所未入力") + (log.str("alighting_stop")?.takeIf { it.isNotBlank() }?.let { " → $it" } ?: ""), fontSize = 12.sp)
                            }
                            log.str("memo")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text(SOURCE_LABEL[log.str("source")] ?: "手入力", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val id = log.str("id").orEmpty()
                        IconButton(onClick = {
                            busy = id
                            scope.launch {
                                try { Api.rpc("rides/server-fns#deleteRideLog", jsonOf("id" to id)); refresh() }
                                catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage().ifBlank { "削除できませんでした" } }
                                finally { busy = "" }
                            }
                        }, enabled = busy.isEmpty()) { Icon(Icons.Outlined.Delete, "削除") }
                    }
                }
            }
        }
    }
}
