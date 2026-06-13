// SPDX-License-Identifier: GPL-2.0-or-later
package com.nachtalb.anyfiledjvu

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.nachtalb.anyfilecompanion.IConverter
import com.nachtalb.anyfilecompanion.IConverterCallback
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Headless DjVu -> PDF conversion service. The app's ONLY component — no Activity,
 * no launcher icon, no UI. It exists solely to be bound by AnyFile (or another
 * permitted client) over the neutral [IConverter] AIDL (shared across all AnyFile
 * companions; #154).
 *
 * Conversions are serialized on a single-thread executor: DjVuLibre contexts are
 * not built for concurrent hammering in one process, and a headless converter has
 * no reason to run several at once.
 */
class DjvuDecodeService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    private val binder = object : IConverter.Stub() {
        override fun convert(
            input: ParcelFileDescriptor?,
            options: Bundle?,
            callback: IConverterCallback?,
        ) {
            if (input == null || callback == null) {
                input?.close()
                return
            }
            // "targetDpi" is the one option this raster engine honours; absent/out-of-range -> 150.
            val requested = options?.getInt(KEY_TARGET_DPI, 150) ?: 150
            val dpi = if (requested in 36..600) requested else 150
            executor.execute { runConversion(input, dpi, callback) }
        }
    }

    private fun runConversion(input: ParcelFileDescriptor, dpi: Int, callback: IConverterCallback) {
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

        /**
         * Key in the neutral [IConverter] options Bundle for render resolution (DPI). Must match
         * AnyFile's `CompanionOptions.KEY_TARGET_DPI` — it's part of the wire contract, like the
         * `.aidl`. This raster engine honours it; a vector companion (LibreOffice) ignores it.
         */
        private const val KEY_TARGET_DPI = "targetDpi"
    }
}
