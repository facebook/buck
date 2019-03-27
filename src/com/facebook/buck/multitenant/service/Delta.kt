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

import com.facebook.buck.core.model.UnconfiguredBuildTarget
import java.nio.file.Path

/**
 * Represents a change between versions of a build package.
 */
internal sealed class BuildPackageDelta {
    /** Note that this is an "added" or "modified" package. */
    data class Updated(val directory: Path, val rules: Set<String>) : BuildPackageDelta()

    data class Removed(val directory: Path) : BuildPackageDelta()
}

/**
 * Represents a change between versions of a build rule. This should be extended to reflect a
 * change in any attribute of a build rule (including its type!) whereas currently this only
 * reflects a change in its deps.
 */
internal sealed class RuleDelta {
    /** Note that this is an "added" or "modified" rule. */
    data class Updated(val rule: InternalRawBuildRule) : RuleDelta()

    data class Removed(val buildTarget: UnconfiguredBuildTarget) : RuleDelta()
}
