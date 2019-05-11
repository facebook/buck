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

package com.facebook.buck.multitenant.collect

/**
 * Used with [Map.getOrDefault] to be able to distinguish whether the key is mapped to null or if it
 * has no corresponding entry in the [Map].
 */
private val SENTINEL: Object = Object()

/**
 * Special implementation of GenerationMap that handles lookups only for a specific generation.
 * (Specifying other generations result in [IllegalArgumentException].) The data is primarily
 * supplied by another GenerationMap that is specified as the "delegate," but
 * ForwardingGenerationMap also takes a set of localChanges that will be used to shadow any values
 * in the delegate.
 *
 * Note this is a special case of GenerationMap where "key" and "key info" MUST be the same value
 * in the delegate being composed. This is admittedly somewhat distasteful, but it meets our
 * immediate needs and makes getEntries() easier to implement, so we can revisit if/when our
 * requirements change.
 *
 * @param supportedGeneration the generation with which this map is associated
 * @param localChanges to shadow those in the delegate at the supportedGeneration.
 * @param delegate Source of data for keys that are not in the localChanges map. Note that any
 *     read/write locks that are used to guard access to the delegate should also be used to guard
 *     access to this ForwardingGenerationMap.
 */
class ForwardingGenerationMap<KEY : Any, VALUE : Any>(
        private val supportedGeneration: Int,
        private val localChanges: Map<KEY, VALUE?>,
        private val delegate: GenerationMap<KEY, VALUE, KEY>) : GenerationMap<KEY, VALUE, KEY> {
    override fun getVersion(key: KEY, generation: Int): VALUE? {
        assertGeneration(generation)
        // Incidentally, this cast is a complete lie, but we do not appear to encounter a
        // ClassCastException, so we're good!
        val value = localChanges.getOrDefault(key, SENTINEL as VALUE)

        return if (value === SENTINEL) {
            // In this case, we know localChanges does not have a mapping for key, so we must
            // forward the request to the delegate.
            delegate.getVersion(key, generation)
        } else {
            // Return the value, even if it is null.
            value
        }
    }

    override fun getEntries(generation: Int, filter: ((keyInfo: KEY) -> Boolean)?): Sequence<Pair<KEY, VALUE>> {
        assertGeneration(generation)
        val localEntries = localChanges.entries.asSequence().filter { (key, value) ->
            value != null && (filter == null || filter(key))
        }.map { entry ->
            Pair(entry.key, entry.value as VALUE)
        }

        // Filter out all entries for keys that are present in localChanges.
        val keySet = localChanges.keys
        val delegateEntries = delegate.getEntries(generation, filter).filter { (key, _value) ->
            !keySet.contains(key)
        }

        return localEntries + delegateEntries
    }

    private fun assertGeneration(generation: Int) {
        if (supportedGeneration != generation) {
            throw IllegalArgumentException("Expected generation $supportedGeneration but was $generation")
        }
    }
}
