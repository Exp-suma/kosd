package app.narabuspulse.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.narabuspulse.ui.theme.Brand
import kotlin.math.ceil
import kotlin.math.max

/** グラフの系列（名前・色・値）。 */
data class Series(val name: String, val color: Color, val values: List<Double>)

private fun niceMax(v: Double): Double {
    if (v <= 0) return 1.0
    val mag = Math.pow(10.0, Math.floor(Math.log10(v)))
    val n = v / mag
    val step = when { n <= 1 -> 1.0; n <= 2 -> 2.0; n <= 5 -> 5.0; else -> 10.0 }
    return step * mag
}

private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(v)

/**
 * 棒グラフ（Web の recharts BarChart）。stacked=true で積み上げ。
 * 棒をタップするとその項目の値を下に表示する（Web のツールチップの代わり）。
 */
@Composable
fun BarChart(
    labels: List<String>,
    series: List<Series>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    stacked: Boolean = false,
    labelEvery: Int = 1,
    valueSuffix: String = "",
    tooltipLabel: (Int) -> String = { labels.getOrElse(it) { "" } },
) {
    var picked by remember { mutableStateOf<Int?>(null) }
    val grid = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val n = labels.size
    val maxVal = niceMax(
        if (stacked) (0 until n).maxOfOrNull { i -> series.sumOf { it.values.getOrElse(i) { 0.0 } } } ?: 0.0
        else series.flatMap { it.values }.maxOrNull() ?: 0.0,
    )
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(n) {
                detectTapGesturesCompat { x, w ->
                    val left = 36.dp.toPx()
                    val slot = (w - left) / max(1, n)
                    val i = ((x - left) / slot).toInt()
                    picked = if (i in 0 until n) i else null
                }
            },
        ) {
            val left = 36.dp.toPx(); val bottom = 18.dp.toPx(); val top = 6.dp.toPx()
            val chartH = size.height - bottom - top
            val chartW = size.width - left
            val paint = android.graphics.Paint().apply { color = textColor; textSize = 10.sp.toPx(); isAntiAlias = true }
            for (k in 0..4) {
                val y = top + chartH * (1 - k / 4f)
                drawLine(grid, Offset(left, y), Offset(size.width, y), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawContext.canvas.nativeCanvas.drawText(fmt(maxVal * k / 4), 2f, y + 4f, paint)
            }
            if (n == 0) return@Canvas
            val slot = chartW / n
            val barGroupW = slot * 0.72f
            for (i in 0 until n) {
                val x0 = left + slot * i + (slot - barGroupW) / 2
                if (stacked) {
                    var acc = 0.0
                    series.forEach { s ->
                        val v = s.values.getOrElse(i) { 0.0 }
                        if (v > 0) {
                            val y1 = top + chartH * (1 - ((acc + v) / maxVal)).toFloat()
                            val y0 = top + chartH * (1 - (acc / maxVal)).toFloat()
                            drawRect(s.color.copy(alpha = if (picked == null || picked == i) 1f else 0.5f), Offset(x0, y1), Size(barGroupW, y0 - y1))
                        }
                        acc += v
                    }
                } else {
                    val bw = barGroupW / max(1, series.size)
                    series.forEachIndexed { si, s ->
                        val v = s.values.getOrElse(i) { 0.0 }
                        val y1 = top + chartH * (1 - (v / maxVal)).toFloat()
                        drawRoundRect(s.color.copy(alpha = if (picked == null || picked == i) 1f else 0.5f), Offset(x0 + bw * si, y1), Size(bw * 0.92f, top + chartH - y1), CornerRadius(3f, 3f))
                    }
                }
                if (i % max(1, labelEvery) == 0) {
                    val t = labels[i]
                    val tw = paint.measureText(t)
                    drawContext.canvas.nativeCanvas.drawText(t, left + slot * i + slot / 2 - tw / 2, size.height - 3f, paint)
                }
            }
        }
        ChartLegend(series)
        picked?.let { i -> ChartTooltip(tooltipLabel(i), series.map { it.name to fmt(it.values.getOrElse(i) { 0.0 }) + valueSuffix }) }
    }
}

