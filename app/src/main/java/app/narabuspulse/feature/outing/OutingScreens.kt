package app.narabuspulse.feature.outing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import app.narabuspulse.core.objAt
import app.narabuspulse.core.offline.OfflineStore
import app.narabuspulse.core.str
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private fun phoneOf(devices: List<JsonElement>) = devices.firstOrNull { it.str("kind") == "phone" } ?: devices.firstOrNull { it.dbl("level") != null }
private fun chargingCount(pack: JsonElement?) = parseJson(pack.str("sections")).list("facilities.charging").size

/** Web と同じ predictBattery（outing/battery）で帰宅時点の残量を推定する。 */
private suspend fun predict(trip: JsonElement?, devices: List<JsonElement>, pack: JsonElement?, t: OutingPrefs.Thresholds, totalMin: Int): JsonElement? {
    val phone = phoneOf(devices) ?: return null
    return runCatching {
        Logic.call("outing/battery", "predictBattery", jsonOf("level" to phone.dbl("level"), "totalMin" to totalMin, "shootingMin" to (if (trip.bool("shooting") == true) trip.int("shooting_min") ?: 0 else 0),
            "charging" to (phone.bool("charging") == true), "devices" to JsonArray(devices), "chargingSpots" to (if (pack != null) chargingCount(pack) else null),
            "thresholds" to jsonOf("high" to t.high, "low" to t.low, "critical" to t.critical)))
    }.getOrNull()
}
private suspend fun returnAdvice(trip: JsonElement?, remaining: Int?, prediction: JsonElement?): List<JsonElement> =
    runCatching { Logic.call("outing/battery", "returnAdvice", jsonOf("trip" to trip, "remainingMin" to remaining, "battery" to prediction, "nowClock" to OutingTime.now().clock)).items() }.getOrDefault(emptyList())

