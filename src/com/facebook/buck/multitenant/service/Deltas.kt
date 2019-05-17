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

/**
 * Represents a collection of changes to apply to an [Index] to reflect [BuildPackageChanges] on top
 * of a [Generation].
 */
internal data class Deltas(val buildPackageDeltas: List<BuildPackageDelta>,
                           val ruleDeltas: List<RuleDelta>,
                           val rdepsDeltas: Map<BuildTargetId, RdepsSet?>) {
    fun isEmpty(): Boolean = buildPackageDeltas.isEmpty() && ruleDeltas.isEmpty() && rdepsDeltas.isEmpty()
}

/**
 * Takes a repo state defined by a [Generation] and an [IndexGenerationData] and applies
 * [BuildPackageChanges] to produce the [Deltas] that could be applied incrementally to the existing
 * repo state to yield a new repo state that reflects the [BuildPackageChanges]. How the new repo
 * state is realized is up to the caller.
 * @see Index.createIndexForGenerationWithLocalChanges
 * @see IndexAppender.addCommitData
 */
internal fun determineDeltas(
        generation: Generation,
        changes: BuildPackageChanges,
        indexGenerationData: IndexGenerationData,
        buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>
): Deltas {
    val internalChanges = toInternalChanges(changes, buildTargetCache)
    val buildPackageDeltas = mutableListOf<BuildPackageDelta>()
    val buildTargetIdsOfRemovedRules = mutableListOf<UnconfiguredBuildTarget>()
    val ruleDeltas = mutableListOf<RuleDelta>()

    val rdepsUpdates = mutableListOf<Pair<BuildTargetId, BuildTargetSetDelta>>()
    indexGenerationData.withBuildPackageMap { buildPackageMap ->
        for (added in internalChanges.addedBuildPackages) {
            val oldRules = buildPackageMap.getVersion(added.buildFileDirectory, generation)
            if (oldRules != null) {
                throw IllegalArgumentException("Build package to add already existed at ${added
                        .buildFileDirectory} for generation $generation")
            }

            val ruleNames = ArrayList<String>(added.rules.size)
            for (rule in added.rules) {
                val buildTarget = rule.targetNode.buildTarget
                ruleNames.add(buildTarget.name)
                ruleDeltas.add(RuleDelta.Added(rule))
                val add = BuildTargetSetDelta.Add(buildTargetCache.get(buildTarget))
                rule.deps.mapTo(rdepsUpdates) { dep -> Pair(dep, add) }
            }
            buildPackageDeltas.add(BuildPackageDelta.Updated(
                    added.buildFileDirectory,
                    ruleNames.asSequence().toBuildRuleNames()))
        }

        for (removed in internalChanges.removedBuildPackages) {
            val oldRules = requireNotNull(buildPackageMap.getVersion(removed, generation)) {
                "Build package to remove did not exist at $removed for generation $generation"
            }

            buildPackageDeltas.add(BuildPackageDelta.Removed(removed))
            // Record the build targets of the removed rules. We must wait until we acquire the
            // read lock on the rule map to get the deps so we can record the corresponding
            // RuleDelta.Removed objects.
            for (ruleName in oldRules) {
                val buildTarget = BuildTargets.createBuildTargetFromParts(removed, ruleName)
                buildTargetIdsOfRemovedRules.add(buildTarget)
            }
        }

        indexGenerationData.withRuleMap { ruleMap ->
            for (buildTarget in buildTargetIdsOfRemovedRules) {
                val buildTargetId = buildTargetCache.get(buildTarget)
                val removedRule = requireNotNull(ruleMap.getVersion(buildTargetId, generation)) {
                    "No rule found for '$buildTarget' at generation $generation"
                }
                ruleDeltas.add(RuleDelta.Removed(removedRule))
                val remove = BuildTargetSetDelta.Remove(buildTargetId)
                removedRule.deps.mapTo(rdepsUpdates) { dep -> Pair(dep, remove) }
            }

            for (modified in internalChanges.modifiedBuildPackages) {
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
                    // Note that oldRuleNames is a persistent collection, so we want to derive
                    // newRuleNames from oldRuleNames so they can share as much memory as possible.
                    var newRuleNames = oldRuleNames
                    ruleChanges.forEach { ruleChange ->
                        when (ruleChange) {
                            is RuleDelta.Added -> {
                                val buildTarget = ruleChange.rule.targetNode.buildTarget
                                newRuleNames = newRuleNames.add(buildTarget.name)
                                val add = BuildTargetSetDelta.Add(buildTargetCache.get(buildTarget))
                                ruleChange.rule.deps.mapTo(rdepsUpdates) { dep -> Pair(dep, add) }
                            }
                            is RuleDelta.Modified -> {
                                // Because the rule has been modified, newRuleNames will be
                                // unaffected, but the rule's deps may have changed.
                                val buildTargetId = buildTargetCache.get(ruleChange.oldRule.targetNode.buildTarget)
                                diffDeps(ruleChange.oldRule.deps, ruleChange.newRule.deps, rdepsUpdates, buildTargetId)
                            }
                            is RuleDelta.Removed -> {
                                val buildTarget = ruleChange.rule.targetNode.buildTarget
                                newRuleNames = newRuleNames.remove(buildTarget.name)
                                val remove = BuildTargetSetDelta.Remove(buildTargetCache.get(buildTarget))
                                ruleChange.rule.deps.mapTo(rdepsUpdates) { dep -> Pair(dep, remove) }
                            }
                        }
                    }

                    buildPackageDeltas.add(BuildPackageDelta.Updated(modified.buildFileDirectory, newRuleNames))
                    ruleDeltas.addAll(ruleChanges)
                }
            }
        }
    }

    val localRdepsRuleMapChanges = deriveRdepsDeltas(rdepsUpdates, generation, indexGenerationData)
    return Deltas(buildPackageDeltas, ruleDeltas, localRdepsRuleMapChanges)
}

