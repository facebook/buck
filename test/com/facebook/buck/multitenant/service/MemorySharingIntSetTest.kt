/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.multitenant.service

import io.vavr.collection.HashSet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySharingIntSetTest {

    @Test
    @SuppressWarnings("MagicNumber")
    fun replaceSetWithNullWhenDeltasRemoveAllEntries() {
        val id = 1
        val oldValues = uniqueSet(10, 20, 30)
        val info = DeltaDeriveInfo(id, oldValues, mutableListOf(
                remove(30),
                remove(10),
                remove(20)
        ))
        assertEquals(
                "Because the deltas remove all the entries, should map to null.",
                mapOf<Int, MemorySharingIntSet?>(id to null),
            aggregateDeltaDeriveInfos(listOf(info))
        )
    }

    @Test
    @SuppressWarnings("MagicNumber")
    fun addingToEmptyOldDepsCreatesUniqueSetWithProperlySortedValues() {
        val id = 1
        val oldValues = null
        val info = DeltaDeriveInfo(id, oldValues, mutableListOf(
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
        assertArrayEquals(
                intArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90),
            asUniqueValues(set)
        )
    }

    @Test(expected = IllegalStateException::class)
    @SuppressWarnings("MagicNumber")
    fun throwsIfRemoveSpecifiedToEmptyOldDeps() {
        val id = 1
        val oldValues = null
        val info = DeltaDeriveInfo(id, oldValues, mutableListOf(remove(10)))
        aggregateDeltaDeriveInfos(listOf(info))
    }

    @Test(expected = IllegalStateException::class)
    @SuppressWarnings("MagicNumber")
    fun throwsIfAddSpecifiedForIdAlreadyInOldDeps() {
        val id = 1
        val oldValues = uniqueSet(10, 20, 30, 50, 60)
        val info = DeltaDeriveInfo(id, oldValues, mutableListOf(add(20)))
        aggregateDeltaDeriveInfos(listOf(info))
    }

    @Test(expected = IllegalStateException::class)
    @SuppressWarnings("MagicNumber")
    fun throwsIfRemoveSpecifiedForIdNotInOldDeps() {
        val id = 1
        val oldValues = uniqueSet(10, 20, 30, 50, 60)
        val info = DeltaDeriveInfo(id, oldValues, mutableListOf(remove(40)))
        aggregateDeltaDeriveInfos(listOf(info))
    }

    @Test
    @SuppressWarnings("MagicNumber")
    fun addDeltasGreaterThanExistingValues() {
        val id = 1
        val oldValues = uniqueSet(10, 20, 30)
        val info = DeltaDeriveInfo(id, oldValues, mutableListOf(
                add(60),
                add(40),
                add(50)
        ))
        val set = aggregateDeltaDeriveInfos(listOf(info)).getValue(id)
        assertArrayEquals(
                intArrayOf(10, 20, 30, 40, 50, 60),
            asUniqueValues(set)
        )
    }

    @Test
    @SuppressWarnings("MagicNumber")
    fun addDeltasLessThanExistingValues() {
        val id = 1
        val oldValues = uniqueSet(40, 50, 60)
        val info = DeltaDeriveInfo(id, oldValues, mutableListOf(
                add(20),
                add(10),
                add(30)
        ))
        val set = aggregateDeltaDeriveInfos(listOf(info)).getValue(id)
        assertArrayEquals(
                intArrayOf(10, 20, 30, 40, 50, 60),
            asUniqueValues(set)
        )
    }

    @Test
    @SuppressWarnings("MagicNumber")
    fun crossOverPersistentSizeThreshold() {
        val id = 1
        val values = IntArray(THRESHOLD_FOR_UNIQUE_VS_PERSISTENT - 1) { i ->
            i * 10
        }
        val oldValues = MemorySharingIntSet.Unique(values)
        val info = DeltaDeriveInfo(id, oldValues, mutableListOf(
                remove(0),
                add(values.size * 10),
                add((values.size + 1) * 10)
        ))
        val set = aggregateDeltaDeriveInfos(listOf(info)).getValue(id)

        var expected = HashSet.empty<Int>()
        for (i in 1..THRESHOLD_FOR_UNIQUE_VS_PERSISTENT) {
            expected = expected.add(i * 10)
        }
        assertEquals(expected, asPersistentValues(set))
    }

    @Test
    @SuppressWarnings("MagicNumber")
    fun fallUnderPersistentSizeThreshold() {
        val id = 1
        var values = HashSet.empty<Int>()
        for (i in 0 until THRESHOLD_FOR_UNIQUE_VS_PERSISTENT) {
            values = values.add(i * 10)
        }
        val oldValues = MemorySharingIntSet.Persistent(values)
        val info = DeltaDeriveInfo(id, oldValues, mutableListOf(
                remove((values.size() - 1) * 10)
        ))
        val set = aggregateDeltaDeriveInfos(listOf(info)).getValue(id)

        val expected = IntArray(THRESHOLD_FOR_UNIQUE_VS_PERSISTENT - 1) { i ->
            i * 10
        }
        assertArrayEquals(expected, asUniqueValues(set))
    }

    @Test
    @SuppressWarnings("MagicNumber")
    fun modifyPersistentSetThatExceedsThreshold() {
        // oldIds will contain all even numbers.
        var oldKeys = HashSet.empty<Int>()
        // Ensure that both the old and new set exceed the size threshold.
        val maxId = THRESHOLD_FOR_UNIQUE_VS_PERSISTENT * 4
        for (i in 0 until maxId step 2) {
            oldKeys = oldKeys.add(i)
        }
        val oldValues = MemorySharingIntSet.Persistent(oldKeys)

        // remove numbers divisible by 4 and add all odd numbers.
        val deltas = mutableListOf<SetDelta>()
        for (i in 0 until maxId step 4) {
            deltas.add(SetDelta.Remove(i))
        }
        val numRemoves = deltas.size
        for (i in 1 until maxId step 2) {
            deltas.add(SetDelta.Add(i))
        }
        val numAdds = deltas.size - numRemoves
        val id = maxId + 1
        val info = DeltaDeriveInfo(id, oldValues, deltas)
        val set = aggregateDeltaDeriveInfos(listOf(info)).getValue(id)

        val expectedSize = oldKeys.size() + numAdds - numRemoves
        var expected = HashSet.empty<Int>()
        for (i in 0 until maxId) {
            if (i % 4 != 0) {
                expected = expected.add(i)
            }
        }
        assertEquals(
                "Sanity check expected set before comparing to observed value.",
                expectedSize,
                expected.size())
        assertEquals(expected, asPersistentValues(set))
    }

    @Test
    @SuppressWarnings("MagicNumber")
    fun contains() {
        val uniqueSet = uniqueSet(10, 20, 30, 40, 50, 60)
        assertTrue(uniqueSet.contains(40))
        assertFalse(uniqueSet.contains(35))

        val persistentSet = MemorySharingIntSet.Persistent(HashSet.ofAll(uniqueSet.values.toSet()))
        assertTrue(persistentSet.contains(40))
        assertFalse(persistentSet.contains(35))
    }
}

private fun uniqueSet(vararg ids: Int) = MemorySharingIntSet.Unique(ids.sortedArray())
private fun add(id: Int): SetDelta = SetDelta.Add(id)
private fun remove(id: Int): SetDelta = SetDelta.Remove(id)

private fun asUniqueValues(set: MemorySharingIntSet?): IntArray {
    @SuppressWarnings("UnsafeCast")
    val unique = set as MemorySharingIntSet.Unique
    return unique.values
}

private fun asPersistentValues(set: MemorySharingIntSet?): Iterable<Int> {
    @SuppressWarnings("UnsafeCast")
    val persistent = set as MemorySharingIntSet.Persistent
    return persistent.values
}
