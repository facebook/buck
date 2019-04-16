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
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Object that represents the client has acquired the read lock for Index. All public methods of
 * Index that require the read lock to be held take this object as a parameter. This ensures
 * multiple read-only calls into Index can be made while only acquiring the read lock once.
 *
 * In C++, using folly::Synchronized makes it natural for a caller to acquire a read lock and
 * then use a number of methods that take it as a parameter (indicating it is already
 * held) to avoid having to reacquire the lock for each such method (reentrant locks are not
 * commonly used in C++, anyway). Java's concurrency API does not seem to lend itself to this
 * as elegantly.
 *
 * @param readLock caller is responsible for ensuring the specified readLock is locked when it is
 * passed in.
 */
class IndexReadLock internal constructor(internal val readLock: ReentrantReadWriteLock.ReadLock) : AutoCloseable, Closeable {
    /**
     * Warning: this method is NOT idempotent!!!
     */
    override fun close() {
        readLock.unlock()
    }
}

class Index(private val buildTargetParser: (target: String) -> UnconfiguredBuildTarget) {
    /**
     * To save space, we pass around ints instead of references to BuildTargets.
     * AppendOnlyBidirectionalCache does its own synchronization, so it does not need to be guarded
     * by rwLock.
     */
    private val buildTargetCache = AppendOnlyBidirectionalCache<UnconfiguredBuildTarget>()

    /**
     * Access to all of the fields after this one must be guarded by the rwLock.
     */
    private val rwLock = ReentrantReadWriteLock()

    private var generation = 0
    private val commitToGeneration = mutableMapOf<Commit, Int>()

    /**
     * The key is the path for the directory relative to the Buck root that contains the build file
     * for the corresponding build package.
     */
    private val buildPackageMap = GenerationMap<Path, Set<String>, Unit>({})

    /**
     * Map that captures the value of a build rule at a specific generation, indexed by BuildTarget.
     *
     * We also specify the key as the keyInfo so that we get it back when we use
     * `getAllInfoValuePairsForGeneration()`.
     */
    private val ruleMap = GenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>({ it })

    /**
     * This should always be used with try-with-resources.
     */
    fun acquireReadLock(): IndexReadLock {
        val lock = rwLock.readLock()
        lock.lock()
        return IndexReadLock(lock)
    }

    private fun checkReadLock(indexReadLock: IndexReadLock) {
        require(indexReadLock.readLock === rwLock.readLock()) {
            "Specified lock must belong to this Index."
        }
    }

    /**
     * @param indexReadLock caller is responsible for ensuring this lock is still held, i.e., that
     * `close()` has not been invoked.
     */
    fun getTransitiveDeps(indexReadLock: IndexReadLock, commit: Commit, target: UnconfiguredBuildTarget): Set<UnconfiguredBuildTarget> {
        checkReadLock(indexReadLock)
        val generation = commitToGeneration.getValue(commit)
        val rootBuildTargetId = buildTargetCache.get(target)
        val toVisit = LinkedHashSet<Int>()
        toVisit.add(rootBuildTargetId)
        val visited = mutableSetOf<Int>()

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

        visited.remove(rootBuildTargetId)
        return visited.map { buildTargetCache.getByIndex(it) }.toSet()
    }

    /**
     * @param indexReadLock caller is responsible for ensuring this lock is still held, i.e., that
     * `close()` has not been invoked.
     * @param commit at which to enumerate all build targets
     */
    fun getTargets(indexReadLock: IndexReadLock, commit: Commit): List<UnconfiguredBuildTarget> {
        checkReadLock(indexReadLock)
        val targetIds: MutableList<BuildTargetId> = mutableListOf()

        rwLock.readLock().withLock {
            val generation = requireNotNull(commitToGeneration[commit]) {
                "No generation found for $commit"
            }
            ruleMap.getAllInfoValuePairsForGeneration(generation).mapTo(targetIds) { it.first }
        }

        // Note that we release the read lock before making a bunch of requests to the
        // buildTargetCache.
        return targetIds.map { buildTargetCache.getByIndex(it) }
    }

    /**
     * Currently, the caller is responsible for ensuring that addCommitData() is invoked
     * serially (never concurrently) for each commit in a chain of version control history.
     *
     * Note this method handles its own synchronization internally, so callers should not invoke
     * [acquireReadLock] or anything like that.
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
        val deltas = determineDeltas(toInternalChanges(changes))

        // If there are no updates to any of the generation maps, add a new entry for the current
        // commit using the existing generation in the commitToGeneration map.
        if (deltas.isEmpty()) {
            rwLock.writeLock().withLock {
                commitToGeneration[commit] = generation;
            }
            return;
        }

        // If any generation map needs to be updated, grab the write lock, bump the generation for
        // all of the maps, insert all of the new values into the maps, and as a final step, add a
        // new entry to commitToGeneration with the new generation value.
        rwLock.writeLock().withLock {
            val nextGeneration = generation + 1;

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

            commitToGeneration[commit] = nextGeneration;
            ++generation
        }
    }

    private fun createBuildTarget(buildFileDirectory: Path, name: String): UnconfiguredBuildTarget {
        return buildTargetParser(String.format("//%s:%s", buildFileDirectory, name))
    }

    private fun determineDeltas(changes: InternalChanges): Deltas {
        val buildPackageDeltas = mutableListOf<BuildPackageDelta>()
        val ruleDeltas = mutableListOf<RuleDelta>()

        rwLock.readLock().withLock {
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
                    "Build package to remove did not exist at ${removed} for generation $generation"
                }

                buildPackageDeltas.add(BuildPackageDelta.Removed(removed))
                for (ruleName in oldRules) {
                    val buildTarget = createBuildTarget(removed, ruleName)
                    ruleDeltas.add(RuleDelta.Removed(buildTarget))
                }
            }

            for (modified in changes.modifiedBuildPackages) {
                val oldRuleNames = requireNotNull(buildPackageMap.getVersion(modified
                        .buildFileDirectory,
                        generation)) {
                    "No version found for build file in ${modified.buildFileDirectory} for " +
                            "generation $generation"
                }

                val oldRules = oldRuleNames.map { oldRuleName: String ->
                    val buildTarget = createBuildTarget(modified.buildFileDirectory, oldRuleName)
                    requireNotNull(ruleMap.getVersion(buildTargetCache.get(buildTarget),
                            generation)) {
                        "Missing deps for $buildTarget at generation $generation"
                    }
                }.toSet()

                val newRules = modified.rules
                // Compare oldRules and newRules to see whether the build package actually changed.
                // Keep track of the individual rule changes so we need not recompute them later.
                val ruleChanges = diffRules(oldRules, newRules)
                if (!ruleChanges.isEmpty()) {
                    buildPackageDeltas.add(BuildPackageDelta.Updated(modified
                            .buildFileDirectory, getRuleNames(newRules)))
                    ruleDeltas.addAll(ruleChanges)
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

internal data class Deltas(val buildPackageDeltas: List<BuildPackageDelta>,
                           val ruleDeltas: List<RuleDelta>) {
    fun isEmpty(): Boolean {
        return buildPackageDeltas.isEmpty() && ruleDeltas.isEmpty()
    }
}

private fun getRuleNames(rules: Set<InternalRawBuildRule>): Set<String> {
    return rules.map { it.targetNode.buildTarget.name }.toSet()
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
