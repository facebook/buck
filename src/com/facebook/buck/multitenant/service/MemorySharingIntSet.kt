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
import io.vavr.collection.Set
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntCollection

private val EMPTY_INT_SET: MemorySharingIntSet = MemorySharingIntSet.Unique(IntArray(0))

/**
 * A set of [Int] objects. Note that the type of storage we use depends on a combination of (1) the size of
 * the set and (2) how frequently it is updated.
 */
sealed class MemorySharingIntSet : Iterable<Int> {

    companion object {
        fun empty(): MemorySharingIntSet = EMPTY_INT_SET
    }

    abstract val size: Int

    /**
     * Returns `true` if [element] is found in the [MemorySharingIntSet].
     */
    abstract fun contains(element: Int): Boolean

    /**
     * Custom method for adding the values in this set to an [IntCollection]. This gives the
     * implementation the opportunity to avoid boxing and unboxing integers.
     */
    fun addAllTo(destination: IntCollection) {
        /**
        Although it might be slightly more efficient to wrap the [IntArray] as an [IntCollection]
        so that we can use [IntCollection.addAll], as it does some some capacity checks
        before calling [IntCollection.add] in a loop, but [IntCollection] is a little annoying to implement, so we
        should only bother if profiling proves it is worth it.

        Incidentally, [it.unimi.dsi.fastutil.ints.IntIterators.wrap] (int[]) almost does what we need
        except [addAll] requires an [IntCollection] rather than an [IntListIterator].
         */
        for (value in iterator()) {
            destination.add(value)
        }
    }

    /**
     * Returns [true] if the [MemorySharingIntSet] is empty (contains no elements), [false] otherwise.
     */
    fun isEmpty() = size == 0

    /**
     * Unique representation of a [IntArray] that does not share any memory with another [MemorySharingIntSet].
     * The [values] passed into the constructor must be sorted in incrementing order and contain no duplicates.
     */
    class Unique(val values: IntArray) : MemorySharingIntSet() {
        override val size: Int get() = values.size
        override fun iterator(): Iterator<Int> = IntArrayList.wrap(values).iterator()
        override fun contains(element: Int): Boolean = values.binarySearch(element) in 0 until size
    }

    /**
     * Note this is backed by a persistent collection, which for a single instance, we expect to
     * take up more memory than if it were represented as a [IntArray].
     */
    class Persistent(val values: Set<Int>) : MemorySharingIntSet() {
        override val size: Int get() = values.size()
        override fun iterator(): Iterator<Int> = values.iterator()
        override fun contains(element: Int): Boolean = values.contains(element)
    }
}

fun MemorySharingIntSet?.isNullOrEmpty() = this == null || this.isEmpty()

/**
 * Enum type that represents either an "add" or a "remove" to a [MemorySharingIntSet]. These can be
 * computed independently and later "applied" to a persistent collection to derive a new version.
 */
sealed class SetDelta : Comparable<SetDelta> {
    abstract val value: Int
    override fun compareTo(other: SetDelta): Int = value.compareTo(other.value)

    data class Add(override val value: Int) : SetDelta()
    data class Remove(override val value: Int) : SetDelta()
}

/**
 * Takes a list of individual updates applied to a repo at a point in time and computes the
 * aggregate changes that need to be made.
 */
fun <T> deriveDeltas(
    updates: List<Pair<T, SetDelta>>,
    loadPreviousState: (value: T) -> MemorySharingIntSet?
): Map<T, MemorySharingIntSet?> {
    val deltasByKey = groupDeltasByKey(updates)
    val deltaDeriveInfos = deltasByKey.map { (value, setDeltas) ->
        DeltaDeriveInfo(value, loadPreviousState(value), setDeltas)
    }
    return aggregateDeltaDeriveInfos(deltaDeriveInfos)
}

/**
 * Iterates the `deltas` and for each key, collects its corresponding deltas into its
 * own [List]. Returns [Map], grouped by a distinct key.
 */
private fun <T> groupDeltasByKey(deltas: List<Pair<T, SetDelta>>): Map<T, MutableList<SetDelta>> {
    /**
    [out] is effectively a multimap, but none of the Guava [ListMultimap]
    implementations work for us here because we want to be able to sort the [List] for each
    entry when we are done populating the map and Guava's [ListMultimap] returns the [List] for
    each entry as an unmodifiable view.
     */
    val out = mutableMapOf<T, MutableList<SetDelta>>()
    deltas.forEach { (value, setDelta) ->
        out.getOrPut(value, { mutableListOf() }).add(setDelta)
    }
    return out
}

/**
 * Information needed to derive a new [MemorySharingIntSet] from an existing one.
 * @property value target whose [MemorySharingIntSet] this represents
 * @property previousState previous version of [MemorySharingIntSet] for the [value]
 * @property deltas on top of old [MemorySharingIntSet]. This is not guaranteed to be sorted! Though it is a
 * [MutableList] so a client is free to sort it.
 */
internal data class DeltaDeriveInfo<T>(
    val value: T,
    val previousState: MemorySharingIntSet?,
    val deltas: MutableList<SetDelta>
)

/**
 * Takes a list of values whose [MemorySharingIntSet]s have changed and produces the new version of the [MemorySharingIntSet]s
 * for each value. Where it makes sense, persistent collections are used to make more
 * efficient use of memory, as they make it possible to share information between old and new
 * versions of [MemorySharingIntSet].
 */
