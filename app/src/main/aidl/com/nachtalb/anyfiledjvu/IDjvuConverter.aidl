// SPDX-License-Identifier: GPL-2.0-or-later
package com.nachtalb.anyfiledjvu;

import com.nachtalb.anyfiledjvu.IDjvuCallback;
import android.os.ParcelFileDescriptor;

/**
 * Headless DjVu -> PDF conversion service.
 *
 * AnyFile (or any client holding the bind permission) passes an open DjVu file
 * descriptor and receives, via the callback, an open PDF file descriptor. No
 * URIs, no FileProvider grants — just fds across the Binder boundary.
 *
 * This .aidl is authored by us (not derived from DjVuLibre) and is duplicated
 * verbatim into the AnyFile repo; each side generates its own Binder stub. The
 * GPL DjVuLibre engine never crosses this boundary — only decoded PDF bytes do.
 */
interface IDjvuConverter {
    /**
     * Convert the DjVu document behind {@code input} to a PDF.
     *
     * @param input    an open, readable fd for a .djvu document
     * @param targetDpi render resolution (e.g. 150 for screen, 300 for print)
     * @param callback receives onResult(pdfFd) or onError(code, message)
     */
    oneway void convert(in ParcelFileDescriptor input, int targetDpi, IDjvuCallback callback);
}
