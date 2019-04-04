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
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode
import java.nio.file.Path
import java.util.*

typealias Commit = String

internal typealias BuildTargetId = Int

/**
 * The values in the array must be sorted in ascending order or else [equals_BuildTargetSet] and
 * [hashCode_BuildTargetSet] will not work properly.
 */
internal typealias BuildTargetSet = IntArray

/**
 * This is a RawTargetNode paired with its deps as determined by configuring the RawTargetNode with
 * the empty configuration.
 */
data class RawBuildRule(val targetNode: RawTargetNode, val deps: Set<UnconfiguredBuildTarget>)

/**
 * @param[deps] must be sorted in ascending order!!!
 */
internal data class InternalRawBuildRule(val targetNode: RawTargetNode, val deps: BuildTargetSet) {
    /*
     * Because RawTargetNodeAndDeps contains an IntArray field, which does not play well with
     * `.equals()` (or `hashCode()`, for that matter), we have to do a bit of work to implement
     * these methods properly when the default implementations for a data class are not appropriate.
     */

    override fun equals(other: Any?): Boolean {
        if (other !is InternalRawBuildRule) {
            return false
        }
        return targetNode == other.targetNode && equals_BuildTargetSet(deps, other.deps)
    }

    override fun hashCode(): Int {
        return 31 * Objects.hash(targetNode) + hashCode_BuildTargetSet(deps)
    }
}

private fun equals_BuildTargetSet(set1: BuildTargetSet, set2: BuildTargetSet): Boolean {
    return set1.contentEquals(set2)
}

private fun hashCode_BuildTargetSet(set: BuildTargetSet): Int {
    return set.contentHashCode()
}

/**
 * By construction, the name for each rule in rules should be distinct across all of the rules in
 * the set.
 */
data class BuildPackage(val buildFileDirectory: Path, val rules: Set<RawBuildRule>)

internal data class InternalBuildPackage(val buildFileDirectory: Path, val rules: Set<InternalRawBuildRule>)

/**
 * By construction, the Path for each BuildPackage should be distinct across all of the
 * collections of build packages.
 */
data class Changes(val addedBuildPackages: List<BuildPackage>,
                   val modifiedBuildPackages: List<BuildPackage>,
                   val removedBuildPackages: List<Path>)

internal data class InternalChanges(val addedBuildPackages: List<InternalBuildPackage>,
                                    val modifiedBuildPackages: List<InternalBuildPackage>,
                                    val removedBuildPackages: List<Path>)
