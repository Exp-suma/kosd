package app.narabuspulse.feature.fleet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.bus.Gps
import app.narabuspulse.feature.bus.Vehicle
import app.narabuspulse.feature.bus.VehicleConsole
import app.narabuspulse.feature.bus.VehiclePosition
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ErrorBox
import app.narabuspulse.ui.kit.KeyValue
import app.narabuspulse.ui.kit.Loading
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.Note
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.kit.rememberRpc
import app.narabuspulse.ui.kit.userMessage
import app.narabuspulse.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val UPDATE_INTERVAL_MS = 30_000L
private const val STALE_MINUTES = 5

/** 車両詳細（公式バスロケ・Web: src/routes/_authed/fleet.$carCode.jsx） */
@Composable
fun FleetVehicleScreen(carCode: String, tabParam: String?) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val query = rememberLoad("fleet-vehicle", carCode) { Api.rpc("fleet/fns#getFleetVehicle", jsonOf("carCode" to carCode)) }
    LaunchedEffect(carCode) { while (true) { delay(UPDATE_INTERVAL_MS); query.reload() } }
    val favorites = rememberRpc("fleet/fns#listFleetFavorites", jsonOf())
    var favBusy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    val data = query.data
    val vehicle = data.objAt("vehicle")
    val lastKnown = data.objAt("lastKnown")
    val favorite = favorites.data.items("items").firstOrNull { it.str("car_code") == carCode }
    val plate = vehicle.str("plateNo") ?: lastKnown.str("plate_no") ?: "車両コード $carCode"
    val naviUrl = vehicle.str("carCode")?.let { "https://navi.narakotsu.co.jp/bus_service/?carCode=$it" }.orEmpty()
    val line = data.objAt("route").list("line")

    fun toggleFavorite() {
        val target = vehicle.str("plateNo") ?: lastKnown.str("plate_no") ?: return
        favBusy = true; notice = ""
        scope.launch {
            try {
                if (favorite != null) {
                    Api.rpc("fleet/fns#deleteFleetFavorite", jsonOf("id" to favorite.str("id"), "stopTracking" to true))
                    notice = "お気に入りを解除しました（出現通知も停止しました）"
                } else {
                    Api.rpc("fleet/fns#saveFleetFavorite", jsonOf("carCode" to (vehicle.str("carCode") ?: carCode), "plateNo" to target, "notifyStart" to true, "notifyApproach" to false))
                    notice = "お気に入りに登録しました。運行開始を検知すると車両出現通知AIが通知します"
                }
                favorites.reload()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                notice = e.userMessage().ifBlank { "お気に入りを更新できませんでした" }
            } finally { favBusy = false }
        }
    }

    val header: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(plate, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("奈良交通バスロケーションシステム（公式）の車両を、車両位置画面と同じ操作（基本情報・現在の運行・走行経路・太陽光線・運用履歴・次の運行予測・履歴再生）で確認できます。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("車番検索", { nav.go("/fleet") }, icon = Icons.AutoMirrored.Outlined.ArrowBack)
                SmallButton(if (favorite != null) "お気に入り解除" else "お気に入り", { toggleFavorite() }, enabled = !favBusy && (vehicle != null || lastKnown != null), icon = if (favorite != null) Icons.Outlined.StarOutline else Icons.Filled.Star)
            }
            FleetNav("/fleet")
            if (notice.isNotBlank()) Text(notice, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (query.error != null) Text("取得できませんでした: ${query.error}${data.long("fetchedAt")?.let { "（最後に取得できた時刻 ${fleetClockPlain(it)}）" } ?: ""}", fontSize = 12.sp, color = Brand.Warning)
        }
    }

    if (vehicle == null) {
        ScreenList {
            item { header() }
            when {
                query.loading && data == null -> item { Loading(text = "公式バスロケーションから車両情報を取得しています…") }
                data == null && query.error != null -> item { ErrorBox(query.error!!, onRetry = { query.reload() }) }
                data != null -> {
                    item {
                        Panel(title = "この車両は現在、公式バスロケで確認できません") {
                            Text("運行していない、または公式バスロケーションに位置が出ていないため、現在の位置・系統・行先は表示できません。" +
                                (lastKnown.long("at")?.let { " 収集処理が保存した最終確認は ${fleetClockPlain(it)}（${BusFormat.elapsed(it)}）です。" } ?: ""), fontSize = 12.sp)
                            if (lastKnown != null && (lastKnown.dbl("lat") != null || !lastKnown.str("stop_name").isNullOrBlank())) {
                                KeyValue("最終確認の停留所", lastKnown.str("stop_name")?.ifBlank { null } ?: "取得できません")
                                KeyValue("最終確認の系統・行先", (lastKnown.str("route")?.ifBlank { null } ?: "不明") + (lastKnown.str("destination")?.let { " / $it" } ?: ""))
                            }
                        }
                    }
                    val lat = lastKnown.dbl("lat"); val lon = lastKnown.dbl("lon")
                    if (lat != null && lon != null) item {
                        Panel {
                            OsmMap(Modifier.fillMaxWidth().height(280.dp), pins = listOf(MapPin("last", lat, lon, "最終確認位置", lastKnown.str("stop_name").orEmpty(), Color(0xFF9AA3AB), 16.dp)), center = lat to lon, zoom = 15.0)
                            Note("灰色の点は収集処理が最後に確認した位置です。現在の位置ではないため、現在地としては扱いません。", modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        return
    }

    val routeName = vehicle.str("route").orEmpty()
    val routeStops = if (routeName.isNotBlank() && line.size > 1) mapOf(routeName to line.map { it.str("name").orEmpty() }) else emptyMap()
    val extraCoords = buildMap {
        line.forEach { s -> val la = s.dbl("lat"); val lo = s.dbl("lon"); val n = s.str("name"); if (la != null && lo != null && n != null && n !in this) put(n, Gps(la, lo, null)) }
    }
    val stops = data.objAt("route").list("stops")
    val currentSeq = data.objAt("route").objAt("current").int("seq") ?: 0
    val timeline = if (stops.isEmpty() || currentSeq == 0) null else stops.map { s ->
        val seq = s.int("seq") ?: 0
        jsonOf("name" to s.str("name"), "state" to when { seq == currentSeq -> "current"; seq < currentSeq -> "passed"; else -> "future" })
    }
    val stale = data.bool("stale") == true
    val vLat = vehicle.dbl("lat"); val vLon = vehicle.dbl("lon")
    val positions = mapOf(plate to if (vLat == null || vLon == null) VehiclePosition("none") else VehiclePosition(if (stale) "stale" else "ok", vLat, vLon, vehicle.long("at")))
    val head = "${vehicle.str("route").orEmpty()} ${vehicle.str("startingStop") ?: "始発不明"}".trim()
    val consoleVehicle = Vehicle.from(jsonOf(
        "name" to plate, "stop" to vehicle.str("stop").orEmpty(), "operation" to "$head→${vehicle.str("destination") ?: "行先不明"}",
        "url" to naviUrl, "onOff" to "ON", "lastCheck" to vehicle.long("at"),
    ))
    val latLon = if (vLat != null && vLon != null) "%.5f, %.5f".format(vLat, vLon) else "取得できません"
    val infoRows = listOf(
        "車両番号" to plate,
        "車両コード" to (vehicle.str("carCode") ?: "取得できません"),
        "運行状態" to if (stale) "位置情報が古い" else "運行中",
        "系統・行先" to "${vehicle.str("route")?.ifBlank { null } ?: "不明"} / ${vehicle.str("destination")?.ifBlank { null } ?: "行先不明"}",
        "現在の停留所" to (vehicle.str("stop")?.ifBlank { null } ?: "取得できません"),
        "始発" to (vehicle.str("startingStop")?.ifBlank { null } ?: "取得できません"),
        "位置（緯度・経度）" to latLon,
        "速度" to (vehicle.dbl("speedKmh")?.let { "${app.narabuspulse.feature.bus.jsNum(it)} km/h" } ?: "取得できません"),
        "進行方向" to (vehicle.str("direction")?.ifBlank { null } ?: "取得できません"),
        "最終更新" to (vehicle.long("at")?.let { BusFormat.dateTime(it) } ?: "取得できません"),
        "ロケーションURL" to naviUrl.ifBlank { "取得できません" },
    )
    VehicleConsole(
        vehicles = listOf(consoleVehicle),
        routeStops = routeStops,
        logs = emptyList(),
        positions = positions,
        positionsLoading = query.loading,
        positionsError = data.str("error").orEmpty(),
        updatedAt = data.long("fetchedAt"),
        staleMinutes = STALE_MINUTES,
        autoUpdateLabel = "${UPDATE_INTERVAL_MS / 1000}秒ごとに自動更新（公式データ・取得後45秒キャッシュ）",
        onRefresh = { query.reload() },
        refreshing = query.loading,
        extraCoords = extraCoords,
        providedTimeline = timeline,
        infoRows = infoRows,
        infoNote = "車種・所属営業所は公式バスロケーションに含まれないため表示できません。系統・行先・停留所・位置は公式の取得値をそのまま表示しています。",
        positionNote = "現在位置は奈良交通バスロケーションシステム（公式）の実測値です。停留所の並びは公式の運行順、道路形状はOpenStreetMap（OSRM）で結んでいます。5分以上更新がない車両は「位置情報が古い」として地図に表示しません（推測での代用はしません）。",
        loadingText = "位置情報を読み込んでいます…",
        staleText = "位置情報の更新が止まっています",
        missingText = "この車両の位置情報が提供されていません（停留所位置での代用はしません）",
        initialTab = if (tabParam == "runs") "runs" else "info",
        header = header,
    )
}
