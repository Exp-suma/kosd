package app.narabuspulse.feature.meter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.list
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.bus.BusFormat
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.ChipRow
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SelectField
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.kit.Tag
import app.narabuspulse.ui.theme.Brand
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

// ---- 露出値の表示・換算（Web: lib/meter/ev.js と同じ規則） ----
internal object Ev {
    val MODES = listOf(Triple("M", "M マニュアル", "ISO・SS・F値を固定し、適正露出との差を表示"), Triple("S", "S シャッター優先", "SSとISOから推奨F値を算出"),
        Triple("A", "A 絞り優先", "F値とISOから推奨SSを算出"), Triple("ISO", "ISO優先", "ISOを決めて、SSとF値の組み合わせを提案"))
    val METERINGS = listOf("average" to "平均測光", "center" to "中央重点", "spot" to "スポット")
    val APERTURES = listOf(1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1, 8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0)
    private fun trim(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    fun shutter(t: Double?): String = when { t == null || !t.isFinite() || t <= 0 -> "—"; t >= 0.25 -> "${trim((t * 10).roundToLong() / 10.0)}s"; else -> "1/${(1 / t).roundToLong()}" }
    fun aperture(n: Double?): String = if (n == null || !n.isFinite() || n <= 0) "—" else "F${trim((n * 10).roundToLong() / 10.0)}"
    fun iso(v: Double?): String = if (v == null || !v.isFinite() || v <= 0) "—" else "ISO ${v.roundToLong()}"
    fun ev(v: Double?): String = if (v == null || !v.isFinite()) "—" else "%.1f".format(v)
    fun parseShutter(input: String?): Double? {
        val text = input.orEmpty().trim().removeSuffix("s").removeSuffix("S")
        if (text.isEmpty()) return null
        if ("/" in text) { val p = text.split("/").map { it.toDoubleOrNull() }; val a = p.getOrNull(0); val b = p.getOrNull(1); return if (a != null && b != null && a > 0 && b > 0) a / b else null }
        return text.toDoubleOrNull()?.takeIf { it > 0 }
    }
    fun shutterInput(t: Double?): String = when { t == null || !t.isFinite() || t <= 0 -> ""; t >= 1 -> trim((t * 10).roundToLong() / 10.0); else -> "1/${(1 / t).roundToLong()}" }
    fun delta(d: Double?): String {
        if (d == null || !d.isFinite()) return "—"
        val r = (d * 10).roundToLong() / 10.0
        return if (abs(r) < 0.15) "適正" else "${if (r > 0) "+" else ""}${"%.1f".format(r)} EV"
    }
    fun signed(v: Double) = "${if (v > 0) "+" else ""}${"%.1f".format(v)}"
}

private const val PHONE_APERTURE = 1.8
internal data class Measure(val ev100: Double, val offset: Double, val source: String, val iso: Double?, val shutter: Double?, val aperture: Double, val estimated: Boolean)

/** 輝度からシーンの EV100 を求める（Web: lib/meter/measure.js measureEv。絞り値は端末から取得できた実測値を優先）。 */
internal fun measureEv(stats: FrameStats?, exposure: CameraExposure?, locked: Boolean, metering: String, calibration: JsonElement?, cameraId: String, lensId: String): Measure? {
    if (stats == null) return null
    fun num(path: String) = calibration.dbl(path) ?: 0.0
    val sensitivity = num("sensitivity").takeIf { it > 0 } ?: 1.0
    val isoFactor = num("isoFactor").takeIf { it > 0 } ?: 1.0
    val rel = max(1e-6, stats.rel * sensitivity)
    val aperture = exposure?.aperture ?: PHONE_APERTURE
    var iso: Double? = null; var shutter: Double? = null; var source = "unknown"
    if (exposure?.iso != null && exposure.exposureTime != null) {
        iso = if (locked) exposure.iso else exposure.iso * isoFactor
        shutter = exposure.exposureTime
        source = if (locked) "locked" else "auto"
    }
    val offset = num("global") + (calibration.objAt("byCamera").dbl(cameraId) ?: 0.0) + (calibration.objAt("byLens").dbl(lensId) ?: 0.0) + (calibration.objAt("byMode").dbl(metering) ?: 0.0)
    val raw = if (source == "unknown") ln(aperture.pow(2) * rel / ((1.0 / 60) * 0.18)) / ln(2.0) else ln(aperture.pow(2) * rel / (shutter!! * (iso!! / 100) * 0.18)) / ln(2.0)
    return Measure(min(24.0, max(-6.0, raw + offset)), offset, source, iso, shutter, aperture, source == "unknown")
}

private fun exposureLabel(s: FrameStats?): String {
    if (s == null) return "—"
    if (s.clipped && s.highlightRatio > 0.08) return "明部に寄りすぎ（白飛び注意）"
    if (s.crushed && s.shadowRatio > 0.08) return "暗部に寄りすぎ（黒つぶれ注意）"
    val diff = ln((s.meanRel + 0.0005) / 0.18) / ln(2.0)
    return if (diff > 0.7) "やや明るい（露出過多寄り）" else if (diff < -0.7) "やや暗い（露出不足寄り）" else "ほぼ中庸"
}

private val TABS = listOf("/meter" to "露出計", "/meter/cameras" to "カメラ", "/meter/lenses" to "レンズ", "/meter/combos" to "組み合わせ", "/meter/presets" to "プリセット", "/meter/calibration" to "校正", "/meter/history" to "履歴")

@Composable
private fun MeterHeader(current: String) {
    val nav = LocalNav.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Heading("露出計", "スマートフォンの背面カメラで明るさを測り、撮影設定を提案します。映像は端末内で解析され、機材・プリセット・校正・履歴はすべて端末に保存されるため、通信がない場所でも使えます。")
        ChipRow(TABS, current, { if (it != current) nav.go(it) })
    }
}

