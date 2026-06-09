/*
 * Copyright 2026 Basheer
 *
 * Derived from the OnMultiCompressListener concept in AdvancedLuban (Copyright 2016 shaohui10086),
 * itself derived from Luban (Copyright 2016 Zheng Zibin). Apache License 2.0.
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
import java.util.List;

/**
 * Callback for compressing multiple images, delivered on the main thread.
 *
 * <p>{@link #onSuccess(List)} reports the results in the same order the sources were supplied.
 */
public interface OnMultiCompressListener {

    /** Fired (on the main thread) just before compression begins. */
    void onStart();

    /** Fired (on the main thread) with all compressed result files, in input order. */
    void onSuccess(List<File> files);

    /** Fired (on the main thread) if compression fails. */
    void onError(Throwable e);
}
