package app.narabuspulse.feature.registry

import androidx.compose.runtime.Composable

/** 各機能の画面登録をまとめる。機能を移植したらここに 1 行追加する。 */
fun featureScreens(): Map<String, @Composable (Map<String, String>) -> Unit> = buildMap {
    put("home") { _ -> app.narabuspulse.feature.home.HomeScreen() }
    put("vehicles") { _ -> app.narabuspulse.feature.vehicles.VehiclesScreen() }
    put("status") { _ -> app.narabuspulse.feature.status.StatusScreen() }
    put("all-vehicles") { a -> app.narabuspulse.feature.fleet.AllVehiclesScreen(a["focus"]) }
    put("fleet") { _ -> app.narabuspulse.feature.fleet.FleetSearchScreen() }
    put("fleet/{carCode}") { a -> app.narabuspulse.feature.fleet.FleetVehicleScreen(a["carCode"].orEmpty(), a["tab"]) }
    put("fleet/favorites") { _ -> app.narabuspulse.feature.fleet.FleetFavoritesScreen() }
    put("fleet/logs") { _ -> app.narabuspulse.feature.fleet.FleetLogsScreen() }
}
