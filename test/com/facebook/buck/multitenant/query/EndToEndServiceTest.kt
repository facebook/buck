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

import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.facebook.buck.multitenant.importer.RuleTypeFactory
import com.facebook.buck.multitenant.importer.ServiceRawTargetNode
import com.facebook.buck.multitenant.importer.populateIndexFromStream
import com.facebook.buck.multitenant.service.BuildPackage
import com.facebook.buck.multitenant.service.BuildPackageChanges
import com.facebook.buck.multitenant.service.BuildTargets
import com.facebook.buck.multitenant.service.FsChange
import com.facebook.buck.multitenant.service.FsChanges
import com.facebook.buck.multitenant.service.FsToBuildPackageChangeTranslator
import com.facebook.buck.multitenant.service.Index
import com.facebook.buck.multitenant.service.IndexAppender
import com.facebook.buck.multitenant.service.IndexFactory
import com.facebook.buck.multitenant.service.RawBuildRule
import com.google.common.collect.ImmutableMap
import org.junit.Assert.assertEquals
import org.junit.Test

private val BUCK_RULE_WITH_DEPS = """
java_binary(
    name = "buck",
    deps = [
        "//java/com/example:B",
    ],
)
""".trimIndent()

/**
 * This test is meant to illustrate how the service would be used end-to-end. Here we
 * programmatically drive canned data through the service, so it is not tied to a particular RPC
 * mechanism, such as Thrift or gRPC.
 */
class EndToEndServiceTest {
    @Test
    fun doQueryWithNoLocalChanges() {
        val translator = FakeFsToBuildPackageChangeTranslator()
        val service = createService("diamond_dependency_graph.json", translator)

        val fsChanges = FsChanges("608fd7bdf9")
        val depsWithNoFileChanges = service.handleBuckQueryRequest(
                "deps(//java/com/facebook/buck:buck)",
                fsChanges)
        assertEquals(setOf(
                "//java/com/facebook/buck:buck"
        ), depsWithNoFileChanges.toSet())
    }

    @Test
    fun doQueryWithLocallyAddedBuildFile() {
        val translator = FakeFsToBuildPackageChangeTranslator()
        val service = createService("diamond_dependency_graph.json", translator)

        val fsChangesWithAddedBuildFile = FsChanges("608fd7bdf9", added = listOf(
                FsChange.Added(
                        FsAgnosticPath.of("java/com/newpkg/BUCK"), BUCK_RULE_WITH_DEPS.toByteArray())
        ))
        val universeWithBuildFileAddition = service.handleBuckQueryRequest(
                "//...",
                fsChangesWithAddedBuildFile)
        assertEquals(
                "Universe should now include //java/com/newpkg:buck",
                setOf(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/example:C",
                        "//java/com/example:D",
                        "//java/com/facebook/buck:buck",
                        "//java/com/newpkg:buck",
                        "//test/com/example:script",
                        "//test/com/example:test"
                ), universeWithBuildFileAddition.toSet())
    }

    @Test
    fun doQueryWithLocallyModifiedBuildFile() {
        val translator = FakeFsToBuildPackageChangeTranslator()
        val service = createService("diamond_dependency_graph.json", translator)

        val fsChangesWithModifiedBuildFile = FsChanges("608fd7bdf9", modified = listOf(
                FsChange.Modified(
                        FsAgnosticPath.of("java/com/facebook/buck/BUCK"), BUCK_RULE_WITH_DEPS.toByteArray())
        ))
        val depsWithBuildFileChange = service.handleBuckQueryRequest(
                "deps(//java/com/facebook/buck:buck)",
                fsChangesWithModifiedBuildFile)
        assertEquals(
                "Local change that adds dep for :B also adds transitive dep to :A.",
                setOf(
                        "//java/com/example:A",
                        "//java/com/example:B",
                        "//java/com/facebook/buck:buck"
                ), depsWithBuildFileChange.toSet())
    }
}

