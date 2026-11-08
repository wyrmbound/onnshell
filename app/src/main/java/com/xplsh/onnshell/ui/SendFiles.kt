package com.xplsh.onnshell.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.xplsh.onnshell.R
import com.xplsh.onnshell.data.FileDrop
import com.xplsh.onnshell.data.GameFiles
import com.xplsh.onnshell.data.StoreState
import kotlinx.coroutines.delay

/** The QR code as a bitmap, one pixel per module (drawn scaled up without smoothing). */
fun qr(text: String): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp.asImageBitmap()
}

/**
 * "Send game files": the QR code and address of the upload page, what has arrived, and which games it made ready.
 * The page is open only while this is on screen.
 */
@Composable
fun SendFilesPanel(store: StoreState) {
    val theme = LocalTheme.current
    var url by remember { mutableStateOf<String?>(null) }
    var started by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        url = runCatching { FileDrop.start() }.getOrNull(); started = true
        onDispose { FileDrop.stop(); store.scanFiles() }
    }
    val readyBefore = remember { store.files.filterValues { it == GameFiles.Status.READY }.keys.toSet() }
    val got = FileDrop.received
    LaunchedEffect(got.size) { if (got.isNotEmpty()) { delay(1500); store.scanFiles() } } // look once files stop arriving
    val u = url
    if (u == null) {
        if (started) Text(stringResource(R.string.send_no_network), fontSize = 20.sp, color = theme.text)
        return
    }
    Row {
        val code = remember(u) { qr(u) }
        Image(code, null, filterQuality = FilterQuality.None,
            modifier = Modifier.size(260.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp))
        Spacer(Modifier.width(36.dp))
        Column {
            listOf(R.string.send_step1, R.string.send_step2, R.string.send_step3).forEach {
                Text(stringResource(it), fontSize = 20.sp, color = theme.text, modifier = Modifier.padding(bottom = 10.dp))
            }
            Text(stringResource(R.string.send_or_type), fontSize = 15.sp, color = theme.text2, modifier = Modifier.padding(top = 8.dp))
            Text(u, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = theme.text)
            Text(stringResource(R.string.send_where), fontSize = 14.sp, color = theme.text2, modifier = Modifier.padding(top = 10.dp))
        }
    }
    if (got.isNotEmpty()) {
        Text(stringResource(R.string.send_received, got.size), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = theme.text,
            modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))
        got.takeLast(5).forEach { Text("${it.path}  ·  %.1f MB".format(it.size / 1048576.0), fontSize = 15.sp, color = theme.text2) }
        val now = store.files.filterValues { it == GameFiles.Status.READY }.keys - readyBefore
        val names = now.mapNotNull { id -> store.entry(id)?.name }.sorted()
        if (names.isNotEmpty()) Text(stringResource(R.string.send_unlocked, names.joinToString(", ")), fontSize = 18.sp,
            color = theme.text, modifier = Modifier.padding(top = 12.dp))
    }
}
