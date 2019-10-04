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

import com.facebook.buck.multitenant.collect.Generation
import com.facebook.buck.multitenant.fs.FsAgnosticPath

typealias Include = FsAgnosticPath
typealias Includes = Set<Include>

/**
 * Processes parse-time includes changes - calculates new includes map state based on the previous state and new changes [internalChanges].
 * @param generation [Generation] that defines a current state of [Index]
 * @param internalChanges data class for internal changes (added, modified, removed packages)
 * @param indexGenerationData thread-safe, read-only access to the underlying data in an [Index]
 * @return [IncludesMapChange] that represents a new state for include maps.
 */
internal fun processIncludes(
    generation: Generation,
    internalChanges: InternalChanges,
    indexGenerationData: IndexGenerationData
): IncludesMapChange {

    // Copying previous state under a lock to mutable maps.
    val (forwardMap, reverseMap) = indexGenerationData.withIncludesMap { (forwardIncludesMap, reverseIncludesMap) ->
        forwardIncludesMap.toMutableMap(generation) to reverseIncludesMap.toMutableMap(generation)
    }

    val includesMapChangeBuilder =
        IncludesMapChangeBuilder(forwardMap = forwardMap, reverseMap = reverseMap)
    val includesMapState = includesMapChangeBuilder.build()

    internalChanges.addedBuildPackages.forEach { added ->
        includesMapChangeBuilder.processAddedPackage(packagePath = added.buildFileDirectory,
            includes = added.includes, previousIncludesMapState = includesMapState)
    }

    internalChanges.modifiedBuildPackages.forEach { modified ->
        includesMapChangeBuilder.processModifiedPackage(
            packagePath = modified.buildFileDirectory, includes = modified.includes,
            previousIncludesMapState = includesMapState)
    }

    internalChanges.removedBuildPackages.forEach { removed ->
        includesMapChangeBuilder.processRemovedPackage(packageToRemove = removed,
            previousIncludesMapState = includesMapState)
    }

    return includesMapChangeBuilder.build()
}

/**
 * Builder that allows to process parse-time includes changes.
 */
class IncludesMapChangeBuilder(
    private val forwardMap: MutableMap<FsAgnosticPath, MutableSet<Include>> = mutableMapOf(),
    private val reverseMap: MutableMap<Include, MutableSet<FsAgnosticPath>> = mutableMapOf()
) {

    /**
     * Processes includes from the added package.
     * If includes are not empty then add them to forward and reverse include maps.
     *
     * @param packagePath build file directory path that corresponds to the added package
     * @param includes parse time includes that corresponds to the added package
     * @param previousIncludesMapState previous state of the include maps
     */
    fun processAddedPackage(
        packagePath: FsAgnosticPath,
        includes: Includes,
        previousIncludesMapState: IncludesMapChange
    ) {
        // verification that the package is new
        require(!previousIncludesMapState.forwardMap.containsKey(packagePath)) {
            "New package $packagePath already existed"
        }
        includes.forEach { include ->
            appendForwardMap(buildFilePath = packagePath, include = include)
            appendReverseMap(buildFilePath = packagePath, include = include)
        }
    }

    /**
     * Processes includes from the modified package.
     * If includes changed (comparing to the previous state for this modified package)
     * then remove the package from forward and reverse maps and append maps with new package includes
     *
     * @param packagePath build file directory path that corresponds to the modified package
     * @param includes parse time includes that corresponds to the modified package
     * @param previousIncludesMapState previous state of the include maps
     */
    fun processModifiedPackage(
        packagePath: FsAgnosticPath,
        includes: Includes,
        previousIncludesMapState: IncludesMapChange
    ) {
        if (previousIncludesMapState.forwardMap[packagePath] != includes) {
            processRemovedPackage(packageToRemove = packagePath,
                previousIncludesMapState = previousIncludesMapState)
            includes.forEach { include ->
                appendForwardMap(buildFilePath = packagePath, include = include)
                appendReverseMap(buildFilePath = packagePath, include = include)
            }
        }
    }

    /**
     * Processes includes from the removed package.
     * Removes the package from forward and reverse maps
     *
     * @param packageToRemove build file directory path that corresponds to the removed package
     * @param previousIncludesMapState previous state of the include maps
     */
    fun processRemovedPackage(
        packageToRemove: FsAgnosticPath,
        previousIncludesMapState: IncludesMapChange
    ) {
        forwardMap.remove(packageToRemove)
        previousIncludesMapState.forwardMap[packageToRemove]?.forEach { include ->
            removeFromReverseMap(buildFilePath = packageToRemove, include = include)
        }
    }

    /**
     * @return [IncludesMapChange] that represents a new read-only state for include maps.
     */
    fun build(): IncludesMapChange =
        IncludesMapChange(forwardMap = forwardMap.toMap(), reverseMap = reverseMap.toMap())

    private fun appendForwardMap(buildFilePath: FsAgnosticPath, include: Include) {
        forwardMap.getOrPut(buildFilePath, { HashSet() }).add(include)
    }

    private fun appendReverseMap(buildFilePath: FsAgnosticPath, include: Include) {
        reverseMap.getOrPut(include, { HashSet() }).add(buildFilePath)
    }

    private fun removeFromReverseMap(buildFilePath: FsAgnosticPath, include: Include) {
        val filePaths = reverseMap.getOrPut(include, { HashSet() })
        filePaths.remove(buildFilePath)
        // if file paths set is empty then remove the entire map's entry for this key
        if (filePaths.isEmpty()) {
            reverseMap.remove(include)
        }
    }
}