/**
 * Note that a real implementation of the service would subscribe to new commits to the repo and use
 * the changeTranslator to take the commit data and turn it into a [BuildPackageChanges] that it can
 * record via [IndexAppender.addCommitData].
 */
private class FakeMultitenantService(
        private val index: Index,
        private val indexAppender: IndexAppender,
        private val changeTranslator: FsToBuildPackageChangeTranslator
) {
    fun handleBuckQueryRequest(query: String, changes: FsChanges): List<String> {
        val generation = indexAppender.getGeneration(changes.commit)
                ?: throw IllegalArgumentException("commit '${changes.commit}' not indexed by service")
        val buildPackageChanges = changeTranslator.translateChanges(changes)
        val localizedIndex = index.createIndexForGenerationWithLocalChanges(generation, buildPackageChanges)
        val cellToBuildFileName = mapOf("" to "BUCK")
        var env = MultitenantQueryEnvironment(localizedIndex, generation, cellToBuildFileName)
        val queryTargets = env.evaluateQuery(query)
        return queryTargets.map { it.toString() }
    }
}

/**
 * Fake implementation of [FsToBuildPackageChangeTranslator] that looks for very specific changes.
 * In a true implementation of this interface, if a `BUCK` or `.bzl` file were modified, we would
 * need to re-parse it to determine the build package changes. Also, the addition or removal of
 * ordinary source files should trigger their owning packages to be re-parsed because glob()s may
 * have been affected.
 */
private class FakeFsToBuildPackageChangeTranslator : FsToBuildPackageChangeTranslator {
    override fun translateChanges(fsChanges: FsChanges): BuildPackageChanges {
        val addedBuildPackageChanges: MutableList<BuildPackage> = mutableListOf()
        val modifiedBuildPackageChanges: MutableList<BuildPackage> = mutableListOf()
        val removedBuildPackages: MutableList<FsAgnosticPath> = mutableListOf()

        fsChanges.added.forEach(fun(added: FsChange.Added) {
            val contents = added.contents ?: return
            if (added.path == FsAgnosticPath.of("java/com/newpkg/BUCK") &&
                    contents.contentEquals(BUCK_RULE_WITH_DEPS.toByteArray())) {
                addedBuildPackageChanges.add(
                        BuildPackage(
                                FsAgnosticPath.of("java/com/newpkg"),
                                setOf(RawBuildRule(
                                        ServiceRawTargetNode(
                                                BuildTargets.parseOrThrow("//java/com/newpkg:buck"),
                                                RuleTypeFactory.createBuildRule("java_binary"),
                                                ImmutableMap.of()
                                        ),
                                        setOf(BuildTargets.parseOrThrow("//java/com/example:B"))
                                ))
                        )
                )

            }
        })

        fsChanges.modified.forEach(fun(modified: FsChange.Modified) {
            val contents = modified.contents ?: return
            if (modified.path == FsAgnosticPath.of("java/com/facebook/buck/BUCK") &&
                    contents.contentEquals(BUCK_RULE_WITH_DEPS.toByteArray())) {
                modifiedBuildPackageChanges.add(
                        BuildPackage(
                                FsAgnosticPath.of("java/com/facebook/buck"),
                                setOf(RawBuildRule(
                                        ServiceRawTargetNode(
                                                BuildTargets.parseOrThrow("//java/com/facebook/buck:buck"),
                                                RuleTypeFactory.createBuildRule("java_binary"),
                                                ImmutableMap.of()
                                        ),
                                        setOf(BuildTargets.parseOrThrow("//java/com/example:B"))
                                ))
                        )
                )
            }
        })

        return BuildPackageChanges(addedBuildPackageChanges, modifiedBuildPackageChanges, removedBuildPackages)
    }
}

private fun createService(resource: String, changeTranslator: FsToBuildPackageChangeTranslator): FakeMultitenantService {
    val (index, indexAppender) = IndexFactory.createIndex()
    populateIndexFromStream(indexAppender, EndToEndServiceTest::class.java.getResourceAsStream(resource))
    return FakeMultitenantService(index, indexAppender, changeTranslator)
}
