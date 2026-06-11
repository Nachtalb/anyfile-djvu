// SPDX-License-Identifier: GPL-2.0-or-later
//
// JNI bridge for the AnyFile DjVu decode companion.
//
// Decode-only surface: open a .djvu by file path, report page count, and render
// any page to a packed RGBA8888 buffer (what android.graphics.Bitmap consumes
// directly). PDF assembly is done on the Kotlin side with the framework
// android.graphics.pdf.PdfDocument — keeping this native layer minimal (no PDF
// writer, no libtiff/libjpeg dependency).
//
// DjVuLibre's ddjvu API is asynchronous: jobs (document/page decode) run on the
// context's worker and surface progress via messages. For a headless one-shot
// converter we drive it synchronously by pumping ddjvu_message_wait/pop until the
// relevant job reports done/error. This is the standard "ddjvu tool" pattern.

#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <string>

#include "djvulibre/ddjvuapi.h"

#define LOG_TAG "DjvuJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// Pump the message queue until the document's decode job finishes. ddjvu posts
// m_error / m_info / progress messages; we only need to block until the document
// is fully decoded (or has failed) before querying page count / rendering.
void handle_messages(ddjvu_context_t *ctx, bool wait) {
    const ddjvu_message_t *msg;
    if (wait) {
        ddjvu_message_wait(ctx);
    }
    while ((msg = ddjvu_message_peek(ctx))) {
        switch (msg->m_any.tag) {
            case DDJVU_ERROR:
                LOGE("ddjvu error: %s", msg->m_error.message ? msg->m_error.message : "(null)");
                break;
            default:
                break;
        }
        ddjvu_message_pop(ctx);
    }
}

// Block until a document is fully decoded. Returns true on success.
bool wait_document(ddjvu_context_t *ctx, ddjvu_document_t *doc) {
    while (!ddjvu_document_decoding_done(doc)) {
        handle_messages(ctx, true);
    }
    return !ddjvu_document_decoding_error(doc);
}

// Block until a page is fully decoded. Returns true on success.
bool wait_page(ddjvu_context_t *ctx, ddjvu_page_t *page) {
    while (!ddjvu_page_decoding_done(page)) {
        handle_messages(ctx, true);
    }
    return !ddjvu_page_decoding_error(page);
}

} // namespace

extern "C" {

// Opens a document and returns the page count, or -1 on failure. The document
// handle is returned via the long[] outHandle (index 0 = document, index 1 =
// context) so the caller can render pages then release. We keep context+document
// alive across calls for the lifetime of one conversion.
JNIEXPORT jlong JNICALL
Java_com_nachtalb_anyfiledjvu_DjvuNative_nativeOpen(
        JNIEnv *env, jclass, jstring jpath, jintArray jOutPageCount) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;

    ddjvu_context_t *ctx = ddjvu_context_create("anyfile-djvu");
    if (!ctx) {
        env->ReleaseStringUTFChars(jpath, path);
        LOGE("ddjvu_context_create failed");
        return 0;
    }

    ddjvu_document_t *doc =
            ddjvu_document_create_by_filename_utf8(ctx, path, /*cache=*/TRUE);
    env->ReleaseStringUTFChars(jpath, path);
    if (!doc) {
        ddjvu_context_release(ctx);
        LOGE("ddjvu_document_create failed");
        return 0;
    }

    if (!wait_document(ctx, doc)) {
        LOGE("document decode failed");
        ddjvu_document_release(doc);
        ddjvu_context_release(ctx);
        return 0;
    }

    int pages = ddjvu_document_get_pagenum(doc);
    LOGI("opened document: %d pages", pages);
    jint p = pages;
    env->SetIntArrayRegion(jOutPageCount, 0, 1, &p);

    // Pack context+document into one opaque handle the Kotlin side passes back.
    // We allocate a tiny struct so both pointers survive between JNI calls.
    auto *handle = static_cast<void **>(malloc(2 * sizeof(void *)));
    handle[0] = ctx;
    handle[1] = doc;
    return reinterpret_cast<jlong>(handle);
}

