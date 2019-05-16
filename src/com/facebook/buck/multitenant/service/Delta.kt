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

import com.facebook.buck.multitenant.fs.FsAgnosticPath

/**
 * Represents a change between versions of a build package.
 */
internal sealed class BuildPackageDelta {
    /** Note that this is an "added" or "modified" package. */
    data class Updated(val directory: FsAgnosticPath, val rules: BuildRuleNames) : BuildPackageDelta()

    data class Removed(val directory: FsAgnosticPath) : BuildPackageDelta()
}

/**
 * Represents a change between versions of a build rule. This should be extended to reflect a
 * change in any attribute of a build rule (including its type!) whereas currently this only
 * reflects a change in its deps.
 */
internal sealed class RuleDelta {
    data class Added(val rule: InternalRawBuildRule) : RuleDelta()
    data class Modified(val newRule: InternalRawBuildRule, val oldRule: InternalRawBuildRule) : RuleDelta()
    data class Removed(val rule: InternalRawBuildRule) : RuleDelta()
}
