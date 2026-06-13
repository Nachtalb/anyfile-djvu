// SPDX-License-Identifier: GPL-2.0-or-later
package com.nachtalb.anyfilecompanion;

import android.os.ParcelFileDescriptor;

/**
 * Async result callback for {@link IConverter#convert}. Duplicated VERBATIM into the
 * AnyFile repo and every companion -- keep the package (com.nachtalb.anyfilecompanion)
 * in sync so both sides generate a wire-compatible Binder stub.
 */
interface IConverterCallback {
    /** Conversion succeeded; {@code pdf} is an open, readable fd for the result PDF. */
    oneway void onResult(in ParcelFileDescriptor pdf);

    /** Conversion failed. {@code code} is one of ConverterError.*; {@code message} is for logs. */
    oneway void onError(int code, String message);

    /**
     * Progress, posted once per page as decoding proceeds. {@code done} is units
     * completed so far, {@code total} the total unit count (pages). Best-effort and
     * oneway: a dead/slow client must never break the conversion.
     */
    oneway void onProgress(int done, int total);
}
