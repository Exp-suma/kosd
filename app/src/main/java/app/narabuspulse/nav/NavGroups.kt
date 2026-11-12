package app.narabuspulse.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/** ドロワーの項目（Web 版 src/components/layout/AppShell.jsx の NAV_GROUPS と同じ構成）。 */
data class NavItem(val path: String, val label: String, val icon: ImageVector, val match: String? = null)

data class NavGroup(val label: String, val items: List<NavItem>)

val NAV_GROUPS = listOf(
    NavGroup("運行管理", listOf(
        NavItem("/", "ホーム", Icons.Outlined.Home),
        NavItem("/dashboard", "パーソナル", Icons.Outlined.Dashboard),
        NavItem("/vehicles", "車両位置", Icons.Outlined.DirectionsBus),
        NavItem("/all-vehicles", "全車両マップ", Icons.Outlined.Map),
        NavItem("/fleet", "車番検索", Icons.Outlined.Search, "/fleet"),
        NavItem("/status", "車両状態一覧", Icons.Outlined.ViewList),
        NavItem("/rides/plans", "乗車予定", Icons.Outlined.Alarm),
        NavItem("/rides/history", "乗車履歴", Icons.Outlined.Assignment),
        NavItem("/notifications", "通知", Icons.Outlined.Notifications),
        NavItem("/vehicle-notify", "車両通知", Icons.Outlined.NotificationsActive, "/vehicle-notify"),
    )),
    NavGroup("運行データ", listOf(
        NavItem("/calendar", "稼働カレンダー", Icons.Outlined.CalendarMonth),
        NavItem("/forecast", "1日運用予測", Icons.Outlined.Schedule, "/forecast"),
        NavItem("/logs", "運行変更履歴", Icons.Outlined.History),
        NavItem("/analysis", "時間帯別分析", Icons.Outlined.BarChart),
        NavItem("/analytics", "運行分析", Icons.Outlined.Insights, "/analytics"),
        NavItem("/operations", "運用表", Icons.Outlined.TableChart),
        NavItem("/research", "運用調査", Icons.Outlined.FindInPage, "/research"),
        NavItem("/search", "自由条件検索", Icons.Outlined.FilterAlt),
        NavItem("/ai-search", "AI検索", Icons.Outlined.ManageSearch),
        NavItem("/compare", "お気に入り・比較", Icons.Outlined.StarOutline),
    )),
    NavGroup("予測AI", listOf(
        NavItem("/predict/next", "次運用予測", Icons.Outlined.Schedule),
        NavItem("/predict/intersection", "交差点通過予測", Icons.Outlined.Place),
        NavItem("/predict/watch", "車両出現通知", Icons.Outlined.NotificationsNone),
        NavItem("/predict/report", "車両運行レポート", Icons.Outlined.Description),
        NavItem("/predict/accuracy", "予測精度・履歴", Icons.Outlined.TrackChanges),
    )),
    NavGroup("交通・おでかけ", listOf(
        NavItem("/transit", "経路検索", Icons.Outlined.Train),
        NavItem("/nara", "奈良交通メニュー", Icons.Outlined.DirectionsTransit, "/nara"),
        NavItem("/outing", "外出アシスタント", Icons.Outlined.Work, "/outing"),
        NavItem("/field", "現地モード", Icons.Outlined.WifiOff, "/field"),
        NavItem("/weather", "市町村の天気", Icons.Outlined.WbCloudy, "/weather"),
        NavItem("/radar", "気象レーダー", Icons.Outlined.Thunderstorm),
    )),
    NavGroup("掲示板・車両情報", listOf(
        NavItem("/board", "みんなの掲示板", Icons.Outlined.Newspaper, "/board"),
        NavItem("/vehicle-catalog", "車両図鑑", Icons.Outlined.MenuBook, "/vehicle-catalog"),
        NavItem("/vehicle-db", "車両データベース", Icons.Outlined.LibraryBooks, "/vehicle-db"),
        NavItem("/vehicle-ai", "車両情報AI", Icons.Outlined.SmartToy),
        NavItem("/sightings", "目撃マップ", Icons.Outlined.PinDrop),
        NavItem("/admin/board", "掲示板・車両管理", Icons.Outlined.AdminPanelSettings, "/admin"),
    )),
    NavGroup("撮影・調査ツール", listOf(
        NavItem("/camera", "カメラ接続（CamBridge・USB）", Icons.Outlined.Usb),
        NavItem("/wifi", "Wi-Fi画像転送", Icons.Outlined.Wifi, "/wifi"),
        NavItem("/shoot/light", "AI撮影支援", Icons.Outlined.PhotoCamera, "/shoot"),
        NavItem("/meter", "露出計", Icons.Outlined.Camera, "/meter"),
        NavItem("/occlusion", "AI被り率解析", Icons.Outlined.CenterFocusStrong, "/occlusion"),
        NavItem("/discover", "撮影地新規開拓AI", Icons.Outlined.Explore, "/discover"),
        NavItem("/sim3d", "3D撮影シミュレーター", Icons.Outlined.ViewInAr, "/sim3d"),
        NavItem("/photos", "撮影データベース", Icons.Outlined.PhotoLibrary, "/photos"),
        NavItem("/terrain", "路線地形分析", Icons.Outlined.Terrain, "/terrain"),
        NavItem("/expedition", "遠征プランナー", Icons.Outlined.Route, "/expedition"),
    )),
    NavGroup("AI・自動化", listOf(
        NavItem("/assistant", "AIアシスタント", Icons.Outlined.SmartToy, "/assistant"),
        NavItem("/ai", "AIダッシュボード", Icons.Outlined.AutoAwesome),
        NavItem("/expert", "総合相談AI", Icons.Outlined.Forum, "/expert"),
        NavItem("/automation", "自動化・レポート", Icons.Outlined.AccountTree, "/automation"),
    )),
    NavGroup("データ・設定", listOf(
        NavItem("/collection", "データ収集", Icons.Outlined.Storage),
        NavItem("/services", "外部サービス接続", Icons.Outlined.Extension, "/services"),
        NavItem("/profile", "マイページ", Icons.Outlined.Person),
        NavItem("/settings", "設定", Icons.Outlined.Settings, "/settings"),
    )),
)

fun NavItem.isActive(path: String): Boolean = when {
    this.path == "/" -> path == "/"
    match != null -> path == match || path.startsWith("$match/")
    else -> path == this.path || path.startsWith("${this.path}/")
}

fun currentNav(path: String): Pair<NavGroup, NavItem>? {
    for (g in NAV_GROUPS) for (i in g.items) if (i.isActive(path)) return g to i
    return null
}
