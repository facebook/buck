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

package com.facebook.buck.multitenant.query

import org.hamcrest.Matchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThat
import org.junit.Test

class FsAgnosticSourcePathTest {
    @Test
    fun toStringIsPath() {
        val emptyPath = FsAgnosticSourcePath.of("")
        assertEquals("", emptyPath.toString())

        val fooBar = FsAgnosticSourcePath.of("foo/bar")
        assertEquals("foo/bar", fooBar.toString())
    }

    @Test
    fun compareToIdenticalObject() {
        val fooBar = FsAgnosticSourcePath.of("foo/bar")
        assertEquals(0, fooBar.compareTo(fooBar))
    }

    @Test
    fun compareFsAgnosticSourcePathsToEachOther() {
        val alpha = FsAgnosticSourcePath.of("alpha/foo")
        val beta = FsAgnosticSourcePath.of("beta/foo")
        assertThat(alpha.compareTo(beta), Matchers.lessThan(0))
        assertThat(beta.compareTo(alpha), Matchers.greaterThan(0))
    }

    /**
     * This is designed to exercise the "compareClasses" logic in [FsAgnosticSourcePath.compareTo].
     */
    @Test
    fun compareFsAgnosticSourcePathToPathSourcePath() {
        val fooPath = DummySourcePath("foo")
        val fooFsAgnostic = FsAgnosticSourcePath.of("foo")

        assertEquals(
                "This test assumes FsAgnosticSourcePath has a specific fully-qualified name, " +
                        "so this test should be updated if that assumption no longer holds.",
                "com.facebook.buck.multitenant.query.FsAgnosticSourcePath",
                fooFsAgnostic.javaClass.name
        )

        assertThat(
                "com.facebook.buck.multitenant.query.DummySourcePath is before " +
                        "com.facebook.buck.multitenant.query.FsAgnosticSourcePath lexicographically",
                fooPath.compareTo(fooFsAgnostic),
                Matchers.lessThan(0))
        assertThat(
                "com.facebook.buck.multitenant.query.DummySourcePath is before " +
                        "com.facebook.buck.multitenant.query.FsAgnosticSourcePath lexicographically",
                fooFsAgnostic.compareTo(fooPath),
                Matchers.greaterThan(0))
    }
}
