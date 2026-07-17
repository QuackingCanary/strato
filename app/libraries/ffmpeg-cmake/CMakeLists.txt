# SPDX-License-Identifier: MPL-2.0
# Builds a minimal static FFmpeg (libavcodec + libavutil + libswscale) for NVDEC emulation
# The FFmpeg sources are expected as a git submodule at libraries/ffmpeg

include(ExternalProject)

set(FFMPEG_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../ffmpeg")
set(FFMPEG_PREFIX "${CMAKE_CURRENT_BINARY_DIR}/prefix")

if (NOT EXISTS "${FFMPEG_SOURCE_DIR}/configure")
    message(FATAL_ERROR "FFmpeg sources not found at ${FFMPEG_SOURCE_DIR}, run: git submodule update --init app/libraries/ffmpeg")
endif ()

set(FFMPEG_LIBS
        "${FFMPEG_PREFIX}/lib/libavcodec.a"
        "${FFMPEG_PREFIX}/lib/libswscale.a"
        "${FFMPEG_PREFIX}/lib/libavutil.a")

# The NDK toolchain sets CMAKE_C_COMPILER_TARGET (e.g. aarch64-none-linux-android29), which clang needs to cross-compile
set(FFMPEG_CFLAGS "--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT} -O3 -fPIC")

ExternalProject_Add(ffmpeg-build
        SOURCE_DIR "${FFMPEG_SOURCE_DIR}"
        CONFIGURE_COMMAND "${FFMPEG_SOURCE_DIR}/configure"
            "--prefix=${FFMPEG_PREFIX}"
            --enable-cross-compile
            --target-os=android
            --arch=aarch64
            --cpu=armv8-a
            "--cc=${CMAKE_C_COMPILER}"
            "--cxx=${CMAKE_CXX_COMPILER}"
            "--ld=${CMAKE_C_COMPILER}"
            "--ar=${CMAKE_AR}"
            "--ranlib=${CMAKE_RANLIB}"
            "--nm=${CMAKE_NM}"
            "--strip=${CMAKE_STRIP}"
            "--sysroot=${CMAKE_SYSROOT}"
            "--extra-cflags=${FFMPEG_CFLAGS}"
            "--extra-ldflags=--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT}"
            --enable-pic
            --enable-static
            --disable-shared
            --disable-all
            --disable-autodetect
            --disable-debug
            --disable-doc
            --disable-programs
            --disable-network
            --enable-avcodec
            --enable-swscale
            --enable-neon
            --enable-decoder=h264
            --enable-decoder=vp9
            --enable-parser=h264
            --enable-parser=vp9
        BUILD_COMMAND make -j${CMAKE_BUILD_PARALLEL_LEVEL}
        INSTALL_COMMAND make install
        BUILD_BYPRODUCTS ${FFMPEG_LIBS}
        BUILD_IN_SOURCE OFF
        LOG_CONFIGURE ON
        LOG_BUILD ON
        LOG_INSTALL ON)

# The include directory must exist at configure time for INTERFACE_INCLUDE_DIRECTORIES
file(MAKE_DIRECTORY "${FFMPEG_PREFIX}/include")

function(add_ffmpeg_library name)
    add_library(ffmpeg-${name} STATIC IMPORTED GLOBAL)
    set_target_properties(ffmpeg-${name} PROPERTIES
            IMPORTED_LOCATION "${FFMPEG_PREFIX}/lib/lib${name}.a"
            INTERFACE_INCLUDE_DIRECTORIES "${FFMPEG_PREFIX}/include")
    add_dependencies(ffmpeg-${name} ffmpeg-build)
endfunction()

add_ffmpeg_library(avcodec)
add_ffmpeg_library(swscale)
add_ffmpeg_library(avutil)

# Link order matters for static archives: avcodec and swscale both depend on avutil
target_link_libraries(ffmpeg-avcodec INTERFACE ffmpeg-avutil)
target_link_libraries(ffmpeg-swscale INTERFACE ffmpeg-avutil)
