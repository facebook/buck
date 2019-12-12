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

@file:Suppress("MatchingDeclarationName")

package com.facebook.buck.multitenant.service

import com.facebook.buck.core.path.ForwardRelativePath
import com.facebook.buck.multitenant.collect.Generation
import com.facebook.buck.multitenant.fs.FsAgnosticPath

typealias Include = ForwardRelativePath

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

private typealias EmptyValue = Unit

/**
 * Builder that allows to process parse-time includes changes.
 */
class IncludesMapChangeBuilder(private val prevState: IncludesMapChange) {

    private val forwardMapValues: MutableMap<ForwardRelativePath, EmptyValue?> = mutableMapOf()
    private val forwardMapDeltas: MutableList<Pair<ForwardRelativePath, SetDelta>> = mutableListOf()
    private val reverseMapDeltas: MutableList<Pair<Include, SetDelta>> = mutableListOf()

    /**
     * Processes includes from the added package.
     * If includes are not empty then add them to forward and reverse include maps.
     *
     * @param packagePath build file directory path that corresponds to the added package
     * @param includes parse time includes that corresponds to the added package
     */
    fun processAddedPackage(packagePath: ForwardRelativePath, includes: HashSet<ForwardRelativePath>) {
        // verification that the package is new
        require(prevState.forwardMap[packagePath] == null) {
            "New package $packagePath already existed"
        }

        if (includes.isEmpty()){
            forwardMapValues[packagePath] = EmptyValue
        }
        includes.forEach {
            appendForwardMap(buildFilePath = packagePath, include = it)
            appendReverseMap(buildFilePath = packagePath, include = it)
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
    fun processModifiedPackage(packagePath: ForwardRelativePath, includes: HashSet<ForwardRelativePath>) {
        val previousIncludes = prevState.forwardMap[packagePath]
        val brandNewIncludes = previousIncludes.isNullOrEmpty() && includes.isNotEmpty()
        val previousIncludesHashSet =
            previousIncludes?.asSequence()?.map { FsAgnosticPath.fromIndex(it) }?.toHashSet()
                ?: hashSetOf()
        if (brandNewIncludes || previousIncludesHashSet != includes) {
            processRemovedPackage(packageToRemove = packagePath)
            includes.forEach {
                appendForwardMap(buildFilePath = packagePath, include = it)
                appendReverseMap(buildFilePath = packagePath, include = it)
            }
        }
    }

    /**
     * Processes includes from the removed package.
     * Removes the package from forward and reverse maps
     *
     * @param packageToRemove build file directory path that corresponds to the removed package
     */
    fun processRemovedPackage(packageToRemove: ForwardRelativePath) {
        forwardMapValues[packageToRemove] = null

        val previousIncludes = prevState.forwardMap[packageToRemove]
        previousIncludes?.forEach {
            removeFromReverseMap(buildFilePath = packageToRemove, include = FsAgnosticPath.fromIndex(it))
        }
    }

    /**
     * @return [IncludesMapChange] that represents a new read-only state for include maps.
     */
    fun build(): IncludesMapChange =
        IncludesMapChange(forwardMap = getForwardMap(), reverseMap = getReverseMap())

    private fun getForwardMap(): Map<Include, MemorySharingIntSet?> =
        applyUpdates(
            map = prevState.forwardMap,
            deltas = forwardMapDeltas,
            valuesMap = forwardMapValues
        )

    private fun getReverseMap(): Map<Include, MemorySharingIntSet?> =
        applyUpdates(map = prevState.reverseMap, deltas = reverseMapDeltas)

    private fun applyUpdates(
        map: Map<ForwardRelativePath, MemorySharingIntSet?>,
        deltas: List<Pair<ForwardRelativePath, SetDelta>>,
        valuesMap: Map<ForwardRelativePath, Unit?> = emptyMap()
    ): Map<ForwardRelativePath, MemorySharingIntSet?> {
        if (valuesMap.isEmpty() && deltas.isEmpty()) {
            return map
        }

        val out = map.toMutableMap()
        valuesMap.forEach { (path, values) ->
            out[path] = if (values == null) null else MemorySharingIntSet.empty()
        }
        val derivedDeltas = deriveDeltas(deltas) { out[it] }
        derivedDeltas.forEach { (path, newSet) ->
            out[path] = newSet ?: MemorySharingIntSet.empty()
        }
        return out
    }

    private fun appendForwardMap(buildFilePath: ForwardRelativePath, include: Include) {
        forwardMapDeltas.add(buildFilePath to SetDelta.Add(FsAgnosticPath.toIndex(include)))
    }

    private fun appendReverseMap(buildFilePath: ForwardRelativePath, include: Include) {
        reverseMapDeltas.add(include to SetDelta.Add(FsAgnosticPath.toIndex(buildFilePath)))
    }

    private fun removeFromReverseMap(buildFilePath: ForwardRelativePath, include: Include) {
        reverseMapDeltas.add(include to SetDelta.Remove(FsAgnosticPath.toIndex(buildFilePath)))
    }
}
