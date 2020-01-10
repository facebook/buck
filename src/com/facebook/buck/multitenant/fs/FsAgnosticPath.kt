/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.multitenant.fs

import com.facebook.buck.core.path.ForwardRelativePath
import com.facebook.buck.multitenant.cache.AppendOnlyBidirectionalCache
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder

/**
 * Cache between path [String] and [ForwardRelativePath] wrapper around this [String] value.
 * Soft references are used.
 * Softly-referenced objects will be garbage-collected in a <i>globally</i> least-recently-used manner,
 * in response to memory demand.
 */
private val PATH_CACHE: Cache<String, ForwardRelativePath> =
    CacheBuilder.newBuilder().softValues().build()

/**
 * Cache between [ForwardRelativePath] to unique [Int] value.
 */
private val PATH_TO_INDEX_CACHE = AppendOnlyBidirectionalCache<ForwardRelativePath>()

object FsAgnosticPath {
    fun fromIndex(index: Int): ForwardRelativePath = PATH_TO_INDEX_CACHE.getByIndex(index)

    fun toIndex(fsAgnosticPath: ForwardRelativePath): Int = PATH_TO_INDEX_CACHE.get(fsAgnosticPath)

    /**
     * @param path must be a normalized, relative path.
     */
    fun of(path: String): ForwardRelativePath {
        return PATH_CACHE.getIfPresent(path) ?: run {
            val pathObject = ForwardRelativePath.of(path)
            PATH_CACHE.put(path, pathObject)
            pathObject
        }
    }
}
