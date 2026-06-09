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

/**
 * Decides the output file name (without directory) for a given source.
 *
 * Implemented as a `fun interface` so Kotlin callers can pass a lambda and Java callers can pass a
 * single-method anonymous class. Return value should include the desired base name; the correct
 * extension for the chosen [OutputFormat] is appended automatically if missing.
 */
fun interface Renamer {
    /** @param sourceName best-effort original name (may be null for streams/bitmaps). */
    fun rename(sourceName: String?): String
}
