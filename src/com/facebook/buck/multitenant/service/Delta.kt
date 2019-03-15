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

import java.nio.file.Path
import java.util.*

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
    data class Updated(val buildPackageDirectory: Path, val name: String,
                       val deps: BuildTargetSet) : RuleDelta() {
        /*
         * Because Updated contains an IntArray field, which does not play well with `.equals()`
         * (or `hashCode()`, for that matter), we have to do a bit of work to implement these
         * methods properly when the default implementations for a data class are not appropriate.
         */

        override fun equals(other: Any?): Boolean {
            if (other !is Updated) {
                return false
            }
            return buildPackageDirectory == other.buildPackageDirectory && name == other.name &&
                    equals_BuildTargetSet(deps, other.deps)
        }

        override fun hashCode(): Int {
            return 31 * Objects.hash(buildPackageDirectory, name) + hashCode_BuildTargetSet(deps)
        }
    }

    data class Removed(val buildPackageDirectory: Path,
                       val name: String) : RuleDelta()
}
