package app.narabuspulse.feature.weather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.core.Api
import app.narabuspulse.core.Logic
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.long
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.core.text
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.analytics.fmtNum
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.nav.LocalNav
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.HBar
import app.narabuspulse.ui.kit.MapPin
import app.narabuspulse.ui.kit.OsmMap
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.RowDivider
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.StatTile
import app.narabuspulse.ui.kit.rememberLoad
import app.narabuspulse.ui.theme.Brand
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

private fun hex(s: String) = Color(android.graphics.Color.parseColor(s))
private fun tempColor(t: Double?) = hex(when { t == null -> "#94a3b8"; t < 0 -> "#1d4ed8"; t < 5 -> "#0ea5e9"; t < 10 -> "#22d3ee"; t < 15 -> "#34d399"; t < 20 -> "#fbbf24"; t < 25 -> "#fb923c"; else -> "#ef4444" })
private fun popColor(p: Double?) = hex(when { p == null || p < 20 -> "#cbd5e1"; p < 40 -> "#93c5fd"; p < 60 -> "#3b82f6"; p < 80 -> "#1d4ed8"; else -> "#312e81" })
private fun snowColor(s: Double?, d: Double?) = hex(when { (d ?: 0.0) > 0 -> "#1e3a8a"; (s ?: 0.0) > 0 -> "#3b82f6"; else -> "#cbd5e1" })
private val MODES = listOf("temp" to "気温分布", "pop" to "降水分布", "snow" to "降雪・積雪")

