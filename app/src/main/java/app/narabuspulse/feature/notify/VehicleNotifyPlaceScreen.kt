package app.narabuspulse.feature.notify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.rememberLocationRequest
import app.narabuspulse.core.str
import app.narabuspulse.ui.kit.MapCircle
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.OSM_ATTRIBUTION
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.rememberConfirmer
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private data class Point(val lat: Double, val lon: Double, val label: String)

private val RADII = listOf("100", "300", "500", "1000", "2000")

/** 地点を指定した車両通知（Web: vehicle-notify.place.jsx + NotifyPlaceForm.jsx + NotifyPlaceMap.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleNotifyPlaceScreen() {
    val scope = rememberCoroutineScope()
    val confirm = rememberConfirmer()
    val query = rememberLoad("vn-overview-place") { Api.rpc("notify/vehicle-fns#getVehicleNotifyOverview", jsonOf()) }
    var editing by remember { mutableStateOf<JsonElement?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val placeWatches = query.data.items("watches").filter { it.dbl("lat") != null && it.dbl("lon") != null }
    fun act(block: suspend () -> Unit) {
        busy = true; error = ""
        scope.launch { try { block() } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.userMessage() } finally { busy = false } }
    }

    // NotifyPlaceForm の状態
    var name by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var point by remember { mutableStateOf<Point?>(null) }
    var stopName by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf("300") }
    var notifyArea by remember { mutableStateOf(true) }
    var notifyExit by remember { mutableStateOf(true) }
    var formBusy by remember { mutableStateOf("") }
    var formMessage by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf("") }
    val locate = rememberLocationRequest { loc ->
        formBusy = ""
        if (loc == null) formError = "位置情報を取得できませんでした。地図をタップしてください"
        else { point = Point(loc.lat, loc.lon, "現在地周辺"); if (name.isBlank()) name = "現在地周辺の監視" }
    }

    ScreenList {
        item { PageHeading("地点を指定した車両通知", "地図・停留所名・交差点名・住所から地点を決め、指定した範囲に車両が進入・退出したときに通知します。範囲内にいる間は繰り返し通知しません。") }
        item { VehicleNotifyNav("/vehicle-notify/place") }
        if (message.isNotBlank()) item { Text(message, fontSize = 12.sp, color = Brand.Success) }
        if (error.isNotBlank()) item { Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        editing?.let { w ->
            item {
                WatchForm(w, onSubmit = { payload ->
                    act { Api.rpc("predict/fns#saveAppearanceWatch", payload); message = "監視の条件を更新しました"; editing = null; query.reload() }
                }, onCancel = { editing = null }, busy = busy)
            }
        }
        item {
            Panel(title = "地点を指定して通知する") {
                OutlinedTextField(name, { name = it }, label = { Text("監視の名前", fontSize = 12.sp) }, placeholder = { Text("例 近鉄奈良駅前の接近通知", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedTextField(q, { q = it }, label = { Text("停留所名・交差点名・住所で検索", fontSize = 12.sp) }, placeholder = { Text("例 近鉄奈良駅 / 大安寺交差点", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                    SmallButton("検索", {
                        if (q.isBlank()) return@SmallButton
                        formBusy = "search"; formError = ""; formMessage = ""
                        scope.launch {
                            try {
                                val res = Api.rpc("notify/vehicle-fns#resolveNotifyPlace", jsonOf("q" to q.trim()))
                                if (res.bool("ok") != true) { formError = res.str("error") ?: "地点を特定できませんでした"; return@launch }
                                val label = res.str("label").orEmpty()
                                point = Point(res.dbl("lat")!!, res.dbl("lon")!!, label)
                                stopName = if (res.bool("isStop") == true) label else ""
                                if (name.isBlank()) name = "${label}の監視"
                                formMessage = "${res.str("source")}から「$label」を特定しました"
                            } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; formError = e.userMessage().ifBlank { "地点を検索できませんでした" } } finally { formBusy = "" }
                        }
                    }, enabled = formBusy != "search", icon = Icons.Outlined.Search, modifier = Modifier.padding(start = 6.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    SmallButton("現在地を中心にする", { formBusy = "geo"; locate() }, enabled = formBusy != "geo", icon = Icons.Outlined.GpsFixed)
                    SelectField(RADII.map { it to if (it.toInt() >= 1000) "${it.toInt() / 1000}km" else "${it}m" }, radius, { radius = it }, label = "通知する範囲")
                    Text(point?.let { "%.5f, %.5f".format(it.lat, it.lon) } ?: "未指定（地図をタップ）", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val p = point
                OsmMap(
                    Modifier.fillMaxWidth().height(300.dp),
                    pins = if (p != null) listOf(MapPin("pt", p.lat, p.lon, p.label, "", Color.White)) else emptyList(),
                    circles = if (p != null) listOf(MapCircle(p.lat, p.lon, radius.toDouble())) else emptyList(),
                    center = p?.let { it.lat to it.lon } ?: (34.6837 to 135.827),
                    zoom = 14.0,
                    onMapClick = { lat, lon -> point = Point(lat, lon, point?.label ?: q.trim().ifBlank { "地図で指定した地点" }) },
                )
                Text(OSM_ATTRIBUTION, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("通知する内容", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                FlowRow {
                    CheckRow("範囲に進入したとき", notifyArea) { notifyArea = it }
                    CheckRow("範囲から退出したとき", notifyExit) { notifyExit = it }
                }
                if (stopName.isNotBlank()) Text("停留所「$stopName」の接近・通過も判定に使えます（車両ごとの監視で設定できます）。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (formMessage.isNotBlank()) Text(formMessage, fontSize = 12.sp, color = Brand.Success)
                if (formError.isNotBlank()) Text(formError, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                SmallButton("この地点を監視する", {
                    val pt = point
                    if (pt == null) { formError = "地図をタップするか、地点を検索して位置を決めてください"; return@SmallButton }
                    val label = pt.label.ifBlank { q.trim().ifBlank { "指定地点" } }
                    formBusy = "save"; formError = ""; formMessage = ""
                    scope.launch {
                        try {
                            Api.rpc("predict/fns#saveAppearanceWatch", jsonOf(
                                "name" to name.trim().ifBlank { "${label}の監視" }, "area_name" to label, "stop_name" to stopName,
                                "lat" to pt.lat, "lon" to pt.lon, "radius_m" to (radius.toIntOrNull() ?: 300),
                                "notify_area" to notifyArea, "notify_exit" to notifyExit, "notify_start" to false, "notify_end" to false,
                                "notify_missing" to false, "notify_route" to false, "enabled" to true,
                            ))
                            formMessage = "地点の監視を保存しました"; query.reload()
                        } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; formError = e.userMessage().ifBlank { "保存できませんでした" } } finally { formBusy = "" }
                    }
                }, enabled = formBusy != "save", icon = Icons.Outlined.Save, primary = true, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (query.loading && query.data == null) item { Text("地点の監視を確認しています…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else item {
            NotifyWatchList(
                placeWatches,
                onEdit = { editing = it },
                onToggle = { w -> act { Api.rpc("predict/fns#saveAppearanceWatch", pickWatchFields(w, mapOf("enabled" to (w.str("enabled") == "false")))); query.reload() } },
                onDelete = { w -> confirm.ask("「${w.str("name")}」の監視を解除します。よろしいですか？") { act { Api.rpc("predict/fns#deleteAppearanceWatch", jsonOf("id" to w.str("id"))); query.reload() } } },
                busy = busy, onAdd = null,
            )
        }
        item {
            Text("位置情報は取得できた実測値だけを使います。位置が古い車両は進入・退出の判定に使いません。範囲内にいる間は同じ車両を繰り返し通知せず、退出後に再進入した場合は再び通知します。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