internal fun <T> aggregateDeltaDeriveInfos(
    deltaDeriveInfos: List<DeltaDeriveInfo<T>>
): Map<T, MemorySharingIntSet?> {
    /**
    We use [java.util.HashMap] so we can specify the [initialCapacity]. We use the fully qualified
    name here to clarify that this is NOT a [io.vavr.collection.HashMap].
     */
    val out = java.util.HashMap<T, MemorySharingIntSet?>(deltaDeriveInfos.size)
    deltaDeriveInfos.forEach { (value, previousState, deltas) ->
        out[value] = if (previousState == null) {
            // No one was depending on this value at the previous generation. All of the
            // deltas must be of type Add.
            check(deltas.all { it is SetDelta.Add }) {
                "There was a 'Remove' delta for a non-existent set."
            }
            deltas.sort()
            val values = IntArray(deltas.size) { index ->
                deltas[index].value
            }
            /**
            Even though [values] might be large, we create a unique copy of the data
            because we would prefer to use a more compact storage format if it turns out
            that it is not going to be updated very frequently.
             */
            MemorySharingIntSet.Unique(values)
        } else {
            applyDeltas(previousState, deltas)
        }
    }
    return out
}

/**
 * If the size of the [MemorySharingIntSet] is below this size, we always choose Unique over Persistent.
 * NOTE: we should use telemetry to determine the right value for this constant. Currently, it is
 * completely pulled out of thin air.
 */
internal const val THRESHOLD_FOR_UNIQUE_VS_PERSISTENT = 10

/**
 * Derives a new [MemorySharingIntSet] from an existing one by applying some deltas. If applying all of the
 * deltas results in an empty set, returns [null].
 * @param previousState original set
 * @param deltas is not required to be sorted, but it may be sorted as a result of invoking this
 *     method. By construction, it should also be non-empty.
 */
private fun applyDeltas(
    previousState: MemorySharingIntSet,
    deltas: MutableList<SetDelta>
): MemorySharingIntSet? {
    val size = deltas.fold(previousState.size) { acc, delta ->
        when (delta) {
            is SetDelta.Add -> acc + 1
            is SetDelta.Remove -> acc - 1
        }
    }
    return when {
        size == 0 -> null
        size < THRESHOLD_FOR_UNIQUE_VS_PERSISTENT -> createSimpleSet(previousState, deltas, size)
        else -> {
            val existingSet = when (previousState) {
                is MemorySharingIntSet.Unique -> {
                    /**
                    The old version was [MemorySharingIntSet.Unique], but now we have exceeded [THRESHOLD_FOR_UNIQUE_VS_PERSISTENT],
                    so now we want to use [MemorySharingIntSet.Persistent] for the new version.
                     */
                    HashSet.ofAll(previousState)
                }
                is MemorySharingIntSet.Persistent -> {
                    previousState.values
                }
            }
            deriveNewPersistentSet(existingSet, deltas)
        }
    }
}

private fun createSimpleSet(
    previousState: MemorySharingIntSet,
    deltas: MutableList<SetDelta>,
    expectedSize: Int
): MemorySharingIntSet.Unique {
    val oldSetSorted: IntArray = when (previousState) {
        is MemorySharingIntSet.Unique -> previousState.values
        is MemorySharingIntSet.Persistent -> {
            val array = previousState.values.toMutableList().toIntArray()
            array.sort()
            array
        }
    }
    deltas.sort()
    val newSet = IntArray(expectedSize)

    /**
    Now that both [oldSetSorted] and deltas are sorted, we walk forward and populate [newSet],
    as appropriate.
     */
    var oldIndex = 0
    var deltaIndex = 0
    var index = 0
    while (oldIndex < oldSetSorted.size && deltaIndex < deltas.size) {
        val oldValue = oldSetSorted[oldIndex]
        val delta = deltas[deltaIndex]
        val value = delta.value
        when {
            oldValue < value -> {
                // Next delta does not affect oldValue, so add oldValue to the output.
                newSet[index++] = oldValue
                ++oldIndex
            }
            oldValue > value -> when (delta) {
                is SetDelta.Add -> {
                    newSet[index++] = value
                    ++deltaIndex
                }
                is SetDelta.Remove -> error("Should not Remove when $value does not exist in the previous set.")
            }

            oldValue == value -> when (delta) {
                is SetDelta.Add -> {
                    error("Should not Add when $value already exists in the previous set.")
                }
                is SetDelta.Remove -> {
                    // We should omit this value from the output, so
                    ++oldIndex
                    ++deltaIndex
                }
            }

            else -> error("Case not yet implemented")
        }
    }

    while (oldIndex < oldSetSorted.size) {
        newSet[index++] = oldSetSorted[oldIndex++]
    }
    while (deltaIndex < deltas.size) {
        when (val delta = deltas[deltaIndex++]) {
            is SetDelta.Add -> newSet[index++] = delta.value
            is SetDelta.Remove -> error("Should not Remove when ${delta.value} does not exist in the previous set.")
        }
    }

    if (index != expectedSize) {
        error("Only assigned $index out of $expectedSize slots in output.")
    }

    return MemorySharingIntSet.Unique(newSet)
}

private fun deriveNewPersistentSet(
    previousState: Set<Int>,
    deltas: List<SetDelta>
): MemorySharingIntSet.Persistent {
    var out = previousState
    deltas.forEach { delta ->
        out = when (delta) {
            is SetDelta.Add -> out.add(delta.value)
            is SetDelta.Remove -> out.remove(delta.value)
        }
    }
    return MemorySharingIntSet.Persistent(out)
}