/** 外出アシスタント（Web: outing.index.jsx） */
@Composable
fun OutingDashboardScreen() {
    val context = LocalContext.current
    val nav = LocalNav.current
    val online by OfflineStore.online.collectAsState()
    val thresholds = remember { OutingPrefs.thresholds(context) }
    val load = rememberLoad { Api.rpc("outing/server-fns#getOutingOverview", jsonOf()).also { OutingPrefs.writeCache(context, "overview", it) } }
    val cache = if (load.error != null || (load.data == null && !load.loading)) OutingPrefs.readCache(context, "overview") else null
    val view = load.data ?: cache?.second
    val today = view.str("today") ?: OutingTime.now().date
    val trips = view.items("trips")
    val todayTrip = trips.firstOrNull { it.str("date") == today }
    val upcoming = trips.filter { (it.str("date") ?: "") > today }.take(4)
    val packing = view.items("packings").firstOrNull { it.str("trip_id") == todayTrip.str("id") && todayTrip != null }
    val pack = view.items("packs").firstOrNull { it.str("trip_id") == todayTrip.str("id") && todayTrip != null }
    val devices = view.items("devices")
    val changes = view.items("changes").filter { it.str("trip_id") == todayTrip.str("id") && todayTrip != null }
    val phase = if (todayTrip != null) OutingTime.phase(todayTrip) else "before"
    val remaining = todayTrip?.let { OutingTime.remaining(it) }
    val items = parseItems(packing.str("items"))
    val phone = phoneOf(devices)
    val computed = produceState<Pair<JsonElement?, List<JsonElement>>?>(null, view, thresholds) {
        val total = if (remaining != null && phase != "after") remaining else OutingTime.durationMin(todayTrip) ?: 0
        val p = predict(todayTrip, devices, pack, thresholds, total)
        value = p to returnAdvice(todayTrip, remaining, p)
    }.value
    val prediction = computed?.first
    val alerts = buildList {
        if (phase != "after") items.filter { it.bool("checkedOut") != true }.take(3).forEach { add(OutAlert("out-${it.str("key")}", if (it.str("priority") == "essential") "danger" else "warning", "未確認の持ち物：${it.str("name")}")) }
        else items.filter { it.bool("checkedOut") == true && it.bool("checkedBack") != true }.take(4).forEach { add(OutAlert("back-${it.str("key")}", "danger", "持ち帰り未確認：${it.str("name")}")) }
        changes.forEach { c -> add(OutAlert("change-${c.str("id")}", "info", "未承認の変更案：${c.str("summary")}", change = true, action = todayTrip?.let { t -> { TextButton(onClick = { nav.go("/outing/plans/${t.str("id")}") }) { Text("内容を確認する ›", fontSize = 11.sp) } } })) }
        prediction.items("warnings").forEach { add(OutAlert("bat-${it.str("key")}", it.str("level").orEmpty(), it.str("text").orEmpty())) }
        computed?.second.orEmpty().forEachIndexed { i, r -> add(OutAlert("ret-$i", r.str("level").orEmpty(), r.str("text").orEmpty())) }
    }.take(7)
    val gaps = buildList {
        if (todayTrip == null) add("今日の予定が未登録です")
        if (todayTrip != null && (todayTrip.dbl("lat") == null || todayTrip.dbl("lon") == null)) add("目的地の座標が未設定（現地パック作成に必要）")
        if (todayTrip != null && todayTrip.str("route").isNullOrBlank()) add("経路が未取得です")
        if (todayTrip != null && pack == null) add("現地情報パックが未作成です")
        if (todayTrip != null && packing == null) add("持ち物リストが未作成です")
        if (phone == null) add("スマートフォンの電池残量が未登録です")
    }
    val nextLeg = parseJson(todayTrip.str("route")).list("legs").firstOrNull()
    val adjustment = (parseJson(todayTrip.str("adjustments")) as? JsonArray)?.lastOrNull()
    ScreenList {
        item {
            OutingHeader("/outing", "外出アシスタント", "予定・経路・現地情報・持ち物・電池を1か所で確認します。取得できていない情報は「未取得」と表示します。") {
                SmallButton("予定をつくる", { nav.go("/outing/plans") }, icon = Icons.Outlined.Add); SmallButton("経路を検索 →", { nav.go("/outing/navi") })
            }
        }
        if (load.error != null) item { OfflineNotice(cache?.first) }
        if (load.loading && view == null) item { Muted("読み込み中…", 11) }
        item { TodayStatus(todayTrip, phase, phone.int("level"), prediction.int("endLevel"), remaining, online) }
        item { TodayTripCard(todayTrip, packing, pack, devices) }
        if (todayTrip != null) item {
            Panel(title = "次の移動予定・経路") {
                Text(nextLeg?.let { "${it.str("depart") ?: "—"} ${it.str("from") ?: "—"} → ${it.str("arrive") ?: "—"} ${it.str("to") ?: "—"}" } ?: "保存された経路がありません（経路タブで検索できます）", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Muted(remaining?.let { "帰宅目標 ${todayTrip.str("return_by") ?: "—"} まで ${OutingTime.durationLabel(it)}" } ?: "帰宅目標が未設定です", 10)
                adjustment?.let { a -> Boxed(tone = app.narabuspulse.feature.analytics.INFO) { Text("採用済みの変更：${a.str("summary").orEmpty()}", fontSize = 11.sp, color = app.narabuspulse.feature.analytics.INFO) } }
                TextButton(onClick = { nav.go("/outing/plans/${todayTrip.str("id")}") }) { Text("経路・持ち物・電池の詳細 ›", fontSize = 11.sp) }
            }
        }
        if (todayTrip != null) item { TripMemos(todayTrip) }
        item { Panel { AlertStack(alerts, title = "AIの提案・警告", emptyText = "いま確認が必要な項目はありません。") } }
        item {
            Panel(title = "不足している情報") {
                if (gaps.isEmpty()) Muted("必要な情報はそろっています。", 11) else gaps.forEach { Muted("・$it", 11) }
                todayTrip?.let { t -> TextButton(onClick = { nav.go("/outing/plans/${t.str("id")}") }) { Text("予定の詳細で整える ›", fontSize = 11.sp) } }
            }
        }
        item {
            Panel(title = "帰宅経路") {
                Muted("目的地から帰宅先までの経路を検索できます。最終便・残り時間・電池残量は「帰宅判断」で確認してください。", 11)
                TextButton(onClick = { nav.go("/outing/navi") }) { Text("帰宅経路を検索 ›", fontSize = 11.sp) }
            }
        }
        if (upcoming.isNotEmpty()) item { Panel(title = "💼 直近の予定") { TripList(upcoming) } }
    }
}

/** 外出予定（Web: outing.plans.index.jsx） */
@Composable
fun OutingPlansScreen() {
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val load = rememberLoad { Api.rpc("outing/server-fns#listTrips", jsonOf()) }
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    ScreenList {
        item {
            OutingHeader("/outing/plans", "外出予定", "目的地と日時を登録すると、経路・現地情報パック・持ち物・電池の見通しをまとめて用意できます。") {
                SmallButton(if (open) "入力を閉じる" else "予定をつくる", { open = !open }, icon = Icons.Outlined.Add, primary = true)
            }
        }
        if (open) item {
            TripForm(null, { payload ->
                busy = true; message = ""
                scope.launch {
                    try { Api.rpc("outing/server-fns#saveTrip", payload); open = false; load.reload(); message = "予定を保存しました。詳細から経路・現地情報・持ち物・電池をそろえられます。" }
                    catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = false }
                }
            }, { open = false }, busy)
        }
        if (message.isNotBlank()) item { Muted(message, 11) }
        load.error?.let { item { Text(it, fontSize = 11.sp, color = Brand.Destructive) } }
        item {
            if (load.loading && load.data == null) Muted("読み込み中…", 11)
            else TripList(load.data.items(), onDelete = { t ->
                confirm.ask("「${t.str("title")}」を削除します。紐づく持ち物リスト・現地情報パック・変更履歴も削除されます。よろしいですか？") {
                    busy = true
                    scope.launch {
                        try { Api.rpc("outing/server-fns#deleteTrip", jsonOf("tripId" to t.str("id"))); load.reload() }
                        catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "削除できませんでした" } } finally { busy = false }
                    }
                }
            }, busy = busy)
        }
    }
}

