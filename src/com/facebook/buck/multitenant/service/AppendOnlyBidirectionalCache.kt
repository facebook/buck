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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cache where every unique instance of K added to the cache is assigned a unique int.
 * This makes it possible to pass copies of the int around instead of references to K because the
 * int can always be mapped back to K using this cache.
 *
 * This class is threadsafe.
 */
internal class AppendOnlyBidirectionalCache<K> {
    private val nextId = AtomicInteger()
    // Note that we rely on the specific implementation of ConcurrentHashMap.computeIfAbsent()
    // because it provides stronger guarantees than ConcurrentMap.computeIfAbsent(). Specifically,
    // ConcurrentMap.computeIfAbsent() says that it can potentially call the mapping function
    // multiple times whereas ConcurrentHashMap.computeIfAbsent() guarantees that the mapping
    // function is applied at most once per key. We need the "at most once" guarantee to ensure
    // inserts into the forward and reverse maps are one-to-one.
    private val forward = ConcurrentHashMap<K, Int>()
    private val reverse = ConcurrentHashMap<Int, K>()

    /**
     * Inserts the key and creates a corresponding Int for it if it is not already present in the
     * cache.
     */
    fun get(key: K): Int {
        return forward.computeIfAbsent(key) {
            val index = nextId.getAndIncrement()
            reverse.putIfAbsent(index, key)
            index
        }
    }

    fun getByIndex(index: Int): K {
        return reverse[index]!!
    }
}