private fun nameOf(c: JsonElement?) = "${c.str("maker").orEmpty()} ${c.str("name").orEmpty()}".trim()
@Composable private fun Stat(label: String, value: String, modifier: Modifier = Modifier) = Column(modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) { Muted(label, 10); Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
@Composable private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, decimal: Boolean = true) =
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 10.sp) }, singleLine = true, enabled = enabled, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))

/** 露出計（Web: meter.index.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeterScreen() {
    val nav = LocalNav.current
    val state by MeterStore.state.collectAsState()
    val camera = rememberMeterCamera()
    val last = state.objAt("last")
    var saved by remember { mutableStateOf("") }
    fun update(vararg v: Pair<String, Any?>) { MeterStore.patchLast(*v); saved = "" }
    fun l(k: String) = last.str(k).orEmpty()
    val cam = state.list("cameras").firstOrNull { it.str("id") == l("cameraId") }
    val lens = state.list("lenses").firstOrNull { it.str("id") == l("lensId") }
    val preset = state.list("presets").firstOrNull { it.str("id") == l("presetId") }
    val metering = l("metering").ifBlank { "center" }
    val mode = l("mode").ifBlank { "M" }
    val evComp = last.dbl("evComp") ?: 0.0
    LaunchedEffect(metering) { camera.metering = metering }

    val measure = measureEv(camera.stats, camera.exposure, camera.locked, metering, state.objAt("calibration"), l("cameraId"), l("lensId"))
    val ranges = jsonOf("isoMin" to (cam.dbl("isoMin")?.takeIf { it > 0 } ?: 50.0), "isoMax" to (cam.dbl("isoMax")?.takeIf { it > 0 } ?: 51200.0), "apertureMin" to (lens.dbl("apertureMin")?.takeIf { it > 0 } ?: 1.0), "apertureMax" to (lens.dbl("apertureMax")?.takeIf { it > 0 } ?: 22.0),
        "shutterMin" to (Ev.parseShutter(cam.str("shutterFast") ?: "1/8000") ?: (1.0 / 8000)), "shutterMax" to (Ev.parseShutter(cam.str("shutterSlow") ?: "30") ?: 30.0))
    val evKey = measure?.ev100?.let { (it * 10).roundToLong() / 10.0 }
    val shutterSeconds = Ev.parseShutter(l("shutter"))
    // 推奨値と候補は Web と同じ計算（meter/ev）を使う。測定値は 0.1EV 刻みで丸めてから渡す
    val plan = produceState<Pair<JsonElement?, List<JsonElement>>>(null to emptyList(), evKey, evComp, mode, l("iso"), l("shutter"), l("aperture"), ranges.toString(), preset.str("minShutter")) {
        val values = Logic.call("meter/ev", "evaluate", jsonOf("ev" to evKey, "evComp" to evComp, "mode" to mode, "iso" to (last.dbl("iso") ?: 0.0), "shutter" to shutterSeconds, "aperture" to (last.dbl("aperture") ?: 0.0), "ranges" to ranges))
        val open = lens.dbl("apertureMin")?.takeIf { it > 0 } ?: 2.8
        val apertures = listOf(open, 4.0, 5.6, 8.0, 11.0).distinct().filter { it <= (ranges.dbl("apertureMax") ?: 22.0) }
        val cands = if (evKey == null) emptyList() else Logic.call("meter/ev", "buildCandidates", jsonOf("ev" to evKey, "evComp" to evComp, "iso" to (last.dbl("iso") ?: 0.0), "apertures" to apertures, "shutterFloor" to (Ev.parseShutter(preset.str("minShutter")) ?: 0.0))).items()
        value = values to cands
    }.value
    val values = plan.first

    fun gearChange(key: String, id: String) {
        val next = mutableListOf<Pair<String, Any?>>(key to id)
        when (key) {
            "comboId" -> state.list("combos").firstOrNull { it.str("id") == id }?.let { c -> next += "cameraId" to c.str("cameraId").orEmpty(); next += "lensId" to c.str("lensId").orEmpty() }
            "cameraId" -> if (id != l("cameraId")) state.list("cameras").firstOrNull { it.str("id") == id }?.let { c -> c.str("metering")?.takeIf { it.isNotBlank() }?.let { next += "metering" to it }; next += "evComp" to (c.dbl("evBias") ?: 0.0) }
            "presetId" -> state.list("presets").firstOrNull { it.str("id") == id }?.let { p ->
                next += "mode" to (p.str("mode")?.ifBlank { null } ?: mode); next += "iso" to (p.dbl("iso")?.takeIf { it > 0 } ?: last.dbl("iso"))
                next += "shutter" to (p.str("shutter")?.ifBlank { null } ?: l("shutter")); next += "aperture" to (p.dbl("aperture")?.takeIf { it > 0 } ?: last.dbl("aperture"))
                next += "evComp" to (p.dbl("evBias") ?: 0.0); next += "metering" to (p.str("metering")?.ifBlank { null } ?: metering)
            }
        }
        update(*next.toTypedArray())
    }

    ScreenList {
        item { MeterHeader("/meter") }
        item { MeterPreview(camera) { p -> camera.spot = p; if (metering != "spot") update("metering" to "spot") } }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (camera.locked) SmallButton("露出固定を解除", { camera.unlockExposure() }, icon = Icons.Outlined.LockOpen)
                else SmallButton("露出を固定", { camera.lockExposure() }, enabled = camera.status == "live", icon = Icons.Outlined.Lock)
                SmallButton("カメラを切り替え", { camera.stop(); camera.facingBack = !camera.facingBack; camera.start() }, enabled = camera.status == "live", icon = Icons.Outlined.Cameraswitch)
                SmallButton("AIに相談", {
                    MeterStore.saveLive(jsonOf("ev" to measure?.ev100, "iso" to (values.dbl("iso") ?: last.dbl("iso")), "shutter" to Ev.shutter(values.dbl("shutter") ?: shutterSeconds), "aperture" to (values.dbl("aperture") ?: last.dbl("aperture")),
                        "metering" to metering, "mode" to mode, "evComp" to evComp, "camera" to nameOf(cam), "lens" to lens.str("name").orEmpty(), "preset" to preset.str("name").orEmpty(), "luma" to camera.stats?.luma255?.roundToLong()))
                    nav.go("/assistant")
                }, icon = Icons.Outlined.AutoAwesome)
            }
            Muted("対応端末では自動露出を固定し、被写体の明るさだけを測ります。", 10)
        }
        item {
            val s = camera.stats; val e = camera.exposure
            Panel {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) { Muted("測定EV（ISO100基準）", 11); Text(Ev.ev(measure?.ev100), fontSize = 38.sp, fontWeight = FontWeight.Bold) }
                    Column(horizontalAlignment = Alignment.End) {
                        Tag(when { measure == null -> "待機中"; measure.estimated -> "推定（露出情報なし）"; measure.source == "locked" -> "実測（露出固定）"; else -> "実測（自動露出値を補正）" }, when { measure == null -> Brand.MutedFg; measure.estimated -> Brand.Warning; else -> Brand.Success })
                        Muted("補正 ${measure?.let { "${Ev.signed(it.offset)} EV" } ?: "—"}", 10)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Ev.METERINGS.forEach { (k, lbl) -> FilterChip(metering == k, { update("metering" to k) }, label = { Text(lbl, fontSize = 11.sp) }) } }
                if (metering == "spot") Muted("映像をタップした位置の狭い範囲を測光します。", 10)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Stat("平均輝度", s?.let { "${it.mean255.roundToLong()}/255" } ?: "—", Modifier.weight(1f)); Stat("中央輝度", s?.let { "${it.center255.roundToLong()}/255" } ?: "—", Modifier.weight(1f)); Stat("タップ地点", s?.let { "${it.spot255.roundToLong()}/255" } ?: "—", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Stat("ハイライト", s?.let { "%.1f%%".format(it.highlightRatio * 100) } ?: "—", Modifier.weight(1f)); Stat("シャドウ", s?.let { "%.1f%%".format(it.shadowRatio * 100) } ?: "—", Modifier.weight(1f)); Stat("明暗差", s?.let { "%.1f EV".format(it.contrastStops) } ?: "—", Modifier.weight(1f))
                }
                Muted("露出の傾向：${exposureLabel(s)}", 11)
                Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(8.dp)) {
                    Text("スマートフォンカメラの実測値", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Muted("露出 ${e?.exposureTime?.let { Ev.shutter(it) + if (camera.locked) "（固定）" else "" } ?: "取得不可"} ・ ISO ${e?.iso?.roundToLong() ?: "取得不可"} ・ F値 ${e?.aperture?.let { Ev.aperture(it) } ?: "取得不可（F1.8と仮定）"} ・ 露出モード ${if (e == null) "取得不可" else if (camera.locked) "固定" else "自動"}", 10)
                    Muted("WB ${e?.awbMode?.ifBlank { null } ?: "取得不可"} ・ AF ${e?.afMode?.ifBlank { null } ?: "取得不可"}", 10)
                    if (camera.lockMessage.isNotBlank()) Text(camera.lockMessage, fontSize = 10.sp, color = Brand.Warning)
                    if (measure?.estimated == true) Muted("この端末ではカメラの露出情報を取得できないため、ISO100・1/60秒を仮定した参考値です。校正で合わせてください。", 10)
                }
            }
        }
        item { Panel(title = "ヒストグラム・露出警告") { Histogram(camera.stats) } }
        item {
            Panel(title = "使用中の機材・プリセット") {
                @Composable fun Pick(label: String, key: String, options: List<Pair<String, String>>) = SelectField(listOf("" to "未選択") + options, l(key), { gearChange(key, it) }, label = label)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pick("使用中のカメラ", "cameraId", state.list("cameras").map { it.str("id").orEmpty() to nameOf(it) })
                    Pick("使用中のレンズ", "lensId", state.list("lenses").map { it.str("id").orEmpty() to it.str("name").orEmpty() })
                    Pick("組み合わせ", "comboId", state.list("combos").map { c ->
                        val cc = state.list("cameras").firstOrNull { it.str("id") == c.str("cameraId") }; val ll = state.list("lenses").firstOrNull { it.str("id") == c.str("lensId") }
                        c.str("id").orEmpty() to "${cc.str("name") ?: "カメラ未選択"} + ${ll.str("name") ?: "レンズ未選択"}${if (!c.str("adapter").isNullOrBlank()) "（アダプター）" else ""}"
                    })
                    Pick("プリセット", "presetId", state.list("presets").map { it.str("id").orEmpty() to it.str("name").orEmpty() })
                }
                Muted("機材は「カメラ」「レンズ」「組み合わせ」のタブで登録できます。", 10)
            }
        }
        item {
            Panel(title = "撮影モードと設定") {
                Ev.MODES.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (k, lbl, hint) ->
                            val active = mode == k
                            Column(Modifier.weight(1f).border(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).background(if (active) Brand.Orange.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp)).clickable { update("mode" to k) }.padding(8.dp)) {
                                Text(lbl, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Muted(hint, 10)
                            }
                        }
                    }
                }
                val autoShutter = mode == "A" || mode == "ISO"; val autoAperture = mode == "S"
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumField("ISO", last.dbl("iso")?.roundToLong()?.toString().orEmpty(), { v -> update("iso" to (v.filter(Char::isDigit).toLongOrNull() ?: 0L)) }, Modifier.weight(1f), decimal = false)
                    OutlinedTextField(if (autoShutter) Ev.shutterInput(values.dbl("shutter")) else l("shutter"), { update("shutter" to it) }, label = { Text(if (autoShutter) "SS（自動算出）" else "SS 例 1/500", fontSize = 10.sp) }, singleLine = true, enabled = !autoShutter, modifier = Modifier.weight(1f))
                    NumField(if (autoAperture) "F値（自動算出）" else "F値 例 5.6", if (autoAperture) values.dbl("aperture")?.toString().orEmpty() else l("aperture"), { v -> update("aperture" to (v.toDoubleOrNull() ?: v)) }, Modifier.weight(1f), enabled = !autoAperture)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Muted("露出補正", 10); Text("${Ev.signed(evComp)} EV", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                    SmallButton("−", { update("evComp" to ((evComp - 0.3) * 10).roundToLong() / 10.0) }); SmallButton("0", { update("evComp" to 0.0) }, modifier = Modifier.padding(horizontal = 4.dp)); SmallButton("＋", { update("evComp" to ((evComp + 0.3) * 10).roundToLong() / 10.0) })
                }
                // 推奨設定（Web: ExposurePlan.jsx）
                val delta = values.dbl("delta")
                Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("推奨設定${preset.str("name")?.let { "（$it）" } ?: ""}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Tag("適正との差 ${Ev.delta(delta)}", if (delta == null) Brand.MutedFg else if (abs(delta) < 0.15) Brand.Success else if (delta > 0) Brand.Warning else INFO)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Stat("ISO", Ev.iso(values.dbl("iso")), Modifier.weight(1f)); Stat("SS", Ev.shutter(values.dbl("shutter")), Modifier.weight(1f)); Stat("F値", Ev.aperture(values.dbl("aperture")), Modifier.weight(1f)) }
                    Muted("適正EV ${Ev.ev(values.dbl("target"))} ・ 同じSS・Fで適正になるISO ${Ev.iso(values.dbl("isoSolution"))}", 10)
                    values.str("note")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, color = Brand.Warning) }
                    if (plan.second.isNotEmpty()) {
                        Muted("候補（タップで適用）", 10)
                        plan.second.forEach { r ->
                            Row(Modifier.fillMaxWidth().clickable { update("aperture" to r.dbl("aperture"), "shutter" to Ev.shutterInput(r.dbl("shutter")), "iso" to r.dbl("iso")) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${Ev.aperture(r.dbl("aperture"))} ・ ${Ev.shutter(r.dbl("shutter"))} ・ ${Ev.iso(r.dbl("iso"))}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (r.bool("safe") == false) Tag("低速SS", Brand.Warning)
                                Muted("  ${Ev.delta(r.dbl("delta"))}", 11)
                            }
                        }
                    }
                    SmallButton("この測定を履歴に保存", {
                        val m = measure ?: return@SmallButton
                        MeterStore.upsert("history", jsonOf("at" to System.currentTimeMillis(), "ev" to (m.ev100 * 10).roundToLong() / 10.0, "iso" to values.dbl("iso"), "shutter" to Ev.shutter(values.dbl("shutter")), "aperture" to values.dbl("aperture"),
                            "cameraId" to l("cameraId"), "cameraName" to nameOf(cam), "lensId" to l("lensId"), "lensName" to lens.str("name").orEmpty(), "mode" to mode, "metering" to metering, "evComp" to evComp,
                            "luma" to camera.stats?.luma255?.roundToLong(), "source" to m.source, "note" to "", "favorite" to false))
                        saved = "測定を履歴に保存しました"
                    }, enabled = values.dbl("target") != null && measure != null, icon = Icons.Outlined.BookmarkAdd, primary = true)
                }
                preset.str("note")?.takeIf { it.isNotBlank() }?.let { Muted("プリセット：$it", 10) }
                if (saved.isNotBlank()) Text("✓ $saved", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand.Success)
                if (cam == null && lens == null) Muted("機材が未登録のため、汎用的な範囲（F1〜F22・1/8000〜30秒）で計算しています。登録すると使用できる範囲に合わせて提案します。", 10)
                cam?.let { Muted("${nameOf(it)}：ISO ${ranges.dbl("isoMin")?.roundToLong()}〜${ranges.dbl("isoMax")?.roundToLong()} ・${Ev.shutter(ranges.dbl("shutterMin"))}〜${Ev.shutter(ranges.dbl("shutterMax"))}${lens?.let { le -> " ・ ${Ev.aperture(le.dbl("apertureMin"))}〜${Ev.aperture(le.dbl("apertureMax"))}" } ?: ""}", 10) }
            }
        }
    }
}

/** リアルタイム輝度ヒストグラム（Web: Histogram.jsx） */
@Composable
private fun Histogram(stats: FrameStats?) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(96.dp).background(Brand.MutedFg.copy(alpha = 0.12f), RoundedCornerShape(6.dp))) {
        val edge = size.width * 0.047f
        drawRect(Brand.Destructive.copy(alpha = 0.12f), Offset(0f, 0f), Size(edge, size.height)); drawRect(Brand.Destructive.copy(alpha = 0.12f), Offset(size.width - edge, 0f), Size(edge, size.height))
        val bins = stats?.bins ?: return@Canvas
        val maxV = max(1, bins.max()); val w = size.width / bins.size
        bins.forEachIndexed { i, c -> val h = c.toFloat() / maxV * (size.height - 6); drawRect(primary.copy(alpha = 0.35f + i.toFloat() / (bins.size - 1) * 0.6f), Offset(i * w, size.height - h - 1), Size(max(1f, w - 0.6f), h)) }
    }
    Row { Muted("暗部", 10); Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Muted("中間", 10) }; Muted("明部", 10) }
    if (stats == null) Muted("カメラを起動すると分布を表示します", 10)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (stats?.clipped == true) Tag("⚠ 白飛びの可能性 ${"%.1f".format(stats.highlightRatio * 100)}%", Brand.Destructive)
        if (stats?.crushed == true) Tag("⚠ 黒つぶれの可能性 ${"%.1f".format(stats.shadowRatio * 100)}%", INFO)
    }
}

