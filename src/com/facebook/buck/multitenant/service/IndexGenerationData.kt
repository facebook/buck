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

import com.facebook.buck.multitenant.collect.DefaultGenerationMap
import com.facebook.buck.multitenant.collect.ForwardingGenerationMap
import com.facebook.buck.multitenant.collect.Generation
import com.facebook.buck.multitenant.collect.GenerationMap
import com.facebook.buck.multitenant.collect.MutableGenerationMap
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal typealias BuildPackageMap = GenerationMap<FsAgnosticPath, BuildRuleNames, FsAgnosticPath>
internal typealias MutableBuildPackageMap = MutableGenerationMap<FsAgnosticPath, BuildRuleNames, FsAgnosticPath>

internal typealias RuleMap = GenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>
internal typealias MutableRuleMap = MutableGenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>

internal typealias RdepsMap = GenerationMap<BuildTargetId, RdepsSet, BuildTargetId>
internal typealias MutableRdepsMap = MutableGenerationMap<BuildTargetId, RdepsSet, BuildTargetId>

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
     * @return new IndexGenerationData based on this one with [GenerationMap]s that reflect the
     *     specified local changes
     */
    fun createForwardingIndexGenerationData(
        generation: Generation,
        localBuildPackageChanges: Map<FsAgnosticPath, BuildRuleNames?>,
        localRuleMapChanges: Map<BuildTargetId, InternalRawBuildRule?>,
        localRdepsRuleMapChanges: Map<BuildTargetId, RdepsSet?>
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
}

/**
 * Fair locks should be used throughout to prioritize writer threads.
 *
 * @param buildPackageMap the key is the path for the directory relative to the Buck root that contains the build file
 *     for the corresponding build package.
 * @param ruleMap captures the value of a build rule at a specific generation, indexed by BuildTarget.
 *     We also specify the key as the keyInfo so that we get it back when we use `getAllInfoValuePairsForGeneration()`.
 */
internal open class DefaultIndexGenerationData(
    val buildPackageMapWithLock: Pair<BuildPackageMap, ReentrantReadWriteLock> = createGenerationMapWithLock(),
    val ruleMapWithLock: Pair<RuleMap, ReentrantReadWriteLock> = createGenerationMapWithLock(),
    val rdepsMapWithLock: Pair<RdepsMap, ReentrantReadWriteLock> = createGenerationMapWithLock()
) : IndexGenerationData {

    final override inline fun <T> withBuildPackageMap(action: (BuildPackageMap) -> T): T =
        buildPackageMapWithLock.readAction(action)

    final override inline fun <T> withRuleMap(action: (RuleMap) -> T): T =
        ruleMapWithLock.readAction(action)

    final override inline fun <T> withRdepsMap(action: (RdepsMap) -> T): T =
        rdepsMapWithLock.readAction(action)

    override fun createForwardingIndexGenerationData(
        generation: Generation,
        localBuildPackageChanges: Map<FsAgnosticPath, BuildRuleNames?>,
        localRuleMapChanges: Map<BuildTargetId, InternalRawBuildRule?>,
        localRdepsRuleMapChanges: Map<BuildTargetId, RdepsSet?>
    ): IndexGenerationData =
        DefaultIndexGenerationData(
            buildPackageMapWithLock = of(generation, localBuildPackageChanges,
                buildPackageMapWithLock),
            ruleMapWithLock = of(generation, localRuleMapChanges, ruleMapWithLock),
            rdepsMapWithLock = of(generation, localRdepsRuleMapChanges, rdepsMapWithLock))

    private inline fun <K : Any, V : Any> of(
        generation: Generation,
        localChanges: Map<K, V?>,
        delegate: Pair<GenerationMap<K, V, K>, ReentrantReadWriteLock>
    ) =
        forwardingGenerationMap(generation, localChanges, delegate.first) to delegate.second

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
}

private inline fun <K : Any, V : Any> createGenerationMapWithLock() =
    defaultGenerationMap<K, V>() to createFairLock()

private inline fun <K : Any, V : Any> defaultGenerationMap() = DefaultGenerationMap<K, V, K> { it }

private inline fun createFairLock() = ReentrantReadWriteLock(true)

private inline fun <T, U> Pair<U, ReentrantReadWriteLock>.readAction(action: U.() -> T): T =
    second.read { first.action() }

@SuppressWarnings("UnsafeCast")
private inline fun <T, U, V> Pair<U, ReentrantReadWriteLock>.writeAction(action: V.() -> T): T =
    second.write { (first as V).action() }
