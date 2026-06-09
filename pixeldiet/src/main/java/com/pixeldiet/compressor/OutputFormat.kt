/*
 * Copyright 2026 Basheer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.pixeldiet.compressor

import android.graphics.Bitmap
import android.os.Build

/**
 * Output encodings PixelDiet can produce.
 *
 * All of these use the Android platform encoder — PixelDiet bundles no native code, so there is
 * never a `.so` in the resulting AAR (and therefore no Android 15 16 KB page-size concern).
 *
 * WebP generally yields smaller files than JPEG at comparable visual quality, which is the main
 * "better compression" lever over the original JPEG-only Luban strategy.
 */
enum class OutputFormat {
    /** Baseline JPEG. Lossy. Smallest, most compatible default. */
    JPEG,

    /** PNG. Lossless — quality parameter is ignored; use only when transparency must be kept. */
    PNG,

    /** Lossy WebP. Typically ~25–30% smaller than JPEG at the same quality. */
    WEBP_LOSSY,

    /** Lossless WebP. Smaller than PNG while preserving transparency. */
    WEBP_LOSSLESS;

    /** File extension (without the dot) for this format. */
    val extension: String
        get() = when (this) {
            JPEG -> "jpg"
            PNG -> "png"
            WEBP_LOSSY, WEBP_LOSSLESS -> "webp"
        }

    /** True if re-encoding at a lower quality can shrink the file (i.e. the quality loop applies). */
    val isLossy: Boolean
        get() = this == JPEG || this == WEBP_LOSSY

    /**
     * Maps to a platform [Bitmap.CompressFormat], picking the API-appropriate constant.
     *
     * `WEBP_LOSSY` / `WEBP_LOSSLESS` exist from API 30; below that we fall back to the deprecated
     * but functional [Bitmap.CompressFormat.WEBP].
     */
    @Suppress("DEPRECATION")
    fun toCompressFormat(): Bitmap.CompressFormat = when (this) {
        JPEG -> Bitmap.CompressFormat.JPEG
        PNG -> Bitmap.CompressFormat.PNG
        WEBP_LOSSY ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
            else Bitmap.CompressFormat.WEBP
        WEBP_LOSSLESS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS
            else Bitmap.CompressFormat.WEBP
    }
}
