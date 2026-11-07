package com.xplsh.onnshell.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.xplsh.onnshell.R
import com.xplsh.onnshell.data.Accent

object Ui {
    val BG = Color(0xFF0C0C10)
    val SURFACE = Color(0xFF1B1B22)
    val SURFACE_HI = Color(0xFF2A2A34)
    val TEXT2 = Color(0xFFB4B4C2)
    val TEXT3 = Color(0xFF6E6E7C)
}

/**
 * Outfit, a variable font (OFL, docs/licenses/Outfit-OFL.txt). The wght axis has to be set
 * explicitly, otherwise every weight renders at the file's default (Thin).
 */
@OptIn(ExperimentalTextApi::class)
val Outfit = FontFamily(
    listOf(300, 400, 500, 600, 700, 800).map {
        Font(R.font.outfit, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it)))
    }
)

/**
 * The aurora, drawn once into a tiny bitmap and stretched over the screen with bilinear filtering.
 * The Mali-G310 could not afford three full screen alpha gradients every frame (30 fps); one small
 * texture moved by a layer transform costs almost nothing.
 */
fun auroraBitmap(a: Accent): ImageBitmap {
    val w = 160
    val h = 90
    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    fun blob(color: Color, x: Float, y: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(x, y, r, intArrayOf(color.copy(alpha = 0.55f).toArgb(), 0), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
    }
    blob(a.at(0), w * 0.10f, h * 0.05f, w * 0.55f)
    blob(a.at(1), w * 0.90f, h * 0.10f, w * 0.42f)
    blob(a.at(2), w * 0.60f, h * 1.00f, w * 0.50f)
    return b.asImageBitmap()
}
