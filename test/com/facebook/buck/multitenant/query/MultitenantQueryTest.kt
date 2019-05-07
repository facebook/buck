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

package com.facebook.buck.multitenant.query

import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.multitenant.importer.parseOrdinaryBuildTarget
import com.facebook.buck.multitenant.importer.populateIndexFromStream
import com.facebook.buck.multitenant.service.Index
import org.junit.Assert.assertEquals
import org.junit.Test

class MultitenantQueryTest {

    @Test
    fun universeQuery() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                asOutput(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:C",
                        "//java/com/example:D"
                ),
                env.evaluateQuery("//...")
        )
    }

    @Test
    fun depsQuery() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "Depth of 1 should not include //java/com/example:A.",
                asOutput(
                        "//java/com/example:B",
                        "//java/com/example:C",
                        "//java/com/example:D"),
                env.evaluateQuery("deps(//java/com/example:D, 1)"))

        assertEquals(
                "first_order_deps() should work as third argument to deps()",
                asOutput(
                        "//java/com/example:B",
                        "//java/com/example:C",
                        "//java/com/example:D"),
                env.evaluateQuery("deps(//java/com/example:D, 1, first_order_deps())"))

        assertEquals(
                "deps() with no bounds should include the entire graph.",
                asOutput(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:C",
                        "//java/com/example:D"),
                env.evaluateQuery("deps(//java/com/example:D)"))
    }
}

private fun asOutput(vararg target: String): Set<UnconfiguredBuildTarget> {
    return target.map(::parseOrdinaryBuildTarget).toSet()
}

private fun loadIndex(resource: String, commitIndex: Int): MultitenantQueryEnvironment {
    val index = Index(::parseOrdinaryBuildTarget)
    val commits = populateIndexFromStream(index, MultitenantQueryTest::class.java.getResourceAsStream(resource))
    return MultitenantQueryEnvironment(index, commits[commitIndex])
}
