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

import com.facebook.buck.core.model.RuleType
import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.facebook.buck.multitenant.importer.RuleTypeFactory
import com.facebook.buck.multitenant.importer.ServiceRawTargetNode
import com.facebook.buck.multitenant.importer.populateIndexFromStream
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

private val JAVA_LIBRARY: RuleType = RuleTypeFactory.createBuildRule("java_library")

class IndexTest {

    @Test
    fun getLatestCommit() {
        val (_, indexAppender) = IndexFactory.createIndex()
        assertNull("Empty Index should not have a latest commit.", indexAppender.getLatestCommit())

        val commit1 = "e69ef4691d6af7b6c6b5b3cdc1b8b2efcc4ad64b"
        indexAppender.addCommitData(commit1, BuildPackageChanges())
        assertEquals(commit1, indexAppender.getLatestCommit())
        assertEquals(
                "No graph changes, so generation should still be 0.",
                0,
                indexAppender.getGeneration(commit1))

        val commit2 = "1d76d7640394be3685980da4c0f2109424678937"
        indexAppender.addCommitData(commit2, BuildPackageChanges(addedBuildPackages = listOf(
                BuildPackage(FsAgnosticPath.of("foo"), setOf(createRawRule("//foo:bar", listOf())))
        )))
        assertEquals(commit2, indexAppender.getLatestCommit())
        assertEquals(
                "Graph changes should increment generation to 1.",
                1,
                indexAppender.getGeneration(commit2))
    }

    @Test
    fun getTargetsAndDeps() {
        val (index, generations) = loadIndex("index_test_targets_and_deps.json")
        val generation1 = generations[0]
        val generation2 = generations[1]
        val generation3 = generations[2]
        val generation4 = generations[3]
        val generation5 = generations[4]

        assertEquals(
                targetSet("//java/com/facebook/buck/base:base"),
                index.getTargets(generation1).toSet())
        assertEquals(
                targetSet("//java/com/facebook/buck/base:base", "//java/com/facebook/buck/model:model"),
                index.getTargets(generation2).toSet())
        assertEquals(
                targetSet(
                        "//java/com/facebook/buck/base:base",
                        "//java/com/facebook/buck/model:model",
                        "//java/com/facebook/buck/util:util"),
                index.getTargets(generation3).toSet())
        assertEquals(
                targetSet(
                        "//java/com/facebook/buck/base:base",
                        "//java/com/facebook/buck/model:model",
                        "//java/com/facebook/buck/util:util"),
                index.getTargets(generation4).toSet())
        assertEquals(
                targetSet(
                        "//java/com/facebook/buck/base:base",
                        "//java/com/facebook/buck/util:util"),
                index.getTargets(generation5).toSet())

        assertEquals(
                targetSet(
                        "//java/com/facebook/buck/base:base",
                        "//java/com/facebook/buck/model:model"
                ),
                index.getTransitiveDeps(generation2, targetSequence("//java/com/facebook/buck/model:model"))
        )
        assertEquals(
                targetSet(
                        "//java/com/facebook/buck/base:base",
                        "//java/com/facebook/buck/model:model",
                        "//java/com/facebook/buck/util:util"
                ),
                index.getTransitiveDeps(generation3, targetSequence("//java/com/facebook/buck/model:model"))
        )

        val commit1baseFwdDeps = ImmutableSet.Builder<UnconfiguredBuildTarget>()
        index.getFwdDeps(generation1, targetList("//java/com/facebook/buck/base:base"), commit1baseFwdDeps)
        assertEquals(commit1baseFwdDeps.build(), targetSet())

        val commit2modelFwdDeps = ImmutableSet.Builder<UnconfiguredBuildTarget>()
        index.getFwdDeps(generation2, targetList("//java/com/facebook/buck/model:model"), commit2modelFwdDeps)
        assertEquals(commit2modelFwdDeps.build(), targetSet("//java/com/facebook/buck/base:base"))

        val commit3modelFwdDeps = ImmutableSet.Builder<UnconfiguredBuildTarget>()
        index.getFwdDeps(generation3, targetList("//java/com/facebook/buck/model:model"), commit3modelFwdDeps)
        assertEquals(commit3modelFwdDeps.build(), targetSet("//java/com/facebook/buck/base:base", "//java/com/facebook/buck/util:util"))

        val commit3utilFwdDeps = ImmutableSet.Builder<UnconfiguredBuildTarget>()
        index.getFwdDeps(generation3, targetList("//java/com/facebook/buck/util:util"), commit3utilFwdDeps)
        assertEquals(commit3utilFwdDeps.build(), targetSet("//java/com/facebook/buck/base:base"))
    }

