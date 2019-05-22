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

import com.facebook.buck.core.model.UnconfiguredBuildTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Appends data that backs an [Index].
 *
 * By design, this class does not have a direct reference to [Index]: all mutations will be
 * performed via the [MutableIndexGenerationData].
 */
class IndexAppender internal constructor(
        private val indexGenerationData: MutableIndexGenerationData,
        private val buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>) {

    /**
     * id of the current generation. should only be read and set by [addCommitData] as clients
     * should get generation ids via [commitToGeneration]/[getGeneration].
     */
    private val generation = AtomicInteger()

    private val latestCommit = AtomicReference<Commit>()

    /** Stores commit to generation mappings created by [addCommitData]. */
    private val commitToGeneration = ConcurrentHashMap<Commit, Int>()

    /**
     * @return the generation that corresponds to the specified commit or `null` if no such
     *     generation is available
     */
    fun getGeneration(commit: Commit): Int? = commitToGeneration[commit]

    /**
     * @return the commit most recently added to the index or `null` if no commits have been added.
     *     If the result is non-null, then it is guaranteed to return a non-null value when used
     *     with [getGeneration].
     */
    fun getLatestCommit(): Commit? = latestCommit.get()

    /**
     * Currently, the caller is responsible for ensuring that addCommitData() is invoked
     * serially (never concurrently) for each commit in a chain of version control history.
     *
     * The expectation is that the caller will use something like `buck audit rules` based on the
     * changes in the commit to produce the BuildPackageChanges object to pass to this method.
     */
    fun addCommitData(commit: Commit, changes: BuildPackageChanges) {
        // Although the first portion of this method requires read-only access to all of the
        // data structures, we want to be sure that only one caller is invoking addCommitData() at a
        // time.

        // First, determine if any of the changes from the commits require new values to be added
        // to the generation map.
        val currentGeneration = generation.get()
        val deltas = determineDeltas(currentGeneration, changes, indexGenerationData, buildTargetCache)

        // If there are no updates to any of the generation maps, add a new entry for the current
        // commit using the existing generation in the commitToGeneration map.
        if (deltas.isEmpty()) {
            addMapping(commit, currentGeneration, updateGeneration = false)
            return
        }

        val nextGeneration = currentGeneration + 1

        // If any generation map needs to be updated, grab the write lock, bump the generation for
        // all of the maps, insert all of the new values into the maps, and as a final step, add a
        // new entry to commitToGeneration with the new generation value.
        indexGenerationData.withMutableBuildPackageMap { buildPackageMap ->
            for (delta in deltas.buildPackageDeltas) {
                when (delta) {
                    is BuildPackageDelta.Updated -> {
                        buildPackageMap.addVersion(delta.directory, delta.rules, nextGeneration)
                    }
                    is BuildPackageDelta.Removed -> {
                        buildPackageMap.addVersion(delta.directory, null, nextGeneration)
                    }
                }
            }
        }

        indexGenerationData.withMutableRuleMap { ruleMap ->
            for (delta in deltas.ruleDeltas) {
                val (buildTarget, newNodeAndDeps) = when (delta) {
                    is RuleDelta.Added -> {
                        Pair(delta.rule.targetNode.buildTarget, delta.rule)
                    }
                    is RuleDelta.Modified -> {
                        Pair(delta.newRule.targetNode.buildTarget, delta.newRule)
                    }
                    is RuleDelta.Removed -> {
                        Pair(delta.rule.targetNode.buildTarget, null)
                    }
                }
                ruleMap.addVersion(buildTargetCache.get(buildTarget), newNodeAndDeps, nextGeneration)
            }
        }

        indexGenerationData.withMutableRdepsMap { rdepsMap ->
            deltas.rdepsDeltas.forEach { buildTargetId, rdepsSet ->
                rdepsMap.addVersion(buildTargetId, rdepsSet, nextGeneration)
            }
        }

        addMapping(commit, nextGeneration, updateGeneration = true)
    }

    private fun addMapping(commit: Commit, nextGeneration: Generation, updateGeneration: Boolean) {
        val oldValue = commitToGeneration.putIfAbsent(commit, nextGeneration)
        require(oldValue == null) { "Should not have existing value for $commit" }
        latestCommit.set(commit)
        if (updateGeneration) {
            generation.set(nextGeneration)
        }
    }
}