/** 一覧＋編集フォームの共通枠（カメラ・レンズ・組み合わせ・プリセット）。 */
@Composable
private fun GearPage(route: String, key: String, title: String, newTitle: String, editTitle: String, empty: String, row: @Composable (JsonElement) -> Unit, extraActions: @Composable (JsonElement) -> Unit = {},
                     onRemoved: (String) -> Unit = {}, form: @Composable (JsonElement?, (JsonObject) -> Unit, () -> Unit) -> Unit) {
    val state by MeterStore.state.collectAsState()
    var editing by remember { mutableStateOf("") }
    val rows = state.list(key)
    ScreenList {
        item { MeterHeader(route) }
        item {
            Panel(title = "$title（${rows.size}）", action = { SmallButton("追加", { editing = "new" }, icon = Icons.Outlined.Add) }) {
                if (rows.isEmpty()) Muted(empty, 11)
                rows.forEach { r ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { row(r) }
                        extraActions(r)
                        IconButton(onClick = { editing = r.str("id").orEmpty() }) { Icon(Icons.Outlined.Edit, "編集") }
                        IconButton(onClick = { val id = r.str("id").orEmpty(); MeterStore.remove(key, id); onRemoved(id) }) { Icon(Icons.Outlined.Delete, "削除") }
                    }
                }
            }
        }
        if (editing.isNotBlank()) item(key = "form-$editing") {
            Panel(title = if (editing == "new") newTitle else editTitle) {
                form(if (editing == "new") null else rows.firstOrNull { it.str("id") == editing }, { values -> MeterStore.upsert(key, if (editing == "new") values else JsonObject(values + ("id" to kotlinx.serialization.json.JsonPrimitive(editing)))); editing = "" }, { editing = "" })
            }
        }
    }
}

