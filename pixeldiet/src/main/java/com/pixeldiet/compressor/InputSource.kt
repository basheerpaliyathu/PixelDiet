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
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/**
 * A thing PixelDiet can read pixels from.
 *
 * Unlike the original Luban (which only accepted [File] and so broke under Android 10+ scoped
 * storage), every source here can be opened as an [InputStream] **repeatedly** — once to read the
 * header/EXIF, once to decode — which is what makes `content://` Uris from the Photo Picker,
 * MediaStore and SAF work.
 */
sealed class InputSource {

    /** Opens a fresh stream over the underlying bytes. The caller is responsible for closing it. */
    internal abstract fun openStream(context: Context): InputStream

    /** Byte length if cheaply known, else -1 (the engine will fall back to reading the stream). */
    internal abstract fun knownLength(context: Context): Long

    /** A human-ish name used to derive the output filename when no rename rule is supplied. */
    internal abstract fun displayName(context: Context): String?

    internal class FileSource(val file: File) : InputSource() {
        override fun openStream(context: Context): InputStream = file.inputStream()
        override fun knownLength(context: Context): Long = file.length()
        override fun displayName(context: Context): String = file.name
    }

    internal class UriSource(val uri: Uri) : InputSource() {
        override fun openStream(context: Context): InputStream =
            context.contentResolver.openInputStream(uri)
                ?: throw java.io.IOException("Cannot open stream for uri: $uri")

        override fun knownLength(context: Context): Long {
            // Try the provider's reported size; -1 means "unknown, read it".
            return runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else -1L
                    } ?: -1L
            }.getOrDefault(-1L)
        }

        override fun displayName(context: Context): String? = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getString(idx) else null
                }
        }.getOrNull() ?: uri.lastPathSegment
    }

    /** A caller-supplied, repeatable stream factory (must return a fresh stream on each call). */
    internal class StreamSource(val factory: () -> InputStream) : InputSource() {
        override fun openStream(context: Context): InputStream = factory()
        override fun knownLength(context: Context): Long = -1L
        override fun displayName(context: Context): String? = null
    }

    /** An already-decoded bitmap. Skips decoding entirely; only re-encoding/scaling is applied. */
    internal class BitmapSource(val bitmap: Bitmap) : InputSource() {
        override fun openStream(context: Context): InputStream =
            throw UnsupportedOperationException("BitmapSource is not stream-backed")
        override fun knownLength(context: Context): Long = -1L
        override fun displayName(context: Context): String? = null
    }
}
