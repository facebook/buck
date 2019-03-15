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

import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

val BUILD_FILE_DIRECTORY: Path = Paths.get("foo")

class MapDiffTest {
    @Test
    fun emptyRulesShouldHaveNoDeltas() {
        val oldRules = mapOf<String, BuildTargetSet>()
        val newRules = mapOf<String, BuildTargetSet>()
        val deltas = diffRules(oldRules, newRules, BUILD_FILE_DIRECTORY)
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun emptyOldRulesWithNewRules() {
        val oldRules = mapOf<String, BuildTargetSet>()
        val newRules = mapOf("one" to intArrayOf(1), "two" to intArrayOf(2, 3))
        val deltas = diffRules(oldRules, newRules, BUILD_FILE_DIRECTORY)
        assertEquals(setOf(
                RuleDelta.Updated(BUILD_FILE_DIRECTORY, "one", intArrayOf(1)),
                RuleDelta.Updated(BUILD_FILE_DIRECTORY, "two", intArrayOf(2, 3))
        ), deltas.toSet())
    }

    @Test
    fun nonEmptyOldRulesWithEmptyNewRules() {
        val oldRules = mapOf("one" to intArrayOf(1), "two" to intArrayOf(2, 3))
        val newRules = mapOf<String, BuildTargetSet>()
        val deltas = diffRules(oldRules, newRules, BUILD_FILE_DIRECTORY)
        assertEquals(setOf(
                RuleDelta.Removed(BUILD_FILE_DIRECTORY, "one"),
                RuleDelta.Removed(BUILD_FILE_DIRECTORY, "two")
        ), deltas.toSet())
    }

    @Test
    fun detectModifiedRulesWithSameSizeMaps() {
        val oldRules = mapOf(
                "foo" to intArrayOf(1),
                "bar" to intArrayOf(2),
                "baz" to intArrayOf(4, 5))
        val newRules = mapOf(
                "foo" to intArrayOf(1),
                "bar" to intArrayOf(2, 3),
                "baz" to intArrayOf(5))
        val deltas = diffRules(oldRules, newRules, BUILD_FILE_DIRECTORY)
        assertEquals(setOf(
                RuleDelta.Updated(BUILD_FILE_DIRECTORY, "bar", intArrayOf(2, 3)),
                RuleDelta.Updated(BUILD_FILE_DIRECTORY, "baz", intArrayOf(5))
        ), deltas.toSet())
    }

    @Test
    fun detectModifiedRulesWithMoreOldRules() {
        val oldRules = mapOf(
                "foo" to intArrayOf(1),
                "bar" to intArrayOf(2),
                "baz" to intArrayOf(4, 5),
                "foobazbar" to intArrayOf(0))
        val newRules = mapOf(
                "foo" to intArrayOf(1),
                "bar" to intArrayOf(2, 3),
                "baz" to intArrayOf(5))
        val deltas = diffRules(oldRules, newRules, BUILD_FILE_DIRECTORY)
        assertEquals(setOf(
                RuleDelta.Updated(BUILD_FILE_DIRECTORY, "bar", intArrayOf(2, 3)),
                RuleDelta.Updated(BUILD_FILE_DIRECTORY, "baz", intArrayOf(5)),
                RuleDelta.Removed(BUILD_FILE_DIRECTORY, "foobazbar")
        ), deltas.toSet())
    }

    @Test
    fun detectModifiedRulesWithMoreNewRules() {
        val oldRules = mapOf(
                "foo" to intArrayOf(1),
                "bar" to intArrayOf(2),
                "baz" to intArrayOf(4, 5))
        val newRules = mapOf(
                "foo" to intArrayOf(1),
                "bar" to intArrayOf(2, 3),
                "baz" to intArrayOf(5),
                "foobazbar" to intArrayOf(0))
        val deltas = diffRules(oldRules, newRules, BUILD_FILE_DIRECTORY)
        assertEquals(setOf(
                RuleDelta.Updated(BUILD_FILE_DIRECTORY, "bar", intArrayOf(2, 3)),
                RuleDelta.Updated(BUILD_FILE_DIRECTORY, "baz", intArrayOf(5)),
                RuleDelta.Updated(BUILD_FILE_DIRECTORY, "foobazbar", intArrayOf(0))
        ), deltas.toSet())
    }

    @Test
    fun deltasWithSameContentsAreNotDotEqualsToOneAnother() {
        val buildRules1 = mapOf("one" to intArrayOf(1), "two" to intArrayOf(2))
        val buildRules2 = mapOf("one" to intArrayOf(1), "two" to intArrayOf(2))
        assertNotEquals(buildRules1, buildRules2, "Because IntArray.equals() uses reference " +
                "equality, these two maps are not .equals() to one another even though they are " +
                "'contentEquals' to one another.")
    }
}
