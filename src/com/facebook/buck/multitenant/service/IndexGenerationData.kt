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
    fun <T> withBuildPackageMap(action: (map: GenerationMap<FsAgnosticPath, Set<String>, FsAgnosticPath>) -> T): T

    /**
     * @param action will be performed will the read lock is held for the [GenerationMap]
     */
    fun <T> withRuleMap(action: (map: GenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>) -> T): T
}

/**
 * Facilitates thread-safe, read/write access to the underlying data in an [Index].
 */
internal interface MutableIndexGenerationData : IndexGenerationData {
    /**
     * @param action will be performed will the write lock is held for the [GenerationMap]
     */
    fun <T> withMutableBuildPackageMap(action: (map: MutableGenerationMap<FsAgnosticPath, Set<String>, FsAgnosticPath>) -> T): T

    /**
     * @param action will be performed will the write lock is held for the [GenerationMap]
     */
    fun <T> withMutableRuleMap(action: (map: MutableGenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>) -> T): T
}

internal class DefaultGenerationData : MutableIndexGenerationData {
    // We use fair locks throughout to prioritize writer threads.

    /**
     * The key is the path for the directory relative to the Buck root that contains the build file
     * for the corresponding build package.
     */
    private val buildPackageMap = DefaultGenerationMap<FsAgnosticPath, Set<String>, FsAgnosticPath> { it }
    private val buildPackageMapLock = ReentrantReadWriteLock(/*fair*/ true)

    /**
     * Map that captures the value of a build rule at a specific generation, indexed by BuildTarget.
     *
     * We also specify the key as the keyInfo so that we get it back when we use
     * `getAllInfoValuePairsForGeneration()`.
     */
    private val ruleMap = DefaultGenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId> { it }
    private val ruleMapLock = ReentrantReadWriteLock(/*fair*/ true)

    override inline fun <T> withBuildPackageMap(action: (map: GenerationMap<FsAgnosticPath, Set<String>, FsAgnosticPath>) -> T): T {
        val readLock = buildPackageMapLock.readLock()
        readLock.lock()
        try {
            return action(buildPackageMap)
        } finally {
            readLock.unlock()
        }
    }

    override inline fun <T> withMutableBuildPackageMap(action: (map: MutableGenerationMap<FsAgnosticPath, Set<String>, FsAgnosticPath>) -> T): T {
        val writeLock = buildPackageMapLock.writeLock()
        writeLock.lock()
        try {
            return action(buildPackageMap)
        } finally {
            writeLock.unlock()
        }
    }

    override inline fun <T> withRuleMap(action: (map: GenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>) -> T): T {
        val readLock = ruleMapLock.readLock()
        readLock.lock()
        try {
            return action(ruleMap)
        } finally {
            readLock.unlock()
        }
    }

    override inline fun <T> withMutableRuleMap(action: (map: MutableGenerationMap<BuildTargetId, InternalRawBuildRule, BuildTargetId>) -> T): T {
        val writeLock = ruleMapLock.writeLock()
        writeLock.lock()
        try {
            return action(ruleMap)
        } finally {
            writeLock.unlock()
        }
    }
}
