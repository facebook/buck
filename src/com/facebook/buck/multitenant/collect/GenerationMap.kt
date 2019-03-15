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

import java.util.*

private data class GenerationValue<VALUE>(val generation: Int, val value: VALUE?)

/**
 * A bucket is a pair of:
 * - Info that applies to the general bucket.
 * - Sequence of values that the bucket has taken over time. Each value is paired with a generation.
 */
private class Bucket<INFO, VALUE : Any>(val info: INFO) {
    /**
     * If this were C++, we would use folly::small_vector here. If it were Rust, we would use the
     * smallvec crate. Because we're on the JVM, perhaps we could approximate either of those with
     * a combination of fixed-size array and a MutableList for overflow, but we'll just keep it
     * simple.
     */
    private val versions: MutableList<GenerationValue<VALUE>> = mutableListOf()

    fun addVersion(generation: Int, value: VALUE?) {
        val generationValue = GenerationValue(generation, value)
        versions.add(generationValue)
    }

    fun getVersion(generation: Int): VALUE? {
        // We expect that the caller is more often interested in recent versions of a bucket rather
        // than older versions. To that end, we attempt a reverse linear search rather than a binary
        // search.
        for (index in (versions.size - 1) downTo 0) {
            val version = versions[index]
            if (generation >= version.generation) {
                return version.value
            }
        }

        return null
    }
}

/**
 * GenerationList is a glorified append-only list-of-lists.
 * This class is not threadsafe: it must be synchronized externally.
 */
private class GenerationList<INFO, VALUE : Any>() {
    private val buckets: MutableList<Bucket<INFO, VALUE>> = mutableListOf()

    /**
     * Note that this will always create a new bucket.
     *
     * Callers are responsible for holding onto the bucket index that is returned because there
     * is no way to get it back later.
     */
    fun addBucket(info: INFO): Int {
        val bucket = Bucket<INFO, VALUE>(info)
        val index = buckets.size
        buckets.add(bucket)
        return index
    }

    fun addVersion(bucketIndex: Int, value: VALUE?, generation: Int) {
        val bucket = buckets[bucketIndex]
        bucket.addVersion(generation, value)
    }

    fun getVersion(bucketIndex: Int, generation: Int): VALUE? {
        val bucket = buckets[bucketIndex]
        return bucket.getVersion(generation)
    }

    fun getAllInfoValuePairsForGeneration(generation: Int): Sequence<Pair<INFO, VALUE>> {
        return buckets.asSequence().map {
            val value = it.getVersion(generation)
            if (value != null) {
                Pair(it.info, value)
            } else {
                null
            }
        }.filterNotNull()
    }
}

/**
 * GenerationMap is a glorified append-only multimap.
 *
 * For convenience, derived info about the key can be stored inline with its versioned values for
 * quick retrieval. This may turn out to be a poor API choice: I just happened to have one other
 * use case for multitenant stuff where this was convenient.
 *
 * This class is not threadsafe: it must be synchronized externally.
 */
class GenerationMap<KEY : Any, VALUE : Any, KEY_INFO>(val keyInfoDeriver: (key: KEY) -> KEY_INFO) {
    private val generationList = GenerationList<KEY_INFO, VALUE>()
    private val keyToBucketIndex = HashMap<KEY, Int>()

    fun addVersion(key: KEY, value: VALUE?, generation: Int) {
        val bucketIndex = findOrCreateBucketIndex(key)
        generationList.addVersion(bucketIndex, value, generation)
    }

    fun getVersion(key: KEY, generation: Int): VALUE? {
        val bucketIndex = findOrCreateBucketIndex(key)
        return generationList.getVersion(bucketIndex, generation)
    }

    fun getAllInfoValuePairsForGeneration(generation: Int): Sequence<Pair<KEY_INFO, VALUE>> {
        // One thing that is special about iterating the generationList rather than the
        // keyToBucketIndex is that we can ensure we return a parallel Stream. Note that the
        // parallelStream() method defined on java.util.Collection (which includes the Set returned
        // by Map.entrySet()) is "possibly parallel," so true parallelism is not guaranteed. The
        // downside of iterating the generationList is that the key is not readily available, though
        // the keyInfo is.
        return generationList.getAllInfoValuePairsForGeneration(generation)
    }

    private fun findOrCreateBucketIndex(key: KEY): Int {
        return keyToBucketIndex.getOrPut(key) {
            val keyInfo = keyInfoDeriver(key)
            val index = generationList.addBucket(keyInfo)
            keyToBucketIndex[key] = index
            index
        }
    }
}
