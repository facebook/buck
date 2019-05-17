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
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.facebook.buck.multitenant.importer.populateIndexFromStream
import com.facebook.buck.multitenant.service.BuildTargets
import com.facebook.buck.multitenant.service.IndexFactory
import com.facebook.buck.query.QueryFileTarget
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
                        "//java/com/example:D",
                        "//java/com/facebook/buck:buck",
                        "//test/com/example:script",
                        "//test/com/example:test"
                ),
                env.evaluateQuery("//...")
        )
    }

    @Test
    fun packageWildcardQuery() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "Return empty set even though no build file exists.",
                asOutput(),
                env.evaluateQuery("//:")
        )

        assertEquals(
                "Return empty set for a build file that defines no rules.",
                asOutput(),
                env.evaluateQuery("//empty/build/file:")
        )

        assertEquals(
                asOutput(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:C",
                        "//java/com/example:D"
                ),
                env.evaluateQuery("//java/com/example:")
        )
    }

    @Test
    fun recursiveWildcardQuery() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                asOutput(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:C",
                        "//java/com/example:D",
                        "//java/com/facebook/buck:buck"
                ),
                env.evaluateQuery("//java/...")
        )

        assertEquals(
                asOutput(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:C",
                        "//java/com/example:D",
                        "//java/com/facebook/buck:buck"
                ),
                env.evaluateQuery("//java/com/...")
        )

        assertEquals(
                "Note how this no longer includes //java/com/facebook/buck:buck",
                asOutput(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:C",
                        "//java/com/example:D"
                ),
                env.evaluateQuery("//java/com/example/...")
        )
    }

    @Test
    fun allpathsQuery() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "Everything in the ABCD diamond is on the path from :D to :A.",
                asOutput(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:C",
                        "//java/com/example:D"
                ),
                env.evaluateQuery("allpaths(//java/com/example:D, //java/com/example:A)")
        )
        assertEquals(
                "There is only one path from :D to :B.",
                asOutput(
                        "//java/com/example:B",
                        "//java/com/example:D"
                ),
                env.evaluateQuery("allpaths(//java/com/example:D, //java/com/example:B)")
        )
        assertEquals(
                "There are no paths from :A to :D.",
                asOutput(),
                env.evaluateQuery("allpaths(//java/com/example:A, //java/com/example:D)")
        )
    }

    @Test
    fun buildfileQuerySingleTarget() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "Should find all build files for :C.",
                asFileTargets("java/com/example/BUCK"),
                env.evaluateQuery("buildfile(//java/com/example:C)"))
    }

    @Test
    fun buildfileQueryWithWildcard() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "Should find all build files in the project.",
                asFileTargets(
                        "java/com/example/BUCK",
                        "java/com/facebook/buck/BUCK",
                        "test/com/example/BUCK"
                ),
                env.evaluateQuery("buildfile(//...)"))
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

    @Test
    fun kindQuery() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                asOutput(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:D"),
                env.evaluateQuery("kind('java_library', //...)"))
        assertEquals(
                asOutput(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:D",
                        "//java/com/facebook/buck:buck"),
                env.evaluateQuery("kind('java_.*', //...)"))
    }

    @Test
    fun inputsQuery() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                asFileTargets(
                        "java/com/example/A.java",
                        "java/com/example/B/com/example/B.java",
                        "java/com/example/D.java",
                        "java/com/example/vector.cpp",
                        "java/com/example/vector.h"),
                env.evaluateQuery("inputs(//java/com/example/...)"))
    }

    @Test
    fun ownersQuery() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "Requesting the owner of a non-existent file should return the empty set, not blow up.",
                asOutput(),
                env.evaluateQuery("owner('non/existent/file.txt')")
        )
        assertEquals(
                "Nominal single owner case.",
                asOutput("//java/com/example:A"),
                env.evaluateQuery("owner('java/com/example/A.java')")
        )
        assertEquals(
                "Should be able to find multiple owners, if appropriate.",
                asOutput("//java/com/example:B", "//java/com/example:C"),
                env.evaluateQuery("owner('java/com/example/vector.cpp')")
        )
        assertEquals(
                "Should be able to find owner in non-parent, ancestor directory.",
                asOutput("//java/com/example:B"),
                env.evaluateQuery("owner('java/com/example/B/com/example/B.java')")
        )
    }

    @Test
    fun rdepsQuery() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "rdeps is unbounded by default.",
                asOutput(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:C",
                        "//java/com/example:D"),
                env.evaluateQuery("rdeps(//..., //java/com/example:A)")
        )
        assertEquals(
                "specifying a depth of 1 should return immediate deps only.",
                asOutput(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:C"),
                env.evaluateQuery("rdeps(//..., //java/com/example:A, 1)")
        )
    }

    @Test
    fun testsofWithNoTestsAttribute() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "Empty set for rule that does not declare any tests.",
                asOutput(),
                env.evaluateQuery("testsof(//java/com/example:A)")
        )
    }

    @Test
    fun testsofWithEmptyTestsAttribute() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "Empty set for rule that declares an empty tests array.",
                asOutput(),
                env.evaluateQuery("testsof(//java/com/example:B)")
        )
    }

    @Test
    fun testsofWithTestsAttributeThatDoesNotIdentifyValidBuildTargets() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "Empty set for rule that declares values that are not build targets.",
                asOutput(),
                env.evaluateQuery("testsof(//java/com/example:C)")
        )
    }

    @Test
    fun testsofWithLocalBuildTarget() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "tests is :test",
                asOutput("//test/com/example:test"),
                env.evaluateQuery("testsof(//test/com/example:script)")
        )
    }

    @Test
    fun testsofWithFullyQualifiedBuildTarget() {
        val env = loadIndex("diamond_dependency_graph.json", 0)
        assertEquals(
                "tests is //test/com/example:test",
                asOutput("//test/com/example:test"),
                env.evaluateQuery("testsof(//java/com/example:D)")
        )
    }
}

private fun asOutput(vararg target: String): Set<UnconfiguredBuildTarget> {
    return target.map(BuildTargets::parseOrThrow).toSet()
}

private fun asFileTargets(vararg path: String): Set<QueryFileTarget> {
    return path.map { QueryFileTarget.of(FsAgnosticSourcePath(FsAgnosticPath.of(it))) }.toSet()
}

private fun loadIndex(resource: String, commitIndex: Int): MultitenantQueryEnvironment {
    val (index, indexAppender) = IndexFactory.createIndex()
    val commits = populateIndexFromStream(indexAppender, MultitenantQueryTest::class.java.getResourceAsStream(resource))
    val generation = indexAppender.getGeneration(commits[commitIndex])
    requireNotNull(generation)
    val cellToBuildFileName = mapOf("" to "BUCK")
    return MultitenantQueryEnvironment(index, generation, cellToBuildFileName)
}
