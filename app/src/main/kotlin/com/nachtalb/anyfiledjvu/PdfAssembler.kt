// SPDX-License-Identifier: GPL-2.0-or-later
package com.nachtalb.anyfiledjvu

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Assembles a multi-page PDF from a decoded DjVu document using the framework
 * [PdfDocument] — no third-party PDF library, no extra native code.
 *
 * Each DjVu page is rendered to an ARGB_8888 [Bitmap] by [DjvuNative], drawn onto
 * a PDF page sized to the bitmap (at 72pt-per-inch scaled from the render DPI),
 * and written out. Pages are processed one at a time so only a single page bitmap
 * is resident at once — important for large scanned books.
 */
internal object PdfAssembler {

    private const val TAG = "DjvuPdfAssembler"
    private const val POINTS_PER_INCH = 72.0

    /**
     * Convert the DjVu at [inputPath] to a PDF written to [out].
     *
     * @param targetDpi render resolution; PDF page size is derived so the printed
     *   page matches the document's physical dimensions (pixels / dpi * 72).
     * @throws IOException on decode or write failure.
     */
    @Throws(IOException::class)
    fun convert(inputPath: String, targetDpi: Int, out: OutputStream) {
        val pageCountHolder = IntArray(1)
        val handle = DjvuNative.nativeOpen(inputPath, pageCountHolder)
        if (handle == 0L) throw IOException("DjVu open failed: $inputPath")

        val pageCount = pageCountHolder[0]
        if (pageCount <= 0) {
            DjvuNative.nativeClose(handle)
            throw IOException("DjVu has no pages")
        }

        val pdf = PdfDocument()
        try {
            val dims = IntArray(2)
            for (i in 0 until pageCount) {
                val pixels = DjvuNative.nativeRenderPage(handle, i, targetDpi, dims)
                if (pixels == null) {
                    Log.w(TAG, "page $i render failed; skipping")
                    continue
                }
                val w = dims[0]
                val h = dims[1]

                // PDF page dimensions in points (1/72"): pixels are at targetDpi.
                val pageW = (w * POINTS_PER_INCH / targetDpi).toInt().coerceAtLeast(1)
                val pageH = (h * POINTS_PER_INCH / targetDpi).toInt().coerceAtLeast(1)

                val info = PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create()
                val page = pdf.startPage(info)

                var bitmap: Bitmap? = null
                try {
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                    // Scale the full-res bitmap down into the (smaller) point-sized page.
                    val canvas = page.canvas
                    val sx = pageW.toFloat() / w
                    val sy = pageH.toFloat() / h
                    canvas.save()
                    canvas.scale(sx, sy)
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                    canvas.restore()
                } finally {
                    pdf.finishPage(page)
                    bitmap?.recycle()
                }
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
            DjvuNative.nativeClose(handle)
        }
    }

    /** Convenience: convert to a temp file and return it. */
    @Throws(IOException::class)
    fun convertToFile(inputPath: String, targetDpi: Int, outFile: File) {
        outFile.outputStream().use { convert(inputPath, targetDpi, it) }
    }
}
