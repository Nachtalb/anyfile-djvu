// SPDX-License-Identifier: GPL-2.0-or-later
package com.nachtalb.anyfiledjvu

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Assembles a multi-page PDF from a decoded DjVu document using the framework
 * [PdfDocument] — no third-party PDF library, no extra native code.
 *
 * **Parallel decode (#196):** DjVuLibre is safe across *separate* `ddjvu` contexts, so pages are
 * decoded concurrently across a small worker pool (each worker re-opens the document and owns its own
 * native handle), while PDF assembly stays single-threaded on the consuming side — the framework
 * [PdfDocument] is not thread-safe, and assembly is cheap next to decode. On a multi-core device this
 * cuts wall-clock conversion time roughly N× for a big scanned book; output is byte-equivalent to the
 * old serial path (same pages, same order, same sizes).
 *
 * **Memory is bounded:** a [Semaphore] caps how many decoded-but-not-yet-written page bitmaps are
 * resident at once ([maxResidentPages] = worker count), so peak memory is ~workers × one page bitmap
 * regardless of document length — the same "don't hold the whole book in RAM" guarantee the serial
 * version had, just with a few pages in flight instead of one.
 *
 * Progress ([onProgress]) is reported by the **consumer in page order**, so it stays monotonic even
 * though decode completes out of order.
 */
internal object PdfAssembler {

    private const val TAG = "DjvuPdfAssembler"
    private const val POINTS_PER_INCH = 72.0

    /** Upper bound on decode worker threads. Caps peak memory (each resident page is one bitmap). */
    private const val MAX_WORKERS = 4

    /** A decoded page handed from a worker to the serial assembler. */
    private class PageResult(
        val pixels: IntArray?,   // null = render failed/skipped
        val width: Int,
        val height: Int,
        val hasPermit: Boolean,  // whether a resident-page permit is held (so the consumer releases exactly once)
    )

    /**
     * Convert the DjVu at [inputPath] to a PDF written to [out].
     *
     * @param targetDpi render resolution; PDF page size is derived so the printed
     *   page matches the document's physical dimensions (pixels / dpi * 72).
     * @param onProgress optional per-page callback (done, total), invoked in page order.
     * @throws IOException on open failure or empty document.
     */
    @Throws(IOException::class)
    fun convert(inputPath: String, targetDpi: Int, out: OutputStream, onProgress: ((done: Int, total: Int) -> Unit)? = null) {
        // Open one handle to learn the page count (and reuse it as worker 0's handle).
        val pageCountHolder = IntArray(1)
        val firstHandle = DjvuNative.nativeOpen(inputPath, pageCountHolder)
        if (firstHandle == 0L) throw IOException("DjVu open failed: $inputPath")
        val pageCount = pageCountHolder[0]
        if (pageCount <= 0) {
            DjvuNative.nativeClose(firstHandle)
            throw IOException("DjVu has no pages")
        }

        // One worker per core (2..MAX_WORKERS), never more than there are pages. Each worker needs its
        // own native handle; reuse firstHandle for worker 0 and open the rest. Opens that fail are
        // dropped (we proceed with however many succeeded — at least the first).
        val desiredWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, MAX_WORKERS)
        val workerCount = minOf(desiredWorkers, pageCount)
        val handles = ArrayList<Long>(workerCount)
        handles.add(firstHandle)
        for (w in 1 until workerCount) {
            val h = DjvuNative.nativeOpen(inputPath, IntArray(1))
            if (h != 0L) handles.add(h) else Log.w(TAG, "worker $w extra open failed; continuing with fewer")
        }
        val effectiveWorkers = handles.size

        // One single-slot queue per page so the consumer can take them strictly in order; a semaphore
        // bounds how many decoded pages are resident (waiting to be written) at once.
        val queues = Array(pageCount) { ArrayBlockingQueue<PageResult>(1) }
        val residentPermits = Semaphore(effectiveWorkers)
        val nextPage = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(effectiveWorkers)

        // Workers: work-steal page indices, decode with their own handle, hand the result to the
        // matching queue. Every claimed index is GUARANTEED a put (even on failure) so the consumer
        // can never hang waiting for a page that no worker will deliver.
        for (handle in handles) {
            executor.execute {
                while (true) {
                    val i = nextPage.getAndIncrement()
                    if (i >= pageCount) break
                    var acquired = false
                    val result = try {
                        residentPermits.acquire()
                        acquired = true
                        val dims = IntArray(2)
                        val pixels = DjvuNative.nativeRenderPage(handle, i, targetDpi, dims)
                        if (pixels == null) Log.w(TAG, "page $i render failed; skipping")
                        PageResult(pixels, dims[0], dims[1], hasPermit = true)
                    } catch (t: Throwable) {
                        Log.w(TAG, "page $i decode threw", t)
                        PageResult(null, 0, 0, hasPermit = acquired)
                    }
                    queues[i].put(result)
                }
            }
        }

        val pdf = PdfDocument()
        try {
            // Consumer: pull pages in order, draw each into the document, release its resident permit.
            for (i in 0 until pageCount) {
                val res = queues[i].take()
                try {
                    val pixels = res.pixels
                    if (pixels != null) {
                        val w = res.width
                        val h = res.height
                        // PDF page dimensions in points (1/72"): pixels are at targetDpi.
                        val pageW = (w * POINTS_PER_INCH / targetDpi).toInt().coerceAtLeast(1)
                        val pageH = (h * POINTS_PER_INCH / targetDpi).toInt().coerceAtLeast(1)

                        val info = PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create()
                        val page = pdf.startPage(info)
                        var bitmap: Bitmap? = null
                        try {
                            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                            val canvas = page.canvas
                            canvas.save()
                            canvas.scale(pageW.toFloat() / w, pageH.toFloat() / h)
                            canvas.drawBitmap(bitmap, 0f, 0f, null)
                            canvas.restore()
                        } finally {
                            pdf.finishPage(page)
                            bitmap?.recycle()
                        }
                    }
                } finally {
                    // Free the resident slot so a blocked worker can decode the next page.
                    if (res.hasPermit) residentPermits.release()
                }
                onProgress?.invoke(i + 1, pageCount)
            }
            pdf.writeTo(out)
        } finally {
            executor.shutdown()
            runCatching { executor.awaitTermination(5, TimeUnit.SECONDS) }
            pdf.close()
            for (h in handles) DjvuNative.nativeClose(h)
        }
    }

    /** Convenience: convert to a temp file and return it. */
    @Throws(IOException::class)
    fun convertToFile(inputPath: String, targetDpi: Int, outFile: File, onProgress: ((done: Int, total: Int) -> Unit)? = null) {
        outFile.outputStream().use { convert(inputPath, targetDpi, it, onProgress) }
    }
}
