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

import com.facebook.buck.core.model.RuleType
import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.facebook.buck.multitenant.importer.RuleTypeFactory
import com.facebook.buck.multitenant.importer.ServiceRawTargetNode
import com.google.common.collect.ImmutableMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val FAKE_RULE_TYPE: RuleType = RuleTypeFactory.createBuildRule("fake_rule")

class MapDiffTest {

    @Test
    fun emptyRulesShouldHaveNoDeltas() {
        val oldRules = listOf<InternalRawBuildRule>()
        val newRules = setOf<InternalRawBuildRule>()
        val deltas = diffRules(oldRules, newRules)
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun emptyOldRulesWithNewRules() {
        val oldRules = listOf<InternalRawBuildRule>()
        val newRules = setOf(createRule("one", intArrayOf(1)), createRule("two", intArrayOf(2, 3)))
        val deltas = diffRules(oldRules, newRules)
        assertEquals(setOf(
                RuleDelta.Added(createRule("one", intArrayOf(1))),
                RuleDelta.Added(createRule("two", intArrayOf(2, 3)))
        ), deltas.toSet())
    }

    @Test
    fun nonEmptyOldRulesWithEmptyNewRules() {
        val oldRules = listOf(createRule("one", intArrayOf(1)), createRule("two", intArrayOf(2, 3)))
        val newRules = setOf<InternalRawBuildRule>()
        val deltas = diffRules(oldRules, newRules)
        assertEquals(setOf(
                RuleDelta.Removed(createRule("one", intArrayOf(1))),
                RuleDelta.Removed(createRule("two", intArrayOf(2, 3)))
        ), deltas.toSet())
    }

    @Test
    fun detectModifiedRulesWithSameSizeMaps() {
        val oldRules = listOf(
                createRule("foo", intArrayOf(1)),
                createRule("bar", intArrayOf(2)),
                createRule("baz", intArrayOf(4, 5)))
        val newRules = setOf(
                createRule("foo", intArrayOf(1)),
                createRule("bar", intArrayOf(2, 3)),
                createRule("baz", intArrayOf(5)))
        val deltas = diffRules(oldRules, newRules)
        assertEquals(setOf(
                RuleDelta.Modified(createRule("bar", intArrayOf(2, 3)), createRule("bar", intArrayOf(2))),
                RuleDelta.Modified(createRule("baz", intArrayOf(5)), createRule("baz", intArrayOf(4, 5)))
        ), deltas.toSet())
    }

    @Test
    fun detectModifiedRulesWithMoreOldRules() {
        val oldRules = listOf(
                createRule("foo", intArrayOf(1)),
                createRule("bar", intArrayOf(2)),
                createRule("baz", intArrayOf(4, 5)),
                createRule("foobazbar", intArrayOf(0)))
        val newRules = setOf(
                createRule("foo", intArrayOf(1)),
                createRule("bar", intArrayOf(2, 3)),
                createRule("baz", intArrayOf(5)))
        val deltas = diffRules(oldRules, newRules)
        assertEquals(setOf(
                RuleDelta.Modified(createRule("bar", intArrayOf(2, 3)), createRule("bar", intArrayOf(2))),
                RuleDelta.Modified(createRule("baz", intArrayOf(5)), createRule("baz", intArrayOf(4, 5))),
                RuleDelta.Removed(createRule("foobazbar", intArrayOf(0)))
        ), deltas.toSet())
    }

    @Test
    fun detectModifiedRulesWithMoreNewRules() {
        val oldRules = listOf(
                createRule("foo", intArrayOf(1)),
                createRule("bar", intArrayOf(2)),
                createRule("baz", intArrayOf(4, 5)))
        val newRules = setOf(
                createRule("foo", intArrayOf(1)),
                createRule("bar", intArrayOf(2, 3)),
                createRule("baz", intArrayOf(5)),
                createRule("foobazbar", intArrayOf(0)))
        val deltas = diffRules(oldRules, newRules)
        assertEquals(setOf(
                RuleDelta.Modified(createRule("bar", intArrayOf(2, 3)), createRule("bar", intArrayOf(2))),
                RuleDelta.Modified(createRule("baz", intArrayOf(5)), createRule("baz", intArrayOf(4, 5))),
                RuleDelta.Added(createRule("foobazbar", intArrayOf(0)))
        ), deltas.toSet())
    }

    @Test
    fun deltasWithSameContentsAreNotDotEqualsToOneAnother() {
        val buildRules1 = mapOf("one" to intArrayOf(1), "two" to intArrayOf(2))
        val buildRules2 = mapOf("one" to intArrayOf(1), "two" to intArrayOf(2))
        assertNotEquals("Because IntArray.equals() uses reference " +
                "equality, these two maps are not .equals() to one another even though they are " +
                "'contentEquals' to one another.", buildRules1, buildRules2)
    }
}

private val BUILD_FILE_DIRECTORY: FsAgnosticPath = FsAgnosticPath.of("foo")
private val BUILD_TARGET_PARSER: ((shortOrFullyQualifiedName: String) -> UnconfiguredBuildTarget) = {
    BuildTargets.createBuildTargetFromParts(BUILD_FILE_DIRECTORY, it)
}

private fun createBuildTarget(shortName: String): UnconfiguredBuildTarget {
    return BUILD_TARGET_PARSER(shortName)
}

private fun createRule(shortName: String, deps: BuildTargetSet): InternalRawBuildRule {
    val buildTarget = createBuildTarget(shortName)
    val node = ServiceRawTargetNode(buildTarget, FAKE_RULE_TYPE, ImmutableMap.of())
    return InternalRawBuildRule(node, deps)
}
