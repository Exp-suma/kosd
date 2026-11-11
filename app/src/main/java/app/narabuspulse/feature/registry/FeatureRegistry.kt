package app.narabuspulse.feature.registry

import androidx.compose.runtime.Composable

/** 各機能の画面登録をまとめる。機能を移植したらここに 1 行追加する。 */
fun featureScreens(): Map<String, @Composable (Map<String, String>) -> Unit> = buildMap {
    put("home") { _ -> app.narabuspulse.feature.home.HomeScreen() }
    put("dashboard") { _ -> app.narabuspulse.feature.home.DashboardScreen() }
    put("calendar") { _ -> app.narabuspulse.feature.data.CalendarScreen() }
    put("logs") { _ -> app.narabuspulse.feature.data.LogsScreen() }
    put("analysis") { _ -> app.narabuspulse.feature.data.AnalysisScreen() }
    put("operations") { _ -> app.narabuspulse.feature.data.OperationsScreen() }
    put("search") { _ -> app.narabuspulse.feature.data.SearchScreen() }
    put("ai-search") { _ -> app.narabuspulse.feature.data.AiSearchScreen() }
    put("compare") { _ -> app.narabuspulse.feature.data.CompareScreen() }
    put("analytics") { _ -> app.narabuspulse.feature.analytics.AnalyticsOverviewScreen() }
    put("analytics/continuity") { _ -> app.narabuspulse.feature.analytics.ContinuityScreen() }
    put("analytics/headway") { _ -> app.narabuspulse.feature.analytics.HeadwayScreen() }
    put("analytics/fleet") { _ -> app.narabuspulse.feature.analytics.FleetAnalyticsScreen() }
    put("analytics/transfers") { _ -> app.narabuspulse.feature.analytics.TransfersScreen() }
    put("analytics/vehicles") { _ -> app.narabuspulse.feature.analytics.VehicleUtilizationScreen() }
    put("analytics/longterm") { _ -> app.narabuspulse.feature.analytics.LongTermScreen() }
    put("research") { _ -> app.narabuspulse.feature.research.ResearchCenterScreen() }
    put("research/vehicles") { a -> app.narabuspulse.feature.research.ResearchVehiclesScreen(a["vehicle"]) }
    put("research/routes") { a -> app.narabuspulse.feature.research.ResearchRoutesScreen(a["route"]) }
    put("research/diff") { _ -> app.narabuspulse.feature.research.ResearchDiffScreen() }
    put("forecast") { _ -> app.narabuspulse.feature.forecast.ForecastScreen() }
    put("forecast/plan") { _ -> app.narabuspulse.feature.forecast.PlanForecastScreen() }
    put("forecast/verify") { _ -> app.narabuspulse.feature.forecast.PlanVerifyScreen() }
    put("forecast/history") { _ -> app.narabuspulse.feature.forecast.ForecastHistoryScreen() }
    put("forecast/accuracy") { _ -> app.narabuspulse.feature.forecast.ForecastAccuracyScreen() }
    put("predict/next") { a -> app.narabuspulse.feature.predict.NextRunScreen(a["vehicle"]) }
    put("predict/intersection") { a -> app.narabuspulse.feature.predict.IntersectionScreen(a["q"]) }
    put("predict/accuracy") { _ -> app.narabuspulse.feature.predict.PredictAccuracyScreen() }
    put("predict/report") { _ -> app.narabuspulse.feature.predict.PredictReportScreen() }
    put("predict/watch") { _ -> app.narabuspulse.feature.predict.PredictWatchScreen() }
    put("nara") { a -> app.narabuspulse.feature.nara.NaraSearchScreen(a) }
    put("nara/stops") { _ -> app.narabuspulse.feature.nara.NaraStopsScreen() }
    put("nara/stops/{code}") { a -> app.narabuspulse.feature.nara.NaraStopDetailScreen(a["code"].orEmpty()) }
    put("nara/timetable") { a -> app.narabuspulse.feature.nara.NaraTimetableScreen(a) }
    put("nara/data") { _ -> app.narabuspulse.feature.nara.NaraDataScreen() }
    put("transit") { a -> app.narabuspulse.feature.transit.TransitScreen(a) }
    put("weather") { _ -> app.narabuspulse.feature.weather.WeatherListScreen() }
    put("weather/{code}") { a -> app.narabuspulse.feature.weather.WeatherDetailScreen(a["code"].orEmpty()) }
    put("weather/map") { _ -> app.narabuspulse.feature.weather.WeatherMapScreen() }
    put("weather/compare") { _ -> app.narabuspulse.feature.weather.WeatherCompareScreen() }
    put("weather/snow") { _ -> app.narabuspulse.feature.weather.WeatherSnowScreen() }
    put("weather/elevation") { _ -> app.narabuspulse.feature.weather.WeatherElevationScreen() }
    put("weather/similar") { _ -> app.narabuspulse.feature.weather.WeatherSimilarScreen() }
    put("weather/ai") { _ -> app.narabuspulse.feature.weather.WeatherAiScreen() }
    put("vehicles") { _ -> app.narabuspulse.feature.vehicles.VehiclesScreen() }
    put("status") { _ -> app.narabuspulse.feature.status.StatusScreen() }
    put("all-vehicles") { a -> app.narabuspulse.feature.fleet.AllVehiclesScreen(a["focus"]) }
    put("fleet") { _ -> app.narabuspulse.feature.fleet.FleetSearchScreen() }
    put("fleet/{carCode}") { a -> app.narabuspulse.feature.fleet.FleetVehicleScreen(a["carCode"].orEmpty(), a["tab"]) }
    put("fleet/favorites") { _ -> app.narabuspulse.feature.fleet.FleetFavoritesScreen() }
    put("fleet/logs") { _ -> app.narabuspulse.feature.fleet.FleetLogsScreen() }
    put("rides/plans") { _ -> app.narabuspulse.feature.rides.RidePlansScreen() }
    put("rides/history") { _ -> app.narabuspulse.feature.rides.RideHistoryScreen() }
    put("notifications") { _ -> app.narabuspulse.feature.notify.NotificationsScreen() }
    put("vehicle-notify") { _ -> app.narabuspulse.feature.notify.VehicleNotifyScreen() }
    put("vehicle-notify/history") { _ -> app.narabuspulse.feature.notify.VehicleNotifyHistoryScreen() }
    put("vehicle-notify/place") { _ -> app.narabuspulse.feature.notify.VehicleNotifyPlaceScreen() }
}
