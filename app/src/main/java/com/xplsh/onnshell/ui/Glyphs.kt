package com.xplsh.onnshell.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Flat monochrome line icons on a 24 unit grid, drawn here so no icon library is needed. */
object Glyphs {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    PathParser().parsePathString(it).toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private const val CIRCLE = "M3,12 A9,9 0 1 1 21,12 A9,9 0 1 1 3,12"

    val settings = icon("settings", "M9,12 A3,3 0 1 1 15,12 A3,3 0 1 1 9,12",
        "M12,2.5 V5.5", "M12,18.5 V21.5", "M2.5,12 H5.5", "M18.5,12 H21.5",
        "M5.3,5.3 L7.4,7.4", "M16.6,16.6 L18.7,18.7", "M5.3,18.7 L7.4,16.6", "M16.6,7.4 L18.7,5.3")
    val sliders = icon("sliders", "M4,7 H20", "M4,17 H20", "M9,4.5 V9.5", "M15,14.5 V19.5")
    val wifi = icon("wifi", "M2,9 A15,15 0 0 1 22,9", "M5,12.5 A10,10 0 0 1 19,12.5", "M8.5,16 A5,5 0 0 1 15.5,16", "M12,19.5 L12,19.6")
    val ethernet = icon("ethernet", "M4,5 H20 V16 H15 V19 H9 V16 H4 Z", "M8,9 V11", "M12,9 V11", "M16,9 V11")
    val bluetooth = icon("bluetooth", "M7,7.5 L17,16.5 L12,21 V3 L17,7.5 L7,16.5")
    val gamepad = icon("gamepad", "M7,8 H17 A5,5 0 0 1 17,18 H7 A5,5 0 0 1 7,8 Z", "M7,11 V15", "M5,13 H9", "M15.5,12 L15.5,12.1", "M18,14 L18,14.1")
    val touchpad = icon("touchpad", "M3,6 H21 V16 H3 Z", "M12,6 V16", "M9,20 H15")
    val storage = icon("storage", "M4,4 H20 V10 H4 Z", "M4,14 H20 V20 H4 Z", "M7,7 L7,7.1", "M7,17 L7,17.1")
    val sync = icon("sync", "M19,12 A7,7 0 0 1 7,17", "M5,12 A7,7 0 0 1 17,7", "M17,3 V7 H13", "M7,21 V17 H11")
    val terminal = icon("terminal", "M3,5 H21 V19 H3 Z", "M7,9 L10,12 L7,15", "M12,15 H17")
    val info = icon("info", CIRCLE, "M12,11 V16.5", "M12,7.5 L12,7.6")
    val sleep = icon("sleep", "M20,14.5 A8.5,8.5 0 1 1 9.5,4 A6.5,6.5 0 0 0 20,14.5 Z")
    val reboot = icon("reboot", "M20,12 A8,8 0 1 1 17.7,6.3", "M18,2.5 V6.5 H14")
    val apps = icon("apps", "M4,4 H10 V10 H4 Z", "M14,4 H20 V10 H14 Z", "M4,14 H10 V20 H4 Z", "M14,14 H20 V20 H14 Z")
}