@Composable
private fun FormButtons(isEdit: Boolean, valid: Boolean, onSave: () -> Unit, onCancel: () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    SmallButton(if (isEdit) "更新" else "登録", onSave, enabled = valid, primary = true); SmallButton("キャンセル", onCancel)
}
@Composable private fun TextF(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), placeholder: String = "", lines: Int = 1) =
    OutlinedTextField(value, onChange, label = { Text(label, fontSize = 10.sp) }, placeholder = { Text(placeholder, fontSize = 12.sp) }, singleLine = lines == 1, minLines = lines, modifier = modifier)

/** カメラ（Web: meter.cameras.jsx + CameraForm.jsx） */
@Composable
fun MeterCamerasScreen() = GearPage("/meter/cameras", "cameras", "登録済みのカメラ", "カメラを登録", "カメラを編集", "カメラを登録すると、ISO・シャッター速度の範囲や初期の測光方式が露出計算に反映されます。",
    row = { c ->
        Text(nameOf(c), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Muted("${MeterStore.SENSORS.firstOrNull { it.first == c.str("sensor") }?.second ?: c.str("sensor").orEmpty()} ・ ${c.str("mount")} ・ ISO ${c.str("isoMin")}〜${c.str("isoMax")}", 11)
        Muted("SS ${c.str("shutterFast")}〜${c.str("shutterSlow")} ・ 測光 ${c.str("metering")} ・ 補正 ${c.dbl("evBias") ?: 0.0} EV", 11)
        c.str("custom")?.takeIf { it.isNotBlank() }?.let { Muted("設定：$it", 10) }; c.str("note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
    }, onRemoved = { id -> if (MeterStore.state.value.str("last.cameraId") == id) MeterStore.patchLast("cameraId" to "") }) { initial, onSave, onCancel ->
    fun s(k: String, d: String) = initial.str(k) ?: d
    var maker by remember { mutableStateOf(s("maker", "")) }; var name by remember { mutableStateOf(s("name", "")) }
    var sensor by remember { mutableStateOf(s("sensor", "ff")) }; var mount by remember { mutableStateOf(s("mount", MeterStore.MOUNTS[0])) }
    var isoMin by remember { mutableStateOf(s("isoMin", "100")) }; var isoMax by remember { mutableStateOf(s("isoMax", "12800")) }
    var fast by remember { mutableStateOf(s("shutterFast", "1/4000")) }; var slow by remember { mutableStateOf(s("shutterSlow", "30")) }
    var evMin by remember { mutableStateOf(s("evMin", "-5")) }; var evMax by remember { mutableStateOf(s("evMax", "5")) }
    var metering by remember { mutableStateOf(s("metering", "center")) }; var evBias by remember { mutableStateOf(s("evBias", "0")) }
    var custom by remember { mutableStateOf(s("custom", "")) }; var note by remember { mutableStateOf(s("note", "")) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TextF("メーカー", maker, { maker = it }, Modifier.weight(1f), "Nikon"); TextF("カメラ名", name, { name = it }, Modifier.weight(1f), "D750") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SelectField(MeterStore.SENSORS.map { it.first to it.second }, sensor, { sensor = it }, label = "センサー"); SelectField(MeterStore.MOUNTS.map { it to it }, mount, { mount = it }, label = "マウント") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField("ISO 下限", isoMin, { isoMin = it }, Modifier.weight(1f)); NumField("ISO 上限", isoMax, { isoMax = it }, Modifier.weight(1f)) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TextF("最速SS", fast, { fast = it }, Modifier.weight(1f), "1/4000"); TextF("最遅SS", slow, { slow = it }, Modifier.weight(1f), "30") }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TextF("露出補正 下限", evMin, { evMin = it }, Modifier.weight(1f)); TextF("露出補正 上限", evMax, { evMax = it }, Modifier.weight(1f)) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { SelectField(Ev.METERINGS, metering, { metering = it }, label = "測光方式"); TextF("露出補正の初期値", evBias, { evBias = it }, Modifier.weight(1f)) }
    TextF("カスタム設定（ファームウェア・MF設定など）", custom, { custom = it }); TextF("メモ", note, { note = it }, lines = 2)
    FormButtons(initial != null, maker.isNotBlank() && name.isNotBlank(), {
        onSave(jsonOf("maker" to maker, "name" to name, "sensor" to sensor, "mount" to mount, "isoMin" to (isoMin.toDoubleOrNull() ?: 100.0), "isoMax" to (isoMax.toDoubleOrNull() ?: 12800.0), "shutterFast" to fast, "shutterSlow" to slow,
            "evMin" to (evMin.toDoubleOrNull() ?: 0.0), "evMax" to (evMax.toDoubleOrNull() ?: 0.0), "metering" to metering, "evBias" to (evBias.toDoubleOrNull() ?: 0.0), "custom" to custom, "note" to note))
    }, onCancel)
}

/** レンズ（Web: meter.lenses.jsx + LensForm.jsx） */
@Composable
fun MeterLensesScreen() = GearPage("/meter/lenses", "lenses", "登録済みのレンズ", "レンズを登録", "レンズを編集", "レンズを登録すると、開放F値〜最小絞り値の範囲で絞り値の候補を出します。",
    row = { l ->
        Text(nameOf(l), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Muted("${if (l.dbl("focalMin") == l.dbl("focalMax")) "${l.str("focalMin")}mm（単焦点）" else "${l.str("focalMin")}-${l.str("focalMax")}mm（ズーム）"} ・ ${Ev.aperture(l.dbl("apertureMin"))}〜${Ev.aperture(l.dbl("apertureMax"))} ・ ${l.str("mount")}", 11)
        Muted("手ブレ補正 ${if (l.bool("vr") == true) "あり${l.str("vrMode")?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}" else "なし"}", 11)
        l.str("note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
    }, onRemoved = { id -> if (MeterStore.state.value.str("last.lensId") == id) MeterStore.patchLast("lensId" to "") }) { initial, onSave, onCancel ->
    fun s(k: String, d: String) = initial.str(k) ?: d
    var maker by remember { mutableStateOf(s("maker", "")) }; var name by remember { mutableStateOf(s("name", "")) }; var mount by remember { mutableStateOf(s("mount", MeterStore.MOUNTS[0])) }
    var fMin by remember { mutableStateOf(s("focalMin", "50")) }; var fMax by remember { mutableStateOf(s("focalMax", "50")) }
    var aMin by remember { mutableStateOf(s("apertureMin", "1.8")) }; var aMax by remember { mutableStateOf(s("apertureMax", "22")) }
    var vr by remember { mutableStateOf(initial.bool("vr") == true) }; var vrMode by remember { mutableStateOf(s("vrMode", "")) }; var note by remember { mutableStateOf(s("note", "")) }
    val zoom = (fMax.toDoubleOrNull() ?: 0.0) > (fMin.toDoubleOrNull() ?: 0.0)
    TextF("メーカー", maker, { maker = it }, placeholder = "TAMRON"); TextF("レンズ名", name, { name = it }, placeholder = "SP 70-200mm F/2.8 Di VC USD G2")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { SelectField(MeterStore.MOUNTS.map { it to it }, mount, { mount = it }, label = "マウント"); Tag(if (zoom) "ズームレンズ" else "単焦点レンズ", Brand.MutedFg) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField("焦点距離 最短 (mm)", fMin, { fMin = it }, Modifier.weight(1f)); NumField("焦点距離 最長 (mm)", fMax, { fMax = it }, Modifier.weight(1f)) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField("開放F値", aMin, { aMin = it }, Modifier.weight(1f)); NumField("最小絞り値（最大F値）", aMax, { aMax = it }, Modifier.weight(1f)) }
    Row(verticalAlignment = Alignment.CenterVertically) { Text("手ブレ補正", fontSize = 12.sp, modifier = Modifier.weight(1f)); Switch(vr, { vr = it }) }
    TextF("手ブレ補正の設定（モードなど）", vrMode, { vrMode = it }, placeholder = "VC MODE 1 / OFF")
    Muted("使用可能な絞り値：${Ev.APERTURES.filter { it >= (aMin.toDoubleOrNull() ?: 1.0) - 0.001 && it <= (aMax.toDoubleOrNull() ?: 22.0) + 0.001 }.joinToString(" / ") { Ev.aperture(it) }}", 10)
    TextF("メモ", note, { note = it }, lines = 2)
    FormButtons(initial != null, maker.isNotBlank() && name.isNotBlank(), {
        val a = fMin.toDoubleOrNull() ?: 0.0
        onSave(jsonOf("maker" to maker, "name" to name, "mount" to mount, "focalMin" to a, "focalMax" to (fMax.toDoubleOrNull()?.takeIf { it > 0 } ?: a), "apertureMin" to (aMin.toDoubleOrNull() ?: 1.0), "apertureMax" to (aMax.toDoubleOrNull() ?: 22.0), "vr" to vr, "vrMode" to vrMode, "note" to note, "zoom" to zoom))
    }, onCancel)
}

/** カメラとレンズの組み合わせ（Web: meter.combos.jsx + ComboForm.jsx） */
@Composable
fun MeterCombosScreen() {
    val state by MeterStore.state.collectAsState()
    val cameras = state.list("cameras"); val lenses = state.list("lenses")
    GearPage("/meter/combos", "combos", "カメラとレンズの組み合わせ", "組み合わせを登録", "組み合わせを編集", "よく使う組み合わせを登録すると、露出計の画面で素早く切り替えられます。",
        row = { c ->
            val cam = cameras.firstOrNull { it.str("id") == c.str("cameraId") }; val lens = lenses.firstOrNull { it.str("id") == c.str("lensId") }
            Text("${cam?.let { nameOf(it) } ?: "カメラ未登録"} ＋ ${lens.str("name") ?: "レンズ未登録"}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Muted("${cam.str("mount").orEmpty()} / ${lens.str("mount").orEmpty()}${c.str("adapter")?.takeIf { it.isNotBlank() }?.let { " ・ アダプター $it" } ?: ""}", 11)
            if (cam != null && lens != null && cam.str("mount") != lens.str("mount") && c.str("adapter").isNullOrBlank()) Text("⚠ マウント非互換（アダプター未登録）", fontSize = 10.sp, color = Brand.Warning)
            c.str("note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
        }) { initial, onSave, onCancel ->
        if (cameras.isEmpty() || lenses.isEmpty()) { Muted("先にカメラとレンズを登録してください。", 12); SmallButton("閉じる", onCancel); return@GearPage }
        var cameraId by remember { mutableStateOf(initial.str("cameraId") ?: cameras.first().str("id").orEmpty()) }; var lensId by remember { mutableStateOf(initial.str("lensId") ?: lenses.first().str("id").orEmpty()) }
        var adapter by remember { mutableStateOf(initial.str("adapter").orEmpty()) }; var note by remember { mutableStateOf(initial.str("note").orEmpty()) }
        val cam = cameras.firstOrNull { it.str("id") == cameraId }; val lens = lenses.firstOrNull { it.str("id") == lensId }
        val mismatch = cam != null && lens != null && cam.str("mount") != lens.str("mount")
        SelectField(cameras.map { it.str("id").orEmpty() to "${nameOf(it)}（${it.str("mount")}）" }, cameraId, { cameraId = it }, label = "カメラ")
        SelectField(lenses.map { it.str("id").orEmpty() to "${it.str("name")}（${it.str("mount")}）" }, lensId, { lensId = it }, label = "レンズ")
        if (mismatch) { Text("⚠ マウントが一致しません（${cam.str("mount")} / ${lens.str("mount")}）", fontSize = 11.sp, color = Brand.Warning); Muted("マウントアダプターを使う場合は、アダプター情報を入力してください。", 10) }
        TextF("マウントアダプター（使用時）", adapter, { adapter = it }, placeholder = "例 EF-EOS R / FTZ"); TextF("メモ", note, { note = it }, lines = 2)
        FormButtons(initial != null, cam != null && lens != null && (!mismatch || adapter.isNotBlank()), { onSave(jsonOf("cameraId" to cameraId, "lensId" to lensId, "adapter" to adapter, "note" to note, "cameraName" to (cam?.let { nameOf(it) } ?: ""), "lensName" to lens.str("name").orEmpty())) }, onCancel)
    }
}

/** 撮影ジャンル別プリセット（Web: meter.presets.jsx + PresetForm.jsx） */
@Composable
fun MeterPresetsScreen() {
    val state by MeterStore.state.collectAsState()
    GearPage("/meter/presets", "presets", "撮影ジャンル別プリセット", "プリセットを登録", "プリセットを編集", "",
        row = { p ->
            Text(p.str("name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Muted("${Ev.MODES.firstOrNull { it.first == p.str("mode") }?.second ?: p.str("mode").orEmpty()} ・ ISO ${p.str("iso")} ・ ${p.str("shutter")} ・ ${Ev.aperture(p.dbl("aperture"))}", 11)
            Muted("最低SS ${p.str("minShutter")?.ifBlank { null } ?: "—"} ・ 補正 ${p.dbl("evBias") ?: 0.0} EV ・ ${p.str("metering")}", 11)
            p.str("note")?.takeIf { it.isNotBlank() }?.let { Muted(it, 10) }
        }, extraActions = { p ->
            val active = state.str("last.presetId") == p.str("id")
            IconButton(onClick = { MeterStore.patchLast("presetId" to p.str("id").orEmpty()) }) { Icon(if (active) Icons.Outlined.Star else Icons.Outlined.StarOutline, "露出計で使う", tint = if (active) Brand.Warning else Brand.MutedFg) }
        }) { initial, onSave, onCancel ->
        fun s(k: String, d: String) = initial.str(k) ?: d
        var name by remember { mutableStateOf(s("name", "")) }; var mode by remember { mutableStateOf(s("mode", "S")) }; var metering by remember { mutableStateOf(s("metering", "center")) }
        var iso by remember { mutableStateOf(s("iso", "400")) }; var shutter by remember { mutableStateOf(s("shutter", "1/500")) }; var aperture by remember { mutableStateOf(s("aperture", "5.6")) }
        var minShutter by remember { mutableStateOf(s("minShutter", "1/500")) }; var evBias by remember { mutableStateOf(s("evBias", "0")) }; var note by remember { mutableStateOf(s("note", "")) }
        TextF("プリセット名", name, { name = it }, placeholder = "鉄道撮影")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SelectField(Ev.MODES.map { it.first to it.second }, mode, { mode = it }, label = "撮影モード"); SelectField(Ev.METERINGS, metering, { metering = it }, label = "測光方式") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NumField("ISO", iso, { iso = it }, Modifier.weight(1f)); TextF("シャッター速度", shutter, { shutter = it }, Modifier.weight(1f)); NumField("絞り値", aperture, { aperture = it }, Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TextF("最低シャッター速度", minShutter, { minShutter = it }, Modifier.weight(1f)); TextF("露出補正（EV）", evBias, { evBias = it }, Modifier.weight(1f)) }
        TextF("メモ", note, { note = it }, lines = 2)
        FormButtons(initial != null, name.isNotBlank(), { onSave(jsonOf("name" to name, "mode" to mode, "iso" to (iso.toDoubleOrNull() ?: 400.0), "shutter" to shutter, "aperture" to (aperture.toDoubleOrNull() ?: 5.6), "minShutter" to minShutter, "evBias" to (evBias.toDoubleOrNull() ?: 0.0), "metering" to metering, "note" to note)) }, onCancel)
    }
}

/** 露出の校正（Web: meter.calibration.jsx + CalibrationForm.jsx） */
@Composable
fun MeterCalibrationScreen() {
    val state by MeterStore.state.collectAsState()
    val cal = state.objAt("calibration") ?: MeterStore.defaultCalibration()
    fun set(vararg v: Pair<String, Any?>) = MeterStore.patch("calibration" to JsonObject(cal + v.associate { it.first to app.narabuspulse.core.toJson(it.second) }))
    fun setMap(key: String, id: String, value: Double) = set(key to JsonObject(((cal[key] as? JsonObject) ?: emptyMap()) + (id to kotlinx.serialization.json.JsonPrimitive(value))))
    @Composable fun NumRow(label: String, value: Double, suffix: String = "EV", onChange: (Double) -> Unit) {
        var text by remember(label) { mutableStateOf(if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
            OutlinedTextField(text, { text = it; onChange(it.toDoubleOrNull() ?: 0.0) }, singleLine = true, modifier = Modifier.padding(horizontal = 6.dp).weight(0.6f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
            Muted(suffix, 11)
        }
    }
    ScreenList {
        item { MeterHeader("/meter/calibration") }
        item {
            Panel(title = "露出の校正", subtitle = "スマートフォンのカメラで測った明るさと、実際のカメラで撮影した結果には機種ごとの差があります。同じ場面を露出計と実際のカメラで測り、明るく写った／暗く写った分を補正値に入れてください（＋で明るく、−で暗く）。校正値は端末に保存され、次回起動時も維持されます。") {
                Text("全体の補正", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                NumRow("露出補正（全体）", cal.dbl("global") ?: 0.0) { set("global" to it) }
                NumRow("測光感度補正（明るさの重み）", cal.dbl("sensitivity") ?: 1.0, "倍") { set("sensitivity" to it) }
                NumRow("ISO換算補正", cal.dbl("isoFactor") ?: 1.0, "倍") { set("isoFactor" to it) }
                Text("カメラ別の補正", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                if (state.list("cameras").isEmpty()) Muted("カメラを登録すると個別に補正できます。", 11)
                state.list("cameras").forEach { c -> val id = c.str("id").orEmpty(); NumRow(nameOf(c), cal.objAt("byCamera").dbl(id) ?: 0.0) { setMap("byCamera", id, it) } }
                Text("レンズ別の補正", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                if (state.list("lenses").isEmpty()) Muted("レンズを登録すると個別に補正できます。", 11)
                state.list("lenses").forEach { c -> val id = c.str("id").orEmpty(); NumRow(c.str("name").orEmpty(), cal.objAt("byLens").dbl(id) ?: 0.0) { setMap("byLens", id, it) } }
                Text("測光方式別の補正", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Ev.METERINGS.forEach { (k, lbl) -> NumRow(lbl, cal.objAt("byMode").dbl(k) ?: 0.0) { setMap("byMode", k, it) } }
                SmallButton("すべて初期値に戻す", { MeterStore.patch("calibration" to MeterStore.defaultCalibration()) }, icon = Icons.Outlined.RestartAlt)
            }
        }
    }
}

/** 測定履歴（Web: meter.history.jsx） */
@Composable
fun MeterHistoryScreen() {
    val state by MeterStore.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var favOnly by remember { mutableStateOf(false) }
    val rows = state.list("history").filter { !favOnly || it.bool("favorite") == true }
        .filter { r -> query.isBlank() || listOf("cameraName", "lensName", "note", "mode", "metering", "ev").any { r.str(it).orEmpty().lowercase().contains(query.trim().lowercase()) } }
        .sortedByDescending { it.long("at") ?: 0 }
    ScreenList {
        item { MeterHeader("/meter/history") }
        item {
            Panel(title = "測定履歴（${state.list("history").size}件）") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("カメラ・レンズ・メモで検索", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                    FilterChip(favOnly, { favOnly = !favOnly }, label = { Text("★ お気に入り", fontSize = 11.sp) }, modifier = Modifier.padding(start = 6.dp))
                }
                if (rows.isEmpty()) Muted("該当する測定履歴がありません。", 11)
            }
        }
        rows.forEach { r ->
            item(key = r.str("id")) {
                val o = r as JsonObject
                var note by remember(r.str("id")) { mutableStateOf(r.str("note").orEmpty()) }
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("EV ${"%.1f".format(r.dbl("ev") ?: 0.0)} ・ ISO ${r.dbl("iso")?.roundToLong() ?: "—"} ・ ${r.str("shutter")} ・ ${Ev.aperture(r.dbl("aperture"))}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Muted("${BusFormat.dateTime(r.long("at"))} ・ ${r.str("mode")} ・ ${r.str("metering")} ・ 補正 ${r.dbl("evComp") ?: 0.0} EV", 10)
                            Muted(listOfNotNull(r.str("cameraName"), r.str("lensName")).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { "機材未選択" }, 10)
                        }
                        IconButton(onClick = { MeterStore.upsert("history", JsonObject(o + ("favorite" to kotlinx.serialization.json.JsonPrimitive(r.bool("favorite") != true)))) }) { Icon(if (r.bool("favorite") == true) Icons.Outlined.Star else Icons.Outlined.StarOutline, "お気に入り", tint = if (r.bool("favorite") == true) Brand.Warning else Brand.MutedFg) }
                        IconButton(onClick = { MeterStore.remove("history", r.str("id").orEmpty()) }) { Icon(Icons.Outlined.Delete, "削除") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(note, { note = it }, placeholder = { Text("メモを追加", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                        if (note != r.str("note").orEmpty()) TextButton(onClick = { MeterStore.upsert("history", JsonObject(o + ("note" to kotlinx.serialization.json.JsonPrimitive(note)))) }) { Text("保存", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}
