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

package com.facebook.buck.multitenant.runner

import com.facebook.buck.core.cell.name.CanonicalCellName
import com.facebook.buck.core.cell.nameresolver.CellNameResolver
import com.facebook.buck.core.path.ForwardRelativePath
import com.facebook.buck.multitenant.query.MultitenantQueryEnvironment
import com.facebook.buck.multitenant.service.DefaultFsToBuildPackageChangeTranslator
import com.facebook.buck.multitenant.service.FsChanges
import com.facebook.buck.multitenant.service.Index
import com.facebook.buck.multitenant.service.IndexAppender
import com.facebook.buck.query.QueryNormalizer
import com.google.common.collect.ImmutableMap
import java.nio.file.Path
import java.util.Optional

/**
 * Note that a real implementation of the service would subscribe to new commits to the repo and use
 * the changeTranslator to take the commit data and turn it into a [BuildPackageChanges] that it can
 * record via [IndexAppender.addCommitData].
 */
class FakeMultitenantService(
    private val index: Index,
    private val indexAppender: IndexAppender,
    private val buildFileName: ForwardRelativePath,
    private val projectRoot: Path
) {
    fun handleBuckQueryRequest(query: String, changes: FsChanges): List<String> {
        val generation = requireNotNull(indexAppender.getGeneration(changes.commit)) {
            "commit '${changes.commit}' not indexed by service"
        }
        val changeTranslator =
            DefaultFsToBuildPackageChangeTranslator(buildFileName = buildFileName,
                projectRoot = projectRoot,
                existenceChecker = { path -> index.packageExists(generation, path) },
                equalityChecker = { buildPackage ->
                    index.containsBuildPackage(generation, buildPackage)
                },
                includesProvider = { path -> index.getReverseIncludes(generation, path) })
        val buildPackageChanges = changeTranslator.translateChanges(changes)
        val localizedIndex =
            index.createIndexForGenerationWithLocalChanges(generation, buildPackageChanges)
        val cellToBuildFileName = mapOf("" to "BUCK")
        val env = MultitenantQueryEnvironment(localizedIndex, generation, cellToBuildFileName, object :
            CellNameResolver {
            override fun getName(localName: Optional<String>?): CanonicalCellName {
                TODO("not implemented")
            }

            override fun getNameIfResolvable(
                localName: Optional<String>?
            ): Optional<CanonicalCellName> {
                TODO("not implemented")
            }

            override fun getKnownCells(): ImmutableMap<Optional<String>, CanonicalCellName>? {
                TODO("not implemented")
            }
        })
        val queryTargets = env.evaluateQuery(QueryNormalizer.normalize(query))
        return queryTargets.map { it.toString() }
    }
}
