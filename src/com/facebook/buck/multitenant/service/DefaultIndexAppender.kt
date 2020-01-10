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

package com.facebook.buck.multitenant.service

import com.facebook.buck.core.model.UnconfiguredBuildTarget
import com.facebook.buck.multitenant.cache.AppendOnlyBidirectionalCache
import com.facebook.buck.multitenant.collect.Generation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Appends data that backs an [Index].
 *
 * By design, this class does not have a direct reference to [Index]: all mutations will be
 * performed via the [MutableIndexGenerationData].
 */
class DefaultIndexAppender internal constructor(
    private val indexGenerationData: MutableIndexGenerationData,
    private val buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>
) : IndexAppender {

    /**
     * id of the current generation. should only be read and set by [addCommitData] as clients
     * should get generation ids via [commitToGeneration]/[getGeneration].
     */
    private val generation = AtomicInteger()

    /** Stores commit to generation mappings created by [addCommitData]. */
    private val commitToGeneration = ConcurrentHashMap<Commit, Generation>()

    /**
     * Stores all commits in the order of insertion
     */
    private val commits = mutableListOf<CommitData>()
    private val commitIndex = mutableMapOf<Commit, Int>()
    private val commitLock = ReentrantReadWriteLock()

    /**
     * @return the generation that corresponds to the specified commit or `null` if no such
     *     generation is available
     */
    override fun getGeneration(commit: Commit): Generation? = commitToGeneration[commit]

    /**
     * @return the commit most recently added to the index or `null` if no commits have been added.
     *     If the result is non-null, then it is guaranteed to return a non-null value when used
     *     with [getGeneration].
     */
    override fun getLatestCommit(): Commit? = commitLock.read { commits.lastOrNull()?.commit }

    override fun commitExists(commit: Commit): Boolean = commitToGeneration.containsKey(commit)

    override fun getCommits(startCommit: Commit?, endCommit: Commit?): List<CommitData> {
        // return a copy of commits list, or sublist depending on if boundaries specified
        // if boundary is specified then it is expected to be in the index, if it is not then
        // empty list is returned
        return commitLock.read {
            if (startCommit == null && endCommit == null) {
                // fast path to return all commits
                commits.toList()
            } else {
                val start = if (startCommit == null) 0 else commitIndex.get(startCommit)
                    ?: return listOf()
                val end = if (endCommit == null) commits.size - 1 else commitIndex.get(endCommit)
                    ?: return listOf()
                if (start > end) {
                    listOf()
                } else {
                    commits.subList(start, end + 1)
                }
            }
        }
    }

    /**
     * Currently, the caller is responsible for ensuring that addCommitData() is invoked
     * serially (never concurrently) for each commit in a chain of version control history.
     *
     * The expectation is that the caller will use something like `buck audit rules` based on the
     * changes in the commit to produce the BuildPackageChanges object to pass to this method.
     */
    override fun addCommitData(commit: Commit, changes: BuildPackageChanges) {
        // Although the first portion of this method requires read-only access to all of the
        // data structures, we want to be sure that only one caller is invoking addCommitData() at a
        // time.

        // First, determine if any of the changes from the commits require new values to be added
        // to the generation map.
        val currentGeneration = generation.get()
        val deltas = determineDeltas(
            generation = currentGeneration,
            changes = changes,
            indexGenerationData = indexGenerationData,
            buildTargetCache = buildTargetCache)

        // If there are no updates to any of the generation maps, add a new entry for the current
        // commit using the existing generation in the commitToGeneration map.
        if (deltas.isEmpty()) {
            addMapping(commit = commit, nextGeneration = currentGeneration, updateGeneration = false)
            return
        }

        val nextGeneration = currentGeneration + 1

        // If any generation map needs to be updated, grab the write lock, bump the generation for
        // all of the maps, insert all of the new values into the maps, and as a final step, add a
        // new entry to commitToGeneration with the new generation value.
        indexGenerationData.withMutableBuildPackageMap { buildPackageMap ->
            deltas.buildPackageDeltas.forEach { delta ->
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
            deltas.ruleDeltas.forEach { delta ->
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
                ruleMap.addVersion(buildTargetCache.get(buildTarget), newNodeAndDeps,
                    nextGeneration)
            }
        }

        indexGenerationData.withMutableRdepsMap { rdepsMap ->
            deltas.rdepsDeltas.forEach { (buildTargetId, rdepsSet) ->
                rdepsMap.addVersion(buildTargetId, rdepsSet, nextGeneration)
            }
        }

        if (!deltas.includesDeltas.isEmpty()) {
            indexGenerationData.withMutableIncludesMap { (forwardIncludes, reverseIncludes) ->
                val (forwardDeltas, reverseDeltas) = deltas.includesDeltas
                forwardDeltas.forEach {
                    forwardIncludes.addVersion(it.key, it.value, nextGeneration)
                }
                reverseDeltas.forEach {
                    reverseIncludes.addVersion(it.key, it.value, nextGeneration)
                }
            }
        }

        addMapping(commit = commit, nextGeneration = nextGeneration, updateGeneration = true)
    }

    private fun addMapping(commit: Commit, nextGeneration: Generation, updateGeneration: Boolean) {
        require(!commitExists(commit)) { "Should not have existing value for $commit" }
        commitToGeneration[commit] = nextGeneration
        if (updateGeneration) {
            generation.set(nextGeneration)
        }
        val data = CommitData(commit = commit, timestampLoadedMillies = System.currentTimeMillis())
        commitLock.write {
            commits.add(data)
            commitIndex.put(commit, commits.size - 1)
        }
    }
}