/** 奈良県全域の天気マップ（Web: weather.map.jsx + RegionWeatherMap.jsx） */
@Composable
fun WeatherMapScreen() {
    val nav = LocalNav.current
    val q = rememberLoad("weather-overview") { Api.rpc("weather/server-fns#getRegionOverview", JsonNull) }
    var mode by remember { mutableStateOf("temp") }
    var picked by remember { mutableStateOf<JsonElement?>(null) }
    val data = q.data
    val items = data.items("items")
    ScreenList {
        item { WeatherTabs("/weather/map") }
        item { Heading("奈良県全域の天気マップ", "市町村の代表地点の実データを地図に表示します。色は気温・降水確率・降雪から切り替えられます。マーカーを押すと詳細に入れます。") }
        if (q.loading && data == null) item { Muted("気象データを取得しています…", 12) }
        q.error?.let { item { ErrorText("気象データを取得できませんでした：$it") } }
        if (data != null) {
            item {
                Panel(title = "奈良県全域の天気マップ") {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { MODES.forEach { (k, l) -> FilterChip(mode == k, { mode = k }, label = { Text(l, fontSize = 11.sp) }) } }
                    val pins = items.filter { it.dbl("lat") != null && it.dbl("lon") != null }.map { it ->
                        val c = when (mode) { "pop" -> popColor(it.dbl("today.pop")); "snow" -> snowColor(it.dbl("today.snow"), it.dbl("current.snowDepth")); else -> tempColor(it.dbl("current.temp") ?: it.dbl("today.tmax")) }
                        MapPin(it.str("code").orEmpty(), it.dbl("lat")!!, it.dbl("lon")!!, it.str("name").orEmpty(), valueOr(it.dbl("current.temp"), "℃"), color = c, size = if (it.str("kind") == "市") 20.dp else 16.dp)
                    }
                    OsmMap(Modifier.fillMaxWidth().height(420.dp), pins = pins, center = 34.35 to 135.85, zoom = 9.0, onPinClick = { p -> picked = items.firstOrNull { it.str("code") == p.id } })
                    if (mode == "temp") Row(verticalAlignment = Alignment.CenterVertically) {
                        Muted("寒い ", 10); listOf("#1d4ed8", "#22d3ee", "#34d399", "#fbbf24", "#ef4444").forEach { Text("■", color = hex(it), fontSize = 14.sp) }; Muted(" 暑い", 10)
                    } else Muted(if (mode == "pop") "降水確率（濃いほど高い）" else "降雪・積雪（濃いほど多い）", 10)
                    picked?.let { it ->
                        Column(Modifier.padding(top = 6.dp)) {
                            Text(it.str("name").orEmpty(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Muted("${it.str("kana").orEmpty()}・標高 ${valueOr(it.dbl("elevation"), "m", 0)}", 10)
                            Text("気温 ${valueOr(it.dbl("current.temp"), "℃")}　最高/最低 ${valueOr(it.dbl("today.tmax"), "°", 0)} / ${valueOr(it.dbl("today.tmin"), "°", 0)}　降水確率 ${valueOr(it.dbl("today.pop"), "%", 0)}　降雪/積雪 ${valueOr(it.dbl("today.snow"), "cm")} / ${valueOr(it.dbl("current.snowDepth"), "cm")}", fontSize = 12.sp)
                            if (it.bool("available") != true) Text("この地点のデータは取得できません", fontSize = 10.sp, color = Brand.Warning)
                            TextButton(onClick = { nav.go("/weather/${it.str("code")}") }) { Text("30日予報を見る →", fontSize = 12.sp) }
                        }
                    }
                }
            }
            item {
                val coldest = items.filter { it.dbl("current.temp") != null }.sortedBy { it.dbl("current.temp") }.take(5)
                Panel(title = "気温が低い地域（現在）") {
                    if (coldest.isEmpty()) Muted("取得できる地点がありません。", 11)
                    coldest.forEach { it -> Row(Modifier.fillMaxWidth().clickable { nav.go("/weather/${it.str("code")}") }.padding(vertical = 4.dp)) {
                        Text(it.str("name").orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f)); Muted("標高 ${valueOr(it.dbl("elevation"), "m", 0)}  ", 10); Text(valueOr(it.dbl("current.temp"), "℃"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = INFO)
                    } }
                }
            }
            item {
                val snowy = items.filter { (it.dbl("today.snow") ?: 0.0) > 0 || (it.dbl("current.snowDepth") ?: 0.0) > 0 }.sortedByDescending { it.dbl("current.snowDepth") ?: 0.0 }
                Panel(title = "降雪・積雪のある地域") {
                    if (snowy.isEmpty()) Muted("現在、降雪・積雪のある地点はありません（推定値）。", 11)
                    snowy.forEach { it -> Row(Modifier.fillMaxWidth().clickable { nav.go("/weather/${it.str("code")}") }.padding(vertical = 4.dp)) {
                        Text(it.str("name").orEmpty(), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f)); Muted("降雪 ${valueOr(it.dbl("today.snow"), "cm")}　積雪 ${valueOr(it.dbl("current.snowDepth"), "cm")}", 11)
                    } }
                }
            }
            item { Muted("予報取得：${data.str("source").orEmpty()}・更新 ${dateTimeLabel(data.long("fetchedAt"))}", 10) }
        }
        item { WeatherFootnote() }
    }
}

/** 過去の類似気象日（Web: weather.similar.jsx） */
@Composable
fun WeatherSimilarScreen() {
    var codes by remember { mutableStateOf(listOf("nara")) }
    var date by remember { mutableStateOf(jstTodayKey()) }
    val code = codes.firstOrNull() ?: "nara"
    val q = rememberLoad("weather-similar", code, date) { Api.rpc("weather/advanced-fns#getSimilarDays", jsonOf("code" to code, "date" to date)) }
    val data = q.data
    ScreenList {
        item { WeatherTabs("/weather/similar") }
        item { Heading("過去の類似気象日", "今の（または指定日の）予報と気象条件が近い過去の日を探し、雪の残り方や撮影条件の参考にします。") }
        item { MunicipalityPicker(codes, { codes = it.takeLast(1) }, 2) }
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Muted("対象日", 12); DateField(date, { date = it }) } }
        if (q.loading) item { Muted("過去2年分と比較しています…", 12) }
        q.error?.let { item { ErrorText(it) } }
        if (data != null) {
            item {
                Panel(title = "比較する気象条件（${data.str("target.date").orEmpty()}）") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("最高気温" to ("tmax" to "℃"), "最低気温" to ("tmin" to "℃"), "降水量" to ("precip" to "mm"), "降雪量" to ("snow" to "cm"), "風速" to ("wind" to "m/s")).forEach { (l, ku) ->
                            StatTile(l, data.dbl("target.${ku.first}")?.let { fmtNum(it) } ?: "—", Modifier.weight(1f), ku.second)
                        }
                    }
                }
            }
            item {
                val m = data.items("matches")
                Panel(title = "類似度の高い過去日") {
                    if (m.isEmpty()) Muted("類似する日が見つかりませんでした。", 12)
                    m.forEachIndexed { i, r ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.str("date").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp))
                                HBar(((r.dbl("score") ?: 0.0) / 100).toFloat(), Modifier.weight(1f))
                                Text(" ${r.dbl("score")?.let { fmtNum(it) } ?: "—"}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Muted("${r.dbl("tmax")?.let { fmtNum(it) } ?: "—"}/${r.dbl("tmin")?.let { fmtNum(it) } ?: "—"}℃　${r.dbl("precip")?.let { fmtNum(it) } ?: "—"}mm" +
                                (r.dbl("snow")?.takeIf { it > 0 }?.let { "　降雪${fmtNum(it)}cm" } ?: "") + (r.dbl("snowDepth")?.let { "　積雪深 ${fmtNum(it)}cm" } ?: ""), 11)
                        }
                    }
                    Muted(data.str("note").orEmpty(), 10)
                }
            }
        }
        item { WeatherFootnote() }
    }
}

