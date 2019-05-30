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

package com.facebook.buck.multitenant.service

import com.facebook.buck.core.exceptions.BuildTargetParseException
import com.facebook.buck.core.model.ImmutableInternedUnconfiguredBuildTarget
import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser
import com.facebook.buck.multitenant.fs.FsAgnosticPath

/**
 * Collection of convenience methods for parsing build targets. Returned build targets are strongly
 * interned to make `equals` calls faster in order to speed up maps/set data structure operations.
 */
object BuildTargets {
    fun createBuildTargetFromParts(cell: String, basePath: FsAgnosticPath, name: String): UnconfiguredBuildTarget =
            ImmutableInternedUnconfiguredBuildTarget.of(
                    cell,
                    "//$basePath",
                    name,
                    UnconfiguredBuildTarget.NO_FLAVORS)

    fun createBuildTargetFromParts(basePath: FsAgnosticPath, name: String): UnconfiguredBuildTarget =
            createBuildTargetFromParts("", basePath, name)

    /**
     * @param target must be a fully-qualified build target
     * @throws BuildTargetParseException
     */
    fun parseOrThrow(target: String): UnconfiguredBuildTarget {
        return UnconfiguredBuildTargetParser.parse(target, true)
    }
}