/** 外出予定の詳細（Web: outing.plans.$tripId.jsx） */
@Composable
fun TripDetailScreen(tripId: String) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val load = rememberLoad(tripId) { Api.rpc("outing/server-fns#getTripHub", jsonOf("tripId" to tripId)).also { if (it.bool("ok") == true) OutingPrefs.writeCache(context, "trip:$tripId", it) } }
    val failed = load.error != null || load.data?.bool("ok") == false
    val cache = if (failed) OutingPrefs.readCache(context, "trip:$tripId") else null
    val hub = if (load.data?.bool("ok") == true) load.data else cache?.second
    val trip = hub.objAt("trip")
    val packing = hub.objAt("packing"); val pack = hub.objAt("pack")
    val devices = hub.items("devices"); val gear = hub.items("gear"); val changes = hub.items("changes")
    var busy by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var thresholds by remember { mutableStateOf(OutingPrefs.thresholds(context)) }
    var items by remember { mutableStateOf(listOf<JsonObject>()) }
    var sets by remember { mutableStateOf(OutingPrefs.gearSets(context)) }
    var setDialog by remember { mutableStateOf(false) }
    var setName by remember { mutableStateOf("") }
    var timer by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(packing.str("id"), packing.str("items")) { items = parseItems(packing.str("items")) }

    suspend fun commit(next: List<JsonObject>, vararg extra: Pair<String, Any?>) {
        val t = trip ?: return
        Api.rpc("outing/server-fns#savePacking", jsonOf("tripId" to t.str("id"), "title" to "${t.str("title")} の持ち物", "date" to t.str("date"), "items" to JsonArray(next), *extra))
        load.reload()
    }
    fun changeItems(next: List<JsonObject>) {
        items = next
        timer?.cancel()
        timer = scope.launch { delay(1000); runCatching { commit(next) } }
    }
    fun <T> run(key: String, block: suspend () -> T, after: (T) -> Unit = {}) {
        busy = key; message = ""
        scope.launch {
            try { val r = block(); load.reload(); after(r) }
            catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "処理できませんでした" } } finally { busy = "" }
        }
    }

    if (trip == null) {
        ScreenList {
            item {
                Panel(title = "予定が見つかりません", subtitle = "削除されたか、通信できず端末に保存もされていません。") {
                    if (load.loading) Muted("読み込み中…", 11)
                    load.error?.let { Text(it, fontSize = 11.sp, color = Brand.Destructive) }
                    TextButton(onClick = { nav.go("/outing/plans") }) { Text("予定一覧へ戻る", fontSize = 12.sp) }
                }
            }
        }
        return
    }
    val phase = OutingTime.phase(trip); val remaining = OutingTime.remaining(trip)
    val weather = parseJson(pack.str("sections")).objAt("weather")
    val phone = phoneOf(devices)
    val ctx = produceState<JsonElement?>(null, trip, weather) { value = runCatching { Logic.call("outing/gear", "contextFromTrip", trip, weather) }.getOrNull() }.value
    val computed = produceState<Pair<JsonElement?, List<JsonElement>>?>(null, hub, thresholds) {
        val total = if (remaining != null && phase != "after") remaining else OutingTime.durationMin(trip) ?: 0
        val p = predict(trip, devices, pack, thresholds, total)
        value = p to returnAdvice(trip, remaining, p)
    }.value
    val prediction = computed?.first
    val alerts = buildList {
        if (phase != "after") items.filter { it.bool("checkedOut") != true }.take(3).forEach { add(OutAlert("o-${it.str("key")}", if (it.str("priority") == "essential") "danger" else "warning", "未確認：${it.str("name")}")) }
        else items.filter { it.bool("checkedOut") == true && it.bool("checkedBack") != true }.take(4).forEach { add(OutAlert("b-${it.str("key")}", "danger", "持ち帰り未確認：${it.str("name")}")) }
        prediction.items("warnings").forEach { add(OutAlert("bat-${it.str("key")}", it.str("level").orEmpty(), it.str("text").orEmpty())) }
        computed?.second.orEmpty().forEachIndexed { i, r -> add(OutAlert("ret-$i", r.str("level").orEmpty(), r.str("text").orEmpty())) }
        if (trip.str("route").isNullOrBlank()) add(OutAlert("route", "info", "経路が未取得です。下の経路検索から保存できます。"))
        if (pack == null) add(OutAlert("pack", "info", "現地情報パックが未作成です。"))
    }
    fun logDevice(deviceId: String, level: Int, charging: Boolean) = run("battery", { Api.rpc("outing/server-fns#logBattery", jsonOf("deviceId" to deviceId, "level" to level, "charging" to charging, "tripId" to trip.str("id"))) })

    if (setDialog) AlertDialog(onDismissRequest = { setDialog = false }, title = { Text("セット名") },
        text = { OutlinedTextField(setName, { setName = it }, placeholder = { Text("例：バス撮影の基本") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            val ids = items.mapNotNull { it.str("gearId")?.takeIf { g -> g.isNotBlank() } }
            if (ids.isEmpty()) message = "登録済みの持ち物が無いため、セットとして保存できません。"
            else if (setName.isNotBlank()) { OutingPrefs.saveGearSet(context, setName.trim(), ids); sets = OutingPrefs.gearSets(context); message = "セット「${setName.trim()}」を保存しました。" }
            setDialog = false; setName = ""
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { setDialog = false }) { Text("やめる") } })

    ScreenList {
        item {
            OutingHeader("/outing/plans", trip.str("title").orEmpty(), "${trip.str("date")}・${trip.str("dest_name")}・${trip.str("depart_at") ?: "—"}〜${trip.str("return_by") ?: "—"}") {
                SmallButton("一覧", { nav.go("/outing/plans") }, icon = Icons.Outlined.ArrowBack)
                if (phase != "after") SmallButton("出発確認", { run("packing", { commit(items, "status" to "out", "departedAt" to System.currentTimeMillis()) }) }, enabled = busy.isEmpty(), icon = Icons.Outlined.PlayArrow, primary = true)
                else SmallButton("帰宅確認", {
                    val missing = items.filter { it.bool("checkedOut") == true && it.bool("checkedBack") != true }
                    val gearIds = missing.mapNotNull { it.str("gearId")?.takeIf { g -> g.isNotBlank() } }
                    run("packing", {
                        commit(items, "status" to "returned", "returnedAt" to System.currentTimeMillis(), "forgotten" to missing.map { it.str("name").orEmpty() })
                        if (gearIds.isNotEmpty()) runCatching { Api.rpc("outing/server-fns#markForgotten", jsonOf("gearIds" to gearIds)) }
                    }) { if (missing.isNotEmpty()) message = "持ち帰り未確認が${missing.size}件あります。次回の提案で優先表示します。" }
                }, enabled = busy.isEmpty(), icon = Icons.Outlined.CheckCircle, primary = true)
            }
        }
        if (message.isNotBlank()) item { Muted(message, 11) }
        if (failed) item { OfflineNotice(cache?.first) }
        item {
            Panel(title = "📍 持ち物チェックリスト", action = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    SmallButton("セット保存", { setDialog = true }, icon = Icons.Outlined.Save)
                    SmallButton("保存", { run("packing", { commit(items) }) }, enabled = busy.isEmpty(), modifier = Modifier.padding(top = 4.dp))
                }
            }) {
                if (sets.isNotEmpty()) SelectField(listOf("" to "セットから追加") + sets.map { it.name to "${it.name}（${it.gearIds.size}点）" }, "", { name ->
                    val set = sets.firstOrNull { it.name == name } ?: return@SelectField
                    val rows = set.gearIds.mapNotNull { id -> gear.firstOrNull { it.str("id") == id } }.filter { g -> items.none { it.str("gearId") == g.str("id") } }
                        .map { g -> packingItem(jsonOf("name" to g.str("name"), "category" to g.str("category"), "priority" to (if (g.bool("essential") == true) "essential" else "normal"), "gearId" to g.str("id"), "registered" to true, "reason" to "保存したセットから追加")) }
                    if (rows.isNotEmpty()) changeItems(items + rows)
                })
                PackingChecklist(items, { changeItems(it) }, { key -> changeItems(items.filter { it.str("key") != key }) }, phase, busy.isNotEmpty())
            }
        }
        item { SuggestPanel(ctx, gear, items, { changeItems(it) }, busy.isNotEmpty()) }
        item {
            FieldPackPanel(trip, pack, { force -> run("pack", { Api.rpc("outing/server-fns#buildFieldPack", jsonOf("tripId" to trip.str("id"), "force" to force)) }) }, { body ->
                scope.launch {
                    OfflineStore.savePart(jsonOf("key" to "pack:${trip.str("id")}", "label" to (body.str("title") ?: "現地情報パック"), "group" to "fieldpack", "rows" to JsonArray(listOf(body)), "count" to 1,
                        "bytes" to body.toString().length, "savedAt" to System.currentTimeMillis(), "note" to "現地情報パック（端末保存）"))
                    body.str("id")?.let { id -> runCatching { Api.rpc("outing/server-fns#markPackSaved", jsonOf("packId" to id)) } }
                    message = "現地情報パックを端末に保存しました。通信できないときはこの内容を表示します。"
                }
            }, busy == "pack")
        }
        item {
            ChangeAssistant(trip, changes, { trigger, detail ->
                run("change", { Api.rpc("outing/server-fns#proposePlanChange", jsonOf("tripId" to trip.str("id"), "trigger" to trigger, "detail" to detail)) }) { r -> if (r.bool("ok") == false) message = r.str("error") ?: "変更案を作成できませんでした" }
            }, { changeId, decision, routeOptionId ->
                run("change", { Api.rpc("outing/server-fns#decidePlanChange", jsonOf("changeId" to changeId, "decision" to decision, "routeOptionId" to routeOptionId)) })
            }, busy == "change")
        }
        item { TripMemos(trip) }
        item { Panel { AlertStack(alerts, title = "確認が必要な項目", emptyText = "いま確認が必要な項目はありません。") } }
        item {
            BatteryPanel(devices, trip, prediction, thresholds, busy == "battery", ::logDevice, { level, charging ->
                if (phone == null) message = "スマートフォンの機器が未登録です。バッテリータブで登録してください。" else logDevice(phone.str("id").orEmpty(), level, charging)
            }, { next -> thresholds = OutingPrefs.saveThresholds(context, next); message = "警告のしきい値を保存しました。" })
        }
        item {
            Panel(title = "🏠 帰宅判断") {
                Muted("帰宅目標 ${trip.str("return_by") ?: "未設定"}・残り ${remaining?.let { OutingTime.durationLabel(it) } ?: "不明"}", 11)
                Muted("最終便・現在地からの距離は、取得できた経路と位置情報がある場合のみ判断できます（この画面では位置情報を保存していません）。", 10)
                TextButton(onClick = { nav.go("/outing/navi") }) { Text("帰宅経路を検索", fontSize = 11.sp) }
            }
        }
        item { ChargingSpots(trip) }
        item {
            Text("経路の検索・比較（保存するとこの予定の経路になります）", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.MutedFg)
            RouteSearchPanel(trip.str("from_id")?.takeIf { it.isNotBlank() }?.let { jsonOf("label" to (trip.str("origin")?.ifBlank { null } ?: "出発地"), "endpoint" to it) },
                trip.str("to_id")?.takeIf { it.isNotBlank() }?.let { jsonOf("label" to trip.str("dest_name"), "endpoint" to it, "lat" to trip.dbl("lat"), "lon" to trip.dbl("lon")) },
                listOf(trip), { _, route -> run("route", { Api.rpc("outing/server-fns#saveTrip", tripSavePayload(trip, "route" to route.toString(), "route_meta" to jsonOf("source" to "経路検索API", "fetchedAt" to (route.str("fetchedAt")?.toLongOrNull() ?: System.currentTimeMillis())).toString())) }) }, busy == "route")
        }
    }
}

