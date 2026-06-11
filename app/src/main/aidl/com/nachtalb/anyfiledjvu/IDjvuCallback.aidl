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
}
