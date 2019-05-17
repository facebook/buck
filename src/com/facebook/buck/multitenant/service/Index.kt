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
import com.facebook.buck.multitenant.collect.GenerationMap
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.google.common.collect.ImmutableSet

/**
 * View of build graph data across a range of generations. Because this is a "view," it is not
 * possible to update the [Index] directly: all mutations to the underlying data are expected to be
 * made via its complementary [IndexAppender].
 */
class Index internal constructor(
        private val indexGenerationData: IndexGenerationData,
        private val buildTargetCache: AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>) {

    /**
     * Uses the repo state represented by this [Index] at the specified [Generation] and applies
     * [BuildPackageChanges] to produce an [Index] whose behavior is guaranteed only for the specified
     * [Generation]. Specifically, queries to the returned [Index] will be answered in terms of the
     * state of the original [Index] with the [BuildPackageChanges] applied on top. Although the
     * same value for [Generation] can be used with both the old and new [Index] objects, the same
     * queries may yield different results based on the [BuildPackageChanges].
     *
     * Note: we may want to consider introducing a new type, `GenerationBoundIndex`, that has the
     * same API as Index, but with the `Generation` parameter removed from each of its public
     * methods to eliminate the possibility of the caller invoking one of its methods with an
     * unsupported generation.
     */
    fun createIndexForGenerationWithLocalChanges(generation: Generation, changes: BuildPackageChanges): Index {
        if (changes.isEmpty()) {
            return this
        }

        val deltas = determineDeltas(generation, changes, indexGenerationData, buildTargetCache)
        if (deltas.isEmpty()) {
            return this
        }

        val buildPackageMap: Map<FsAgnosticPath, BuildRuleNames?> =
                deltas.buildPackageDeltas.asSequence().map { delta ->
                    when (delta) {
                        is BuildPackageDelta.Updated -> {
                            delta.directory to delta.rules
                        }
                        is BuildPackageDelta.Removed -> {
                            delta.directory to null
                        }
                    }
                }.toMap()
        val ruleMap: Map<BuildTargetId, InternalRawBuildRule?> =
                deltas.ruleDeltas.asSequence().map { delta ->
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
                    buildTargetCache.get(buildTarget) to newNodeAndDeps
                }.toMap()
        val indexData = indexGenerationData.createForwardingIndexGenerationData(
                generation, buildPackageMap, ruleMap, deltas.rdepsDeltas)
        return Index(indexData, buildTargetCache)
    }

    /**
     * If you need to look up multiple target nodes for the same commit, prefer [getTargetNodes].
     *
     * @return the corresponding [RawBuildRule] at the specified commit, if it exists;
     *     otherwise, return `null`.
     */
    fun getTargetNode(generation: Generation, target: UnconfiguredBuildTarget): RawBuildRule? {
        return getTargetNodes(generation, listOf(target))[0]
    }

    /**
     * @return a list whose entries correspond to the input list of `targets` where each element in
     *     the output is the corresponding target node for the build target at the commit or `null`
     *     if no rule existed for that target at that commit.
     */
    fun getTargetNodes(generation: Generation, targets: List<UnconfiguredBuildTarget>): List<RawBuildRule?> {
        val targetIds = targets.map { buildTargetCache.get(it) }

        // internalRules is a List rather than a Sequence because sequences are lazy and we need to
        // ensure all reads to ruleMap are done while the lock is held.
        val internalRules = indexGenerationData.withRuleMap { ruleMap ->
            targetIds.map { ruleMap.getVersion(it, generation) }.toList()
        }
        // We can release the lock because now we only need access to buildTargetCache, which does
        // not need to be guarded by rwLock.
        return internalRules.map {
            if (it != null) {
                val deps = it.deps.asSequence().map { buildTargetCache.getByIndex(it) }.toSet()
                RawBuildRule(it.targetNode, deps)
            } else {
                null
            }
        }
    }

    /**
     * @return the transitive deps of the specified targets (includes targets)
     */
    fun getTransitiveDeps(generation: Generation, targets: Sequence<UnconfiguredBuildTarget>): Set<UnconfiguredBuildTarget> {
        val toVisit = LinkedHashSet<BuildTargetId>()
        targets.mapTo(toVisit) { buildTargetCache.get(it) }
        val visited = mutableSetOf<BuildTargetId>()

        indexGenerationData.withRuleMap { ruleMap ->
            while (toVisit.isNotEmpty()) {
                val targetId = getFirst(toVisit)
                val node = ruleMap.getVersion(targetId, generation)
                visited.add(targetId)

                if (node == null) {
                    continue
                }

                for (dep in node.deps) {
                    if (!toVisit.contains(dep) && !visited.contains(dep)) {
                        toVisit.add(dep)
                    }
                }
            }
        }

        // We use a HashSet instead of a Kotlin Set so we can specify the initialCapacity.
        val out = HashSet<UnconfiguredBuildTarget>(visited.size)
        return visited.mapTo(out) { buildTargetCache.getByIndex(it) }
    }

    fun getFwdDeps(generation: Generation, targets: Iterable<UnconfiguredBuildTarget>, out: ImmutableSet.Builder<UnconfiguredBuildTarget>) {
        val targetIds = targets.map { buildTargetCache.get(it) }
        val rules: List<InternalRawBuildRule> = indexGenerationData.withRuleMap { ruleMap ->
            targetIds.mapNotNull { targetId ->
                ruleMap.getVersion(targetId, generation)
            }
        }

        rules.forEach { rule ->
            rule.deps.forEach { dep ->
                out.add(buildTargetCache.getByIndex(dep))
            }
        }
    }

    /**
     * For all of the specified targets at the specified generation, returns the union of their
     * <em>immediate</em> reverse dependencies. Note that unless one target is an immediate
     * reverse dependency of another, none of the targets is included in the output.
     */
    fun getReverseDeps(generation: Generation, targets: Iterable<UnconfiguredBuildTarget>): Set<UnconfiguredBuildTarget> {
        val targetIds = targets.map { buildTargetCache.get(it) }
        val rdepsSets = indexGenerationData.withRdepsMap { rdepsMap ->
            targetIds.mapNotNull { targetId ->
                rdepsMap.getVersion(targetId, generation)
            }
        }

        val out = mutableSetOf<UnconfiguredBuildTarget>()
        rdepsSets.forEach { rdepsSet ->
            rdepsSet.forEach { dep ->
                out.add(buildTargetCache.getByIndex(dep))
            }
        }
        return out
    }

    /**
     * @param generation at which to enumerate all build targets
     */
    fun getTargets(generation: Generation): List<UnconfiguredBuildTarget> {
        val pairs = indexGenerationData.withRuleMap { ruleMap ->
            ruleMap.getEntries(generation)
        }

        // Note that we release the read lock before making a bunch of requests to the
        // buildTargetCache. As this is going to do a LOT of lookups to the buildTargetCache, we
        // should probably see whether we can do some sort of "multi-get" operation that requires
        // less locking, or potentially change the locking strategy for AppendOnlyBidirectionalCache
        // completely so that it is not thread-safe internally, but is guarded by its own lock.
        return pairs.map { buildTargetCache.getByIndex(it.first) }.toList()
    }

    /**
     * Used to match a ":" build target pattern wildcard. If there is no build file at the specified
     * (revision, basePath) pair, this will return `null`.
     *
     * @param generation at which to enumerate all build targets under `basePath`
     * @param basePath under which to look. If the query is for `//:`, then `basePath` would be
     *     the empty string. If the query is for `//foo/bar:`, then `basePath` would be
     *     `foo/bar`.
     */
    fun getTargetsInBasePath(generation: Generation, basePath: FsAgnosticPath): List<UnconfiguredBuildTarget>? {
        val targetNames = indexGenerationData.withBuildPackageMap { buildPackageMap ->
            buildPackageMap.getVersion(basePath, generation)
        } ?: return null

        return targetNames.asSequence().map {
            BuildTargets.createBuildTargetFromParts(basePath, it)
        }.toList()
    }

    /**
     * This is similar to [getTargetsInBasePath], except when there is no build file under
     * `basePath`, it will recursively check the parent directory until it finds a build file and
     * returns  all of the build targets defined in that build package. In the unlikely event
     * that it reaches the root of the tree and still has not found a build file, it returns null.
     */
    fun getTargetsInOwningBuildPackage(generation: Generation, basePath: FsAgnosticPath): Pair<FsAgnosticPath, List<UnconfiguredBuildTarget>>? {
        var candidateBasePath = basePath
        val targetNames = indexGenerationData.withBuildPackageMap(
                fun(buildPackageMap: GenerationMap<FsAgnosticPath, BuildRuleNames, FsAgnosticPath>): BuildRuleNames? {
                    do {
                        val targetNames = buildPackageMap.getVersion(candidateBasePath, generation)
                        if (targetNames != null) {
                            return targetNames
                        } else {
                            candidateBasePath = candidateBasePath.dirname()
                        }
                    } while (!candidateBasePath.isEmpty())
                    return null
                }) ?: return null

        val buildTargets = targetNames.asSequence().map {
            BuildTargets.createBuildTargetFromParts(candidateBasePath, it)
        }.toList()
        return Pair(candidateBasePath, buildTargets)
    }

    /**
     * Used to match a "/..." build target pattern wildcard.
     *
     * @param generation at which to enumerate all build targets under `basePath`
     * @param basePath under which to look. If the query is for `//...`, then `basePath` would be
     *     the empty string. If the query is for `//foo/bar/...`, then `basePath` would be
     *     `foo/bar`.
     */
    fun getTargetsUnderBasePath(generation: Generation, basePath: FsAgnosticPath): List<UnconfiguredBuildTarget> {
        if (basePath.isEmpty()) {
            return getTargets(generation)
        }

        val entries = indexGenerationData.withBuildPackageMap { buildPackageMap ->
            buildPackageMap.getEntries(generation) { it.startsWith(basePath) }
        }

        return entries.flatMap { (basePath, names) ->
            names.asSequence().map { name ->
                BuildTargets.createBuildTargetFromParts(basePath, name)
            }
        }.toList()
    }
}

/**
 * @param set a non-empty set
 */
private fun <T> getFirst(set: LinkedHashSet<T>): T {
    // There are other ways to do this that seem like they might be cheaper:
    // https://stackoverflow.com/questions/5792596/removing-the-first-object-from-a-set.
    val iterator = set.iterator()
    val value = iterator.next()
    iterator.remove()
    return value
}
