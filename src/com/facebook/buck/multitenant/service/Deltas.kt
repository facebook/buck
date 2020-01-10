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
import com.facebook.buck.core.path.ForwardRelativePath
import com.facebook.buck.multitenant.cache.AppendOnlyBidirectionalCache
import com.facebook.buck.multitenant.collect.Generation

/**
 * Represents a collection of changes to apply to an [Index] to reflect [BuildPackageChanges] on top
 * of a [Generation].
 */
internal data class Deltas(
    val buildPackageDeltas: List<BuildPackageDelta>,
    val ruleDeltas: List<RuleDelta>,
    val rdepsDeltas: Map<BuildTargetId, MemorySharingIntSet?>,
    val includesDeltas: IncludesMapChange
) {
    fun isEmpty(): Boolean = buildPackageDeltas.isEmpty() && ruleDeltas.isEmpty() && rdepsDeltas.isEmpty() && includesDeltas.isEmpty()
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
    // Perform lookupBuildPackages() before processing addedBuildPackages below because
    // lookupBuildPackages() performs some sanity checks on addedBuildPackages.
    val (modifiedPackageInfo, removedPackageInfo) = lookupBuildPackages(generation, internalChanges,
        indexGenerationData, buildTargetCache)

    val ruleDeltas = mutableListOf<RuleDelta>()
    val rdepsUpdates = mutableListOf<Pair<BuildTargetId, SetDelta>>()
    val buildPackageDeltas = mutableListOf<BuildPackageDelta>()
    internalChanges.addedBuildPackages.forEach { added ->
        val ruleNames = ArrayList<String>(added.rules.size)
        added.rules.forEach { rule ->
            val buildTarget = rule.targetNode.buildTarget
            ruleNames.add(buildTarget.name)
            ruleDeltas.add(RuleDelta.Added(rule))
            val add = SetDelta.Add(buildTargetCache.get(buildTarget))
            rule.deps.mapTo(rdepsUpdates) { dep -> Pair(dep, add) }
        }
        buildPackageDeltas.add(BuildPackageDelta.Updated(added.buildFileDirectory,
            ruleNames.asSequence().toBuildRuleNames()))
    }

    val buildTargetsOfRemovedRules = mutableListOf<UnconfiguredBuildTarget>()
    removedPackageInfo.forEach { (removed, oldRuleNames) ->
        buildPackageDeltas.add(BuildPackageDelta.Removed(removed))
        // Record the build targets of the removed rules. We must wait until we acquire the
        // read lock on the rule map to get the deps so we can record the corresponding
        // RuleDelta.Removed objects.
        oldRuleNames.forEach { ruleName ->
            val buildTarget = BuildTargets.createBuildTargetFromParts(removed, ruleName)
            buildTargetsOfRemovedRules.add(buildTarget)
        }
    }
    val buildTargetIdsOfRemovedRules = buildTargetsOfRemovedRules.map { buildTargetCache.get(it) }
    val (modifiedRulesToProcess, removedRulesToProcess) = lookupBuildRules(
            generation,
            indexGenerationData,
            modifiedPackageInfo,
            buildTargetIdsOfRemovedRules,
            buildTargetCache)

    removedRulesToProcess.forEach { (buildTargetId, removedRule) ->
        ruleDeltas.add(RuleDelta.Removed(removedRule))
        val remove = SetDelta.Remove(buildTargetId)
        removedRule.deps.mapTo(rdepsUpdates) { dep -> Pair(dep, remove) }
    }

    modifiedRulesToProcess.forEach { (internalBuildPackage, oldRuleNames, oldRules) ->
        val newRules = internalBuildPackage.rules
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
                        val add = SetDelta.Add(buildTargetCache.get(buildTarget))
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
                        val remove = SetDelta.Remove(buildTargetCache.get(buildTarget))
                        ruleChange.rule.deps.mapTo(rdepsUpdates) { dep -> Pair(dep, remove) }
                    }
                }
            }

            buildPackageDeltas.add(BuildPackageDelta.Updated(internalBuildPackage.buildFileDirectory, newRuleNames))
            ruleDeltas.addAll(ruleChanges)
        }
    }

    val includesMapChange = processIncludes(internalChanges, generation, indexGenerationData)
    return Deltas(
        buildPackageDeltas = buildPackageDeltas,
        ruleDeltas = ruleDeltas,
        rdepsDeltas = deriveDeltas(rdepsUpdates) { key ->
            indexGenerationData.withRdepsMap { it.getVersion(key, generation) }
        },
        includesDeltas = includesMapChange
    )
}

