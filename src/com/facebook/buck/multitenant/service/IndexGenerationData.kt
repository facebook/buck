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

import com.facebook.buck.core.path.ForwardRelativePath
import com.facebook.buck.multitenant.collect.DefaultGenerationMap
import com.facebook.buck.multitenant.collect.ForwardingGenerationMap
import com.facebook.buck.multitenant.collect.Generation
import com.facebook.buck.multitenant.collect.GenerationMap
import com.facebook.buck.multitenant.collect.MutableGenerationMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal typealias BuildPackageMap = GenerationMap<ForwardRelativePath, BuildRuleNames, ForwardRelativePath>
internal typealias MutableBuildPackageMap = MutableGenerationMap<ForwardRelativePath, BuildRuleNames, ForwardRelativePath>

internal typealias RuleMap = GenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>
internal typealias MutableRuleMap = MutableGenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>

internal typealias RdepsMap = GenerationMap<BuildTargetId, MemorySharingIntSet, BuildTargetId>
internal typealias MutableRdepsMap = MutableGenerationMap<BuildTargetId, MemorySharingIntSet, BuildTargetId>

internal typealias IncludesMap = GenerationMap<Include, MemorySharingIntSet, Include>
internal typealias MutableIncludesMap = MutableGenerationMap<Include, MemorySharingIntSet, Include>

internal fun GenerationMap<Include, MemorySharingIntSet, Include>.toMap(generation: Generation) =
    getEntries(generation).toMap()

data class IncludesMapsHolder(val forwardMap: IncludesMap, val reverseMap: IncludesMap)
data class MutableIncludesMapHolder(
    val forwardMap: MutableIncludesMap,
    val reverseMap: MutableIncludesMap
)

/**
 * Includes Map Changes - represents a read-only state for include maps.
 *
 * Contains two maps:
 * @param forwardMap from build file to set of includes
 * @param reverseMap from include file to set of build files
 */
data class IncludesMapChange(
    val forwardMap: Map<Include, MemorySharingIntSet?>,
    val reverseMap: Map<Include, MemorySharingIntSet?>
) {
    fun isEmpty(): Boolean = forwardMap.isEmpty() && reverseMap.isEmpty()
}

/**
 * Facilitates thread-safe, read-only access to the underlying data in an [Index].
 */
internal interface IndexGenerationData {
    /**
     * @param action will be performed will the read lock is held for the [GenerationMap]
     */
    fun <T> withBuildPackageMap(action: (BuildPackageMap) -> T): T

    /**
     * @param action will be performed will the read lock is held for the [GenerationMap]
     */
    fun <T> withRuleMap(action: (RuleMap) -> T): T

    /**
     * @param action will be performed will the read lock is held for the [GenerationMap]
     */
    fun <T> withRdepsMap(action: (RdepsMap) -> T): T

    /**
     * @param action will be performed will the read lock is held for the [GenerationMap]
     */
    fun <T> withIncludesMap(action: (IncludesMapsHolder) -> T): T

    /**
     * @return new IndexGenerationData based on this one with [GenerationMap]s that reflect the
     *     specified local changes
     */
    fun createForwardingIndexGenerationData(
        generation: Generation,
        localBuildPackageChanges: Map<ForwardRelativePath, BuildRuleNames?>,
        localRuleMapChanges: Map<BuildTargetId, InternalRawBuildRule?>,
        localRdepsRuleMapChanges: Map<BuildTargetId, MemorySharingIntSet?>,
        localIncludesMapChange: IncludesMapChange
    ): IndexGenerationData
}

/**
 * Facilitates thread-safe, read/write access to the underlying data in an [Index].
 */
internal interface MutableIndexGenerationData : IndexGenerationData {
    /**
     * @param action will be performed will the write lock is held for the [GenerationMap]
     */
    fun <T> withMutableBuildPackageMap(action: (MutableBuildPackageMap) -> T): T

    /**
     * @param action will be performed will the write lock is held for the [GenerationMap]
     */
    fun <T> withMutableRuleMap(action: (MutableRuleMap) -> T): T

    /**
     * @param action will be performed will the write lock is held for the [GenerationMap]
     */
    fun <T> withMutableRdepsMap(action: (MutableRdepsMap) -> T): T

    /**
     * @param action will be performed will the write lock is held for the [GenerationMap]
     */
    fun <T> withMutableIncludesMap(action: (MutableIncludesMapHolder) -> T): T
}

/**
 * Fair locks should be used throughout to prioritize writer threads.
 *
 * @param buildPackageMapWithLock the key is the path for the directory relative to the Buck root that contains the build file
 *     for the corresponding build package.
 * @param ruleMapWithLock captures the value of a build rule at a specific generation, indexed by BuildTarget.
 *     We also specify the key as the keyInfo so that we get it back when we use `getAllInfoValuePairsForGeneration()`.
 * @param rdepsMapWithLock stores the reverse dependencies at a specific generation with a read-write lock.
 * @param includesMapPairWithLock stores the includes maps at a specific generation with a read-write lock.
 */
