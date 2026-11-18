package com.rigalong.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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

    /** Page [index]'s width over its height (1/1.414 for A4 upright); A4's for a page that is not there. */
    fun aspect(index: Int): Float = synchronized(this) {
        if (index !in 0 until renderer.pageCount) return 0.7071f
        renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
    }

    override fun close() {
        synchronized(this) {
            renderer.close()
            fd.close()
        }
    }
}

/**
 * How many pages [bytes] has as a PDF Android can show, or the reason it
 * cannot (thrown): checked before a lead sheet is attached, so a file that
 * would only say "cannot be opened" on the stage is refused in the editor.
 */
fun pdfPageCount(bytes: ByteArray, scratchDir: File): Int {
    val tmp = File.createTempFile("leadsheet", ".pdf", scratchDir)
    try {
        tmp.writeBytes(bytes)
        val r = SheetRenderer(tmp)
        try { return r.pageCount.also { require(it > 0) { "no pages" } } } finally { r.close() }
    } finally {
        tmp.delete()
    }
}

@Composable
fun LeadSheetView(file: File, firstPage: Int, twoUp: Boolean, onPageCount: (Int) -> Unit, modifier: Modifier = Modifier,
                  onForward: () -> Unit = {}, onBack: () -> Unit = {}) {
    val sheet = remember(file) { runCatching { SheetRenderer(file) }.getOrNull() }
    DisposableEffect(sheet) { onDispose { sheet?.close() } }
    if (sheet == null) {
        Box(modifier.background(Panel), contentAlignment = Alignment.Center) {
            Text(androidx.compose.ui.res.stringResource(com.rigalong.R.string.lead_sheet_unreadable), color = Dim)
        }
        return
    }
    LaunchedEffect(sheet) { onPageCount(sheet.pageCount) }
    // A swipe turns the page, as the page keys do: to the left forward, to the right back.
    var swiped by remember { mutableStateOf(0f) }
    BoxWithConstraints(modifier.pointerInput(sheet) {
        val far = 48.dp.toPx()
        detectHorizontalDragGestures(
            onDragStart = { swiped = 0f },
            onDragEnd = { if (swiped < -far) onForward() else if (swiped > far) onBack() },
            onHorizontalDrag = { change, dx -> change.consume(); swiped += dx })
    }) {
        // The pages as large as the room allows at their own proportions, side by
        // side as a pair with a small gap -- centred together, so what room is left
        // over is outside the pair, not a stretch between the pages (Michiel, 2026-11-18).
        val columns = if (twoUp) 2 else 1
        val density = androidx.compose.ui.platform.LocalDensity.current
        val gapPx = with(density) { PAGE_GAP.toPx() }
        val aspect = remember(sheet, firstPage) { sheet.aspect(firstPage) }
        val roomW = (constraints.maxWidth - gapPx * (columns - 1)) / columns
        val roomH = constraints.maxHeight.toFloat()
        val pageW = minOf(roomW, roomH * aspect).coerceAtLeast(1f)
        val pageH = pageW / aspect
        val pageSize = with(density) { androidx.compose.ui.unit.DpSize(pageW.toDp(), pageH.toDp()) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(PAGE_GAP, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            for (k in 0 until columns) {
                PageImage(sheet, firstPage + k, pageW.toInt().coerceAtLeast(200),
                    Modifier.size(pageSize))
            }
        }
    }
}

/** Between two pages side by side. */
private val PAGE_GAP = 8.dp

@Composable
private fun PageImage(sheet: SheetRenderer, index: Int, widthPx: Int, modifier: Modifier) {
    var bmp by remember(sheet, index, widthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sheet, index, widthPx) {
        bmp = withContext(Dispatchers.Default) { runCatching { sheet.render(index, widthPx) }.getOrNull() }
    }
    Box(modifier.background(Bg), contentAlignment = Alignment.TopCenter) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize())
        } else if (index < sheet.pageCount) {
            Text("…", color = Dim, fontSize = 24.sp, modifier = Modifier.padding(24.dp))
        }
    }
}
