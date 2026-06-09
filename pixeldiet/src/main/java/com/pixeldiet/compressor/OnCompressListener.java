/*
 * Copyright 2026 Basheer
 *
 * Derived from the OnCompressListener concept in Luban (Copyright 2016 Zheng Zibin) /
 * AdvancedLuban (Copyright 2016 shaohui10086), Apache License 2.0.
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
package com.pixeldiet.compressor;

import java.io.File;

/**
 * Callback for compressing a single image, delivered on the main thread.
 *
 * <p>Provided primarily for Java callers; Kotlin users can instead {@code suspend} on
 * {@code PixelDiet.with(ctx).load(...).get()}.
 */
public interface OnCompressListener {

    /** Fired (on the main thread) just before compression begins. */
    void onStart();

    /** Fired (on the main thread) with the compressed result file. */
    void onSuccess(File file);

    /** Fired (on the main thread) if compression fails. */
    void onError(Throwable e);
}
