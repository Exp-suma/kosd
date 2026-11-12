package com.example.d750bridge.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** アプリ専用の線画アイコン（material-icons-extended を入れずにAPKを軽く保つ）。 */
object CamIcons {
    private fun c(cx: Float, cy: Float, r: Float) = "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

    private fun icon(name: String, stroke: List<String>, fill: List<String> = emptyList(), width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            stroke.forEach {
                addPath(
                    addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
                )
            }
            fill.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val Camera = icon("camera", listOf("M5,7 H8 L9.5,4.6 H14.5 L16,7 H19 A2,2 0 0,1 21,9 V17 A2,2 0 0,1 19,19 H5 A2,2 0 0,1 3,17 V9 A2,2 0 0,1 5,7 Z", c(12f, 13f, 3.6f)))
    val Wifi = icon("wifi", listOf("M2.5,8.8 C7.8,4 16.2,4 21.5,8.8", "M5.6,12.2 C9.4,8.9 14.6,8.9 18.4,12.2", "M8.8,15.5 C10.7,14 13.3,14 15.2,15.5"), listOf(c(12f, 19f, 1.3f)))
    val Sparkle = icon("sparkle", listOf("M12,3 C12.8,8 16,11.2 21,12 C16,12.8 12.8,16 12,21 C11.2,16 8,12.8 3,12 C8,11.2 11.2,8 12,3 Z"))
    val Gallery = icon("gallery", listOf("M5,4 H19 A2,2 0 0,1 21,6 V18 A2,2 0 0,1 19,20 H5 A2,2 0 0,1 3,18 V6 A2,2 0 0,1 5,4 Z", "M3.5,16.5 L9,11 L13,15 L15.5,12.5 L20.5,17.5", c(15.5f, 8.5f, 1.5f)))
    val Tune = icon("tune", listOf("M4,7 H6.5", "M11.5,7 H20", c(9f, 7f, 2.3f), "M4,17 H12.5", "M17.5,17 H20", c(15f, 17f, 2.3f)))
    val Aperture = icon("aperture", listOf(c(12f, 12f, 9f), "M16.5,12 L14.25,15.9 L9.75,15.9 L7.5,12 L9.75,8.1 L14.25,8.1 Z", "M14.25,8.1 L17.6,4.9", "M16.5,12 L20.9,12.6", "M14.25,15.9 L15.4,20.1", "M9.75,15.9 L6.4,19.1", "M7.5,12 L3.1,11.4", "M9.75,8.1 L8.6,3.9"))
    val Focus = icon("focus", listOf("M4,9 V5 H8", "M16,5 H20 V9", "M20,15 V19 H16", "M8,19 H4 V15"), listOf(c(12f, 12f, 1.8f)))
    val Bolt = icon("bolt", emptyList(), listOf("M13,2 L4.5,13.5 H11.5 L10.5,22 L19.5,10 H12.5 Z"))
    val Play = icon("play", emptyList(), listOf("M8,5.2 C8,4.4 8.9,3.9 9.6,4.4 L19,10.9 C19.6,11.3 19.6,12.7 19,13.1 L9.6,19.6 C8.9,20.1 8,19.6 8,18.8 Z"))
    val Stop = icon("stop", emptyList(), listOf("M8,6 H16 A2,2 0 0,1 18,8 V16 A2,2 0 0,1 16,18 H8 A2,2 0 0,1 6,16 V8 A2,2 0 0,1 8,6 Z"))
    val Speed = icon("speed", listOf("M4.2,17 A8.5,8.5 0 1,1 19.8,17", "M12,14 L16.2,9.2"), listOf(c(12f, 14f, 1.6f)))
    val Burst = icon("burst", listOf("M8,7 H18 A2,2 0 0,1 20,9 V17 A2,2 0 0,1 18,19 H8 A2,2 0 0,1 6,17 V9 A2,2 0 0,1 8,7 Z", "M3.5,15 V6.5 A2,2 0 0,1 5.5,4.5 H15"))
    val Pin = icon("pin", listOf("M12,21 C12,21 5.5,14.8 5.5,9.6 A6.5,6.5 0 0,1 18.5,9.6 C18.5,14.8 12,21 12,21 Z", c(12f, 9.6f, 2.4f)))
    val Track = icon("track", listOf(c(12f, 12f, 7f), "M12,2.5 V6", "M12,18 V21.5", "M2.5,12 H6", "M18,12 H21.5"), listOf(c(12f, 12f, 1.6f)))
    val Download = icon("download", listOf("M12,4 V15", "M7.5,10.5 L12,15 L16.5,10.5", "M5,20 H19"))
    val Grid = icon("grid", listOf("M4,4 H20 V20 H4 Z", "M9.33,4 V20", "M14.67,4 V20", "M4,9.33 H20", "M4,14.67 H20"), width = 1.5f)
    val Moon = icon("moon", listOf("M20,14.5 A8,8 0 1,1 9.5,4 A6.5,6.5 0 0,0 20,14.5 Z"))
    val Radar = icon("radar", listOf(c(12f, 12f, 9f), c(12f, 12f, 5f), "M12,12 L18.4,5.6"), listOf(c(12f, 12f, 1.4f)))
    val Battery = icon("battery", listOf("M4,8 H17 A1.5,1.5 0 0,1 18.5,9.5 V14.5 A1.5,1.5 0 0,1 17,16 H4 A1.5,1.5 0 0,1 2.5,14.5 V9.5 A1.5,1.5 0 0,1 4,8 Z", "M21,10.5 V13.5"))
    val Link = icon("link", listOf("M10,14 A4,4 0 0,0 15.6,14 L18.4,11.2 A4,4 0 0,0 12.8,5.6 L11.6,6.8", "M14,10 A4,4 0 0,0 8.4,10 L5.6,12.8 A4,4 0 0,0 11.2,18.4 L12.4,17.2"))
    val Eye = icon("eye", listOf("M2.5,12 C5,7 8.5,5 12,5 C15.5,5 19,7 21.5,12 C19,17 15.5,19 12,19 C8.5,19 5,17 2.5,12 Z", c(12f, 12f, 3f)))
    val EyeOff = icon("eyeoff", listOf("M2.5,12 C5,7 8.5,5 12,5 C15.5,5 19,7 21.5,12 C19,17 15.5,19 12,19 C8.5,19 5,17 2.5,12 Z", c(12f, 12f, 3f), "M4,4 L20,20"))
    val Log = icon("log", listOf("M6,3.5 H14 L19,8.5 V19 A1.5,1.5 0 0,1 17.5,20.5 H6 A1.5,1.5 0 0,1 4.5,19 V5 A1.5,1.5 0 0,1 6,3.5 Z", "M14,3.5 V8.5 H19", "M8,12.5 H15.5", "M8,16 H13"))
    val Timer = icon("timer", listOf(c(12f, 13.5f, 7.5f), "M12,13.5 L12,9.5", "M12,13.5 L15,15.5", "M9.5,2.8 H14.5", "M12,2.8 V6", "M18.6,6.4 L19.9,5.1"))
    val Film = icon("film", listOf("M4,5 H20 A1,1 0 0,1 21,6 V18 A1,1 0 0,1 20,19 H4 A1,1 0 0,1 3,18 V6 A1,1 0 0,1 4,5 Z", "M7,5 V19", "M17,5 V19", "M3,9 H7", "M3,15 H7", "M17,9 H21", "M17,15 H21"))
    val Chip = icon("chip", listOf("M7,6 H17 A1,1 0 0,1 18,7 V17 A1,1 0 0,1 17,18 H7 A1,1 0 0,1 6,17 V7 A1,1 0 0,1 7,6 Z", "M9.5,9.5 H14.5 V14.5 H9.5 Z", "M9,3 V6", "M15,3 V6", "M9,18 V21", "M15,18 V21", "M3,9 H6", "M3,15 H6", "M18,9 H21", "M18,15 H21"))
}
