package app.narabuspulse.feature.forecast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.narabuspulse.core.Api
import app.narabuspulse.core.bool
import app.narabuspulse.core.dbl
import app.narabuspulse.core.int
import app.narabuspulse.core.items
import app.narabuspulse.core.jsonOf
import app.narabuspulse.core.objAt
import app.narabuspulse.core.str
import app.narabuspulse.feature.analytics.Col
import app.narabuspulse.feature.analytics.DataTable
import app.narabuspulse.feature.analytics.ErrorText
import app.narabuspulse.feature.analytics.INFO
import app.narabuspulse.feature.analytics.KpiRow
import app.narabuspulse.feature.analytics.Muted
import app.narabuspulse.feature.analytics.fmtNum
import app.narabuspulse.feature.data.Heading
import app.narabuspulse.feature.data.addDays
import app.narabuspulse.feature.data.jstTodayKey
import app.narabuspulse.feature.research.rememberRunner
import app.narabuspulse.ui.kit.DateField
import app.narabuspulse.ui.kit.Note
import app.narabuspulse.ui.kit.Panel
import app.narabuspulse.ui.kit.ScreenList
import app.narabuspulse.ui.kit.SmallButton
import app.narabuspulse.ui.theme.Brand

private fun rate(v: Double?) = v?.let { fmtNum(it) } ?: "—"

/** 運用表で予測を検証（Web: forecast.verify.jsx + PlanBacktestTable.jsx） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanVerifyScreen() {
    val today = jstTodayKey()
    var from by remember { mutableStateOf(addDays(today, -14)) }
    var to by remember { mutableStateOf(today) }
    val (st, launch) = rememberRunner("検証できませんでした")
    val result = st.result
    val totals = result?.objAt("totals")
    ScreenList {
        item { Heading("運用表で予測を検証", "保存済みの運用表を対象に、その日より前の運用表だけで1日の運用を予測し、実際の運用表と照合します。従来方式（運行記録ベース）の同じ期間の結果も並べて比較できます。") }
        item { ForecastNav("/forecast/verify") }
        item {
            Panel {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Muted("開始日", 12); DateField(from, { from = it }, max = to)
                    Muted("終了日", 12); DateField(to, { to = it }, min = from)
                    SmallButton(if (st.loading) "検証中…" else "検証する", { launch { Api.rpc("forecast/plan-fns#backtestPlanEngine", jsonOf("from" to from, "to" to to)) } }, enabled = !st.loading, icon = Icons.Outlined.PlayArrow, primary = true)
                }
                Muted("運用表はスプレッドシートから読み取るだけで、書き換えは行いません。奈良交通APIへの追加アクセスもありません。", 10)
            }
        }
        st.error?.let { item { ErrorText(it) } }
        if (st.loading) item { Muted("運用表を読み込み、日ごとに予測と照合しています…", 12) }
        if (result != null) {
            if (result.bool("configured") == false) item { Note("スプレッドシートが設定されていません。", tone = Brand.Warning) }
            else {
                if (totals != null) {
                    item {
                        KpiRow(
                            Triple("検証した運用表", "${totals.int("dates") ?: 0}", null), Triple("予測できた車両", "${totals.int("predicted") ?: 0}", INFO),
                            Triple("一致率", rate(totals.dbl("matchRate")), Brand.Success), Triple("時刻差の中央値", rate(totals.dbl("timeError")), null),
                            units = listOf("日", "台", "%", "分"),
                        )
                        Muted("${result.str("range.from")}〜${result.str("range.to")} ／ 予測不可 ${totals.int("unavailable") ?: 0} 台 ／ 一致＝系統・行先が同じで時刻±20分以内 ／ 時刻差は一致・部分一致した運行のみ", 10)
                    }
                    item {
                        Panel {
                            Muted("一致 ${totals.int("match") ?: 0}　部分一致 ${totals.int("partial") ?: 0}　不一致 ${totals.int("mismatch") ?: 0}　予測外の運行 ${totals.int("extra") ?: 0}　予測した運行のうち一致 ${rate(totals.dbl("exactOrderRate"))}%", 12)
                            result.str("note")?.let { Muted(it, 10) }
                        }
                    }
                    val bySvc = result.items("byService")
                    if (bySvc.isNotEmpty()) item {
                        Panel(title = "運行区分ごとの結果") {
                            DataTable(listOf(Col("区分", 64.dp), Col("日数", 52.dp, true), Col("一致", 52.dp, true), Col("部分", 52.dp, true), Col("不一致", 56.dp, true), Col("予測外", 56.dp, true), Col("一致率", 64.dp, true)),
                                bySvc.map { r -> listOf(r.str("serviceLabel").orEmpty(), "${r.int("dates") ?: 0} 日", "${r.int("match") ?: 0}", "${r.int("partial") ?: 0}", "${r.int("mismatch") ?: 0}", "${r.int("extra") ?: 0}", "${rate(r.dbl("matchRate"))}%") })
                        }
                    }
                } else item { Muted(result.str("note") ?: "検証できる運用表がありませんでした。", 12) }
                item {
                    val rows = result.items("dates")
                    Panel(title = "運用表ごとの検証結果", subtitle = "各日は、その日より前の運用表だけで予測しています") {
                        if (rows.isEmpty()) Muted("指定した期間に検証できる運用表がありませんでした。", 12)
                        else DataTable(
                            listOf(Col("運用表", 92.dp), Col("区分", 52.dp), Col("車両", 44.dp, true), Col("予測可", 52.dp, true), Col("予測不可", 60.dp, true), Col("一致", 44.dp, true), Col("部分", 44.dp, true), Col("不一致", 52.dp, true), Col("予測外", 52.dp, true), Col("時刻差", 56.dp, true), Col("一致率", 56.dp, true)),
                            rows.map { r -> listOf(r.str("date").orEmpty(), r.str("serviceLabel").orEmpty(), "${r.int("vehicles") ?: 0}", "${r.int("predicted") ?: 0}", "${r.int("unavailable") ?: 0}", "${r.int("match") ?: 0}", "${r.int("partial") ?: 0}", "${r.int("mismatch") ?: 0}", "${r.int("extra") ?: 0}", r.dbl("timeError")?.let { "${fmtNum(it)}分" } ?: "—", "${rate(r.dbl("matchRate"))}%") },
                        )
                    }
                }
                item {
                    val old = result.objAt("old")
                    Panel(title = "従来方式との比較", subtitle = old.str("label") ?: "従来方式（時間帯別・運行記録ベース）") {
                        if (old == null) Muted("この期間に車両の運行記録が無いため、従来方式の比較はありません。", 12)
                        else {
                            Muted("対象日 ${old.int("totals.services") ?: 0}　予測した車両 ${old.int("totals.vehicles") ?: 0}　一致 ${old.int("totals.match") ?: 0}　不一致 ${old.int("totals.mismatch") ?: 0}　一致率 ${rate(old.dbl("totals.matchRate"))}%", 12)
                            old.items("dates").forEach { r -> Muted("${r.str("date")}（${r.str("serviceLabel").orEmpty()}）：予測 ${r.int("predicted") ?: 0} 台・一致 ${r.int("match") ?: 0}・部分 ${r.int("partial") ?: 0}・不一致 ${r.int("mismatch") ?: 0}・予測外 ${r.int("extra") ?: 0}・一致率 ${rate(r.dbl("matchRate"))}%", 10) }
                            old.str("note")?.let { Muted(it, 10) }
                        }
                    }
                }
            }
        }
        if (result == null && !st.loading) item { Muted("期間を指定して「検証する」を押すと、保存済みの運用表で予測を後追い検証します。", 12) }
    }
}
