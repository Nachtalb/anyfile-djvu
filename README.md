# anyfile-djvu

A **headless DjVu → PDF decode companion** for [AnyFile](https://github.com/Nachtalb/anyfile).

This app has **no user interface** — no Activity, no launcher icon. It exists solely
to be bound by AnyFile (or another permitted client) over an AIDL service, convert a
`.djvu` document to PDF fully offline on-device, and hand the PDF back as a file
descriptor.

## Why it's a separate, GPL-licensed app

DjVu decoding has **no permissive implementation** — every real decoder descends from
[DjVuLibre](https://djvu.sourceforge.net/), which is **GPL v2**. Bundling it directly
into AnyFile (a proprietary app) would force the whole app under the GPL.

So the GPL engine is **isolated in this separate, open-source app**. AnyFile calls it
over arm's-length Binder IPC: only *data* crosses the boundary (a DjVu fd in, a PDF fd
out). AnyFile never links or ships a byte of the GPL code, and stays proprietary. This
app, bundling DjVuLibre, is correctly licensed **GPL-2.0-or-later** — see `LICENSE`.

## Architecture

```
AnyFile  ──bind IConverter (neutral AIDL)──►  DjvuDecodeService (headless)
         ◄─── onResult(pdfFd) ──────────    └─ DjVuLibre (vendored, GPL) via JNI
                                              └─ render pages → PdfDocument (framework)
```

- **`app/src/main/cpp/djvulibre/`** — vendored DjVuLibre 3.5.28 source (GPL v2),
  compiled per-ABI by CMake. Decode-only; `libjpeg`/`libtiff` deps removed.
- **`app/src/main/cpp/djvu_jni.cpp`** — JNI bridge: open, page count, render page →
  ARGB_8888 buffer.
- **`PdfAssembler.kt`** — stitches rendered pages into a PDF with the framework
  `android.graphics.pdf.PdfDocument` (no third-party PDF lib).
- **`DjvuDecodeService.kt`** — the only component; serializes conversions on a
  single-thread executor.
- **`*.aidl`** — the inter-app contract (authored here, duplicated verbatim into
  AnyFile; each side generates its own Binder stub).

## Build

System Gradle (no wrapper), AGP 9.x, NDK with CMake. Debug build adds an x86_64 slice
for the emulator; release is arm-only.

```
gradle :app:assembleDebug
```

## Licensing notes

- This app: **GPL-2.0-or-later** (bundles DjVuLibre). Full source is this repository.
- DjVuLibre copyright/authors notices: `app/src/main/cpp/djvulibre/COPYRIGHT` and
  `AUTHORS`.
- The `.aidl` interface is original glue authored for this project, not derived from
  DjVuLibre.
