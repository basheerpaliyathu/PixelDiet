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

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** A repeatable stream factory (Java-friendly single-method interface). */
fun interface StreamProvider {
    fun open(): InputStream
}

/**
 * PixelDiet — a pure-JVM Android image compressor.
 *
 * No native libraries are bundled, so the output AAR contains no `.so` and is unaffected by the
 * Android 15 16 KB page-size requirement. The sizing heuristics are the proven WeChat-Moments-style
 * strategy from Luban; the output can additionally be WebP for smaller files than JPEG.
 *
 * Kotlin:
 * ```
 * val files = PixelDiet.with(context)
 *     .load(uri)
 *     .format(OutputFormat.WEBP_LOSSY)
 *     .get()
 * ```
 *
 * Java:
 * ```
 * PixelDiet.with(context).load(uri).launch(new OnCompressListener() { ... });
 * ```
 */
object PixelDiet {

    const val GEAR_FIRST = CompressionStrategy.GEAR_FIRST
    const val GEAR_THIRD = CompressionStrategy.GEAR_THIRD
    const val GEAR_CUSTOM = CompressionStrategy.GEAR_CUSTOM

    @JvmStatic
    fun with(context: Context): Request = Request(context.applicationContext)
}

/**
 * Fluent request builder. Mutating methods return `this` for chaining; terminal operations are
 * [get]/[getFirst] (Kotlin coroutines), [asFlow] (Kotlin Flow), or [launch] (Java callbacks).
 */
class Request internal constructor(private val appContext: Context) {

    private val sources = mutableListOf<InputSource>()

    private var gear: Int = CompressionStrategy.GEAR_THIRD
    private var format: OutputFormat = OutputFormat.JPEG
    private var ignoreByKb: Int = 0
    private var targetDir: File? = null
    private var maxSize: Int = 0
    private var maxWidth: Int = 0
    private var maxHeight: Int = 0
    private var hardCapKb: Int = 0
    private var renamer: Renamer? = null

    // --- inputs -------------------------------------------------------------------------------

    fun load(file: File): Request = apply { sources.add(InputSource.FileSource(file)) }
    fun load(uri: Uri): Request = apply { sources.add(InputSource.UriSource(uri)) }
    fun load(bitmap: Bitmap): Request = apply { sources.add(InputSource.BitmapSource(bitmap)) }
    fun loadStream(provider: StreamProvider): Request =
        apply { sources.add(InputSource.StreamSource { provider.open() }) }

    fun loadFiles(files: List<File>): Request =
        apply { files.forEach { sources.add(InputSource.FileSource(it)) } }

    fun loadUris(uris: List<Uri>): Request =
        apply { uris.forEach { sources.add(InputSource.UriSource(it)) } }

    // --- configuration ------------------------------------------------------------------------

    /**
     * Compression intensity gear. One of [PixelDiet.GEAR_FIRST], [PixelDiet.GEAR_THIRD]
     * (default), [PixelDiet.GEAR_CUSTOM].
     *
     * Note: calling [maxWidth] / [maxHeight] automatically switches to [PixelDiet.GEAR_CUSTOM]
     * because those are dimension decisions. [maxSize] / [hardCap] do NOT force GEAR_CUSTOM —
     * they act as a size cap on top of whichever gear you chose.
     */
    fun gear(gear: Int): Request = apply { this.gear = gear }

    /** Alias for [gear] — matches the AdvancedLuban `putGear()` name for easy migration. */
    fun putGear(gear: Int): Request = gear(gear)

    fun format(format: OutputFormat): Request = apply { this.format = format }

    /** Skip (pass through) any source smaller than this many KB. */
    fun ignoreBy(kb: Int): Request = apply { this.ignoreByKb = kb }

    fun setTargetDir(dir: File): Request = apply { this.targetDir = dir }

