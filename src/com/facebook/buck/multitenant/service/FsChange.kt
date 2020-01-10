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

package com.facebook.buck.multitenant.service

import com.facebook.buck.core.path.ForwardRelativePath
import com.facebook.buck.multitenant.service.FsChange.Added
import com.facebook.buck.multitenant.service.FsChange.Modified
import com.facebook.buck.multitenant.service.FsChange.Removed

/**
 * Represents a local change to a regular file from a user. For [Added] and [Modified],
 * `contents` may not be provided if the user is certain that the service does not need the contents
 * of the file to do its job.
 */
sealed class FsChange {
    data class Added(val path: ForwardRelativePath, val contents: ByteArray?) : FsChange()
    data class Modified(val path: ForwardRelativePath, val contents: ByteArray?) : FsChange()
    data class Removed(val path: ForwardRelativePath) : FsChange()
}

/**
 * A collection of local changes relative to a commit from source control. In practice, we expect
 * the commit to be on trunk / be indexed by the multitenant service.
 */
data class FsChanges(
    val commit: Commit,
    val added: List<Added> = emptyList(),
    val modified: List<Modified> = emptyList(),
    val removed: List<Removed> = emptyList()
)
