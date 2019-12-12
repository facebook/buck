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
import com.facebook.buck.multitenant.service.FsChanges
import com.facebook.buck.multitenant.service.FsToBuildPackageChangeTranslator
import com.facebook.buck.multitenant.service.Index
import com.facebook.buck.multitenant.service.IndexAppender

/**
 * Fake implementation of a service for tests
 */
class MultitenantServiceStub(
    private val index: Index,
    private val indexAppender: IndexAppender,
    private val fsToBuildPackageChangeTranslator: FsToBuildPackageChangeTranslator,
    private val cellNameResolver: CellNameResolver
) {
    fun handleBuckQueryRequest(query: String, changes: FsChanges): List<String> {
        val generation =
            indexAppender.getGeneration(changes.commit) ?: throw IllegalArgumentException(
                "commit '${changes.commit}' not indexed by service")
        val buildPackageChanges = fsToBuildPackageChangeTranslator.translateChanges(changes)
        val localizedIndex =
            index.createIndexForGenerationWithLocalChanges(generation, buildPackageChanges)
        val cellToBuildFileName = mapOf("" to "BUCK")
        val env = MultitenantQueryEnvironment(localizedIndex, generation, cellToBuildFileName, cellNameResolver)
        val queryTargets = env.evaluateQuery(query)
        return queryTargets.map { it.toString() }
    }
}
