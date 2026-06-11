// SPDX-License-Identifier: GPL-2.0-or-later
package com.nachtalb.anyfiledjvu

/**
 * Thin JNI wrapper over the vendored DjVuLibre decode core (`libdjvujni.so`).
 *
 * Decode-only: open a .djvu by path, get the page count, render any page to an
 * ARGB_8888 int[] (width*height). PDF assembly lives in [PdfAssembler] using the
 * framework PdfDocument — the native layer never touches PDF.
 */
internal object DjvuNative {

    init {
        System.loadLibrary("djvujni")
    }

    /** Opaque handle bundling the ddjvu context+document. 0 on failure. */
    external fun nativeOpen(path: String, outPageCount: IntArray): Long

    /** Renders page [pageNo] (0-based) at [targetDpi]; dims returned via [outDims] (w,h). null on failure. */
    external fun nativeRenderPage(handle: Long, pageNo: Int, targetDpi: Int, outDims: IntArray): IntArray?

    /** Releases the document + context behind [handle]. */
    external fun nativeClose(handle: Long)
}