/**
 * While holding the read lock for the `buildPackageMap`, extracts the data needed by
 * [determineDeltas] and nothing more so the lock is held as briefly as possible.
 */
private fun lookupBuildPackages(
    generation: Generation,
    internalChanges: InternalChanges,
    indexGenerationData: IndexGenerationData,
    buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>
): BuildPackagesLookup {
    // We allocate the arrays before taking the lock to reduce the number of allocations made while
    // holding the lock.
    val modified = ArrayList<Pair<InternalBuildPackage, BuildRuleNames>>(internalChanges.modifiedBuildPackages.size)
    val removed = ArrayList<Pair<ForwardRelativePath, BuildRuleNames>>(internalChanges.removedBuildPackages.size)

    indexGenerationData.withBuildPackageMap { buildPackageMap ->
        // As a sanity check, make sure there are no oldRules for any of the "added" packages.
        internalChanges.addedBuildPackages.forEach { added ->
            val oldRuleNames = buildPackageMap.getVersion(added.buildFileDirectory, generation)
            require(oldRuleNames == null) {
                "Build package to add already existed at ${added.buildFileDirectory} for generation $generation"
            }
        }

        internalChanges.modifiedBuildPackages.mapTo(modified) { modified ->
            val oldRuleNames = requireNotNull(
                buildPackageMap.getVersion(modified.buildFileDirectory, generation)) {
                "No version found for build file in ${modified.buildFileDirectory} for generation $generation"
            }
            Pair(modified, oldRuleNames)
        }

        internalChanges.removedBuildPackages.mapTo(removed) { removed ->
            val oldRuleNames = requireNotNull(buildPackageMap.getVersion(removed, generation)) {
                "Build package to remove did not exist at $removed for generation $generation"
            }
            Pair(removed, oldRuleNames)
        }
    }

    val modifiedWithTargetIds = modified.map { (buildPackage, buildRuleNames) ->
        val buildTargetIds = buildRuleNames.asSequence().map { name ->
            val buildTarget = BuildTargets.createBuildTargetFromParts(buildPackage.buildFileDirectory, name)
            requireNotNull(buildTargetCache.get(buildTarget))
        }.toList()
        ModifiedPackageByIds(
            internalBuildPackage = buildPackage,
            oldRuleNames = buildRuleNames,
            oldBuildTargetIds = buildTargetIds)
    }
    return BuildPackagesLookup(
        modifiedPackageInfo = modifiedWithTargetIds,
        removedPackageInfo = removed)
}

private fun lookupBuildRules(
    generation: Generation,
    indexGenerationData: IndexGenerationData,
    modified: List<ModifiedPackageByIds>,
    buildTargetIdsOfRemovedRules: List<BuildTargetId>,
    buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>
): BuildRuleLookup {
    // Pre-allocate arrays before taking the lock.
    val modifiedRulesToProcess = ArrayList<ModifiedPackageByRules>(modified.size)
    val removedRulesToProcess = ArrayList<Pair<BuildTargetId, InternalRawBuildRule>>(buildTargetIdsOfRemovedRules.size)

    indexGenerationData.withRuleMap { ruleMap ->
        modified.mapTo(modifiedRulesToProcess) { (internalBuildPackage, oldRuleNames, oldBuildTargetIds) ->
            val oldRules = oldBuildTargetIds.map { oldBuildTargetId ->
                requireNotNull(ruleMap.getVersion(oldBuildTargetId, generation)) {
                    "Missing deps for '${buildTargetCache.getByIndex(oldBuildTargetId)}' at generation $generation"
                }
            }
            ModifiedPackageByRules(internalBuildPackage, oldRuleNames, oldRules)
        }

        buildTargetIdsOfRemovedRules.mapTo(removedRulesToProcess) { buildTargetId ->
            val removedRule = requireNotNull(ruleMap.getVersion(buildTargetId, generation)) {
                "No rule found for '${buildTargetCache.getByIndex(buildTargetId)}' at generation $generation"
            }
            Pair(buildTargetId, removedRule)
        }
    }

    return BuildRuleLookup(modifiedRulesToProcess, removedRulesToProcess)
}

