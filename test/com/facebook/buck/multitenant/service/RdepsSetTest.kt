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

import io.vavr.collection.HashSet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RdepsSetTest {
    @Test
    fun replaceSetWithNullWhenDeltasRemoveAllEntries() {
        val id = 1
        val oldRdeps = uniqueSet(10, 20, 30)
        val info = DeltaDeriveInfo(id, oldRdeps, mutableListOf(
                remove(30),
                remove(10),
                remove(20)
        ))
        assertEquals(
                "Because the deltas remove all the entries, should map to null.",
                mapOf<BuildTargetId, RdepsSet?>(id to null),
                aggregateDeltaDeriveInfos(listOf(info))
        )
    }

    @Test
    fun addingToEmptyOldDepsCreatesUniqueSetWithProperlySortedValues() {
        val id = 1
        val oldRdeps = null
        val info = DeltaDeriveInfo(id, oldRdeps, mutableListOf(
                add(60),
                add(10),
                add(80),
                add(50),
                add(20),
                add(30),
                add(90),
                add(40),
                add(70)
        ))
        val set = aggregateDeltaDeriveInfos(listOf(info)).getValue(id)
        val unique = set as RdepsSet.Unique
        assertArrayEquals(
                intArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90),
                unique.rdeps
        )
    }

    @Test(expected = IllegalStateException::class)
    fun throwsIfRemoveSpecifiedToEmptyOldDeps() {
        val id = 1
        val oldRdeps = null
        val info = DeltaDeriveInfo(id, oldRdeps, mutableListOf(remove(10)))
        aggregateDeltaDeriveInfos(listOf(info))
    }

    @Test(expected = IllegalStateException::class)
    fun throwsIfAddSpecifiedForIdAlreadyInOldDeps() {
        val id = 1
        val oldRdeps = uniqueSet(10, 20, 30, 50, 60)
        val info = DeltaDeriveInfo(id, oldRdeps, mutableListOf(add(20)))
        aggregateDeltaDeriveInfos(listOf(info))
    }

    @Test(expected = IllegalStateException::class)
    fun throwsIfRemoveSpecifiedForIdNotInOldDeps() {
        val id = 1
        val oldRdeps = uniqueSet(10, 20, 30, 50, 60)
        val info = DeltaDeriveInfo(id, oldRdeps, mutableListOf(remove(40)))
        aggregateDeltaDeriveInfos(listOf(info))
    }

    @Test
    fun addDeltasGreaterThanExistingValues() {
        val id = 1
        val oldRdeps = uniqueSet(10, 20, 30)
        val info = DeltaDeriveInfo(id, oldRdeps, mutableListOf(
                add(60),
                add(40),
                add(50)
        ))
        val set = aggregateDeltaDeriveInfos(listOf(info)).getValue(id)
        val unique = set as RdepsSet.Unique
        assertArrayEquals(
                intArrayOf(10, 20, 30, 40, 50, 60),
                unique.rdeps
        )
    }

    @Test
    fun addDeltasLessThanExistingValues() {
        val id = 1
        val oldRdeps = uniqueSet(40, 50, 60)
        val info = DeltaDeriveInfo(id, oldRdeps, mutableListOf(
                add(20),
                add(10),
                add(30)
        ))
        val set = aggregateDeltaDeriveInfos(listOf(info)).getValue(id)
        val unique = set as RdepsSet.Unique
        assertArrayEquals(
                intArrayOf(10, 20, 30, 40, 50, 60),
                unique.rdeps
        )
    }

    @Test
    fun crossOverPersistentSizeThreshold() {
        val id = 1
        val buildTargetIds = IntArray(THRESHOLD_FOR_UNIQUE_VS_PERSISTENT - 1) { i ->
            i * 10
        }
        val oldRdeps = RdepsSet.Unique(buildTargetIds)
        val info = DeltaDeriveInfo(id, oldRdeps, mutableListOf(
                remove(0),
                add(buildTargetIds.size * 10),
                add((buildTargetIds.size + 1) * 10)
        ))
        val set = aggregateDeltaDeriveInfos(listOf(info)).getValue(id)
        val persistent = set as RdepsSet.Persistent

        var expected = HashSet.empty<BuildTargetId>()
        for (i in 1..THRESHOLD_FOR_UNIQUE_VS_PERSISTENT) {
            expected = expected.add(i * 10)
        }
        assertEquals(expected, persistent.rdeps)
    }

    @Test
    fun fallUnderPersistentSizeThreshold() {
        val id = 1
        var buildTargetIds = HashSet.empty<BuildTargetId>()
        for (i in 0 until THRESHOLD_FOR_UNIQUE_VS_PERSISTENT) {
            buildTargetIds = buildTargetIds.add(i * 10)
        }
        val oldRdeps = RdepsSet.Persistent(buildTargetIds)
        val info = DeltaDeriveInfo(id, oldRdeps, mutableListOf(
                remove((buildTargetIds.size() - 1) * 10)
        ))
        val set = aggregateDeltaDeriveInfos(listOf(info)).getValue(id)
        val unique = set as RdepsSet.Unique

        val expected = IntArray(THRESHOLD_FOR_UNIQUE_VS_PERSISTENT - 1) { i ->
            i * 10
        }
        assertArrayEquals(expected, unique.rdeps)
    }

    @Test
    fun modifyPersistentSetThatExceedsThreshold() {
        // oldIds will contain all even numbers.
        var oldIds = HashSet.empty<BuildTargetId>()
        // Ensure that both the old and new set exceed the size threshold.
        val maxId = THRESHOLD_FOR_UNIQUE_VS_PERSISTENT * 4
        for (i in 0 until maxId step 2) {
            oldIds = oldIds.add(i)
        }
        val oldRdeps = RdepsSet.Persistent(oldIds)

        // remove numbers divisible by 4 and add all odd numbers.
        val deltas = mutableListOf<BuildTargetSetDelta>()
        for (i in 0 until maxId step 4) {
            deltas.add(BuildTargetSetDelta.Remove(i))
        }
        val numRemoves = deltas.size
        for (i in 1 until maxId step 2) {
            deltas.add(BuildTargetSetDelta.Add(i))
        }
        val numAdds = deltas.size - numRemoves
        val id = maxId + 1
        val info = DeltaDeriveInfo(id, oldRdeps, deltas)
        val set = aggregateDeltaDeriveInfos(listOf(info)).getValue(id)
        val persistent = set as RdepsSet.Persistent

        val expectedSize = oldIds.size() + numAdds - numRemoves
        var expected = HashSet.empty<BuildTargetId>()
        for (i in 0 until maxId) {
            if (i % 4 != 0) {
                expected = expected.add(i)
            }
        }
        assertEquals(
                "Sanity check expected set before comparing to observed value.",
                expectedSize,
                expected.size())
        assertEquals(expected, persistent.rdeps)
    }
}

private fun uniqueSet(vararg ids: Int): RdepsSet.Unique = RdepsSet.Unique(ids)
private fun add(id: Int): BuildTargetSetDelta = BuildTargetSetDelta.Add(id)
private fun remove(id: Int): BuildTargetSetDelta = BuildTargetSetDelta.Remove(id)