    @Test
    fun getTargetNodes() {
        val (index, generations) = loadIndex("index_test_targets_and_deps.json")
        val generation5 = generations[4]

        val targetNodes = index.getTargetNodes(generation5, targetList(
                "//java/com/facebook/buck/base:base",
                "//java/com/facebook/buck/model:model",
                "//java/com/facebook/buck/util:util"
        ))
        assertEquals(targetNodes[0]!!.targetNode.ruleType.name, "java_library")
        assertNull("model was deleted at commit 5", targetNodes[1])
        assertEquals(targetNodes[2]!!.deps, targetSet("//java/com/facebook/buck/base:base"))

        assertEquals(targetNodes[0], index.getTargetNode(generation5, "//java/com/facebook/buck/base:base".buildTarget()))
        assertEquals(targetNodes[1], null)
    }

    @Test
    fun getTargetsInBasePath() {
        val (index, generations) = loadIndex("index_test_targets_and_deps.json")
        val generation = generations[5]

        assertEquals(
                "Should return null for directory with no build file.",
                null,
                index.getTargetsInBasePath(generation, FsAgnosticPath.of("empty")))

        assertEquals(
                "Should return empty list for build file that defines no rules",
                emptyList<UnconfiguredBuildTarget>(),
                index.getTargetsInBasePath(generation, FsAgnosticPath.of("empty/build/file")))

        assertEquals(
                "Should return singleton list for build file that defines one rule.",
                targetList("//java/com/facebook/buck/base:base"),
                index.getTargetsInBasePath(generation, FsAgnosticPath.of("java/com/facebook/buck/base")))
    }

    @Test
    fun getTargetsInOwningBuildPackage() {
        val (index, generations) = loadIndex("index_test_targets_and_deps.json")
        val generation = generations[5]

        assertEquals(
                "Should return null for directory with no build file ancestor.",
                null,
                index.getTargetsInOwningBuildPackage(generation, FsAgnosticPath.of("empty/build")))

        assertEquals(
                "Should return empty list for build file that defines no rules",
                Pair(FsAgnosticPath.of("empty/build/file"), emptyList<UnconfiguredBuildTarget>()),
                index.getTargetsInOwningBuildPackage(generation, FsAgnosticPath.of("empty/build/file")))

        assertEquals(
                "Should return singleton list for build file that defines one rule.",
                Pair(FsAgnosticPath.of("java/com/facebook/buck/base"), targetList("//java/com/facebook/buck/base:base")),
                index.getTargetsInOwningBuildPackage(generation, FsAgnosticPath.of("java/com/facebook/buck/base")))

        assertEquals(
                "Should find appropriate build target in ancestor directory.",
                Pair(FsAgnosticPath.of("deep/package"), targetList("//deep/package:lib")),
                index.getTargetsInOwningBuildPackage(generation, FsAgnosticPath.of("deep/package/src/com/example")))
    }

    @Test
    fun emptyChangesShouldReturnSameIndex() {
        val (index, generations) = loadIndex("index_test_targets_and_deps.json")
        val generation = generations[1]
        val changes = BuildPackageChanges()
        val localizedIndex = index.createIndexForGenerationWithLocalChanges(generation, changes)
        assertSame(index, localizedIndex)
    }

