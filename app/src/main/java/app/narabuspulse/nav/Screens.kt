package app.narabuspulse.nav

import androidx.compose.runtime.Composable
import app.narabuspulse.feature.common.PendingScreen

/**
 * route → 画面。移植した画面はここに追加する（未移植は PendingScreen）。
 * args にはパスの {param} が入る。
 */
@Composable
fun ScreenContent(def: ScreenDef, args: Map<String, String>) {
    val screen = SCREEN_IMPLS[def.route]
    if (screen != null) screen(args) else PendingScreen(def)
}

/** 移植済みの画面。機能ごとのファイルで登録する。 */
val SCREEN_IMPLS: Map<String, @Composable (Map<String, String>) -> Unit> = buildMap {
    putAll(app.narabuspulse.feature.registry.featureScreens())
}
