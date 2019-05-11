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

    /** Stores commit to generation mappings created by [addCommitData]. */
    private val commitToGeneration = ConcurrentHashMap<Commit, Int>()

    /**
     * @return the generation that corresponds to the specified commit or `null` if no such
     *     generation is available
     */
    fun getGeneration(commit: Commit): Int? {
        return commitToGeneration[commit]
    }

    /**
     * Currently, the caller is responsible for ensuring that addCommitData() is invoked
     * serially (never concurrently) for each commit in a chain of version control history.
     *
     * The expectation is that the caller will use something like `buck audit rules` based on the
     * changes in the commit to produce the Changes object to pass to this method.
     */
    fun addCommitData(commit: Commit, changes: Changes) {
        // Although the first portion of this method requires read-only access to all of the
        // data structures, we want to be sure that only one caller is invoking addCommitData() at a
        // time.

        // First, determine if any of the changes from the commits require new values to be added
        // to the generation map.
        val currentGeneration = generation.get()
        val deltas = determineDeltas(toInternalChanges(changes), currentGeneration)

        // If there are no updates to any of the generation maps, add a new entry for the current
        // commit using the existing generation in the commitToGeneration map.
        if (deltas.isEmpty()) {
            val oldValue = commitToGeneration.putIfAbsent(commit, currentGeneration)
            require(oldValue == null) { "Should not have existing value for $commit" }
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
                val buildTarget: UnconfiguredBuildTarget
                val newNodeAndDeps: InternalRawBuildRule?
                when (delta) {
                    is RuleDelta.Updated -> {
                        buildTarget = delta.rule.targetNode.buildTarget
                        newNodeAndDeps = delta.rule
                    }
                    is RuleDelta.Removed -> {
                        buildTarget = delta.buildTarget
                        newNodeAndDeps = null
                    }
                }
                ruleMap.addVersion(buildTargetCache.get(buildTarget), newNodeAndDeps, nextGeneration)
            }
        }

        val oldValue = commitToGeneration.putIfAbsent(commit, nextGeneration)
        require(oldValue == null) { "Should not have existing value for $commit" }
        generation.set(nextGeneration)
    }

    private fun determineDeltas(changes: InternalChanges, generation: Generation): Deltas {
        val buildPackageDeltas = mutableListOf<BuildPackageDelta>()
        val ruleDeltas = mutableListOf<RuleDelta>()

        indexGenerationData.withBuildPackageMap { buildPackageMap ->
            for (added in changes.addedBuildPackages) {
                val oldRules = buildPackageMap.getVersion(added.buildFileDirectory, generation)
                if (oldRules != null) {
                    throw IllegalArgumentException("Build package to add already existed at ${added
                            .buildFileDirectory} for generation $generation")
                }

                val ruleNames = getRuleNames(added.rules)
                buildPackageDeltas.add(BuildPackageDelta.Updated(added.buildFileDirectory, ruleNames))
                for (rule in added.rules) {
                    ruleDeltas.add(RuleDelta.Updated(rule))
                }
            }

            for (removed in changes.removedBuildPackages) {
                val oldRules = requireNotNull(buildPackageMap.getVersion(removed, generation)) {
                    "Build package to remove did not exist at $removed for generation $generation"
                }

                buildPackageDeltas.add(BuildPackageDelta.Removed(removed))
                for (ruleName in oldRules) {
                    val buildTarget = BuildTargets.createBuildTargetFromParts(removed, ruleName)
                    ruleDeltas.add(RuleDelta.Removed(buildTarget))
                }
            }

            indexGenerationData.withRuleMap { ruleMap ->
                for (modified in changes.modifiedBuildPackages) {
                    val oldRuleNames = requireNotNull(buildPackageMap.getVersion(modified
                            .buildFileDirectory,
                            generation)) {
                        "No version found for build file in ${modified.buildFileDirectory} for " +
                                "generation $generation"
                    }

                    val oldRules = oldRuleNames.asSequence().map { oldRuleName: String ->
                        val buildTarget = BuildTargets.createBuildTargetFromParts(modified.buildFileDirectory, oldRuleName)
                        requireNotNull(ruleMap.getVersion(buildTargetCache.get(buildTarget),
                                generation)) {
                            "Missing deps for $buildTarget at generation $generation"
                        }
                    }.toSet()

                    val newRules = modified.rules
                    // Compare oldRules and newRules to see whether the build package actually changed.
                    // Keep track of the individual rule changes so we need not recompute them later.
                    val ruleChanges = diffRules(oldRules, newRules)
                    if (ruleChanges.isNotEmpty()) {
                        buildPackageDeltas.add(BuildPackageDelta.Updated(modified
                                .buildFileDirectory, getRuleNames(newRules)))
                        ruleDeltas.addAll(ruleChanges)
                    }
                }
            }
        }

        return Deltas(buildPackageDeltas, ruleDeltas)
    }

    private fun toInternalChanges(changes: Changes): InternalChanges {
        return InternalChanges(changes.addedBuildPackages.map { toInternalBuildPackage(it) }.toList(),
                changes.modifiedBuildPackages.map { toInternalBuildPackage(it) }.toList(),
                changes.removedBuildPackages
        )
    }

    private fun toInternalBuildPackage(buildPackage: BuildPackage): InternalBuildPackage {
        return InternalBuildPackage(buildPackage.buildFileDirectory, buildPackage.rules.map { toInternalRawBuildRule(it) }.toSet())
    }

    private fun toInternalRawBuildRule(rawBuildRule: RawBuildRule): InternalRawBuildRule {
        return InternalRawBuildRule(rawBuildRule.targetNode, toBuildTargetSet(rawBuildRule.deps))
    }

    private fun toBuildTargetSet(targets: Set<UnconfiguredBuildTarget>): BuildTargetSet {
        val ids = targets.map { buildTargetCache.get(it) }.toIntArray()
        ids.sort()
        return ids
    }
}

private data class Deltas(val buildPackageDeltas: List<BuildPackageDelta>,
                          val ruleDeltas: List<RuleDelta>) {
    fun isEmpty(): Boolean {
        return buildPackageDeltas.isEmpty() && ruleDeltas.isEmpty()
    }
}

private fun getRuleNames(rules: Set<InternalRawBuildRule>): Set<String> {
    return rules.asSequence().map { it.targetNode.buildTarget.name }.toSet()
}
