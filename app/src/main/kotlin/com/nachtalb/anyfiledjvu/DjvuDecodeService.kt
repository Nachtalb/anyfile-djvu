// SPDX-License-Identifier: GPL-2.0-or-later
package com.nachtalb.anyfiledjvu

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Headless DjVu -> PDF conversion service. The app's ONLY component — no Activity,
 * no launcher icon, no UI. It exists solely to be bound by AnyFile (or another
 * permitted client) over the [IDjvuConverter] AIDL.
 *
 * Conversions are serialized on a single-thread executor: DjVuLibre contexts are
 * not built for concurrent hammering in one process, and a headless converter has
 * no reason to run several at once.
 */
class DjvuDecodeService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    private val binder = object : IDjvuConverter.Stub() {
        override fun convert(
            input: ParcelFileDescriptor?,
            targetDpi: Int,
            callback: IDjvuCallback?,
        ) {
            if (input == null || callback == null) {
                input?.close()
                return
            }
            val dpi = if (targetDpi in 36..600) targetDpi else 150
            executor.execute { runConversion(input, dpi, callback) }
        }
    }

    private fun runConversion(input: ParcelFileDescriptor, dpi: Int, callback: IDjvuCallback) {
        // DjVuLibre's create_by_filename needs a real path; copy the incoming fd to
        // a private temp file. (Streaming via create_by_data is a later optimization.)
        val inFile = File.createTempFile("in_", ".djvu", cacheDir)
        val outFile = File.createTempFile("out_", ".pdf", cacheDir)
        try {
            FileInputStream(input.fileDescriptor).use { src ->
                FileOutputStream(inFile).use { dst -> src.copyTo(dst) }
            }

            try {
                PdfAssembler.convertToFile(inFile.absolutePath, dpi, outFile) { done, total ->
                    // oneway callback; a dead/slow client must never break the conversion.
                    runCatching { callback.onProgress(done, total) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "conversion failed", e)
                callback.onError(DjvuError.DECODE, e.message ?: "decode failed")
                return
            }

            // Hand the result back as a read-only fd. The client owns it after this.
            val pdfFd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_READ_ONLY)
            callback.onResult(pdfFd)
        } catch (e: Exception) {
            Log.e(TAG, "service error", e)
            runCatching { callback.onError(DjvuError.INTERNAL, e.message ?: "internal error") }
        } finally {
            runCatching { input.close() }
            // inFile is no longer needed; outFile is deleted once the client's fd
            // closes (we keep our handle only until open() above dup'd it).
            inFile.delete()
            outFile.delete()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DjvuDecodeService"
    }
}