/** 標高別気象・積雪残存（Web: weather.elevation.jsx） */
@Composable
fun WeatherElevationScreen() {
    val mountain = produceState(listOf<String>()) { value = Logic.constant("weather/municipalities", "MOUNTAIN_CODES").items().mapNotNull { it.str("code") ?: it.text.ifBlank { null } } }.value
    var codes by remember { mutableStateOf(listOf("yoshino")) }
    var snowCodes by remember { mutableStateOf<List<String>?>(null) }
    val sc = snowCodes ?: mountain.take(4)
    val code = codes.firstOrNull() ?: "yoshino"
    val elev = rememberLoad("weather-elev", code) { Api.rpc("weather/advanced-fns#getElevationAnalysis", jsonOf("code" to code)) }
    val snow = rememberLoad("weather-remain", sc.joinToString(",")) { if (sc.isEmpty()) null else Api.rpc("weather/advanced-fns#getSnowRemaining", jsonOf("codes" to sc)) }
    ScreenList {
        item { WeatherTabs("/weather/elevation") }
        item { Heading("標高別気象・積雪残存", "同じ市町村でも標高によって異なる気温・降雪を地点ごとに比較し、雪が残る可能性を簡易推定します。") }
        item { MunicipalityPicker(codes, { codes = it.takeLast(1) }, 2, "1市町村を選ぶと、その市町村内の地点ごとの差を表示します。") }
        if (elev.loading) item { Muted("取得しています…", 12) }
        elev.error?.let { item { ErrorText(it) } }
        elev.data?.let { d ->
            item {
                Panel(title = "⛰ ${d.str("municipality.name").orEmpty()}の標高別比較") {
                    d.objAt("comparison")?.let { c ->
                        Muted("低い地点 ${c.str("low.name")}（${c.int("low.elevation")}m）／高い地点 ${c.str("high.name")}（${c.int("high.elevation")}m）／標高差 ${c.int("elevationDiff")}m", 11)
                        Muted("最低気温の差（実データ）${c.dbl("actualTempDiff")?.let { fmtNum(it) }}℃（参考: 気温減率 ${c.dbl("lapseReference")?.let { fmtNum(it) }}℃）", 11)
                    }
                    DataTable(listOf(Col("地点", 110.dp), Col("標高", 60.dp, true), Col("最低気温", 72.dp, true), Col("7日降雪", 64.dp, true), Col("雪の可能性", 72.dp, true), Col("降り始め（推定）", 140.dp)),
                        d.items("rows").map { r -> val miss = r.bool("missing") == true
                            listOf(r.str("name").orEmpty(), "${r.int("elevation")?.toString() ?: "—"}m", if (miss) "取得不可" else r.dbl("minTemp")?.let { "${fmtNum(it)}℃" } ?: "—", if (miss) "—" else r.dbl("totalSnow")?.let { "${fmtNum(it)}cm" } ?: "—", r.dbl("snowRisk")?.let { "${fmtNum(it)}%" } ?: "—", r.str("firstSnowAt")?.replace("T", " ") ?: "降水の予報なし")
                        })
                    Muted(d.str("note").orEmpty(), 10)
                }
            }
            item {
                Panel(title = "地点ごとの7日予報") {
                    d.items("rows").filter { it.bool("missing") != true }.forEach { r ->
                        Text(r.str("name").orEmpty(), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            r.items("days").forEach { day ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Muted(day.str("date").orEmpty().drop(5), 10)
                                    Text("${day.dbl("tmax")?.let { fmtNum(it) } ?: "—"}/${day.dbl("tmin")?.let { fmtNum(it) } ?: "—"}", fontSize = 11.sp)
                                    Muted(day.dbl("snow")?.takeIf { it > 0 }?.let { "${fmtNum(it)}cm" } ?: "—", 10)
                                }
                            }
                        }
                    }
                }
            }
        }
        item { MunicipalityPicker(sc, { snowCodes = it }, 6, "積雪残存を推定する市町村（最大6）。山間部を選ぶと差が出ます。") }
        if (snow.loading && sc.isNotEmpty()) item { Muted("取得しています…", 12) }
        snow.error?.let { item { ErrorText(it) } }
        snow.data?.let { d ->
            item {
                val results = d.items("results")
                Panel(title = "積雪残存の推定") {
                    results.forEachIndexed { i, it ->
                        if (i > 0) RowDivider()
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(it.str("municipality.name").orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            val err = it.str("error")
                            if (err != null) Text(err, fontSize = 11.sp, color = Brand.Destructive)
                            else {
                                val cover = it.int("coverDays")
                                Muted("現在の積雪深（推定） ${it.dbl("today.snowDepth")?.let { v -> fmtNum(v) } ?: "—"}cm　過去7日降雪 ${it.dbl("recentSnow")?.let { v -> fmtNum(v) } ?: "0"}cm", 11)
                                Text(when { cover == null -> "10日先まで残雪の可能性"; cover == 0 -> "今日中に消える見込み"; else -> "${cover}日後に消える見込み" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = INFO)
                                val est = it.items("estimates")
                                if (est.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    est.forEach { e -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Muted(e.str("date").orEmpty().drop(5), 10); Text("${e.dbl("depth")?.let { v -> fmtNum(v) } ?: "0"}cm", fontSize = 11.sp, fontWeight = FontWeight.Bold); Muted("${e.dbl("tmean")?.let { v -> fmtNum(v) } ?: "—"}℃", 10) } }
                                }
                            }
                        }
                    }
                    Muted(results.firstOrNull { it.str("note") != null }?.str("note") ?: "積雪深は気象モデルの推定値です。融雪は度日法による簡易推定のため、現地の残雪を保証しません。", 10)
                    results.firstOrNull()?.objAt("params")?.let { p -> Muted("融雪係数 ${p.dbl("baseFactor")?.let { fmtNum(it) }}（${p.str("unit").orEmpty()}）・${p.str("note").orEmpty()}", 10) }
                }
            }
        }
        item { WeatherFootnote() }
    }
}
