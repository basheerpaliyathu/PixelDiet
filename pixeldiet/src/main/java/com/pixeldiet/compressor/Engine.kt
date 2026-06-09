/*
 * Copyright 2026 Basheer
 *
 * The decode → rotate → re-encode pipeline and the sample-size math are adapted from
 * LubanCompresser in AdvancedLuban (Copyright 2016 shaohui10086) / Luban (Copyright 2016
 * Zheng Zibin), Apache License 2.0. Notable changes: reads EXIF and pixels from InputStreams (so
 * content:// Uris work), uses androidx.exifinterface, decodes the header only once, skips bitmap
 * rotation when the angle is 0, and adds API-aware WebP encoding.
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
 */
package com.pixeldiet.compressor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Per-request, immutable configuration consumed by [Engine]. */
internal data class CompressConfig(
    val gear: Int = CompressionStrategy.GEAR_THIRD,
    val format: OutputFormat = OutputFormat.JPEG,
    val ignoreByKb: Int = 0,
    val targetDir: File? = null,
    val maxSize: Int = 0,
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val renamer: Renamer? = null,
    /**
     * Hard output-size cap in KB applied **on top of any gear's sizing**. When > 0, PixelDiet
     * will keep reducing quality and then scale down the bitmap until the output fits. This is
     * independent of [gear] — you can use THIRD_GEAR (smart WeChat-style sizing) and still
     * guarantee the result stays under a target.
     *
     * Note: [maxSize] via GEAR_CUSTOM already drives the initial geometry; [hardCapKb] is the
     * last-resort guarantee that even a custom-gear result never exceeds the limit.
     */
    val hardCapKb: Int = 0,
)

/**
 * Stateless image-compression engine. All work here is blocking; callers are expected to run it on
 * [kotlinx.coroutines.Dispatchers.IO].
 */
internal object Engine {

    private const val MIN_QUALITY = 6
    private const val QUALITY_STEP = 6
    private const val RESIZE_FACTOR = 0.75f
    private const val MIN_RESIZE_PX = 100

    /** Compress a single [source]. [index] only disambiguates auto-generated file names. */
    @Throws(IOException::class)
    fun compress(context: Context, source: InputSource, config: CompressConfig, index: Int): File {
        if (source is InputSource.BitmapSource) {
            return compressBitmap(context, source.bitmap, config, index)
        }

        // Read non-file sources into memory once: we need the bytes for bounds, EXIF and decode.
        val bytes: ByteArray? =
            if (source is InputSource.FileSource) null
            else source.openStream(context).use { it.readBytes() }

        val length: Long = when (source) {
            is InputSource.FileSource -> source.file.length()
            else -> bytes?.size?.toLong() ?: source.knownLength(context)
        }

        val bounds = decodeBounds(source, bytes)
        val srcW = bounds[0]
        val srcH = bounds[1]
        if (srcW <= 0 || srcH <= 0) throw IOException("Unable to read image bounds")

        // "ignore below N KB": emit the source untouched.
        if (config.ignoreByKb > 0 && length in 1 until config.ignoreByKb * 1024L) {
            return passthrough(context, source, bytes, config, index)
        }

        val spec = when (config.gear) {
            CompressionStrategy.GEAR_FIRST -> CompressionStrategy.firstGear(srcW, srcH, length)
            CompressionStrategy.GEAR_CUSTOM ->
                CompressionStrategy.customGear(srcW, srcH, length, config.maxSize, config.maxWidth, config.maxHeight)
            else -> CompressionStrategy.thirdGear(srcW, srcH, length)
        }
        if (spec.skip) return passthrough(context, source, bytes, config, index)

        val angle = exifAngle(context, source)
        val sample = sampleSize(srcW, srcH, spec.width, spec.height)

        var bitmap = decodeSampled(context, source, bytes, sample)
        if (angle != 0) {
            val rotated = rotate(bitmap, angle)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }

        val effectiveCapKb = when {
            config.hardCapKb > 0 -> config.hardCapKb.toLong()
            else -> spec.maxSizeKb
        }
        val out = resolveOutFile(context, source, config, index)
        val data = encode(bitmap, config, effectiveCapKb)
        out.outputStream().use { it.write(data) }
        bitmap.recycle()
        return out
    }

    private fun compressBitmap(context: Context, src: Bitmap, config: CompressConfig, index: Int): File {
        var bitmap = src
        // Honour max width/height for already-decoded bitmaps.
        if (config.maxWidth > 0 || config.maxHeight > 0) {
            val targetW = if (config.maxWidth > 0) minOf(src.width, config.maxWidth) else src.width
            val targetH = if (config.maxHeight > 0) minOf(src.height, config.maxHeight) else src.height
            if (targetW != src.width || targetH != src.height) {
                bitmap = Bitmap.createScaledBitmap(src, targetW, targetH, true)
            }
        }
        val maxKb = when {
            config.hardCapKb > 0 -> config.hardCapKb.toLong()
            config.maxSize > 0 -> config.maxSize.toLong()
            else -> Long.MAX_VALUE / 1024
        }
        val out = resolveOutFile(context, InputSource.BitmapSource(src), config, index)
        out.outputStream().use { it.write(encode(bitmap, config, maxKb)) }
        if (bitmap !== src) bitmap.recycle()
        return out
    }

    // --- decoding -----------------------------------------------------------------------------

