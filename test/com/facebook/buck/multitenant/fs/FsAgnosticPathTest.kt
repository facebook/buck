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

package com.facebook.buck.multitenant.fs

import java.nio.file.FileSystems
import java.nio.file.Paths
import org.hamcrest.Matchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException

class FsAgnosticPathTest {
    @get:Rule
    val thrown: ExpectedException = ExpectedException.none()

    @Test
    fun emptyPathIsOk() {
        val path = FsAgnosticPath.of("")
        assertEquals("", path.toString())
    }

    @Test
    fun singleComponentIsOk() {
        val path = FsAgnosticPath.of("foo")
        assertEquals("foo", path.toString())
    }

    @Test
    fun multiComponentIsOk() {
        val path = FsAgnosticPath.of("foo/bar")
        assertEquals("foo/bar", path.toString())
    }

    @Test
    fun isEmpty() {
        val emptyPath = FsAgnosticPath.of("")
        assertTrue(emptyPath.isEmpty())

        val foo = FsAgnosticPath.of("foo")
        assertFalse(foo.isEmpty())

        val fooBar = FsAgnosticPath.of("foo/bar")
        assertFalse(fooBar.isEmpty())
    }

    @Test
    fun pathStartsWithItself() {
        val emptyPath = FsAgnosticPath.of("")
        assertTrue(emptyPath.startsWith(emptyPath))

        val foo = FsAgnosticPath.of("foo")
        assertTrue(foo.startsWith(foo))

        val fooBar = FsAgnosticPath.of("foo/bar")
        assertTrue(fooBar.startsWith(fooBar))
    }

    @Test
    fun emptyPathIsAUniversalPrefix() {
        val emptyPath = FsAgnosticPath.of("")
        assertTrue(FsAgnosticPath.of("foo").startsWith(emptyPath))
        assertTrue(FsAgnosticPath.of("foo/bar").startsWith(emptyPath))
    }

    @Test
    fun startsWith() {
        val foo = FsAgnosticPath.of("foo")
        val fooBar = FsAgnosticPath.of("foo/bar")
        val food = FsAgnosticPath.of("food")
        assertTrue(fooBar.startsWith(foo))
        assertFalse(foo.startsWith(fooBar))
        assertFalse(food.startsWith(foo))
    }

    @Test
    fun invalidSingleDotPath() {
        thrown.expect(IllegalArgumentException::class.java)
        thrown.expectMessage("dot in path")
        FsAgnosticPath.of(".")
    }

    @Test
    fun invalidDoubleDotPath() {
        thrown.expect(IllegalArgumentException::class.java)
        thrown.expectMessage("dot-dot in path")
        FsAgnosticPath.of("..")
    }

    @Test
    fun invalidSlashOnlyPath() {
        thrown.expect(IllegalArgumentException::class.java)
        thrown.expectMessage("path must not start with slash")
        FsAgnosticPath.of("/")
    }

    @Test
    fun invalidAbsolutePath() {
        thrown.expect(IllegalArgumentException::class.java)
        thrown.expectMessage("path must not start with slash")
        FsAgnosticPath.of("/foo/bar")
    }

    @Test
    fun invalidPathWithTrailingSlash() {
        thrown.expect(IllegalArgumentException::class.java)
        thrown.expectMessage("must not end with slash")
        FsAgnosticPath.of("foo/bar/")
    }

    @Test
    fun invalidPathWithDoubleSlash() {
        thrown.expect(IllegalArgumentException::class.java)
        thrown.expectMessage("two slashes")
        FsAgnosticPath.of("foo//bar")
    }

    @Test
    fun invalidPathWithDotComponent() {
        thrown.expect(IllegalArgumentException::class.java)
        thrown.expectMessage("dot in path")
        FsAgnosticPath.of("foo/./bar")
    }

    @Test
    fun invalidPathWithDoubleDotComponent() {
        thrown.expect(IllegalArgumentException::class.java)
        thrown.expectMessage("dot-dot in path")
        FsAgnosticPath.of("foo/../bar")
    }

    @Test
    fun compareEqual() {
        val a = FsAgnosticPath.of("foo/bar")
        val b = FsAgnosticPath.of("foo/bar")
        assertEquals(0, a.compareTo(b))
        assertEquals(0, b.compareTo(a))
    }

    @Test
    fun compareNotEqual() {
        val a = FsAgnosticPath.of("foo/a")
        val b = FsAgnosticPath.of("foo/b")
        assertThat(a.compareTo(b), Matchers.lessThan(0))
        assertThat(b.compareTo(a), Matchers.greaterThan(0))
    }

    @Test
    fun resolveEmptyAgainstEmpty() {
        val empty1 = FsAgnosticPath.of("")
        val empty2 = FsAgnosticPath.of("")
        assertEquals(FsAgnosticPath.of(""), empty1.resolve(empty2))
    }

    @Test
    fun resolveEmptyAgainstNonEmpty() {
        val empty = FsAgnosticPath.of("")
        val other = FsAgnosticPath.of("foo/bar")
        assertEquals(FsAgnosticPath.of("foo/bar"), empty.resolve(other))
    }

    @Test
    fun resolveNonEmptyAgainstEmpty() {
        val other = FsAgnosticPath.of("foo/bar")
        val empty = FsAgnosticPath.of("")
        assertEquals(FsAgnosticPath.of("foo/bar"), other.resolve(empty))
    }

    @Test
    fun resolveNonEmptyAgainstNonEmpty() {
        val a = FsAgnosticPath.of("foo/bar")
        val b = FsAgnosticPath.of("baz/buzz")
        assertEquals(FsAgnosticPath.of("foo/bar/baz/buzz"), a.resolve(b))
        assertEquals(FsAgnosticPath.of("baz/buzz/foo/bar"), b.resolve(a))
    }

    @Test
    fun toPath() {
        val empty = FsAgnosticPath.of("")
        val foo = FsAgnosticPath.of("foo")
        val fooBar = FsAgnosticPath.of("foo/bar")
        val fooBarBaz = FsAgnosticPath.of("foo/bar/baz")

        val fooPath = Paths.get("foo")
        val fooBarPath = fooPath.resolve(Paths.get("bar"))
        val fooBarBazPath = fooBarPath.resolve(Paths.get("baz"))

        val fileSystem = FileSystems.getDefault()
        assertEquals(empty.toPath(fileSystem), Paths.get(""))
        assertEquals(foo.toPath(fileSystem), fooPath)
        assertEquals(fooBar.toPath(fileSystem), fooBarPath)
        assertEquals(fooBarBaz.toPath(fileSystem), fooBarBazPath)
    }
}