    /**
     * If someone adds a comment to a build file, it should yield a non-empty [BuildPackageChanges], but the
     * resulting [Deltas] should be empty, so [Index.createIndexForGenerationWithLocalChanges]
     * should still return the existing [Index].
     */
    @Test
    fun emptyDeltasShouldReturnSameIndex() {
        val (index, generations) = loadIndex("index_test_targets_and_deps.json")
        val generation = generations[1]
        val changes = BuildPackageChanges(
                modifiedBuildPackages = listOf(
                        BuildPackage(
                                FsAgnosticPath.of("java/com/facebook/buck/model"),
                                setOf(
                                        createRawRule(
                                                "//java/com/facebook/buck/model:model",
                                                listOf("//java/com/facebook/buck/base:base"),
                                                mapOf("buck.type" to "java_library"))
                                )
                        )
                )
        )
        val localizedIndex = index.createIndexForGenerationWithLocalChanges(generation, changes)
        assertSame(index, localizedIndex)
    }

    @Test
    fun addedDepsInLocalChanges() {
        val (index, generations) = loadIndex("index_test_targets_and_deps.json")
        val generation = generations[1]
        // Modify :base so that it depends on a new target, :example.
        val changes = BuildPackageChanges(
                addedBuildPackages = listOf(BuildPackage(
                        FsAgnosticPath.of("java/com/example"),
                        setOf(
                                createRawRule("//java/com/example:example", emptyList())
                        )
                )),
                modifiedBuildPackages = listOf(BuildPackage(
                        FsAgnosticPath.of("java/com/facebook/buck/base"),
                        setOf(
                                createRawRule("//java/com/facebook/buck/base:base", listOf("//java/com/example:example"))
                        )
                ))
        )
        val localizedIndex = index.createIndexForGenerationWithLocalChanges(generation, changes)

        assertEquals(
                ":example should be included in the universe of targets.",
                targetSet(
                        "//java/com/example:example",
                        "//java/com/facebook/buck/base:base",
                        "//java/com/facebook/buck/model:model"
                ),
                localizedIndex.getTargets(generation).toSet())
        assertEquals(
                ":example should be included in the transitive deps of :model.",
                targetSet(
                        "//java/com/example:example",
                        "//java/com/facebook/buck/base:base",
                        "//java/com/facebook/buck/model:model"
                ),
                localizedIndex.getTransitiveDeps(generation, targetSequence("//java/com/facebook/buck/model:model")))
        assertEquals(
                ":example should appear under its corresponding base path.",
                targetList(
                        "//java/com/example:example"
                ),
                localizedIndex.getTargetsInBasePath(generation, FsAgnosticPath.of("java/com/example")))
    }

    @Test
    fun removedDepsInLocalChanges() {
        val (index, generations) = loadIndex("index_test_targets_and_deps.json")
        val generation = generations[3]
        // Remove :util and modify :model so it no longer depends on it.
        val changes = BuildPackageChanges(
                modifiedBuildPackages = listOf(BuildPackage(
                        FsAgnosticPath.of("java/com/facebook/buck/model"),
                        setOf(
                                createRawRule(
                                        "//java/com/facebook/buck/model:model",
                                        listOf("//java/com/facebook/buck/base:base")
                                )
                        )
                )),
                removedBuildPackages = listOf(FsAgnosticPath.of("java/com/facebook/buck/util"))
        )
        val localizedIndex = index.createIndexForGenerationWithLocalChanges(generation, changes)

        assertEquals(
                ":util should no longer be included in the universe of targets.",
                targetSet(
                        "//java/com/facebook/buck/base:base",
                        "//java/com/facebook/buck/model:model"
                ),
                localizedIndex.getTargets(generation).toSet())
        assertEquals(
                ":base is now the lone target in the transitive deps of :model.",
                targetSet(
                        "//java/com/facebook/buck/base:base",
                        "//java/com/facebook/buck/model:model"
                ),
                localizedIndex.getTransitiveDeps(generation, targetSequence("//java/com/facebook/buck/model:model")))
    }

