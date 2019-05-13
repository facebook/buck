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
                targetSet("//java/com/facebook/buck/base:base"),
                index.getTransitiveDeps(generation2, "//java/com/facebook/buck/model:model".buildTarget())
        )
        assertEquals(
                targetSet("//java/com/facebook/buck/base:base", "//java/com/facebook/buck/util:util"),
                index.getTransitiveDeps(generation3, "//java/com/facebook/buck/model:model".buildTarget())
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
                                                listOf("//java/com/facebook/buck/base:base"))
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
                        "//java/com/facebook/buck/base:base"
                ),
                localizedIndex.getTransitiveDeps(generation, "//java/com/facebook/buck/model:model".buildTarget()))
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
                        "//java/com/facebook/buck/base:base"
                ),
                localizedIndex.getTransitiveDeps(generation, "//java/com/facebook/buck/model:model".buildTarget()))
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

private fun targetSet(vararg targets: String): Set<UnconfiguredBuildTarget> =
        targets.asSequence().map(BuildTargets::parseOrThrow).toSet()

private fun String.buildTarget(): UnconfiguredBuildTarget {
    return BuildTargets.parseOrThrow(this)
}

private fun createTargetNode(target: String): RawTargetNode = ServiceRawTargetNode(target.buildTarget(), JAVA_LIBRARY, ImmutableMap.of())

private fun createRawRule(target: String, deps: List<String>) = RawBuildRule(createTargetNode(target), targetSet(*deps.toTypedArray()))