internal open class DefaultIndexGenerationData(
    val buildPackageMapWithLock: Pair<BuildPackageMap, ReentrantReadWriteLock> = createGenerationMapWithLock(),
    val ruleMapWithLock: Pair<RuleMap, ReentrantReadWriteLock> = createGenerationMapWithLock(),
    val rdepsMapWithLock: Pair<RdepsMap, ReentrantReadWriteLock> = createGenerationMapWithLock(),
    val includesMapPairWithLock: Pair<IncludesMapsHolder, ReentrantReadWriteLock> = createGenerationMapPairWithLock()
) :
    IndexGenerationData {

    final override inline fun <T> withBuildPackageMap(action: (BuildPackageMap) -> T): T =
        buildPackageMapWithLock.readAction(action)

    final override inline fun <T> withRuleMap(action: (RuleMap) -> T): T =
        ruleMapWithLock.readAction(action)

    final override inline fun <T> withRdepsMap(action: (RdepsMap) -> T): T =
        rdepsMapWithLock.readAction(action)

    final override fun <T> withIncludesMap(action: (IncludesMapsHolder) -> T): T =
        includesMapPairWithLock.readAction(action)

    override fun createForwardingIndexGenerationData(
        generation: Generation,
        localBuildPackageChanges: Map<ForwardRelativePath, BuildRuleNames?>,
        localRuleMapChanges: Map<BuildTargetId, InternalRawBuildRule?>,
        localRdepsRuleMapChanges: Map<BuildTargetId, MemorySharingIntSet?>,
        localIncludesMapChange: IncludesMapChange
    ): IndexGenerationData =
        DefaultIndexGenerationData(
            buildPackageMapWithLock = of(generation, localBuildPackageChanges,
                buildPackageMapWithLock),
            ruleMapWithLock = of(generation, localRuleMapChanges, ruleMapWithLock),
            rdepsMapWithLock = of(generation, localRdepsRuleMapChanges, rdepsMapWithLock),
            includesMapPairWithLock = of(generation, localIncludesMapChange,
                includesMapPairWithLock))

    private inline fun <K : Any, V : Any> of(
        generation: Generation,
        localChanges: Map<K, V?>,
        delegate: Pair<GenerationMap<K, V, K>, ReentrantReadWriteLock>
    ) =
        forwardingGenerationMap(generation, localChanges, delegate.first) to delegate.second

    private inline fun of(
        generation: Generation,
        localChanges: IncludesMapChange,
        delegate: Pair<IncludesMapsHolder, ReentrantReadWriteLock>
    ): Pair<IncludesMapsHolder, ReentrantReadWriteLock> {
        val (includesMapsHolder, lock) = delegate
        val forwardMap = forwardingGenerationMap(generation, localChanges.forwardMap,
            includesMapsHolder.forwardMap)
        val reverseMap = forwardingGenerationMap(generation, localChanges.reverseMap,
            includesMapsHolder.reverseMap)
        return IncludesMapsHolder(forwardMap, reverseMap) to lock
    }

    private fun <K : Any, V : Any> forwardingGenerationMap(
        generation: Generation,
        localChanges: Map<K, V?>,
        delegate: GenerationMap<K, V, K>
    ) =
        ForwardingGenerationMap(supportedGeneration = generation, localChanges = localChanges,
            delegate = delegate)
}

internal class DefaultMutableIndexGenerationData : DefaultIndexGenerationData(),
    MutableIndexGenerationData {

    override inline fun <T> withMutableBuildPackageMap(action: (MutableBuildPackageMap) -> T): T =
        buildPackageMapWithLock.writeAction(action)

    override inline fun <T> withMutableRuleMap(action: (MutableRuleMap) -> T): T =
        ruleMapWithLock.writeAction(action)

    override inline fun <T> withMutableRdepsMap(action: (MutableRdepsMap) -> T): T =
        rdepsMapWithLock.writeAction(action)

    @SuppressWarnings("UnsafeCast") override fun <T> withMutableIncludesMap(
        action: (MutableIncludesMapHolder) -> T
    ): T {
        val lock = includesMapPairWithLock.second
        val (forwardMap: IncludesMap, reverseMap: IncludesMap) = includesMapPairWithLock.first
        return lock.write {
            action(MutableIncludesMapHolder(forwardMap = forwardMap as MutableIncludesMap,
                reverseMap = reverseMap as MutableIncludesMap))
        }
    }
}

private inline fun createGenerationMapPairWithLock() =
    IncludesMapsHolder(defaultGenerationMap(), defaultGenerationMap()) to createFairLock()

private inline fun <K : Any, V : Any> createGenerationMapWithLock() =
    defaultGenerationMap<K, V>() to createFairLock()

private inline fun <K : Any, V : Any> defaultGenerationMap() = DefaultGenerationMap<K, V, K> { it }

private inline fun createFairLock() = ReentrantReadWriteLock(true)

private inline fun <T, U> Pair<U, ReentrantReadWriteLock>.readAction(action: U.() -> T): T =
    second.read { first.action() }

@SuppressWarnings("UnsafeCast")
private inline fun <T, U, V> Pair<U, ReentrantReadWriteLock>.writeAction(action: V.() -> T): T =
    second.write { (first as V).action() }
