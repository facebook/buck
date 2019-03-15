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

import com.facebook.buck.core.model.BuildTarget
import com.facebook.buck.multitenant.collect.GenerationMap
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * By construction, the Path for each BuildPackage should be distinct across all of the
 * collections of build packages.
 */
data class Changes(val addedBuildPackages: List<BuildPackage>,
                   val modifiedBuildPackages: List<BuildPackage>,
                   val removedBuildPackages: List<Path>)

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
class IndexReadLock internal constructor(internal val readLock: ReentrantReadWriteLock.ReadLock) : AutoCloseable {
    /**
     * Warning: this method is NOT idempotent!!!
     */
    override fun close() {
        readLock.unlock()
    }
}

class Index(private val buildTargetParser: (target: String) -> BuildTarget) {
    /**
     * To save space, we pass around ints instead of references to BuildTargets.
     * AppendOnlyBidirectionalCache does its own synchronization, so it does not need to be guarded
     * by rwLock.
     */
    private val buildTargetCache = AppendOnlyBidirectionalCache<BuildTarget>()

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
     * We also specify the key as the keyInfo so that we get it back when we use
     * `getAllInfoValuePairsForGeneration()`.
     */
    private val depsMap = GenerationMap<BuildTargetId, BuildTargetSet, BuildTargetId>({ it })

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
    fun getTransitiveDeps(indexReadLock: IndexReadLock, commit: Commit, target: BuildTarget): Set<BuildTarget> {
        checkReadLock(indexReadLock)
        val generation = commitToGeneration.getValue(commit)
        val rootBuildTargetId = buildTargetCache.get(target)
        val toVisit = LinkedHashSet<Int>()
        toVisit.add(rootBuildTargetId)
        val visited = mutableSetOf<Int>()

        while (toVisit.isNotEmpty()) {
            val targetId = getFirst(toVisit)
            val deps = depsMap.getVersion(targetId, generation)
            visited.add(targetId)

            if (deps == null) {
                continue
            }

            for (dep in deps) {
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
    fun getTargets(indexReadLock: IndexReadLock, commit: Commit): List<BuildTarget> {
        checkReadLock(indexReadLock)
        val targetIds: MutableList<BuildTargetId> = mutableListOf()

        rwLock.readLock().withLock {
            val generation = requireNotNull(commitToGeneration[commit]) {
                "No generation found for $commit"
            }
            depsMap.getAllInfoValuePairsForGeneration(generation).mapTo(targetIds) { it.first }
        }

        // Note that we release the read lock before making a bunch of requests to the
        // buildTargetCache.
        return targetIds.map { buildTargetCache.getByIndex(it) }
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
        val deltas = determineDeltas(changes)

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
                val buildTarget: BuildTarget
                val newDeps: BuildTargetSet?
                when (delta) {
                    is RuleDelta.Updated -> {
                        buildTarget = createBuildTarget(delta.buildPackageDirectory, delta.name)
                        newDeps = delta.deps
                    }
                    is RuleDelta.Removed -> {
                        buildTarget = createBuildTarget(delta.buildPackageDirectory, delta.name)
                        newDeps = null
                    }
                }
                depsMap.addVersion(buildTargetCache.get(buildTarget), newDeps, nextGeneration)
            }

            commitToGeneration[commit] = nextGeneration;
            ++generation
        }
    }

    private fun createBuildTarget(buildFileDirectory: Path, name: String): BuildTarget {
        return buildTargetParser(String.format("//%s:%s", buildFileDirectory, name))
    }

    private fun determineDeltas(changes: Changes): Deltas {
        val buildPackageDeltas = mutableListOf<BuildPackageDelta>()
        val ruleDeltas = mutableListOf<RuleDelta>()

        rwLock.readLock().withLock {
            for (added in changes.addedBuildPackages) {
                val oldRules = buildPackageMap.getVersion(added.buildFileDirectory, generation)
                if (oldRules != null) {
                    throw IllegalArgumentException("Build package to add already existed at ${added
                            .buildFileDirectory} for generation $generation")
                }

                buildPackageDeltas.add(BuildPackageDelta.Updated(added.buildFileDirectory, added
                        .rules.keys))
                for (entry in added.rules.entries) {
                    ruleDeltas.add(RuleDelta.Updated(added.buildFileDirectory, entry.key,
                            toBuildTargetSet(entry
                                    .value)))
                }
            }

            for (removed in changes.removedBuildPackages) {
                val oldRules = requireNotNull(buildPackageMap.getVersion(removed, generation)) {
                    "Build package to remove did not exist at ${removed} for generation $generation"
                }

                buildPackageDeltas.add(BuildPackageDelta.Removed(removed))
                for (ruleName in oldRules) {
                    ruleDeltas.add(RuleDelta.Removed(removed, ruleName))
                }
            }

            for (modified in changes.modifiedBuildPackages) {
                val oldRuleNames = requireNotNull(buildPackageMap.getVersion(modified
                        .buildFileDirectory,
                        generation)) {
                    "No version found for build file in ${modified.buildFileDirectory} for " +
                            "generation $generation"
                }

                val oldRules = oldRuleNames.associateWith { oldRuleName: String ->
                    val buildTarget = createBuildTarget(modified.buildFileDirectory, oldRuleName)
                    requireNotNull(depsMap.getVersion(buildTargetCache.get(buildTarget),
                            generation)) {
                        "Missing deps for $buildTarget at generation $generation"
                    }
                }

                val newPackageVersion = toInternalBuildPackage(modified)
                val newRules = newPackageVersion.rules
                // Compare oldRules and newRules to see whether the build package actually changed.
                // Keep track of the individual rule changes so we need not recompute them later.
                val ruleChanges = diffRules(oldRules, newRules, modified.buildFileDirectory)
                if (!ruleChanges.isEmpty()) {
                    buildPackageDeltas.add(BuildPackageDelta.Updated(modified
                            .buildFileDirectory, newRules.keys))
                    ruleDeltas.addAll(ruleChanges)
                }
            }
        }

        return Deltas(buildPackageDeltas, ruleDeltas)
    }

    private fun toInternalBuildPackage(buildPackage: BuildPackage): InternalBuildPackage {
        return InternalBuildPackage(buildPackage.buildFileDirectory,
                toInternalBuildRules(buildPackage.rules))
    }

    private fun toInternalBuildRules(buildRules: BuildRules): InternalBuildRules {
        return buildRules.mapValues { toBuildTargetSet(it.value) }
    }

    private fun toBuildTargetSet(targets: Set<BuildTarget>): BuildTargetSet {
        return targets.map({ buildTargetCache.get(it) }).toIntArray()
    }
}

internal data class Deltas(val buildPackageDeltas: List<BuildPackageDelta>,
                           val ruleDeltas: List<RuleDelta>) {
    fun isEmpty(): Boolean {
        return buildPackageDeltas.isEmpty() && ruleDeltas.isEmpty()
    }
}

/**
 * @param set a non-empty set
 */
internal fun <T> getFirst(set: LinkedHashSet<T>): T {
    // There are other ways to do this that seem like they might be cheaper:
    // https://stackoverflow.com/questions/5792596/removing-the-first-object-from-a-set.
    val iterator = set.iterator()
    val value = iterator.next()
    iterator.remove()
    return value
}
