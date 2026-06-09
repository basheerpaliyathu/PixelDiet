/*
 * Copyright 2026 Basheer
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.pixeldiet.sample

import android.net.Uri
import java.io.File

/** One compression outcome — either a success or a failure. */
sealed class CompressResult {
    data class Success(
        val sourceUri: Uri,
        val outputFile: File,
        val originalBytes: Long,
        val compressedBytes: Long,
        val formatLabel: String,
        val gearLabel: String,
        val durationMs: Long,
    ) : CompressResult() {
        val savingsPct: Int
            get() = if (originalBytes > 0) (100 - compressedBytes * 100 / originalBytes).toInt() else 0
    }

    data class Failure(
        val sourceUri: Uri,
        val message: String,
    ) : CompressResult()
}
