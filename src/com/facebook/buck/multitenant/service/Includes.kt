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
@file:Suppress("MatchingDeclarationName")

package com.facebook.buck.multitenant.service

import com.facebook.buck.multitenant.collect.Generation
import com.facebook.buck.multitenant.fs.FsAgnosticPath

typealias Include = FsAgnosticPath

/**
 * Processes parse-time includes changes - calculates new includes map state based on the previous state and new changes [internalChanges].
 * @param internalChanges data class for internal changes (added, modified, removed packages)
 * @param generation [Generation] that defines a current state of [Index]
 * @param indexGenerationData thread-safe, read-only access to the underlying data in an [Index]
 * @return [IncludesMapChange] that represents a new state for include maps or an empty [IncludesMapChange] if a new state is the same as previous one.
 */
internal fun processIncludes(
    internalChanges: InternalChanges,
    generation: Generation,
    indexGenerationData: IndexGenerationData
): IncludesMapChange {

    /** Copying previous state under a lock to read-only [IncludesMapChange] object*/
    val previousState =
        indexGenerationData.withIncludesMap { (forwardIncludesMap, reverseIncludesMap) ->
            IncludesMapChange(
                forwardMap = forwardIncludesMap.toMap(generation),
                reverseMap = reverseIncludesMap.toMap(generation)
            )
        }

    val includesMapChangeBuilder = IncludesMapChangeBuilder(previousState)
    internalChanges.addedBuildPackages.forEach { added ->
        includesMapChangeBuilder.processAddedPackage(
            packagePath = added.buildFileDirectory,
            includes = added.includes
        )
    }

    internalChanges.modifiedBuildPackages.forEach { modified ->
        includesMapChangeBuilder.processModifiedPackage(
            packagePath = modified.buildFileDirectory,
            includes = modified.includes
        )
    }

    internalChanges.removedBuildPackages.forEach { removed ->
        includesMapChangeBuilder.processRemovedPackage(packageToRemove = removed)
    }

    val newState = includesMapChangeBuilder.build()
    return if (newState != previousState) newState
        else IncludesMapChange(forwardMap = emptyMap(), reverseMap = emptyMap())
}

/**
 * Builder that allows to process parse-time includes changes.
 */
class IncludesMapChangeBuilder(private val previousIncludesMapState: IncludesMapChange) {

    private val forwardMapDeltas: MutableList<Pair<Include, SetDelta>> = mutableListOf()
    private val reverseMapDeltas: MutableList<Pair<Include, SetDelta>> = mutableListOf()

    /**
     * Processes includes from the added package.
     * If includes are not empty then add them to forward and reverse include maps.
     *
     * @param packagePath build file directory path that corresponds to the added package
     * @param includes parse time includes that corresponds to the added package
     */
    fun processAddedPackage(packagePath: FsAgnosticPath, includes: HashSet<FsAgnosticPath>) {
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
     */
    fun processModifiedPackage(packagePath: FsAgnosticPath, includes: HashSet<FsAgnosticPath>) {
        val previousIncludes = previousIncludesMapState.forwardMap[packagePath]
        val brandNewIncludes = (previousIncludes == null || previousIncludes.isEmpty()) && includes.isNotEmpty()
        val previousIncludesHashSet =
            previousIncludes?.asSequence()?.map { FsAgnosticPath.fromIndex(it) }?.toHashSet()
                ?: hashSetOf()
        if (brandNewIncludes || previousIncludesHashSet != includes) {
            processRemovedPackage(packageToRemove = packagePath)
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
     */
    fun processRemovedPackage(packageToRemove: FsAgnosticPath) {
        previousIncludesMapState.forwardMap[packageToRemove]?.forEach { include ->
            val includePath = FsAgnosticPath.fromIndex(include)
            removeFromForwardMap(buildFilePath = packageToRemove, include = includePath)
            removeFromReverseMap(buildFilePath = packageToRemove, include = includePath)
        }
    }

    /**
     * @return [IncludesMapChange] that represents a new read-only state for include maps.
     */
    fun build(): IncludesMapChange =
        IncludesMapChange(forwardMap = getForwardMap(), reverseMap = getReverseMap())

    private fun getForwardMap(): Map<Include, MemorySharingIntSet?> {
        return applyUpdates(previousIncludesMapState.forwardMap, forwardMapDeltas)
    }

    private fun getReverseMap(): Map<Include, MemorySharingIntSet?> {
        return applyUpdates(previousIncludesMapState.reverseMap, reverseMapDeltas)
    }

    private fun applyUpdates(
        previousState: Map<Include, MemorySharingIntSet?>,
        updates: List<Pair<Include, SetDelta>>
    ): Map<Include, MemorySharingIntSet?> {
        if (updates.isEmpty()) {
            return previousState
        }

        val derivedDeltas = deriveDeltas(updates) { previousState[it] }
        val map = previousState.toMutableMap()
        derivedDeltas.forEach { (path, newSet) ->
            map[path] = newSet
        }
        return map
    }

    private fun appendForwardMap(buildFilePath: FsAgnosticPath, include: Include) {
        forwardMapDeltas.add(buildFilePath to SetDelta.Add(FsAgnosticPath.toIndex(include)))
    }

    private fun appendReverseMap(buildFilePath: FsAgnosticPath, include: Include) {
        reverseMapDeltas.add(include to SetDelta.Add(FsAgnosticPath.toIndex(buildFilePath)))
    }

    private fun removeFromReverseMap(buildFilePath: FsAgnosticPath, include: Include) {
        reverseMapDeltas.add(include to SetDelta.Remove(FsAgnosticPath.toIndex(buildFilePath)))
    }

    private fun removeFromForwardMap(buildFilePath: FsAgnosticPath, include: Include) {
        forwardMapDeltas.add(buildFilePath to SetDelta.Remove(FsAgnosticPath.toIndex(include)))
    }
}