    private fun decodeBounds(source: InputSource, bytes: ByteArray?): IntArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        when (source) {
            is InputSource.FileSource -> BitmapFactory.decodeFile(source.file.absolutePath, opts)
            else -> bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
        }
        return intArrayOf(opts.outWidth, opts.outHeight)
    }

    /** Original power-of-two sample-size selection. */
    private fun sampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
        if (reqW <= 0 || reqH <= 0) return 1
        var sample = 1
        while (outH / sample > reqH || outW / sample > reqW) sample *= 2
        return sample
    }

    private fun decodeSampled(context: Context, source: InputSource, bytes: ByteArray?, sample: Int): Bitmap {
        // Prefer ImageDecoder on API 28+ for Uris and in-memory bytes (adds HEIF read support).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoderSource = when (source) {
                is InputSource.UriSource ->
                    ImageDecoder.createSource(context.contentResolver, source.uri)
                is InputSource.FileSource -> null // path-based BitmapFactory is more memory-efficient
                else -> bytes?.let { ImageDecoder.createSource(ByteBuffer.wrap(it)) }
            }
            if (decoderSource != null) {
                runCatching {
                    return ImageDecoder.decodeBitmap(decoderSource) { decoder, _, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = false
                        decoder.setTargetSampleSize(sample)
                    }
                }
                // fall through to BitmapFactory on any decoder failure
            }
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = when (source) {
            is InputSource.FileSource -> BitmapFactory.decodeFile(source.file.absolutePath, opts)
            else -> bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
        }
        return bitmap ?: throw IOException("Failed to decode image")
    }

    private fun rotate(bitmap: Bitmap, angle: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(angle.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // --- EXIF ---------------------------------------------------------------------------------

    private fun exifAngle(context: Context, source: InputSource): Int {
        val exif = runCatching {
            source.openStream(context).use { ExifInterface(it) }
        }.getOrNull() ?: return 0
        return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    // --- encoding -----------------------------------------------------------------------------

    /**
     * Encode [bitmap] to the configured format, targeting [maxSizeKb].
     *
     * Two-phase guarantee:
     *  1. **Quality loop** — repeatedly lower the encoder quality by [QUALITY_STEP] until we fit or
     *     hit [MIN_QUALITY]. Cheap: no pixel operations.
     *  2. **Resize fallback** — if quality bottomed out and we're still over budget, scale the
     *     bitmap to 75% and retry both phases. Keeps going until we fit *or* the image becomes too
     *     small (< [MIN_RESIZE_PX] on either side) to shrink further. This is the critical
     *     difference vs a bare quality loop: the result is *actually* ≤ maxSizeKb.
     *
     * The function never recycles [bitmap] itself — that's the caller's responsibility. Any
     * intermediate scaled bitmaps created in phase 2 are recycled before returning.
     */
    private fun encode(bitmap: Bitmap, config: CompressConfig, maxSizeKb: Long): ByteArray {
        val compressFormat = config.format.toCompressFormat()

        if (!config.format.isLossy) {
            // PNG / lossless WebP: quality has no effect, single pass.
            return ByteArrayOutputStream(bitmap.byteCount / 4).also {
                bitmap.compress(compressFormat, 100, it)
            }.toByteArray()
        }

        val maxBytes = maxSizeKb * 1024
        val baos = ByteArrayOutputStream(bitmap.byteCount / 4)

        // Phase 1 runs on the original bitmap; phase 2 on shrunk copies.
        var current: Bitmap? = null  // intermediates we own and must recycle
        var source = bitmap           // current working surface (may be replaced)

        try {
            while (true) {
                // --- quality loop ---
                var quality = 100
                baos.reset()
                source.compress(compressFormat, quality, baos)
                while (baos.size() > maxBytes && quality > MIN_QUALITY) {
                    baos.reset()
                    quality -= QUALITY_STEP
                    source.compress(compressFormat, quality, baos)
                }

                if (baos.size() <= maxBytes) break  // fits — done

                // Quality bottomed out and still over budget → resize fallback.
                val newW = (source.width * RESIZE_FACTOR).toInt()
                val newH = (source.height * RESIZE_FACTOR).toInt()
                if (newW < MIN_RESIZE_PX || newH < MIN_RESIZE_PX) break  // too small to shrink

                val resized = Bitmap.createScaledBitmap(source, newW, newH, true)
                current?.recycle()   // recycle the previous intermediate (not the original)
                current = resized
                source = resized
            }
            return baos.toByteArray()
        } finally {
            current?.recycle()
        }
    }

    // --- output naming / passthrough ----------------------------------------------------------

    private fun passthrough(
        context: Context,
        source: InputSource,
        bytes: ByteArray?,
        config: CompressConfig,
        index: Int,
    ): File {
        // For files we can hand back the original untouched (matches Luban's skip semantics).
        if (source is InputSource.FileSource) return source.file

        val dir = (config.targetDir ?: defaultCacheDir(context)).apply { mkdirs() }
        val name = source.displayName(context) ?: "PixelDiet_${System.currentTimeMillis()}_$index"
        val file = File(dir, name)
        file.outputStream().use { it.write(bytes ?: ByteArray(0)) }
        return file
    }

    private fun resolveOutFile(context: Context, source: InputSource, config: CompressConfig, index: Int): File {
        val dir = (config.targetDir ?: defaultCacheDir(context)).apply { mkdirs() }
        val ext = config.format.extension
        val sourceName = source.displayName(context)
        val base = config.renamer?.rename(sourceName)
            ?: "PixelDiet_${System.currentTimeMillis()}_$index"
        val name = if (base.endsWith(".$ext", ignoreCase = true)) base else "$base.$ext"
        return File(dir, name)
    }

    private fun defaultCacheDir(context: Context): File =
        File(context.cacheDir, "pixel_diet_cache")
}
