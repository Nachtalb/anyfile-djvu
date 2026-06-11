// SPDX-License-Identifier: GPL-2.0-or-later
package com.nachtalb.anyfiledjvu

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * On-device smoke test for the decode path (Phase 2 verification). There's no UI,
 * so this instrumented test IS the device harness: it reads a .djvu pushed to the
 * app's external files dir, converts it, and asserts the output is a valid,
 * multi-page PDF that the framework PdfRenderer can open.
 *
 * Push a sample first:
 *   adb -s <serial> push sample.djvu /sdcard/Android/data/com.nachtalb.anyfiledjvu/files/sample.djvu
 */
class DjvuConversionTest {

    @Test
    fun convertsSampleDjvuToValidPdf() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(ctx.getExternalFilesDir(null), "sample.djvu")
        assertTrue("push sample.djvu to ${input.absolutePath} first", input.exists())

        val out = File(ctx.cacheDir, "out.pdf")
        PdfAssembler.convertToFile(input.absolutePath, 150, out)

        assertTrue("PDF was written", out.exists() && out.length() > 0)

        // Validate it's a real PDF by opening with the framework renderer.
        ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                assertTrue("PDF has pages", renderer.pageCount > 0)
                renderer.openPage(0).use { page ->
                    assertTrue("page 0 has width", page.width > 0)
                    assertTrue("page 0 has height", page.height > 0)
                }
            }
        }
    }
}
