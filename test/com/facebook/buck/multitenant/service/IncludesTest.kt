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
        val includesMapChange = processIncludes(internalChanges = emptyInternalChanges(),
            generation = CURRENT_GENERATION,
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
            mapOf(packageA to uniqueSet(FsAgnosticPath.of("foo_bar.bzl")))))

        thrown.expect(IllegalArgumentException::class.java)
        thrown.expectMessage("New package $packageA already existed")

        processIncludes(internalChanges = internalChanges, generation = CURRENT_GENERATION,
            indexGenerationData = getIndexGenerationData(includesMapsHolder))
    }

    @Test
    fun processIncludesWithAddedPackages() {
        /**
         * changes (added)
         * a -> include 1.bzl and 2.bzl
         * b -> empty includes
         */
        val internalChanges = emptyInternalChanges().copy(
            addedBuildPackages = listOf(
                internalBuildPackage(packageA, include1, include2),
                internalBuildPackage(packageB))
        )

        val includesMapChange =
            processIncludes(
                internalChanges = internalChanges,
                generation = CURRENT_GENERATION,
                indexGenerationData = getIndexGenerationData(emptyIncludesMapHolder())
            )

        assertThat(includesMapChange.isEmpty(), equalTo(false))
        verifyMap(
            includesMapChange.forwardMap,
            packageA to uniqueSet(include1, include2),
            packageB to uniqueSet()
        )
        verifyMap(
            includesMapChange.reverseMap,
            include1 to uniqueSet(packageA),
            include2 to uniqueSet(packageA)
        )
    }

    @Test fun processIncludesWithMultipleAddedPackages() {
        /**
         * changes (added)
         * a -> include 1.bzl and 2.bzl
         * b -> include 1.bzl and 3.bzl
         */
        val internalChanges = emptyInternalChanges().copy(
            addedBuildPackages = listOf(internalBuildPackage(packageA, include1, include2),
                internalBuildPackage(packageB, include1, include3)))

        val includesMapChange =
            processIncludes(internalChanges = internalChanges, generation = CURRENT_GENERATION,
                indexGenerationData = getIndexGenerationData(emptyIncludesMapHolder()))

        assertThat(includesMapChange.isEmpty(), equalTo(false))
        verifyMap(
            includesMapChange.forwardMap,
            packageA to uniqueSet(include1, include2),
            packageB to uniqueSet(include1, include3)
        )
        verifyMap(
            includesMapChange.reverseMap,
            include1 to uniqueSet(packageA, packageB),
            include2 to uniqueSet(packageA),
            include3 to uniqueSet(packageB)
        )
    }

    @Test fun processIncludesWithModifiedPackages() {
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
            mapOf(packageA to uniqueSet(include1, include2),
                packageB to uniqueSet(include2, include3),
                packageC to uniqueSet(include2, include3))), reverseMap = createGenerationMap(
            mapOf(include1 to uniqueSet(packageA),
                include2 to uniqueSet(packageA, packageB, packageC),
                include3 to uniqueSet(packageB, packageC))))

        val internalChanges = emptyInternalChanges().copy(modifiedBuildPackages = listOf(
            internalBuildPackage(packageA, include1, include2, include3),
            internalBuildPackage(packageB, include3, include4),
            internalBuildPackage(packageC, include2, include3)))

        val includesMapChange =
            processIncludes(internalChanges = internalChanges, generation = CURRENT_GENERATION,
                indexGenerationData = getIndexGenerationData(includesMapsHolder))

        assertThat(includesMapChange.isEmpty(), equalTo(false))
        verifyMap(
            includesMapChange.forwardMap,
            packageA to uniqueSet(include1, include2, include3),
            packageB to uniqueSet(include3, include4),
            packageC to uniqueSet(include2, include3)
        )
        verifyMap(
            includesMapChange.reverseMap,
            include1 to uniqueSet(packageA),
            include2 to uniqueSet(packageA, packageC),
            include3 to uniqueSet(packageA, packageB, packageC),
            include4 to uniqueSet(packageB)
        )
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
            mapOf(packageA to uniqueSet(include1, include2),
                packageB to uniqueSet(include2, include3),
                packageC to uniqueSet(include2, include3))), reverseMap = createGenerationMap(
            mapOf(include1 to uniqueSet(packageA),
                include2 to uniqueSet(packageA, packageB, packageC),
                include3 to uniqueSet(packageB, packageC))))

        val internalChanges = emptyInternalChanges().copy(removedBuildPackages = listOf(packageB))

        val includesMapChange =
            processIncludes(internalChanges = internalChanges, generation = CURRENT_GENERATION,
                indexGenerationData = getIndexGenerationData(includesMapsHolder))

        assertThat(includesMapChange.isEmpty(), equalTo(false))
        verifyMap(
            includesMapChange.forwardMap,
            packageA to uniqueSet(include1, include2),
            packageB to null,
            packageC to uniqueSet(include2, include3)
        )
        verifyMap(
            includesMapChange.reverseMap,
            include1 to uniqueSet(packageA),
            include2 to uniqueSet(packageA, packageC),
            include3 to uniqueSet(packageC)
        )
    }

    @Test fun processIncludesWithAddedModifiedAndRemovedPackages() {
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
            mapOf(packageA to uniqueSet(include1, include2),
                packageB to uniqueSet(include2, include3),
                packageC to uniqueSet(include2, include3, include6))),
            reverseMap = createGenerationMap(mapOf(include1 to uniqueSet(packageA),
                include2 to uniqueSet(packageA, packageB, packageC),
                include3 to uniqueSet(packageB, packageC), include6 to uniqueSet(packageC))))

        val internalChanges = InternalChanges(
            addedBuildPackages = listOf(internalBuildPackage(packageD, include2, include4),
                internalBuildPackage(packageE, include1)), modifiedBuildPackages = listOf(
                internalBuildPackage(packageB, include1, include3, include5)),
            removedBuildPackages = listOf(packageC))

        val includesMapChange =
            processIncludes(internalChanges = internalChanges, generation = CURRENT_GENERATION,
                indexGenerationData = getIndexGenerationData(includesMapsHolder))

        assertThat(includesMapChange.isEmpty(), equalTo(false))
        verifyMap(includesMapChange.forwardMap,
            packageA to uniqueSet(include1, include2),
            packageB to uniqueSet(include1, include3, include5),
            packageC to null,
            packageD to uniqueSet(include2, include4),
            packageE to uniqueSet(include1))
        verifyMap(includesMapChange.reverseMap,
            include1 to uniqueSet(packageA, packageB, packageE),
            include2 to uniqueSet(packageA, packageD),
            include3 to uniqueSet(packageB),
            include4 to uniqueSet(packageD),
            include5 to uniqueSet(packageB),
            include6 to uniqueSet())
    }

    @Test
    fun processIncludesAddRemoveAndThenAddAgainPackage() {
        /**
         * initial state
         * c -> include 2.bzl, 3.bzl and 6.blz
         *
         * change #1
         * removed: c
         *
         * change #2
         * c -> include 5.bzl
         */
        val initialState = IncludesMapsHolder(forwardMap = createGenerationMap(
            mapOf(packageC to uniqueSet(include2, include3, include6))),
            reverseMap = createGenerationMap(
                mapOf(
                    include2 to uniqueSet(packageC),
                    include3 to uniqueSet(packageC),
                    include6 to uniqueSet(packageC)
                )))

        val internalChanges1 =
            InternalChanges(
                addedBuildPackages = listOf(),
                modifiedBuildPackages = listOf(),
                removedBuildPackages = listOf(packageC)
            )

        val includesMapChange1 =
            processIncludes(
                internalChanges = internalChanges1,
                generation = CURRENT_GENERATION,
                indexGenerationData = getIndexGenerationData(initialState)
            )

        assertThat(includesMapChange1.isEmpty(), equalTo(false))
        verifyMap(
            includesMapChange1.forwardMap,
            packageC to null
        )
        verifyMap(
            includesMapChange1.reverseMap,
            include2 to uniqueSet(),
            include3 to uniqueSet(),
            include6 to uniqueSet()
        )

        // Process change#2
        val internalChanges2 =
            InternalChanges(
                addedBuildPackages = listOf(internalBuildPackage(packageC, include5)),
                modifiedBuildPackages = listOf(),
                removedBuildPackages = listOf()
            )

        val includesMapsHolder2 =
            IncludesMapsHolder(
                forwardMap = createGenerationMap(includesMapChange1.forwardMap),
                reverseMap = createGenerationMap(includesMapChange1.reverseMap)
            )

        val includesMapChange2 =
            processIncludes(
                internalChanges = internalChanges2,
                generation = CURRENT_GENERATION,
                indexGenerationData = getIndexGenerationData(includesMapsHolder2)
            )

        assertThat(includesMapChange2.isEmpty(), equalTo(false))
        verifyMap(
            includesMapChange2.forwardMap,
            packageC to uniqueSet(include5)
        )
        verifyMap(
            includesMapChange2.reverseMap,
            include2 to uniqueSet(),
            include5 to uniqueSet(packageC),
            include3 to uniqueSet(),
            include6 to uniqueSet()
        )
    }

    @SuppressWarnings("SpreadOperator")
    private fun internalBuildPackage(
        packageA: ForwardRelativePath,
        vararg includes: Include
    ): InternalBuildPackage =
        InternalBuildPackage(buildFileDirectory = packageA, rules = setOf(),
            includes = hashSetOf(*includes))

    private fun emptyInternalChanges() =
        InternalChanges(addedBuildPackages = listOf(), modifiedBuildPackages = listOf(),
            removedBuildPackages = listOf())

    private fun emptyIncludesMapHolder(): IncludesMapsHolder =
        IncludesMapsHolder(forwardMap = createGenerationMap(), reverseMap = createGenerationMap())

    private fun createGenerationMap(
        localChanges: Map<Include, MemorySharingIntSet?> = mapOf()
    ): ForwardingGenerationMap<ForwardRelativePath, MemorySharingIntSet> =
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
        map: Map<ForwardRelativePath, MemorySharingIntSet?>,
        vararg expectedValues: Pair<ForwardRelativePath, MemorySharingIntSet?>
    ) {
        assertThat("Number of expected values must be the same as map size", map.size,
            equalTo(expectedValues.size))
        assertThat("All expected values are unique",
            expectedValues.asSequence().map { p -> p.first }.distinct().count(), equalTo(map.size))
        for ((path, includes) in expectedValues) {
            assertThat(map[path]?.toHashSet(), equalTo(includes?.toHashSet()))
        }
    }

    private fun uniqueSet(vararg values: ForwardRelativePath): MemorySharingIntSet.Unique =
        MemorySharingIntSet.Unique(values.map {
            checkNotNull(FsAgnosticPath.toIndex(it))
        }.toIntArray().sortedArray())
}
