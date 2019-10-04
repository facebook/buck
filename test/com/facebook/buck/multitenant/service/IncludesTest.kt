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
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.hamcrest.Matchers.equalTo
import org.junit.Assert.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException

private const val CURRENT_GENERATION: Generation = 42

private val packageA = FsAgnosticPath.of("a")
private val packageB = FsAgnosticPath.of("b")
private val packageC = FsAgnosticPath.of("c")
private val packageD = FsAgnosticPath.of("d")
private val packageE = FsAgnosticPath.of("e")

private val include1 = FsAgnosticPath.of("1.bzl")
private val include2 = FsAgnosticPath.of("2.bzl")
private val include3 = FsAgnosticPath.of("3.bzl")
private val include4 = FsAgnosticPath.of("4.bzl")
private val include5 = FsAgnosticPath.of("5.bzl")
private val include6 = FsAgnosticPath.of("6.bzl")

internal class IncludesTest {

    @get:Rule var thrown: ExpectedException = ExpectedException.none()

    @Test
    fun processIncludesWithEmptyPackages() {
        val includesMapChange = processIncludes(generation = CURRENT_GENERATION,
            internalChanges = emptyInternalChanges(),
            indexGenerationData = getIndexGenerationData(emptyIncludesMapHolder()))

        assertThat(includesMapChange.isEmpty(), equalTo(true))
    }

    @Test
    fun processIncludesWithExistingAddedPackage() {
        /**
         * Initial state (current generation)
         * a -> include foo_bar.bzl
         *
         * changes (added)
         * a -> include 1.bzl and 2.bzl
         */
        val internalChanges = emptyInternalChanges().copy(
            addedBuildPackages = listOf(internalBuildPackage(packageA, include1, include2)))
        val includesMapsHolder = emptyIncludesMapHolder().copy(forwardMap = createGenerationMap(
            mapOf(packageA to setOf(FsAgnosticPath.of("foo_bar.bzl")))))

        thrown.expect(IllegalArgumentException::class.java)
        thrown.expectMessage("New package $packageA already existed")

        processIncludes(generation = CURRENT_GENERATION, internalChanges = internalChanges,
            indexGenerationData = getIndexGenerationData(includesMapsHolder))
    }

    @Test
    fun processIncludesWithAddedPackages() {
        /**
         * changes (added)
         * a -> include 1.bzl and 2.bzl
         */
        val internalChanges = emptyInternalChanges().copy(
            addedBuildPackages = listOf(internalBuildPackage(packageA, include1, include2)))

        val includesMapChange =
            processIncludes(generation = CURRENT_GENERATION, internalChanges = internalChanges,
                indexGenerationData = getIndexGenerationData(emptyIncludesMapHolder()))

        assertThat(includesMapChange.isEmpty(), equalTo(false))
        verifyMap(includesMapChange.forwardMap, packageA to setOf(include1, include2))
        verifyMap(includesMapChange.reverseMap, include1 to setOf(packageA),
            include2 to setOf(packageA))
    }

    @Test
    fun processIncludesWithMultipleAddedPackages() {
        /**
         * changes (added)
         * a -> include 1.bzl and 2.bzl
         * b -> include 1.bzl and 3.bzl
         */
        val internalChanges = emptyInternalChanges().copy(
            addedBuildPackages = listOf(internalBuildPackage(packageA, include1, include2),
                internalBuildPackage(packageB, include1, include3)))

        val includesMapChange =
            processIncludes(generation = CURRENT_GENERATION, internalChanges = internalChanges,
                indexGenerationData = getIndexGenerationData(emptyIncludesMapHolder()))

        assertThat(includesMapChange.isEmpty(), equalTo(false))
        verifyMap(includesMapChange.forwardMap, packageA to setOf(include1, include2),
            packageB to setOf(include1, include3))
        verifyMap(includesMapChange.reverseMap, include1 to setOf(packageA, packageB),
            include2 to setOf(packageA), include3 to setOf(packageB))
    }

    @Test
    fun processIncludesWithModifiedPackages() {
        /**
         * a -> include 1.bzl and 2.bzl
         * b -> include 2.bzl and 3.bzl
         * c -> include 2.bzl and 3.bzl
         *
         * changes (modified)
         * a -> include 1.bzl, 2.bzl and 3.bzl
         * b -> include 3.bzl and 4.blz
         * c -> include 2.bzl and 3.bzl
         */
        val includesMapsHolder = IncludesMapsHolder(forwardMap = createGenerationMap(
            mapOf(packageA to setOf(include1, include2), packageB to setOf(include2, include3),
                packageC to setOf(include2, include3))), reverseMap = createGenerationMap(
            mapOf(include1 to setOf(packageA), include2 to setOf(packageA, packageB, packageC),
                include3 to setOf(packageB, packageC))))

        val internalChanges = emptyInternalChanges().copy(modifiedBuildPackages = listOf(
            internalBuildPackage(packageA, include1, include2, include3),
            internalBuildPackage(packageB, include3, include4),
            internalBuildPackage(packageC, include2, include3)))

        val includesMapChange =
            processIncludes(generation = CURRENT_GENERATION, internalChanges = internalChanges,
                indexGenerationData = getIndexGenerationData(includesMapsHolder))

        assertThat(includesMapChange.isEmpty(), equalTo(false))
        verifyMap(includesMapChange.forwardMap, packageA to setOf(include1, include2, include3),
            packageB to setOf(include3, include4), packageC to setOf(include2, include3))
        verifyMap(includesMapChange.reverseMap, include1 to setOf(packageA),
            include2 to setOf(packageA, packageC), include3 to setOf(packageA, packageB, packageC),
            include4 to setOf(packageB))
    }

