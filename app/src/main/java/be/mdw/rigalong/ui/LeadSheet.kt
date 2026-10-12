package be.mdw.rigalong.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A song's lead sheet on the stage (REQUIREMENTS S9.1, I9.5): the PDF drawn by
 * Android's own PdfRenderer, one page or two side by side, from [firstPage]
 * (0-based). Page turns come from the player (page-turner keys, I9.2).
 */
class SheetRenderer(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    val pageCount: Int get() = renderer.pageCount

    /** One page at [widthPx]; PdfRenderer is not thread-safe, hence the lock. */
    fun render(index: Int, widthPx: Int): Bitmap? = synchronized(this) {
        if (index !in 0 until renderer.pageCount) return null
        renderer.openPage(index).use { page ->
            val w = widthPx.coerceIn(200, 2400)
            val h = (w.toLong() * page.height / page.width).toInt()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(AColor.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }

    override fun close() {
        synchronized(this) {
            renderer.close()
            fd.close()
        }
    }
}

@Composable
fun LeadSheetView(file: File, firstPage: Int, twoUp: Boolean, onPageCount: (Int) -> Unit, modifier: Modifier = Modifier) {
    val sheet = remember(file) { runCatching { SheetRenderer(file) }.getOrNull() }
    DisposableEffect(sheet) { onDispose { sheet?.close() } }
    if (sheet == null) {
        Box(modifier.background(Color(0xFF1A1D23)), contentAlignment = Alignment.Center) {
            Text("This lead sheet cannot be opened.", color = Color(0xFF8A919C))
        }
        return
    }
    LaunchedEffect(sheet) { onPageCount(sheet.pageCount) }
    BoxWithConstraints(modifier) {
        val columns = if (twoUp) 2 else 1
        val widthPx = (constraints.maxWidth / columns).coerceAtLeast(200)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (k in 0 until columns) {
                PageImage(sheet, firstPage + k, widthPx, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun PageImage(sheet: SheetRenderer, index: Int, widthPx: Int, modifier: Modifier) {
    var bmp by remember(sheet, index, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sheet, index, widthPx) {
        bmp = withContext(Dispatchers.Default) { runCatching { sheet.render(index, widthPx) }.getOrNull() }
    }
    Box(modifier.background(Color(0xFF101216)), contentAlignment = Alignment.TopCenter) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize())
        } else if (index < sheet.pageCount) {
            Text("…", color = Color(0xFF8A919C), fontSize = 24.sp, modifier = Modifier.padding(24.dp))
        }
    }
}
