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

package com.facebook.buck.multitenant.query

import com.facebook.buck.core.path.ForwardRelativePath
import com.facebook.buck.core.sourcepath.SourcePath
import com.facebook.buck.multitenant.fs.FsAgnosticPath

/**
 * Implementation of [SourcePath] that makes sense in the context of
 * `com.facebook.buck.multitenant`. It is designed to be used with
 * [com.facebook.buck.query.QueryFileTarget].
 */
data class FsAgnosticSourcePath(private val path: ForwardRelativePath) : SourcePath {
    companion object {
        /**
         * @param path must be a normalized, relative path.
         */
        fun of(path: String): FsAgnosticSourcePath = FsAgnosticSourcePath(FsAgnosticPath.of(path))
    }

    override fun compareTo(other: SourcePath): Int {
        if (this === other) {
            return 0
        }

        val classComparison = compareClasses(other)
        if (classComparison != 0) {
            return classComparison
        }

        val that = other as FsAgnosticSourcePath
        return path.compareTo(that.path)
    }

    override fun toString(): String = path.toString()
}
