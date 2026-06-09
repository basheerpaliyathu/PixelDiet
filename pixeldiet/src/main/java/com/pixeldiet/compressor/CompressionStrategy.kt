/*
 * Copyright 2026 Basheer
 *
 * The "gear" sizing heuristics below are a faithful port of the image-compression strategy
 * originated in Luban (Copyright 2016 Zheng Zibin / Curzibn, https://github.com/Curzibn/Luban) and
 * carried into AdvancedLuban (Copyright 2016 shaohui10086). Both are licensed under the Apache
 * License, Version 2.0. This file adapts that algorithm to Kotlin as pure, side-effect-free
 * functions; the arithmetic is preserved so the visual/size behaviour matches the original.
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

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The target geometry + size budget produced by a [CompressionStrategy] gear.
 *
 * @param width  target width in the *original* orientation (used only to derive the sample size).
 * @param height target height in the *original* orientation.
 * @param maxSizeKb the re-encode quality loop drives the output below this many KB.
 * @param skip when true the source is already small enough; emit it unchanged.
 */
data class CompressSpec(
    val width: Int,
    val height: Int,
    val maxSizeKb: Long,
    val skip: Boolean = false,
) {
    companion object {
        fun skip() = CompressSpec(0, 0, 0, skip = true)
    }
}

/**
 * Pure sizing math. No Android types — fully unit-testable on the JVM.
 *
 * Gears mirror the original library:
 *  - [thirdGear]: the default WeChat-Moments-style heuristic.
 *  - [firstGear]: an aggressive single-pass downscale.
 *  - [customGear]: caller-driven max size / max width / max height.
 */
object CompressionStrategy {

    const val GEAR_FIRST = 1
    const val GEAR_THIRD = 3
    const val GEAR_CUSTOM = 4

    /** Default WeChat-style strategy. [fileLength] is the source size in bytes. */
    fun thirdGear(srcWidth: Int, srcHeight: Int, fileLength: Long): CompressSpec {
        val flip = srcWidth > srcHeight
        var thumbW = if (srcWidth % 2 == 1) srcWidth + 1 else srcWidth
        var thumbH = if (srcHeight % 2 == 1) srcHeight + 1 else srcHeight

        // Normalise to portrait: width = shorter side, height = longer side.
        val width = if (thumbW > thumbH) thumbH else thumbW
        val height = if (thumbW > thumbH) thumbW else thumbH
        val scale = width.toDouble() / height

        var size: Double
        if (scale <= 1 && scale > 0.5625) {
            when {
                height < 1664 -> {
                    if (fileLength / 1024 < 150) return CompressSpec.skip()
                    size = (width * height) / Math.pow(1664.0, 2.0) * 150
                    size = if (size < 60) 60.0 else size
                }
                height < 4990 -> {
                    thumbW = width / 2
                    thumbH = height / 2
                    size = (thumbW * thumbH) / Math.pow(2495.0, 2.0) * 300
                    size = if (size < 60) 60.0 else size
                }
                height < 10240 -> {
                    thumbW = width / 4
                    thumbH = height / 4
                    size = (thumbW * thumbH) / Math.pow(2560.0, 2.0) * 300
                    size = if (size < 100) 100.0 else size
                }
                else -> {
                    val multiple = if (height / 1280 == 0) 1 else height / 1280
                    thumbW = width / multiple
                    thumbH = height / multiple
                    size = (thumbW * thumbH) / Math.pow(2560.0, 2.0) * 300
                    size = if (size < 100) 100.0 else size
                }
            }
        } else if (scale <= 0.5625 && scale > 0.5) {
            if (height < 1280 && fileLength / 1024 < 200) return CompressSpec.skip()
            val multiple = if (height / 1280 == 0) 1 else height / 1280
            thumbW = width / multiple
            thumbH = height / multiple
            size = (thumbW * thumbH) / (1440.0 * 2560.0) * 400
            size = if (size < 100) 100.0 else size
        } else {
            val multiple = ceil(height / (1280.0 / scale)).toInt()
            thumbW = width / multiple
            thumbH = height / multiple
            size = (thumbW * thumbH) / (1280.0 * (1280 / scale)) * 500
            size = if (size < 100) 100.0 else size
        }

        // Return dimensions back in the source's original orientation.
        val outW = if (flip) thumbH else thumbW
        val outH = if (flip) thumbW else thumbH
        return CompressSpec(outW, outH, size.toLong())
    }

    /** Aggressive single-pass gear. */
    fun firstGear(srcWidth: Int, srcHeight: Int, fileLength: Long): CompressSpec {
        val minSize = 60L
        val longSide = 720
        val shortSide = 1280
        val maxSize = fileLength / 5

        var size = 0L
        var width = 0
        var height = 0
        if (srcWidth <= srcHeight) {
            val scale = srcWidth.toDouble() / srcHeight.toDouble()
            if (scale <= 1.0 && scale > 0.5625) {
                width = if (srcWidth > shortSide) shortSide else srcWidth
                height = width * srcHeight / srcWidth
                size = minSize
            } else if (scale <= 0.5625) {
                height = if (srcHeight > longSide) longSide else srcHeight
                width = height * srcWidth / srcHeight
                size = maxSize
            }
        } else {
            val scale = srcHeight.toDouble() / srcWidth.toDouble()
            if (scale <= 1.0 && scale > 0.5625) {
                height = if (srcHeight > shortSide) shortSide else srcHeight
                width = height * srcWidth / srcHeight
                size = minSize
            } else if (scale <= 0.5625) {
                width = if (srcWidth > longSide) longSide else srcWidth
                height = width * srcHeight / srcWidth
                size = maxSize
            }
        }
        return CompressSpec(width, height, size)
    }

    /**
     * Caller-driven gear.
     *
     * @param maxSize target max output size in KB (<=0 to ignore).
     * @param maxWidth clamp output width (<=0 to ignore).
     * @param maxHeight clamp output height (<=0 to ignore).
     */
    fun customGear(
        srcWidth: Int,
        srcHeight: Int,
        fileLength: Long,
        maxSize: Int,
        maxWidth: Int,
        maxHeight: Int,
    ): CompressSpec {
        val fileSizeKb: Long =
            if (maxSize > 0 && maxSize < fileLength / 1024) maxSize.toLong() else fileLength / 1024

        var width = srcWidth
        var height = srcHeight

        if (maxSize > 0 && maxSize < fileLength / 1024f) {
            val scale = sqrt(fileLength / 1024f / maxSize).toDouble()
            width = (width / scale).toInt()
            height = (height / scale).toInt()
        }
        if (maxWidth > 0) width = min(width, maxWidth)
        if (maxHeight > 0) height = min(height, maxHeight)

        val scale = min(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
        width = (srcWidth * scale).toInt()
        height = (srcHeight * scale).toInt()

        if (maxSize > fileLength / 1024f && scale == 1f) return CompressSpec.skip()

        return CompressSpec(width, height, fileSizeKb)
    }
}