/** AI経路検索（Web: outing.navi.jsx） */
@Composable
fun OutingNaviScreen() {
    val scope = rememberCoroutineScope()
    val load = rememberLoad { Api.rpc("outing/server-fns#listTrips", jsonOf()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val trips = load.data.items()
    ScreenList {
        item { OutingHeader("/outing/navi", "AI経路検索", "取得できた経路だけを表示し、所要時間・運賃・乗換・徒歩で比較します。遅延や運休は取得できていないため表示しません。") }
        if (message.isNotBlank()) item { Muted(message, 11) }
        item {
            RouteSearchPanel(null, null, trips, { tripId, route ->
                val trip = trips.firstOrNull { it.str("id") == tripId } ?: return@RouteSearchPanel
                busy = true; message = ""
                scope.launch {
                    try {
                        Api.rpc("outing/server-fns#saveTrip", tripSavePayload(trip, "route" to route.toString(), "route_meta" to jsonOf("source" to "経路検索API", "fetchedAt" to (route.str("fetchedAt")?.toLongOrNull() ?: System.currentTimeMillis())).toString()))
                        load.reload(); message = "予定に経路を保存しました。"
                    } catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = false }
                }
            }, busy)
        }
        item {
            Panel(title = "この画面で行わないこと") {
                Muted("・遅延・運休の推測、時刻表にない便の補完、運賃の概算は行いません。取得できない区間は「経路がありません」と表示します。", 11)
                Muted("・「現在地」は端末の位置情報の許可を得たときだけ使用し、その場で経路検索に使うだけで保存しません。", 11)
            }
        }
    }
}

/** 持ち物管理（Web: outing.gear.jsx） */
@Composable
fun OutingGearScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val load = rememberLoad { Api.rpc("outing/server-fns#listGear", jsonOf()) }
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var sets by remember { mutableStateOf(OutingPrefs.gearSets(context)) }
    fun save(payload: JsonObject, after: () -> Unit = {}) {
        busy = true; message = ""
        scope.launch {
            try { Api.rpc("outing/server-fns#saveGear", payload); after(); load.reload() }
            catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "保存できませんでした" } } finally { busy = false }
        }
    }
    ScreenList {
        item {
            OutingHeader("/outing/gear", "持ち物管理", "登録した持ち物は予定の持ち物リストで「登録済み」として扱います。登録していない物は候補として表示し、所持を断定しません。") {
                SmallButton(if (open) "入力を閉じる" else "持ち物を追加", { editing = null; open = !open }, icon = Icons.Outlined.Add, primary = true)
            }
        }
        if (open) item { androidx.compose.runtime.key(editing?.str("id")) { GearForm(editing, { save(it) { open = false; editing = null } }, { open = false; editing = null }, busy) } }
        if (message.isNotBlank()) item { Muted(message, 11) }
        load.error?.let { item { Text(it, fontSize = 11.sp, color = Brand.Destructive) } }
        item {
            if (load.loading && load.data == null) Muted("読み込み中…", 11)
            else GearList(load.data.items(), { editing = it; open = true }, { g ->
                confirm.ask("「${g.str("name")}」を削除します。よろしいですか？") {
                    busy = true
                    scope.launch { try { Api.rpc("outing/server-fns#deleteGear", jsonOf("id" to g.str("id"))); load.reload() } catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "削除できませんでした" } } finally { busy = false } }
                }
            }, { g, status ->
                save(jsonOf("id" to g.str("id"), "name" to g.str("name"), "category" to (g.str("category") ?: "other"), "qty" to g.int("qty")?.takeIf { it >= 0 }, "weight_g" to g.int("weight_g")?.takeIf { it > 0 },
                    "storage" to g.str("storage")?.ifBlank { null }, "essential" to g.bool("essential"), "note" to g.str("note")?.ifBlank { null }, "photo_uri" to g.str("photo_uri")?.ifBlank { null }, "status" to status))
            }, busy)
        }
        if (sets.isNotEmpty()) item {
            Panel(title = "保存した持ち物セット（この端末に保存）") {
                sets.forEach { s -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Muted("・${s.name}（${s.gearIds.size}点）", 11); Column(Modifier.weight(1f)) {}; TextButton(onClick = { OutingPrefs.deleteGearSet(context, s.name); sets = OutingPrefs.gearSets(context) }) { Text("削除", fontSize = 11.sp, color = Brand.Destructive) } } }
                Muted("予定の詳細画面で「セットから追加」として使えます。", 10)
            }
        }
    }
}

