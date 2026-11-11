package app.narabuspulse.feature.common

import androidx.compose.runtime.Composable
import app.narabuspulse.nav.ScreenDef
import app.narabuspulse.ui.kit.EmptyState
import app.narabuspulse.ui.kit.ScreenList

/** まだネイティブに移植していない画面（開発中のみ。リリース前にゼロにする）。 */
@Composable
fun PendingScreen(def: ScreenDef) {
    ScreenList {
        item {
            EmptyState(
                title = "${def.title}（移植中）",
                description = "この画面はネイティブ版で作り直している途中です。\n元の画面: ${def.webFile}",
            )
        }
    }
}
