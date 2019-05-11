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
     * @return the transitive deps of the specified target (does not include target)
     */
    fun getTransitiveDeps(generation: Generation, target: UnconfiguredBuildTarget): Set<UnconfiguredBuildTarget> {
        val rootBuildTargetId = buildTargetCache.get(target)
        val toVisit = LinkedHashSet<Int>()
        toVisit.add(rootBuildTargetId)
        val visited = mutableSetOf<Int>()

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

        visited.remove(rootBuildTargetId)
        return visited.asSequence().map { buildTargetCache.getByIndex(it) }.toSet()
    }

    fun getFwdDeps(generation: Generation, targets: Iterable<UnconfiguredBuildTarget>, out: ImmutableSet.Builder<UnconfiguredBuildTarget>) {
        // Compute the list of target ids before taking the lock.
        val targetIds = targets.map { buildTargetCache.get(it) }
        indexGenerationData.withRuleMap { ruleMap ->
            for (targetId in targetIds) {
                val node = ruleMap.getVersion(targetId, generation) ?: continue
                for (dep in node.deps) {
                    out.add(buildTargetCache.getByIndex(dep))
                }
            }
        }
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
     * Used to match a ":" build target pattern wildcard.
     *
     * @param generation at which to enumerate all build targets under `basePath`
     * @param basePath under which to look. If the query is for `//:`, then `basePath` would be
     *     the empty string. If the query is for `//foo/bar:`, then `basePath` would be
     *     `foo/bar`.
     */
    fun getTargetsInBasePath(generation: Generation, basePath: FsAgnosticPath): List<UnconfiguredBuildTarget> {
        val targetNames = indexGenerationData.withBuildPackageMap { buildPackageMap ->
            buildPackageMap.getVersion(basePath, generation) ?: emptySet()
        }

        return targetNames.asSequence().map {
            BuildTargets.createBuildTargetFromParts(basePath, it)
        }.toList()
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

        return entries.flatMap {
            val basePath = it.first
            val names = it.second
            names.map {
                BuildTargets.createBuildTargetFromParts(basePath, it)
            }.asSequence()
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