    @Test
    fun processIncludesWithRemovedPackages() {
        /**
         * a -> include 1.bzl and 2.bzl
         * b -> include 2.bzl and 3.bzl
         * c -> include 2.bzl and 3.bzl
         *
         * changes (removed): package b
         */
        val includesMapsHolder = IncludesMapsHolder(forwardMap = createGenerationMap(
            mapOf(packageA to setOf(include1, include2), packageB to setOf(include2, include3),
                packageC to setOf(include2, include3))), reverseMap = createGenerationMap(
            mapOf(include1 to setOf(packageA), include2 to setOf(packageA, packageB, packageC),
                include3 to setOf(packageB, packageC))))

        val internalChanges = emptyInternalChanges().copy(removedBuildPackages = listOf(packageB))

        val includesMapChange =
            processIncludes(generation = CURRENT_GENERATION, internalChanges = internalChanges,
                indexGenerationData = getIndexGenerationData(includesMapsHolder))

        assertThat(includesMapChange.isEmpty(), equalTo(false))
        verifyMap(includesMapChange.forwardMap, packageA to setOf(include1, include2),
            packageC to setOf(include2, include3))
        verifyMap(includesMapChange.reverseMap, include1 to setOf(packageA),
            include2 to setOf(packageA, packageC), include3 to setOf(packageC))
    }

    @Test
    fun processIncludesWithAddedModifiedAndRemovedPackages() {
        /**
         * initial state
         * a -> include 1.bzl and 2.bzl
         * b -> include 2.bzl and 3.bzl
         * c -> include 2.bzl, 3.bzl and 6.blz
         *
         * changes
         *
         * added:
         * d -> include 2.bzl and 4.bzl
         * e -> include 1.bzl
         *
         * modified:
         * b -> include 1.bzl, 3.bzl and 5.bzl
         *
         * removed:
         * c
         */
        val includesMapsHolder = IncludesMapsHolder(forwardMap = createGenerationMap(
            mapOf(packageA to setOf(include1, include2), packageB to setOf(include2, include3),
                packageC to setOf(include2, include3, include6))), reverseMap = createGenerationMap(
            mapOf(include1 to setOf(packageA), include2 to setOf(packageA, packageB, packageC),
                include3 to setOf(packageB, packageC), include6 to setOf(packageC))))

        val internalChanges = InternalChanges(
            addedBuildPackages = listOf(internalBuildPackage(packageD, include2, include4),
                internalBuildPackage(packageE, include1)), modifiedBuildPackages = listOf(
                internalBuildPackage(packageB, include1, include3, include5)),
            removedBuildPackages = listOf(packageC))

        val includesMapChange =
            processIncludes(generation = CURRENT_GENERATION, internalChanges = internalChanges,
                indexGenerationData = getIndexGenerationData(includesMapsHolder))

        assertThat(includesMapChange.isEmpty(), equalTo(false))
        verifyMap(includesMapChange.forwardMap, packageA to setOf(include1, include2),
            packageB to setOf(include1, include3, include5), packageD to setOf(include2, include4),
            packageE to setOf(include1))
        verifyMap(includesMapChange.reverseMap, include1 to setOf(packageA, packageB, packageE),
            include2 to setOf(packageA, packageD), include3 to setOf(packageB),
            include4 to setOf(packageD), include5 to setOf(packageB))
    }

    @SuppressWarnings("SpreadOperator")
    private fun internalBuildPackage(
        packageA: FsAgnosticPath,
        vararg includes: Include
    ): InternalBuildPackage =
        InternalBuildPackage(buildFileDirectory = packageA, rules = setOf(),
            includes = setOf(*includes))

    private fun emptyInternalChanges() =
        InternalChanges(addedBuildPackages = listOf(), modifiedBuildPackages = listOf(),
            removedBuildPackages = listOf())

    private fun emptyIncludesMapHolder(): IncludesMapsHolder =
        IncludesMapsHolder(forwardMap = createGenerationMap(), reverseMap = createGenerationMap())

    private fun createGenerationMap(
        localChanges: Map<FsAgnosticPath, Includes> = mapOf()
    ): ForwardingGenerationMap<FsAgnosticPath, Includes> =
        ForwardingGenerationMap(CURRENT_GENERATION, localChanges, DefaultGenerationMap { it })

    private fun getIndexGenerationData(
        includesMapsHolder: IncludesMapsHolder
    ): IndexGenerationData {
        val indexGenerationData = mockk<IndexGenerationData>()
        val method = slot<(IncludesMapsHolder) -> Any>()
        // call a real method for `withIncludesMap`
        every { indexGenerationData.withIncludesMap(capture(method)) } answers {
            (method.captured)(includesMapsHolder)
        }
        return indexGenerationData
    }

    private fun verifyMap(
        map: Map<FsAgnosticPath, Includes>,
        vararg expectedValues: Pair<FsAgnosticPath, Includes>
    ) {
        assertThat("Number of expected values must be the same as map size", map.size,
            equalTo(expectedValues.size))
        assertThat("All expected values are unique",
            expectedValues.asSequence().map { p -> p.first }.distinct().count(), equalTo(map.size))
        for ((path, includes) in expectedValues) {
            assertThat(map[path], equalTo(includes))
        }
    }
}