private val DEVICE_LABEL = DEVICE_KINDS.toMap()

/** 充電・バッテリー管理（Web: outing.battery.jsx） */
@Composable
fun OutingBatteryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val load = rememberLoad { Api.rpc("outing/server-fns#getOutingOverview", jsonOf()) }
    var thresholds by remember { mutableStateOf(OutingPrefs.thresholds(context)) }
    var open by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val data = load.data
    val devices = data.items("devices")
    val today = data.str("today") ?: OutingTime.now().date
    val todayTrip = data.items("trips").firstOrNull { it.str("date") == today }
    val pack = todayTrip?.let { t -> data.items("packs").firstOrNull { it.str("trip_id") == t.str("id") } }
    val phone = phoneOf(devices)
    val prediction = produceState<JsonElement?>(null, data, thresholds) {
        val total = if (todayTrip != null && OutingTime.phase(todayTrip) != "after") OutingTime.remaining(todayTrip) ?: OutingTime.durationMin(todayTrip) ?: 0 else OutingTime.durationMin(todayTrip) ?: 0
        value = predict(todayTrip, devices, pack, thresholds, total)
    }.value
    fun act(block: suspend () -> Unit) {
        busy = true; message = ""
        scope.launch { try { block(); load.reload() } catch (e: Throwable) { if (e is CancellationException) throw e; message = e.userMessage().ifBlank { "処理できませんでした" } } finally { busy = false } }
    }
    fun log(id: String, level: Int, charging: Boolean) = act { Api.rpc("outing/server-fns#logBattery", jsonOf("deviceId" to id, "level" to level, "charging" to charging)) }
    ScreenList {
        item {
            OutingHeader("/outing/battery", "充電・バッテリー管理", "端末から取得できた残量と、手入力した残量の両方を扱います。帰宅までの消費は推定値です（実測ではありません）。") {
                SmallButton(if (open) "入力を閉じる" else "機器を追加", { editing = null; open = !open }, icon = Icons.Outlined.Add, primary = true)
            }
        }
        if (open) item { androidx.compose.runtime.key(editing?.str("id")) { DeviceForm(editing, { p -> act { Api.rpc("outing/server-fns#saveDevice", p); open = false; editing = null } }, { open = false; editing = null }, busy) } }
        if (message.isNotBlank()) item { Muted(message, 11) }
        load.error?.let { item { Text(it, fontSize = 11.sp, color = Brand.Destructive) } }
        if (load.loading && data == null) item { Muted("読み込み中…", 11) }
        item {
            BatteryPanel(devices, todayTrip, prediction, thresholds, busy, ::log, { level, charging ->
                if (phone == null) message = "スマートフォンの機器が未登録です。先に「機器を追加」で登録してください。" else log(phone.str("id").orEmpty(), level, charging)
            }, { next -> thresholds = OutingPrefs.saveThresholds(context, next); message = "警告のしきい値を保存しました。" })
        }
        item { ChargingSpots(todayTrip) }
        item {
            Panel(title = "登録機器の管理") {
                if (devices.isEmpty()) Muted("まだ機器が登録されていません。", 11)
                devices.forEach { d ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("${d.str("name")}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Muted("${DEVICE_LABEL[d.str("kind")] ?: d.str("kind")}・${d.int("level")?.let { "$it%" } ?: "残量未登録"}", 10)
                        TextButton(onClick = { editing = d; open = true }) { Text("編集", fontSize = 11.sp) }
                        TextButton(onClick = { confirm.ask("「${d.str("name")}」を削除します。過去の残量記録は残ります。よろしいですか？") { act { Api.rpc("outing/server-fns#deleteDevice", jsonOf("id" to d.str("id"))) } } }) { Text("削除", fontSize = 11.sp, color = Brand.Destructive) }
                    }
                }
            }
        }
    }
}

@Suppress("unused") private val keepArr = Arrangement.spacedBy(1.dp)