    @Test
    fun getReverseDeps() {
        val (index, generations) = loadIndex("graph_with_many_edges.json")
        val generation0 = generations[0]
        assertEquals(
                "Nothing depends on //:A.",
                targetSet(),
                index.getReverseDeps(generation0, targetList("//:A"))
        )
        assertEquals(
                "Some rules depend on //:D.",
                targetSet("//:A", "//:B"),
                index.getReverseDeps(generation0, targetList("//:D"))
        )
        assertEquals(
                "Note that //:A is included in the output even though it is one of the inputs " +
                "because it is an rdep of //:D.",
                targetSet("//:A", "//:B"),
                index.getReverseDeps(generation0, targetList("//:A", "//:D"))
        )
        assertEquals(
                "Many rules depend on //:I.",
                targetSet("//:A", "//:D", "//:E", "//:F", "//:G", "//other:pkg"),
                index.getReverseDeps(generation0, targetList("//:I"))
        )

        val generation1 = generations[1]
        assertEquals(
                "//other:pkg is removed in generation1.",
                targetSet("//:A", "//:D", "//:E", "//:F", "//:G"),
                index.getReverseDeps(generation1, targetList("//:I"))
        )

        fun performAssertions(generation: Int, index: Index) {
            assertEquals(
                    "//:A is removed in generation2.",
                    targetSet(),
                    index.getReverseDeps(generation, targetList("//:A"))
            )
            assertEquals(
                    targetSet("//:F", "//:G", "//:Z"),
                    index.getReverseDeps(generation, targetList("//:I"))
            )
            assertEquals(
                    targetSet("//:I", "//:Z"),
                    index.getReverseDeps(generation, targetList("//:Y"))
            )
            assertEquals(
                    targetSet("//:B"),
                    index.getReverseDeps(generation, targetList("//:Z"))
            )
        }

        val generation2 = generations[2]
        performAssertions(generation2, index)

        // Perform the same changes in generation2 as if they were local changes and verify that all
        // of the assertions still hold.
        val changes = BuildPackageChanges(
                modifiedBuildPackages = listOf(BuildPackage(
                        FsAgnosticPath.of(""),
                        setOf(
                                createRawRule(
                                        "//:B",
                                        listOf("//:Z")
                                ),
                                createRawRule(
                                        "//:C",
                                        listOf()
                                ),
                                createRawRule(
                                        "//:F",
                                        listOf("//:I")
                                ),
                                createRawRule(
                                        "//:G",
                                        listOf("//:I")
                                ),
                                createRawRule(
                                        "//:I",
                                        listOf("//:Y")
                                ),
                                createRawRule(
                                        "//:Y",
                                        listOf()
                                ),
                                createRawRule(
                                        "//:Z",
                                        listOf("//:F", "//:I", "//:Y")
                                )
                        )
                ))
        )
        val localizedIndex = index.createIndexForGenerationWithLocalChanges(generation1, changes)
        performAssertions(generation1, localizedIndex)
    }
}

private fun loadIndex(resource: String): Pair<Index, List<Int>> {
    val (index, indexAppender) = IndexFactory.createIndex()
    val commits = populateIndexFromStream(indexAppender, IndexTest::class.java.getResourceAsStream(resource))
    val generations = commits.map { requireNotNull(indexAppender.getGeneration(it)) }
    return Pair(index, generations)
}

private fun targetList(vararg targets: String): List<UnconfiguredBuildTarget> =
        targets.map(BuildTargets::parseOrThrow)

private fun targetSequence(vararg targets: String): Sequence<UnconfiguredBuildTarget> =
        targets.asSequence().map(BuildTargets::parseOrThrow)

private fun targetSet(vararg targets: String): Set<UnconfiguredBuildTarget> =
        targetSequence(*targets).toSet()

private fun String.buildTarget(): UnconfiguredBuildTarget {
    return BuildTargets.parseOrThrow(this)
}

private fun createTargetNode(target: String, attributes: Map<String, Any> = mapOf()): RawTargetNode = ServiceRawTargetNode(target.buildTarget(), JAVA_LIBRARY, ImmutableMap.copyOf(attributes))

private fun createRawRule(target: String, deps: List<String>, attributes: Map<String, Any> = mapOf()) = RawBuildRule(createTargetNode(target, attributes), targetSet(*deps.toTypedArray()))