/** Diff the deps between old and new and add the updates directly to the specified list. */
private fun diffDeps(
    old: BuildTargetSet,
    new: BuildTargetSet,
    rdepsUpdates: MutableList<Pair<BuildTargetId, SetDelta>>,
    buildTargetId: BuildTargetId
) {
    // We exploit the fact that the ids in a BuildTargetSet are sorted.
    var oldIndex = 0
    var newIndex = 0
    while (oldIndex < old.size && newIndex < new.size) {
        val oldBuildTargetId = old[oldIndex]
        val newBuildTargetId = new[newIndex]
        when {
            oldBuildTargetId < newBuildTargetId -> {
                // oldBuildTargetId does not exist in new.
                rdepsUpdates.add(Pair(oldBuildTargetId, SetDelta.Remove(buildTargetId)))
                ++oldIndex
            }
            oldBuildTargetId > newBuildTargetId -> {
                // newBuildTargetId does not exist in old.
                rdepsUpdates.add(Pair(newBuildTargetId, SetDelta.Add(buildTargetId)))
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
        rdepsUpdates.add(Pair(old[oldIndex++], SetDelta.Remove(buildTargetId)))
    }

    // If there is anything left in new, it must have been added in new.
    while (newIndex < new.size) {
        rdepsUpdates.add(Pair(new[newIndex++], SetDelta.Add(buildTargetId)))
    }
}

private fun toInternalChanges(
    changes: BuildPackageChanges,
    buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>
): InternalChanges =
    InternalChanges(
        addedBuildPackages = changes.addedBuildPackages.asSequence().map {
        toInternalBuildPackage(it, buildTargetCache)
    }.toList(),
        modifiedBuildPackages = changes.modifiedBuildPackages.asSequence().map {
        toInternalBuildPackage(it, buildTargetCache)
    }.toList(),
        removedBuildPackages = changes.removedBuildPackages
    )

private fun toInternalBuildPackage(
    buildPackage: BuildPackage,
    buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>
): InternalBuildPackage =
    InternalBuildPackage(
        buildFileDirectory = buildPackage.buildFileDirectory,
        rules = buildPackage.rules.asSequence().map {
            toInternalRawBuildRule(it, buildTargetCache)
        }.toSet(),
        includes = buildPackage.includes
    )

private fun toInternalRawBuildRule(
    rawBuildRule: RawBuildRule,
    buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>
): InternalRawBuildRule =
    InternalRawBuildRule(rawBuildRule.targetNode,
        toBuildTargetSet(rawBuildRule.deps, buildTargetCache))

private fun toBuildTargetSet(
    targets: Set<UnconfiguredBuildTarget>,
    buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>
): BuildTargetSet {
    val ids = targets.map { buildTargetCache.get(it) }.toIntArray()
    ids.sort()
    return ids
}

/*
 * We create a number of simple types for shuttling data between methods. These seem easier to
 * comprehend than using [Pair] and [Triple] for everything.
 */

/**
 * Data extracted from using [IndexGenerationData.withBuildPackageMap].
 */
private data class BuildPackagesLookup(
    val modifiedPackageInfo: List<ModifiedPackageByIds>,
    val removedPackageInfo: List<Pair<ForwardRelativePath, BuildRuleNames>>
)

/**
 * Data extracted from using [IndexGenerationData.withRuleMap].
 */
private data class BuildRuleLookup(
    val modifiedPackages: List<ModifiedPackageByRules>,
    val removedPackages: List<Pair<BuildTargetId, InternalRawBuildRule>>
)

/**
 * @property internalBuildPackage that has been modified
 * @property oldRuleNames names of the build rules in the old version of the package
 * @property oldBuildTargetIds build target ids corresponding to [oldRuleNames]
 */
private data class ModifiedPackageByIds(
    val internalBuildPackage: InternalBuildPackage,
    val oldRuleNames: BuildRuleNames,
    val oldBuildTargetIds: List<BuildTargetId>
)

/**
 * @property internalBuildPackage that has been modified
 * @property oldRuleNames names of the build rules in the old version of the package
 * @property oldRules rules corresponding to [oldRuleNames]
 */
private data class ModifiedPackageByRules(
    val internalBuildPackage: InternalBuildPackage,
    val oldRuleNames: BuildRuleNames,
    val oldRules: List<InternalRawBuildRule>
)