// Renders page `pageNo` (0-based) at the given DPI scale into a freshly allocated
// int[] of ARGB_8888 pixels (width*height ints). Returns the int[] with
// width/height reported via jOutDims[0]/[1], or null on failure.
JNIEXPORT jintArray JNICALL
Java_com_nachtalb_anyfiledjvu_DjvuNative_nativeRenderPage(
        JNIEnv *env, jclass, jlong jhandle, jint pageNo, jint targetDpi,
        jintArray jOutDims) {
    auto *handle = reinterpret_cast<void **>(jhandle);
    if (!handle) return nullptr;
    auto *ctx = static_cast<ddjvu_context_t *>(handle[0]);
    auto *doc = static_cast<ddjvu_document_t *>(handle[1]);

    ddjvu_page_t *page = ddjvu_page_create_by_pageno(doc, pageNo);
    if (!page) {
        LOGE("page %d create failed", pageNo);
        return nullptr;
    }
    if (!wait_page(ctx, page)) {
        LOGE("page %d decode failed", pageNo);
        ddjvu_page_release(page);
        return nullptr;
    }

    int dpi = ddjvu_page_get_resolution(page);
    if (dpi <= 0) dpi = 300;
    double scale = static_cast<double>(targetDpi) / static_cast<double>(dpi);

    int srcW = ddjvu_page_get_width(page);
    int srcH = ddjvu_page_get_height(page);
    int w = static_cast<int>(srcW * scale + 0.5);
    int h = static_cast<int>(srcH * scale + 0.5);
    if (w < 1) w = 1;
    if (h < 1) h = 1;

    // Cap to keep one page's pixel buffer bounded regardless of source size.
    const int MAX_DIM = 4000;
    if (w > MAX_DIM || h > MAX_DIM) {
        double cap = static_cast<double>(MAX_DIM) / (w > h ? w : h);
        w = static_cast<int>(w * cap);
        h = static_cast<int>(h * cap);
    }

    ddjvu_format_t *fmt = ddjvu_format_create(DDJVU_FORMAT_RGB24, 0, nullptr);
    if (!fmt) {
        ddjvu_page_release(page);
        return nullptr;
    }
    // Render top-down so the row order matches an Android Bitmap (which expects
    // row 0 at the top). RGB24 = 3 bytes/pixel in R,G,B order; no mask args needed.
    ddjvu_format_set_row_order(fmt, 1);   // top-to-bottom
    ddjvu_format_set_y_direction(fmt, 1); // top-down

    ddjvu_rect_t rect;
    rect.x = 0;
    rect.y = 0;
    rect.w = static_cast<unsigned int>(w);
    rect.h = static_cast<unsigned int>(h);

    // RGB24 buffer: 3 bytes per pixel, rows padded to 4 bytes (ddjvu requirement).
    unsigned long rowStride = (static_cast<unsigned long>(w) * 3 + 3) & ~3UL;
    auto *rgb = static_cast<char *>(malloc(rowStride * h));
    if (!rgb) {
        ddjvu_format_release(fmt);
        ddjvu_page_release(page);
        return nullptr;
    }

    int ok = ddjvu_page_render(page, DDJVU_RENDER_COLOR, &rect, &rect, fmt,
                               rowStride, rgb);
    ddjvu_format_release(fmt);
    ddjvu_page_release(page);

    if (!ok) {
        LOGE("page %d render returned no image", pageNo);
        free(rgb);
        return nullptr;
    }

    // Pack RGB24 → ARGB_8888 ints (0xAARRGGBB) with opaque alpha, which is exactly
    // what Bitmap.setPixels(int[]) consumes.
    jintArray out = env->NewIntArray(w * h);
    if (!out) {
        free(rgb);
        return nullptr;
    }
    auto *argb = static_cast<jint *>(malloc(static_cast<size_t>(w) * h * sizeof(jint)));
    if (!argb) {
        free(rgb);
        return nullptr;
    }
    for (int y = 0; y < h; y++) {
        const unsigned char *row = reinterpret_cast<unsigned char *>(rgb) + static_cast<size_t>(y) * rowStride;
        jint *dst = argb + static_cast<size_t>(y) * w;
        for (int x = 0; x < w; x++) {
            unsigned int r = row[x * 3 + 0];
            unsigned int g = row[x * 3 + 1];
            unsigned int b = row[x * 3 + 2];
            dst[x] = static_cast<jint>(0xFF000000u | (r << 16) | (g << 8) | b);
        }
    }
    env->SetIntArrayRegion(out, 0, w * h, argb);
    free(argb);
    free(rgb);

    jint dims[2] = {w, h};
    env->SetIntArrayRegion(jOutDims, 0, 2, dims);
    return out;
}

JNIEXPORT void JNICALL
Java_com_nachtalb_anyfiledjvu_DjvuNative_nativeClose(JNIEnv *, jclass, jlong jhandle) {
    auto *handle = reinterpret_cast<void **>(jhandle);
    if (!handle) return;
    auto *ctx = static_cast<ddjvu_context_t *>(handle[0]);
    auto *doc = static_cast<ddjvu_document_t *>(handle[1]);
    if (doc) ddjvu_document_release(doc);
    if (ctx) ddjvu_context_release(ctx);
    free(handle);
}

} // extern "C"