/** Diff the deps between old and new and add the updates directly to the specified list. */
private fun diffDeps(
        old: BuildTargetSet,
        new: BuildTargetSet,
        rdepsUpdates: MutableList<Pair<BuildTargetId, BuildTargetSetDelta>>,
        buildTargetId: BuildTargetId) {
    // We exploit the fact that the ids in a BuildTargetSet are sorted.
    var oldIndex = 0
    var newIndex = 0
    while (oldIndex < old.size && newIndex < new.size) {
        val oldBuildTargetId = old[oldIndex]
        val newBuildTargetId = new[newIndex]
        when {
            oldBuildTargetId < newBuildTargetId -> {
                // oldBuildTargetId does not exist in new.
                rdepsUpdates.add(Pair(oldBuildTargetId, BuildTargetSetDelta.Remove(buildTargetId)))
                ++oldIndex
            }
            oldBuildTargetId > newBuildTargetId -> {
                // newBuildTargetId does not exist in old.
                rdepsUpdates.add(Pair(newBuildTargetId, BuildTargetSetDelta.Add(buildTargetId)))
                ++newIndex
            }
            else /* oldBuildTargetId == newBuildTargetId */ -> {
                // The buildTargetId is present in old and new, so nothing to update.
                ++oldIndex
                ++newIndex
            }
        }
    }

    // If there is anything left in old, it must have been removed in new.
    while (oldIndex < old.size) {
        rdepsUpdates.add(Pair(old[oldIndex++], BuildTargetSetDelta.Remove(buildTargetId)))
    }

    // If there is anything left in new, it must have been added in new.
    while (newIndex < new.size) {
        rdepsUpdates.add(Pair(new[newIndex++], BuildTargetSetDelta.Add(buildTargetId)))
    }
}

private fun toInternalChanges(changes: BuildPackageChanges, buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>): InternalChanges {
    return InternalChanges(changes.addedBuildPackages.map { toInternalBuildPackage(it, buildTargetCache) }.toList(),
            changes.modifiedBuildPackages.map { toInternalBuildPackage(it, buildTargetCache) }.toList(),
            changes.removedBuildPackages
    )
}

private fun toInternalBuildPackage(buildPackage: BuildPackage, buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>): InternalBuildPackage {
    return InternalBuildPackage(buildPackage.buildFileDirectory, buildPackage.rules.map { toInternalRawBuildRule(it, buildTargetCache) }.toSet())
}

private fun toInternalRawBuildRule(rawBuildRule: RawBuildRule, buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>): InternalRawBuildRule {
    return InternalRawBuildRule(rawBuildRule.targetNode, toBuildTargetSet(rawBuildRule.deps, buildTargetCache))
}

private fun toBuildTargetSet(targets: Set<UnconfiguredBuildTarget>, buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>): BuildTargetSet {
    val ids = targets.map { buildTargetCache.get(it) }.toIntArray()
    ids.sort()
    return ids
}
