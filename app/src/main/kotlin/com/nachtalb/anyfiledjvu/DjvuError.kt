// SPDX-License-Identifier: GPL-2.0-or-later
package com.nachtalb.anyfiledjvu

/** Error codes passed to IDjvuCallback.onError. */
internal object DjvuError {
    const val IO = 1            // couldn't read the input fd / write the output
    const val DECODE = 2        // DjVuLibre failed to open/decode the document
    const val INTERNAL = 3      // unexpected exception
}
