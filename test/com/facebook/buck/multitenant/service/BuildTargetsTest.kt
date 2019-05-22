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

import com.facebook.buck.core.exceptions.BuildTargetParseException
import com.facebook.buck.core.model.Flavor
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.google.common.collect.ImmutableSortedSet
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

class BuildTargetsTest {
    @Test
    fun createBuildTargetFromPartsWithEmptyPath() {
        val basePath = FsAgnosticPath.of("")
        val name = "foo"
        val buildTarget = BuildTargets.createBuildTargetFromParts(basePath, name)
        assertEquals("//:foo", buildTarget.fullyQualifiedName)
    }

    @Test
    fun createBuildTargetFromPartsWithNonEmptyPath() {
        val basePath = FsAgnosticPath.of("foo/bar")
        val name = "baz"
        val buildTarget = BuildTargets.createBuildTargetFromParts(basePath, name)
        assertEquals("//foo/bar:baz", buildTarget.fullyQualifiedName)
    }

    @Test
    fun parseFullyQualifiedTarget() {
        val fooBar = BuildTargets.parseOrThrow("//foo/bar:baz")
        assertEquals("", fooBar.cell)
        assertEquals("//foo/bar", fooBar.baseName)
        assertEquals("baz", fooBar.name)
        assertEquals(ImmutableSortedSet.of<Flavor>(), fooBar.flavors)
    }

    @Test(expected = BuildTargetParseException::class)
    fun parseEmptyStringShouldThrow() {
        BuildTargets.parseOrThrow("")
    }

    @Test(expected = BuildTargetParseException::class)
    fun parseRecursiveWildcardShouldThrow() {
        BuildTargets.parseOrThrow("//foo/bar...")
    }

    @Test(expected = BuildTargetParseException::class)
    fun parsePackageWildcardShouldThrow() {
        BuildTargets.parseOrThrow("//foo/bar:")
    }

    @Test(expected = BuildTargetParseException::class)
    fun parseNoColonShouldThrow() {
        BuildTargets.parseOrThrow("//foo/bar")
    }

    @Test(expected = BuildTargetParseException::class)
    fun parseRelativeTargetShouldThrow() {
        BuildTargets.parseOrThrow(":foo")
    }
}
