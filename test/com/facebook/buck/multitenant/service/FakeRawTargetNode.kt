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

import com.facebook.buck.core.model.FakeRuleTypeForTests
import com.facebook.buck.core.model.RuleType
import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode
import com.facebook.buck.rules.visibility.VisibilityPattern
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import java.nio.file.Path
import java.nio.file.Paths

internal val BUILD_FILE_DIRECTORY: Path = Paths.get("foo")
internal val BUILD_TARGET_PARSER: ((shortOrFullyQualifiedName: String) -> UnconfiguredBuildTarget) = {
    if (it.contains(':')) {
        // Argument must already by a fully-qualified build target.
        UnconfiguredBuildTargetFactoryForTests.newInstance(it).data
    } else {
        UnconfiguredBuildTargetFactoryForTests.newInstance("//%s:%s".format(BUILD_FILE_DIRECTORY, it)).data
    }
}
internal val FAKE_RULE_TYPE = FakeRuleTypeForTests.createFakeBuildRuleType("java_library")

internal fun createBuildTarget(shortName: String): UnconfiguredBuildTarget {
    return BUILD_TARGET_PARSER(shortName)
}

internal fun createRule(shortName: String, deps: BuildTargetSet): InternalRawBuildRule {
    val buildTarget = createBuildTarget(shortName)
    val node = FakeRawTargetNode(buildTarget,
            FAKE_RULE_TYPE, ImmutableMap.of())
    return InternalRawBuildRule(node, deps)
}

internal fun createRawRule(shortName: String, deps: Set<String>): RawBuildRule {
    val buildTarget = createBuildTarget(shortName)
    val node = FakeRawTargetNode(buildTarget,
            FAKE_RULE_TYPE, ImmutableMap.of())
    return RawBuildRule(node, deps.map { createBuildTarget(it)}.toSet())
}

internal data class FakeRawTargetNode(private val buildTarget: UnconfiguredBuildTarget, private val ruleType: RuleType, private val attributes: ImmutableMap<String, Any>) : RawTargetNode {

    override fun getBuildTarget(): UnconfiguredBuildTarget = buildTarget

    override fun getRuleType(): RuleType = ruleType

    override fun getAttributes(): ImmutableMap<String, Any>? = attributes

    override fun getVisibilityPatterns(): ImmutableSet<VisibilityPattern> {
        return ImmutableSet.of()
    }

    override fun getWithinViewPatterns(): ImmutableSet<VisibilityPattern> {
        return ImmutableSet.of()
    }
}