/** 折れ線グラフ（Web の recharts LineChart）。 */
@Composable
fun LineChart(
    labels: List<String>,
    series: List<Series>,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    labelEvery: Int = 1,
    valueSuffix: String = "",
    minValue: Double? = null,
) {
    var picked by remember { mutableStateOf<Int?>(null) }
    val grid = MaterialTheme.colorScheme.outline
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val n = labels.size
    val all = series.flatMap { it.values }.filter { !it.isNaN() }
    val lo = minValue ?: (all.minOrNull()?.let { if (it >= 0) 0.0 else Math.floor(it) } ?: 0.0)
    val hi = lo + niceMax((all.maxOrNull() ?: 1.0) - lo)
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(n) {
                detectTapGesturesCompat { x, w ->
                    val left = 36.dp.toPx()
                    val step = (w - left) / max(1, n - 1)
                    val i = Math.round((x - left) / step)
                    picked = if (i in 0 until n) i else null
                }
            },
        ) {
            val left = 36.dp.toPx(); val bottom = 18.dp.toPx(); val top = 6.dp.toPx()
            val chartH = size.height - bottom - top
            val chartW = size.width - left
            val paint = android.graphics.Paint().apply { color = textColor; textSize = 10.sp.toPx(); isAntiAlias = true }
            for (k in 0..4) {
                val y = top + chartH * (1 - k / 4f)
                drawLine(grid, Offset(left, y), Offset(size.width, y), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawContext.canvas.nativeCanvas.drawText(fmt(lo + (hi - lo) * k / 4), 2f, y + 4f, paint)
            }
            if (n == 0) return@Canvas
            val step = if (n > 1) chartW / (n - 1) else 0f
            fun yOf(v: Double) = top + chartH * (1 - ((v - lo) / (hi - lo))).toFloat()
            series.forEach { s ->
                val path = Path()
                var started = false
                s.values.forEachIndexed { i, v ->
                    if (v.isNaN()) { started = false; return@forEachIndexed }
                    val x = left + step * i; val y = yOf(v)
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                drawPath(path, s.color, style = Stroke(width = 2.dp.toPx()))
                if (n <= 40) s.values.forEachIndexed { i, v -> if (!v.isNaN()) drawCircle(s.color, 2.5.dp.toPx(), Offset(left + step * i, yOf(v))) }
            }
            picked?.let { i -> drawLine(grid, Offset(left + step * i, top), Offset(left + step * i, top + chartH)) }
            for (i in 0 until n) if (i % max(1, labelEvery) == 0) {
                val t = labels[i]; val tw = paint.measureText(t)
                drawContext.canvas.nativeCanvas.drawText(t, (left + step * i - tw / 2).coerceIn(left - 10f, size.width - tw), size.height - 3f, paint)
            }
        }
        ChartLegend(series)
        picked?.let { i -> ChartTooltip(labels.getOrElse(i) { "" }, series.map { it.name to (it.values.getOrElse(i) { Double.NaN }.let { v -> if (v.isNaN()) "—" else fmt(v) + valueSuffix }) }) }
    }
}

/** 横棒（Web の割合バー）。 */
@Composable
fun HBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Brand.Orange, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(color, RoundedCornerShape(4.dp)))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartLegend(series: List<Series>) {
    if (series.size < 2) return
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(s.color, RoundedCornerShape(2.dp)))
                Text(" ${s.name}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChartTooltip(title: String, rows: List<Pair<String, String>>) {
    Column(Modifier.padding(top = 4.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(8.dp)) {
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        rows.forEach { (k, v) -> Row { Text(k, fontSize = 11.sp, modifier = Modifier.weight(1f)); Text(v, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
    }
}

/** タップ位置（x と全体幅）を受け取る。 */
suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGesturesCompat(onTap: (Float, Float) -> Unit) {
    detectTapGestures { offset -> onTap(offset.x, size.width.toFloat()) }
}

val CHART_COLORS = Brand.Chart

@Suppress("unused")
private fun unused() = ceil(0.0)
