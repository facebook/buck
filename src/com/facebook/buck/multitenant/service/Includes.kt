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
class IncludesMapChangeBuilder(private val prevState: IncludesMapChange) {

    private val forwardMapDeltas: MutableMap<FsAgnosticPath, MutableSet<Include>?> = mutableMapOf()
    private val reverseMapDeltas: MutableMap<Include, MutableSet<FsAgnosticPath>?> = mutableMapOf()

    /**
     * Processes includes from the added package.
     * If includes are not empty then add them to forward and reverse include maps.
     *
     * @param packagePath build file directory path that corresponds to the added package
     * @param includes parse time includes that corresponds to the added package
     */
    fun processAddedPackage(packagePath: FsAgnosticPath, includes: HashSet<FsAgnosticPath>) {
        // verification that the package is new
        require(prevState.forwardMap[packagePath] == null) {
            "New package $packagePath already existed"
        }

        forwardMapDeltas[packagePath] = includes
        includes.forEach { processNewInclude(packagePath = packagePath, include = it) }
    }

    /**
     * Processes includes from the modified package.
     * If includes changed (comparing to the previous state for this modified package)
     * then reflect this change to forward and reverse maps
     *
     * @param packagePath build file directory path that corresponds to the modified package
     * @param includes parse time includes that corresponds to the modified package
     */
    fun processModifiedPackage(packagePath: FsAgnosticPath, includes: HashSet<FsAgnosticPath>) {
        val previousIncludes = prevState.forwardMap[packagePath]
        val brandNewIncludes = previousIncludes.isNullOrEmpty() && includes.isNotEmpty()
        val previousIncludesHashSet =
            previousIncludes?.asSequence()?.map { FsAgnosticPath.fromIndex(it) }?.toHashSet()
                ?: hashSetOf()
        if (brandNewIncludes || previousIncludesHashSet != includes) {
            forwardMapDeltas[packagePath] = includes

            // process removed includes
            previousIncludesHashSet.asSequence().filterNot { includes.contains(it) }.forEach {
                processRemovedInclude(packagePath = packagePath, include = it)
            }

            // process new includes
            includes.asSequence().filterNot { previousIncludesHashSet.contains(it) }.forEach {
                processNewInclude(packagePath = packagePath, include = it)
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
        forwardMapDeltas[packageToRemove] = null

        val previousIncludes = prevState.forwardMap.loadValueByKey(packageToRemove)
        previousIncludes?.forEach {
            processRemovedInclude(packagePath = packageToRemove, include = it)
        }
    }

    private fun processNewInclude(packagePath: FsAgnosticPath, include: Include) {
        val packages = reverseMapDeltas[include]
        /** if we already have changes related to this key (they are already in the [reverseMapDeltas] with [include] key)
         * then just add new package to them */
        if (packages != null) {
            packages.add(packagePath)
        } else {
            // load packages from the previous state
            val prevReverseMap = prevState.reverseMap.loadValueByKey(include) ?: hashSetOf()
            prevReverseMap.add(packagePath)
            reverseMapDeltas[include] = prevReverseMap
        }
    }

    private fun processRemovedInclude(packagePath: FsAgnosticPath, include: Include) {
        val packages = reverseMapDeltas[include]
        /** if we already have changes related to this key (they are already in the [reverseMapDeltas] with [include] key)
         * then just remove the package from them */
        if (packages != null) {
            packages.remove(packagePath)
        } else {
            // load packages from the previous state
            val prevReverseMap = prevState.reverseMap.loadValueByKey(include)
            prevReverseMap?.remove(packagePath)
            reverseMapDeltas[include] = prevReverseMap
        }
    }

    /**
     * @return [IncludesMapChange] that represents a new read-only state for include maps.
     */
    fun build(): IncludesMapChange =
        IncludesMapChange(forwardMap = getForwardMap(), reverseMap = getReverseMap())

    private fun getForwardMap(): Map<Include, MemorySharingIntSet?> =
        applyUpdates(map = prevState.forwardMap, deltaMap = forwardMapDeltas)

    private fun getReverseMap(): Map<Include, MemorySharingIntSet?> =
        applyUpdates(map = prevState.reverseMap, deltaMap = reverseMapDeltas)

    private fun applyUpdates(
        map: Map<Include, MemorySharingIntSet?>,
        deltaMap: MutableMap<FsAgnosticPath, MutableSet<Include>?>
    ): Map<Include, MemorySharingIntSet?> {
        if (deltaMap.isEmpty()) {
            return map
        }

        val out = map.toMutableMap()
        deltaMap.forEach { (path, values) ->
            merge(path, values, out)
        }
        return out
    }

    private fun merge(
        path: FsAgnosticPath,
        values: MutableSet<Include>?,
        out: MutableMap<Include, MemorySharingIntSet?>
    ) {

        if (values.isNullOrEmpty()) {
            out[path] = if (values == null) null else MemorySharingIntSet.empty()
            return
        }

        val newValues = values.asSequence().map { FsAgnosticPath.toIndex(it) }.toSet()
        val prevValues = out[path] ?: MemorySharingIntSet.empty()

        val setDeltas = mutableListOf<Pair<FsAgnosticPath, SetDelta>>()
        newValues.asSequence().filterNot { prevValues.contains(it) }
            .mapTo(setDeltas) { path to SetDelta.Add(it) }
        prevValues.asSequence().filterNot { newValues.contains(it) }
            .mapTo(setDeltas) { path to SetDelta.Remove(it) }

        val derivedDeltas = deriveDeltas(setDeltas) { out[it] }
        derivedDeltas.forEach { (path, newSet) ->
            out[path] = newSet
        }
    }
}

private fun Map<FsAgnosticPath, MemorySharingIntSet?>.loadValueByKey(
    key: FsAgnosticPath
): MutableSet<FsAgnosticPath>? =
    this[key]?.asSequence()?.map { FsAgnosticPath.fromIndex(it) }?.toHashSet()
