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

import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardingGenerationMapTest {
    @Test
    fun localNonNullVersionShadowsDelegate() {
        val delegate = createDelegate()
        val changes = mapOf("foo" to "baz")
        var forwardingMap = ForwardingGenerationMap(1, changes, delegate)
        assertEquals("baz", forwardingMap.getVersion("foo", 1))
    }

    @Test
    fun localNullVersionShadowsDelegate() {
        val delegate = createDelegate()
        val changes = mapOf("foo" to null)
        var forwardingMap = ForwardingGenerationMap(1, changes, delegate)
        assertEquals(null, forwardingMap.getVersion("foo", 1))
    }

    @Test
    fun noLocalVersionForwardsToDelegateNonNull() {
        val delegate = createDelegate()
        val changes: Map<String, String> = mapOf()
        var forwardingMap = ForwardingGenerationMap(1, changes, delegate)
        assertEquals("bar1", forwardingMap.getVersion("foo", 1))
    }

    @Test
    fun noLocalVersionForwardsToDelegateNull() {
        val delegate = createDelegate()
        val changes: Map<String, String> = mapOf()
        var forwardingMap = ForwardingGenerationMap(1, changes, delegate)
        assertEquals(null, forwardingMap.getVersion("fizz", 1))
    }

    @Test
    fun noLocalChangesWhenCallingGetEntries() {
        val delegate = createDelegate()
        val changes: Map<String, String> = mapOf()
        var forwardingMap = ForwardingGenerationMap(0, changes, delegate)
        val pairs = forwardingMap.getEntries(0).toSet()
        assertEquals(setOf(Pair("foo", "bar0"), Pair("fizz", "buzz")), pairs)
        val filteredPairs = forwardingMap.getEntries(0, { it == "foo" }).toSet()
        assertEquals(setOf(Pair("foo", "bar0")), filteredPairs)
    }

    @Test
    fun additiveLocalChangesWhenCallingGetEntries() {
        val delegate = createDelegate()
        val changes = mapOf("A" to "T", "C" to "G")
        var forwardingMap = ForwardingGenerationMap(0, changes, delegate)
        val pairs = forwardingMap.getEntries(0).toSet()
        assertEquals(setOf(Pair("foo", "bar0"), Pair("fizz", "buzz"), Pair("A", "T"), Pair("C", "G")), pairs)
        val filteredPairs = forwardingMap.getEntries(0, { it == "A" }).toSet()
        assertEquals(setOf(Pair("A", "T")), filteredPairs)
    }

    @Test
    fun destructiveLocalChangesWhenCallingGetEntries() {
        val delegate = createDelegate()
        val changes = mapOf("foo" to null)
        var forwardingMap = ForwardingGenerationMap(0, changes, delegate)
        val pairs = forwardingMap.getEntries(0).toSet()
        assertEquals(setOf(Pair("fizz", "buzz")), pairs)
        val filteredPairs = forwardingMap.getEntries(0, { it == "foo" }).toSet()
        assertEquals(setOf<Pair<String, String>>(), filteredPairs)
    }

    @Test
    fun differingLocalChangesWhenCallingGetEntries() {
        val delegate = createDelegate()
        val changes = mapOf("foo" to "z")
        var forwardingMap = ForwardingGenerationMap(0, changes, delegate)
        val pairs = forwardingMap.getEntries(0).toSet()
        assertEquals(setOf(Pair("foo", "z"), Pair("fizz", "buzz")), pairs)
        val filteredPairs = forwardingMap.getEntries(0, { it == "foo" }).toSet()
        assertEquals(setOf(Pair("foo", "z")), filteredPairs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedGenerationThrows() {
        val delegate = createDelegate()
        val changes = mapOf("foo" to "baz")
        var forwardingMap = ForwardingGenerationMap(1, changes, delegate)
        forwardingMap.getVersion("foo", 2)
    }

    private fun createDelegate(): GenerationMap<String, String, String> {
        val delegate = DefaultGenerationMap<String, String, String> { it }
        delegate.addVersion("foo", "bar0", 0)
        delegate.addVersion("foo", "bar1", 1)
        delegate.addVersion("foo", "bar2", 2)

        delegate.addVersion("fizz", "buzz", 0)
        delegate.addVersion("fizz", null, 1)

        return delegate
    }
}
