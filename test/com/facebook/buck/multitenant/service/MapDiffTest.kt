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

import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MapDiffTest {

    @Test
    fun emptyRulesShouldHaveNoDeltas() {
        val oldRules = setOf<InternalRawBuildRule>()
        val newRules = setOf<InternalRawBuildRule>()
        val deltas = diffRules(oldRules, newRules)
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun emptyOldRulesWithNewRules() {
        val oldRules = setOf<InternalRawBuildRule>()
        val newRules = setOf(createRule("one", intArrayOf(1)), createRule("two", intArrayOf(2, 3)))
        val deltas = diffRules(oldRules, newRules)
        assertEquals(setOf(
                RuleDelta.Updated(createRule("one", intArrayOf(1))),
                RuleDelta.Updated(createRule("two", intArrayOf(2, 3)))
        ), deltas.toSet())
    }

    @Test
    fun nonEmptyOldRulesWithEmptyNewRules() {
        val oldRules = setOf(createRule("one" , intArrayOf(1)), createRule("two", intArrayOf(2, 3)))
        val newRules = setOf<InternalRawBuildRule>()
        val deltas = diffRules(oldRules, newRules)
        assertEquals(setOf(
                RuleDelta.Removed(createBuildTarget("one")),
                RuleDelta.Removed(createBuildTarget("two"))
        ), deltas.toSet())
    }

    @Test
    fun detectModifiedRulesWithSameSizeMaps() {
        val oldRules = setOf(
                createRule("foo", intArrayOf(1)),
                createRule("bar", intArrayOf(2)),
                createRule("baz", intArrayOf(4, 5)))
        val newRules = setOf(
                createRule("foo", intArrayOf(1)),
                createRule("bar", intArrayOf(2, 3)),
                createRule("baz", intArrayOf(5)))
        val deltas = diffRules(oldRules, newRules)
        assertEquals(setOf(
                RuleDelta.Updated(createRule("bar", intArrayOf(2, 3))),
                RuleDelta.Updated(createRule("baz", intArrayOf(5)))
        ), deltas.toSet())
    }

    @Test
    fun detectModifiedRulesWithMoreOldRules() {
        val oldRules = setOf(
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
                RuleDelta.Updated(createRule("bar", intArrayOf(2, 3))),
                RuleDelta.Updated(createRule("baz", intArrayOf(5))),
                RuleDelta.Removed(createBuildTarget("foobazbar"))
        ), deltas.toSet())
    }

    @Test
    fun detectModifiedRulesWithMoreNewRules() {
        val oldRules = setOf(
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
                RuleDelta.Updated(createRule("bar", intArrayOf(2, 3))),
                RuleDelta.Updated(createRule("baz", intArrayOf(5))),
                RuleDelta.Updated(createRule("foobazbar", intArrayOf(0)))
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
