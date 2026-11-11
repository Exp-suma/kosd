package app.narabuspulse.feature.vehicles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Thunderstorm
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import app.narabuspulse.core.Net
import app.narabuspulse.core.list
import app.narabuspulse.core.text
import app.narabuspulse.feature.bus.BusGate
import app.narabuspulse.feature.bus.BusRepository
import app.narabuspulse.feature.bus.VehicleConsole
import app.narabuspulse.feature.bus.VehiclePosition
import app.narabuspulse.feature.bus.isValid
import app.narabuspulse.feature.bus.rememberNow
import app.narabuspulse.feature.bus.rememberVehicleGps
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.SmallButton
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** 車両位置（Web: src/routes/_authed/vehicles.jsx） */
@Composable
fun VehiclesScreen() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val bus by BusRepository.state.collectAsState()
    BusGate { data ->
        val now = rememberNow()
        val plan by Net.plan.collectAsState()
        val gps = rememberVehicleGps(data.fetchedAt)
        // 現在位置は収集処理が保存した実測GPSだけで決める（停留所座標・他車両での代用はしない）
        val maxAgeMs = 30 * 60_000L
        val positions = data.vehicles.associate { v ->
            val g = v.carCode?.let { gps.byCode[it] }
            v.name to if (!g.isValid()) VehiclePosition("none") else {
                val fresh = g!!.at != null && now - g.at!! <= maxAgeMs
                VehiclePosition(if (fresh) "ok" else "stale", g.lat, g.lon, g.at)
            }
        }
        val routeStops = (data.raw.let { it as? JsonObject }?.get("routeStops") as? JsonObject)?.mapValues { (_, v) -> v.list("").ifEmpty { (v as? kotlinx.serialization.json.JsonArray)?.toList().orEmpty() }.map { it.text } }.orEmpty()
        VehicleConsole(
            vehicles = data.vehicles,
            routeStops = routeStops,
            logs = data.logs,
            positions = positions,
            positionsLoading = gps.loading,
            positionsError = gps.error,
            updatedAt = data.updatedAt,
            staleMinutes = data.staleMinutes,
            autoUpdateLabel = "${plan.intervalMs / 1000}秒ごとに自動更新（${plan.short}）",
            onRefresh = { scope.launch { BusRepository.refresh(force = true) } },
            refreshing = bus.loading,
            header = { SmallButton("気象レーダー", { nav.go("/radar") }, icon = Icons.Outlined.Thunderstorm) },
        )
    }
}
