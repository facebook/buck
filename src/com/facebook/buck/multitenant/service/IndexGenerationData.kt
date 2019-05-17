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
import com.facebook.buck.multitenant.collect.GenerationMap
import com.facebook.buck.multitenant.collect.MutableGenerationMap
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Facilitates thread-safe, read-only access to the underlying data in an [Index].
 */
internal interface IndexGenerationData {
    /**
     * @param action will be performed will the read lock is held for the [GenerationMap]
     */
    fun <T> withBuildPackageMap(action: (map: GenerationMap<FsAgnosticPath, BuildRuleNames, FsAgnosticPath>) -> T): T

    /**
     * @param action will be performed will the read lock is held for the [GenerationMap]
     */
    fun <T> withRuleMap(action: (map: GenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>) -> T): T

    /**
     * @param action will be performed will the read lock is held for the [GenerationMap]
     */
    fun <T> withRdepsMap(action: (map: GenerationMap<BuildTargetId, RdepsSet, BuildTargetId>) -> T): T

    /**
     * @return new IndexGenerationData based on this one with [GenerationMap]s that reflect the
     *     specified local changes
     */
    fun createForwardingIndexGenerationData(
            generation: Int,
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
    fun <T> withMutableBuildPackageMap(action: (map: MutableGenerationMap<FsAgnosticPath, BuildRuleNames, FsAgnosticPath>) -> T): T

    /**
     * @param action will be performed will the write lock is held for the [GenerationMap]
     */
    fun <T> withMutableRuleMap(action: (map: MutableGenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>) -> T): T

    /**
     * @param action will be performed will the write lock is held for the [GenerationMap]
     */
    fun <T> withMutableRdepsMap(action: (map: MutableGenerationMap<BuildTargetId, RdepsSet, BuildTargetId>) -> T): T
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
        protected val buildPackageMap: GenerationMap<FsAgnosticPath, BuildRuleNames, FsAgnosticPath> = DefaultGenerationMap { it },
        protected val buildPackageMapLock: ReentrantReadWriteLock = ReentrantReadWriteLock(/*fair*/ true),
        protected val ruleMap: GenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId> = DefaultGenerationMap { it },
        protected val ruleMapLock: ReentrantReadWriteLock = ReentrantReadWriteLock(/*fair*/ true),
        protected val rdepsMap: GenerationMap<BuildTargetId, RdepsSet, BuildTargetId> = DefaultGenerationMap { it },
        protected val rdepsMapLock: ReentrantReadWriteLock = ReentrantReadWriteLock(/*fair*/ true)
) : IndexGenerationData {
    override final inline fun <T> withBuildPackageMap(action: (map: GenerationMap<FsAgnosticPath, BuildRuleNames, FsAgnosticPath>) -> T): T {
        val readLock = buildPackageMapLock.readLock()
        readLock.lock()
        try {
            return action(buildPackageMap)
        } finally {
            readLock.unlock()
        }
    }

    override final inline fun <T> withRuleMap(action: (map: GenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>) -> T): T {
        val readLock = ruleMapLock.readLock()
        readLock.lock()
        try {
            return action(ruleMap)
        } finally {
            readLock.unlock()
        }
    }

    override final inline fun <T> withRdepsMap(action: (map: GenerationMap<BuildTargetId, RdepsSet, BuildTargetId>) -> T): T {
        val readLock = rdepsMapLock.readLock()
        readLock.lock()
        try {
            return action(rdepsMap)
        } finally {
            readLock.unlock()
        }
    }

    override fun createForwardingIndexGenerationData(
            generation: Int,
            localBuildPackageChanges: Map<FsAgnosticPath, BuildRuleNames?>,
            localRuleMapChanges: Map<BuildTargetId, InternalRawBuildRule?>,
            localRdepsRuleMapChanges: Map<BuildTargetId, RdepsSet?>
    ): IndexGenerationData {
        val forwardingBuildPackageMap = ForwardingGenerationMap(generation, localBuildPackageChanges, buildPackageMap)
        val forwardingRuleMap = ForwardingGenerationMap(generation, localRuleMapChanges, ruleMap)
        val forwardingRdepsRuleMap = ForwardingGenerationMap(generation, localRdepsRuleMapChanges, rdepsMap)
        return DefaultIndexGenerationData(forwardingBuildPackageMap, buildPackageMapLock, forwardingRuleMap, ruleMapLock, forwardingRdepsRuleMap, rdepsMapLock)
    }
}

internal class DefaultMutableIndexGenerationData() : DefaultIndexGenerationData(), MutableIndexGenerationData {
    override inline fun <T> withMutableBuildPackageMap(action: (map: MutableGenerationMap<FsAgnosticPath, BuildRuleNames, FsAgnosticPath>) -> T): T {
        val writeLock = buildPackageMapLock.writeLock()
        writeLock.lock()
        try {
            return action(buildPackageMap as MutableGenerationMap)
        } finally {
            writeLock.unlock()
        }
    }

    override inline fun <T> withMutableRuleMap(action: (map: MutableGenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>) -> T): T {
        val writeLock = ruleMapLock.writeLock()
        writeLock.lock()
        try {
            return action(ruleMap as MutableGenerationMap)
        } finally {
            writeLock.unlock()
        }
    }

    override inline fun <T> withMutableRdepsMap(action: (map: MutableGenerationMap<BuildTargetId, RdepsSet, BuildTargetId>) -> T): T {
        val writeLock = rdepsMapLock.writeLock()
        writeLock.lock()
        try {
            return action(rdepsMap as MutableGenerationMap)
        } finally {
            writeLock.unlock()
        }
    }
}
