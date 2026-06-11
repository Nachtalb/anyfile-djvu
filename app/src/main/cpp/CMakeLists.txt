# SPDX-License-Identifier: GPL-2.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(anyfile_djvu LANGUAGES C CXX)

# --- DjVuLibre (vendored source, GPL v2) --------------------------------------
# We compile the libdjvu core from source for every ABI rather than shipping a
# prebuilt .a, so the build is reproducible and CI-friendly. Two non-obvious
# requirements discovered during the Phase 0 cross-compile spike:
#   1. -std=c++14 — the 2018-era DjVuLibre source uses the `register` keyword,
#      which is a hard ERROR under the C++17 default of NDK r28's clang.
#   2. config.h ships with NEED_JPEG_DECODER undefined (we patched the generated
#      header) so libdjvu does not pull in libjpeg — Android has no system libjpeg
#      and the JPEG-embedded-in-DjVu path is a rare feature we don't need.
file(GLOB DJVU_SOURCES "${CMAKE_SOURCE_DIR}/djvulibre/*.cpp")

add_library(djvulibre STATIC ${DJVU_SOURCES})

target_include_directories(djvulibre PUBLIC "${CMAKE_SOURCE_DIR}/djvulibre")

target_compile_definitions(djvulibre PRIVATE
        HAVE_CONFIG_H=1
        # Normally injected by the autotools Makefile. DIR_DATADIR points at the
        # installed locale/message-catalog tree (DjVuMessage). There is no such
        # install path on Android and the catalogs are not used for headless decode,
        # so a placeholder satisfies the source without shipping anything.
        "DIR_DATADIR=\"/data/local/djvu\"")

target_compile_options(djvulibre PRIVATE
        -std=c++14            # required: see note above (register keyword)
        -O2
        -fPIC
        -DNDEBUG
        -w)                   # silence the legacy lib's warnings (not our code)

set_target_properties(djvulibre PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON)

# --- JNI bridge ---------------------------------------------------------------
add_library(djvujni SHARED djvu_jni.cpp)

target_include_directories(djvujni PRIVATE "${CMAKE_SOURCE_DIR}/djvulibre")

find_library(log-lib log)

target_link_libraries(djvujni
        djvulibre
        ${log-lib})
