package app.narabuspulse.feature.registry

import androidx.compose.runtime.Composable

/** 各機能の画面登録をまとめる。機能を移植したらここに 1 行追加する。 */
fun featureScreens(): Map<String, @Composable (Map<String, String>) -> Unit> = buildMap {
    put("home") { _ -> app.narabuspulse.feature.home.HomeScreen() }
}
