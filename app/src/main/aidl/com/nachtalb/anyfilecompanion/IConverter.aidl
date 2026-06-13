// SPDX-License-Identifier: GPL-2.0-or-later
package com.nachtalb.anyfilecompanion;

import com.nachtalb.anyfilecompanion.IConverterCallback;
import android.os.ParcelFileDescriptor;

/**
 * Neutral file -> PDF conversion service exposed by an AnyFile *companion app*
 * (anyfile-djvu, anyfile-doc-convert, ...). This is the SHARED contract: every
 * companion implements this same interface so AnyFile's CompanionConverter binds
 * and drives them all with one code path -- only the package / action / permission
 * differ per companion.
 *
 * The client passes an open, readable fd for the source document and receives, via
 * the callback, an open fd for the produced PDF. No URIs, no FileProvider grants --
 * just fds across the Binder boundary. The GPL DjVuLibre engine lives entirely in
 * this companion's process; only decoded PDF bytes cross this boundary, which keeps
 * AnyFile itself free of GPL obligations.
 *
 * This .aidl is authored by us (not derived from DjVuLibre) and is duplicated
 * VERBATIM into the AnyFile repo and every other companion; the package MUST stay
 * com.nachtalb.anyfilecompanion so all sides generate a wire-compatible Binder stub.
 */
interface IConverter {
    /**
     * Convert the document behind {@code input} to a PDF.
     *
     * @param input    an open, readable fd for the source document.
     * @param options  per-conversion hints; keys a companion doesn't understand are
     *                 ignored. This companion reads "targetDpi" (int) for render
     *                 resolution; absent/out-of-range falls back to 150.
     * @param callback receives onResult(pdfFd) / onError(code, message) / onProgress.
     */
    oneway void convert(in ParcelFileDescriptor input, in Bundle options, IConverterCallback callback);
}
