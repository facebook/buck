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

package com.facebook.buck.multitenant.query

import com.facebook.buck.core.cell.nameresolver.CellNameResolver
import com.facebook.buck.core.cell.nameresolver.TestCellNameResolver
import com.facebook.buck.core.path.ForwardRelativePath
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.facebook.buck.multitenant.service.BuildPackage
import com.facebook.buck.multitenant.service.BuildPackageChanges
import com.facebook.buck.multitenant.service.BuildTargets
import com.facebook.buck.multitenant.service.FsChange
import com.facebook.buck.multitenant.service.FsChanges
import com.facebook.buck.multitenant.service.FsToBuildPackageChangeTranslator
import com.facebook.buck.multitenant.service.IndexFactory
import com.facebook.buck.multitenant.service.RawBuildRule
import com.facebook.buck.multitenant.service.RuleTypeFactory
import com.facebook.buck.multitenant.service.ServiceUnconfiguredTargetNode
import com.facebook.buck.multitenant.service.buckJsonToBuildPackageParser
import com.facebook.buck.multitenant.service.populateIndexFromStream
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
    @Test fun doQueryWithNoLocalChanges() {
        val translator = FakeFsToBuildPackageChangeTranslator()
        val service = createService("diamond_dependency_graph.json", translator, TestCellNameResolver.forRoot())

        val fsChanges = FsChanges("608fd7bdf9")
        val depsWithNoFileChanges =
            service.handleBuckQueryRequest("deps(//java/com/facebook/buck:buck)", fsChanges)
        assertEquals(setOf("//java/com/facebook/buck:buck"), depsWithNoFileChanges.toSet())
    }

    @Test fun doQueryWithLocallyAddedBuildFile() {
        val translator = FakeFsToBuildPackageChangeTranslator()
        val service = createService("diamond_dependency_graph.json", translator, TestCellNameResolver.forRoot())

        val fsChangesWithAddedBuildFile = FsChanges("608fd7bdf9", added = listOf(
            FsChange.Added(FsAgnosticPath.of("java/com/newpkg/BUCK"),
                BUCK_RULE_WITH_DEPS.toByteArray())))
        val universeWithBuildFileAddition =
            service.handleBuckQueryRequest("//...", fsChangesWithAddedBuildFile)
        assertEquals("Universe should now include //java/com/newpkg:buck",
            setOf("//java/com/example:A", "//java/com/example:B", "//java/com/example:C",
                "//java/com/example:D", "//java/com/facebook/buck:buck", "//java/com/newpkg:buck",
                "//test/com/example:script", "//test/com/example:test"),
            universeWithBuildFileAddition.toSet())
    }

    @Test fun doQueryWithLocallyModifiedBuildFile() {
        val translator = FakeFsToBuildPackageChangeTranslator()
        val service = createService("diamond_dependency_graph.json", translator, TestCellNameResolver.forRoot())

        val fsChangesWithModifiedBuildFile = FsChanges("608fd7bdf9", modified = listOf(
            FsChange.Modified(FsAgnosticPath.of("java/com/facebook/buck/BUCK"),
                BUCK_RULE_WITH_DEPS.toByteArray())))
        val depsWithBuildFileChange =
            service.handleBuckQueryRequest("deps(//java/com/facebook/buck:buck)",
                fsChangesWithModifiedBuildFile)
        assertEquals("Local change that adds dep for :B also adds transitive dep to :A.",
            setOf("//java/com/example:A", "//java/com/example:B", "//java/com/facebook/buck:buck"),
            depsWithBuildFileChange.toSet())
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
        val removedBuildPackages: MutableList<ForwardRelativePath> = mutableListOf()

        fsChanges.added.forEach { added ->
            val contents = added.contents ?: return@forEach
            if (added.path == FsAgnosticPath.of("java/com/newpkg/BUCK") && contents.contentEquals(
                    BUCK_RULE_WITH_DEPS.toByteArray())) {
                addedBuildPackageChanges.add(BuildPackage(FsAgnosticPath.of("java/com/newpkg"),
                    setOf(RawBuildRule(
                        ServiceUnconfiguredTargetNode(BuildTargets.parseOrThrow("//java/com/newpkg:buck"),
                            RuleTypeFactory.createBuildRule("java_binary"), ImmutableMap.of()),
                        setOf(BuildTargets.parseOrThrow("//java/com/example:B"))))))
            }
        }

        fsChanges.modified.forEach { modified ->
            val contents = modified.contents ?: return@forEach
            if (modified.path == FsAgnosticPath.of(
                    "java/com/facebook/buck/BUCK") && contents.contentEquals(
                    BUCK_RULE_WITH_DEPS.toByteArray())) {
                modifiedBuildPackageChanges.add(
                    BuildPackage(FsAgnosticPath.of("java/com/facebook/buck"), setOf(RawBuildRule(
                        ServiceUnconfiguredTargetNode(
                            BuildTargets.parseOrThrow("//java/com/facebook/buck:buck"),
                            RuleTypeFactory.createBuildRule("java_binary"), ImmutableMap.of()),
                        setOf(BuildTargets.parseOrThrow("//java/com/example:B"))))))
            }
        }

        return BuildPackageChanges(addedBuildPackageChanges, modifiedBuildPackageChanges,
            removedBuildPackages)
    }
}

private fun createService(
    resource: String,
    changeTranslator: FsToBuildPackageChangeTranslator,
    cellNameResolver: CellNameResolver
): MultitenantServiceStub {
    val (index, indexAppender) = IndexFactory.createIndex()
    populateIndexFromStream(indexAppender,
        EndToEndServiceTest::class.java.getResourceAsStream("data/$resource"),
        ::buckJsonToBuildPackageParser)
    return MultitenantServiceStub(index, indexAppender, changeTranslator, cellNameResolver)
}
