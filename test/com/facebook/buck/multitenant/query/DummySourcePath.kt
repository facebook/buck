/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.multitenant.query

import com.facebook.buck.core.sourcepath.SourcePath

/**
 * Implementation of [SourcePath] that is something other than [FsAgnosticSourcePath] so that we can
 * exercise the "compareClasses" logic in [FsAgnosticSourcePath.compareTo] in a unit test.
 *
 * Ideally, we would just use [com.facebook.buck.core.sourcepath.PathSourcePath], but that requires
 * a [com.facebook.buck.io.filesystem.ProjectFilesystem], and it appears to be incredibly difficult
 * to make [com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem] available to a unit test in
 * the multitenant module in IntelliJ. Getting it to work with `buck test` is not an issue, though.
 */
class DummySourcePath(val path: String) : SourcePath {
    override fun compareTo(other: SourcePath): Int {
        if (this === other) {
            return 0
        }

        val classComparison = compareClasses(other)
        if (classComparison != 0) {
            return classComparison
        }

        val that = other as DummySourcePath
        return path.compareTo(that.path)
    }
}
