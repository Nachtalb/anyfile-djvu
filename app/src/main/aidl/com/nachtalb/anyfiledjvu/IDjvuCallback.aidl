// SPDX-License-Identifier: GPL-2.0-or-later
package com.nachtalb.anyfiledjvu;

import android.os.ParcelFileDescriptor;

/**
 * Async result callback for {@link IDjvuConverter#convert}.
 */
interface IDjvuCallback {
    /** Conversion succeeded; {@code pdf} is an open, readable fd for the result PDF. */
    oneway void onResult(in ParcelFileDescriptor pdf);

    /** Conversion failed. {@code code} is one of DjvuError.*; {@code message} is for logs. */
    oneway void onError(int code, String message);

    /**
     * Decode progress, posted once per page as decoding proceeds. {@code done} is the number of
     * pages fully decoded so far (1-based as they complete), {@code total} the page count. A client
     * may show a determinate "page done of total" bar. Best-effort and oneway: a client that
     * predates this method (older AIDL) simply never receives it, and conversion is unaffected.
     */
    oneway void onProgress(int done, int total);
}
