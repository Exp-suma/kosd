package app.narabuspulse.feature.fleet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.LocalSnackbar
import app.narabuspulse.ui.kit.rememberRpc
import app.narabuspulse.ui.kit.userMessage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.text.Normalizer

private val FLEET_TABS = listOf("/fleet" to "車番検索", "/all-vehicles" to "全車両マップ", "/fleet/favorites" to "お気に入り・追跡", "/fleet/logs" to "全車両ログ")

/** 車番検索グループのタブ（Web: components/fleet/FleetNav.jsx） */
@Composable
fun FleetNav(current: String) {
    val nav = LocalNav.current
    ChipRow(FLEET_TABS, current, { if (it != current) nav.go(it) })
}

/** 車番の比較用の正規化（Web: all-vehicles.jsx の norm と同じ）。 */
fun normPlate(value: String?): String {
    val s = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
        .replace(Regex("[\\s\\-‐‑–—―ー_/\\\\（）()]"), "")
    val sb = StringBuilder()
    s.forEach { ch -> sb.append(if (ch in 'ァ'..'ヶ') (ch - 0x60) else ch) }
    return sb.toString().uppercase()
}

/** 時刻ラベル（Web の lib/fleet/format.js を使う）。 */
@Composable
fun fleetClock(ts: Long?): String = produceState("—", ts) { value = ts?.let { Logic.call("fleet/format", "jstClock", it).text.ifBlank { "—" } } ?: "—" }.value

@Composable
fun fleetAge(ts: Long?): String = produceState("", ts) { value = ts?.let { Logic.call("fleet/format", "ageLabel", it).text }.orEmpty() }.value

@Composable
fun compassLabel(deg: Double?): String = produceState("取得不可", deg) { value = deg?.let { Logic.call("fleet/format", "compassLabel", it).text } ?: "取得不可" }.value

/** 図鑑の段数表示（catalog/fields.js の STEP_LABEL）。 */
@Composable
fun stepLabels(): Map<String, String> = produceState(emptyMap<String, String>()) {
    value = (Logic.constant("catalog/fields", "STEP_LABEL") as? kotlinx.serialization.json.JsonObject)?.mapValues { it.value.text }.orEmpty()
}.value

/** 車両のお気に入り（サーバー保存・Web の useFleetFavorites）。 */
class FleetFavorites(val byCode: Map<String, JsonElement>, val busyCode: String, val toggle: (code: String, plate: String) -> Unit, val reload: () -> Unit)

@Composable
fun rememberFleetFavorites(): FleetFavorites {
    val list = rememberRpc("fleet/fns#listFleetFavorites", jsonOf())
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var busy by remember { mutableStateOf("") }
    val byCode = remember(list.data) { list.data.items().mapNotNull { i -> i.str("car_code")?.let { it to i } }.toMap() }
    return FleetFavorites(byCode, busy, { code, plate ->
        if (code.isBlank() || plate.isBlank()) return@FleetFavorites
        busy = code
        scope.launch {
            try {
                val existing = byCode[code]
                if (existing != null) {
                    Api.rpc("fleet/fns#deleteFleetFavorite", jsonOf("id" to existing.str("id"), "stopTracking" to true))
                    snackbar.showSnackbar("$plate をお気に入りから解除しました")
                } else {
                    Api.rpc("fleet/fns#saveFleetFavorite", jsonOf("carCode" to code, "plateNo" to plate, "notifyStart" to true, "notifyApproach" to false))
                    snackbar.showSnackbar("$plate をお気に入りに登録しました")
                }
                list.reload()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                snackbar.showSnackbar(e.userMessage().ifBlank { "お気に入りを更新できませんでした" })
            } finally { busy = "" }
        }
    }) { list.reload() }
}
