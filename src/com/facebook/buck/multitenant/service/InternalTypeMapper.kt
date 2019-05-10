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
import com.facebook.buck.multitenant.fs.FsAgnosticPath

internal class InternalTypeMapper(
        val buildTargetParser: (target: String) -> UnconfiguredBuildTarget,
        private val buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>) {
    fun createBuildTarget(buildFileDirectory: FsAgnosticPath, name: String): UnconfiguredBuildTarget {
        return buildTargetParser(String.format("//%s:%s", buildFileDirectory, name))
    }

    fun toInternalChanges(changes: Changes): InternalChanges {
        return InternalChanges(changes.addedBuildPackages.map { toInternalBuildPackage(it) }.toList(),
                changes.modifiedBuildPackages.map { toInternalBuildPackage(it) }.toList(),
                changes.removedBuildPackages
        )
    }

    private fun toInternalBuildPackage(buildPackage: BuildPackage): InternalBuildPackage {
        return InternalBuildPackage(buildPackage.buildFileDirectory, buildPackage.rules.map { toInternalRawBuildRule(it) }.toSet())
    }

    private fun toInternalRawBuildRule(rawBuildRule: RawBuildRule): InternalRawBuildRule {
        return InternalRawBuildRule(rawBuildRule.targetNode, toBuildTargetSet(rawBuildRule.deps))
    }

    private fun toBuildTargetSet(targets: Set<UnconfiguredBuildTarget>): BuildTargetSet {
        val ids = targets.map { buildTargetCache.get(it) }.toIntArray()
        ids.sort()
        return ids
    }
}