    /**
     * Drive the CUSTOM gear's initial geometry sizing to aim for [kb] KB output.
     * Combine with [hardCap] for an absolute guarantee, or use [hardCap] alone on any gear
     * to enforce a cap without changing the sizing strategy.
     *
     * Unlike Luban 2 — which has **no target-size API** — PixelDiet actually honours size
     * targets through a two-phase quality-loop + resize-fallback in the encoder.
     */
    fun maxSize(kb: Int): Request = apply { this.maxSize = kb; if (kb > 0) this.gear = CompressionStrategy.GEAR_CUSTOM }

    /**
     * Hard output-size cap in KB, applied **on top of any gear**.
     *
     * This is independent of [gear] — you can use the smart THIRD_GEAR sizing and still cap the
     * result: `gear(GEAR_THIRD).hardCap(300)`.
     *
     * The encoder will reduce quality and, if needed, iteratively scale the bitmap down until the
     * output fits within [kb] KB or the image becomes too small to shrink further.
     *
     * This is the primary feature PixelDiet offers that Luban 2 does not.
     */
    fun hardCap(kb: Int): Request = apply { this.hardCapKb = kb }

    /** [GEAR_CUSTOM] max output width in px. */
    fun maxWidth(px: Int): Request = apply { this.maxWidth = px; if (px > 0) this.gear = CompressionStrategy.GEAR_CUSTOM }

    /** [GEAR_CUSTOM] max output height in px. */
    fun maxHeight(px: Int): Request = apply { this.maxHeight = px; if (px > 0) this.gear = CompressionStrategy.GEAR_CUSTOM }

    fun setRenameListener(renamer: Renamer): Request = apply { this.renamer = renamer }

    private fun config() = CompressConfig(
        gear = gear,
        format = format,
        ignoreByKb = ignoreByKb,
        targetDir = targetDir,
        maxSize = maxSize,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        hardCapKb = hardCapKb,
        renamer = renamer,
    )

    private fun requireSources() {
        check(sources.isNotEmpty()) { "No input supplied — call load(...) before compressing." }
    }

    // --- terminal: Kotlin coroutines ----------------------------------------------------------

    /** Compresses all sources on [Dispatchers.IO]; results are returned in input order. */
    suspend fun get(): List<File> {
        requireSources()
        val cfg = config()
        return withContext(Dispatchers.IO) {
            sources.mapIndexed { index, source -> Engine.compress(appContext, source, cfg, index) }
        }
    }

    /** Convenience for a single input. */
    suspend fun getFirst(): File {
        requireSources()
        val cfg = config()
        return withContext(Dispatchers.IO) { Engine.compress(appContext, sources.first(), cfg, 0) }
    }

    /** Emits each compressed file as it completes (useful for progress UIs). */
    fun asFlow(): Flow<File> {
        requireSources()
        val cfg = config()
        val snapshot = sources.toList()
        return flow {
            snapshot.forEachIndexed { index, source ->
                emit(Engine.compress(appContext, source, cfg, index))
            }
        }.flowOn(Dispatchers.IO)
    }

    // --- terminal: Java-friendly callbacks ----------------------------------------------------

    /** Compresses the first source and reports on the main thread. Returns a handle to cancel. */
    fun launch(listener: OnCompressListener): Cancelable {
        val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        val job = scope.launch {
            listener.onStart()
            runCatching { getFirst() }
                .onSuccess { listener.onSuccess(it) }
                .onFailure { listener.onError(it) }
        }
        return Cancelable(job)
    }

    /** Compresses all sources and reports on the main thread. Returns a handle to cancel. */
    fun launch(listener: OnMultiCompressListener): Cancelable {
        val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        val job = scope.launch {
            listener.onStart()
            runCatching { get() }
                .onSuccess { listener.onSuccess(it) }
                .onFailure { listener.onError(it) }
        }
        return Cancelable(job)
    }
}

/** A cancellation handle for [Request.launch]; Java-friendly wrapper over a coroutine [Job]. */
class Cancelable internal constructor(private val job: Job) {
    fun cancel() {
        job.cancel()
    }

    val isActive: Boolean get() = job.isActive
}
