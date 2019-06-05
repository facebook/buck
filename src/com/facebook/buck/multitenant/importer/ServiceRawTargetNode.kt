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
package com.facebook.buck.multitenant.importer

import com.facebook.buck.core.model.RuleType
import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode
import com.facebook.buck.rules.visibility.VisibilityPattern
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet

/**
 * Simplified implementation of [RawTargetNode] that is sufficient for the multitenant service's
 * needs.
 */
data class ServiceRawTargetNode(
    private val buildTarget: UnconfiguredBuildTarget,
    private val ruleType: RuleType,
    private val attributes: ImmutableMap<String, Any>
) : RawTargetNode {

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
