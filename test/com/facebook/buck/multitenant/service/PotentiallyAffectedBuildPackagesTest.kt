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
import com.facebook.buck.multitenant.fs.FsAgnosticPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PotentiallyAffectedBuildPackagesTest {
    @Test fun emptyFsChangesShouldResultInEmptyAffectedPackages() {
        val commit = "123"
        val changes = getPotentiallyAffectedBuildPackages(
            fsChanges = FsChanges(commit = commit, added = listOf(), modified = listOf(),
                removed = listOf()), buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { it }, includesProvider = { setOf() }) { false }
        assertEquals(commit, changes.commit)
        assertEquals(0, changes.added.size)
        assertEquals(0, changes.modified.size)
        assertEquals(0, changes.removed.size)
    }

    @Test fun whenBuildFileAddedPackageIsCreated() {
        getPotentiallyAffectedBuildPackages(fsChanges = FsChanges(commit = "123",
            added = listOf(FsChange.Added(FsAgnosticPath.of("BUCK"), null)), modified = listOf(),
            removed = listOf()), buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { it }, includesProvider = { setOf() }) { false }.let { changes ->
            assertEquals(1, changes.added.size)
            assertEquals(FsAgnosticPath.of(""), changes.added[0])
            assertEquals(0, changes.modified.size)
            assertEquals(0, changes.removed.size)
        }

        getPotentiallyAffectedBuildPackages(fsChanges = FsChanges(commit = "123",
            added = listOf(FsChange.Added(FsAgnosticPath.of("a/b/BUCK"), null)),
            modified = listOf(), removed = listOf()), buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { it }, includesProvider = { setOf() }) { false }.let { changes ->
            assertEquals(1, changes.added.size)
            assertEquals(FsAgnosticPath.of("a/b"), changes.added[0])
            assertEquals(0, changes.modified.size)
            assertEquals(0, changes.removed.size)
        }
    }

    @Test fun whenBuildFileAddedPackageIsCreatedAndParentIsModified() {
        getPotentiallyAffectedBuildPackages(fsChanges = FsChanges(commit = "123",
            added = listOf(FsChange.Added(FsAgnosticPath.of("a/b/BUCK"), null)),
            modified = listOf(), removed = listOf()), buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { it }, includesProvider = { setOf() }) {
            it == FsAgnosticPath.of("a")
        }.let { changes ->
            assertEquals(1, changes.added.size)
            assertEquals(FsAgnosticPath.of("a/b"), changes.added[0])
            assertEquals(1, changes.modified.size)
            assertEquals(FsAgnosticPath.of("a"), changes.modified[0])
            assertEquals(0, changes.removed.size)
        }

        getPotentiallyAffectedBuildPackages(fsChanges = FsChanges(commit = "123",
            added = listOf(FsChange.Added(FsAgnosticPath.of("a/BUCK"), null)), modified = listOf(),
            removed = listOf()), buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { it }, includesProvider = { setOf() }) {
            it == FsAgnosticPath.of("")
        }.let { changes ->
            assertEquals(1, changes.added.size)
            assertEquals(FsAgnosticPath.of("a"), changes.added[0])
            assertEquals(1, changes.modified.size)
            assertEquals(FsAgnosticPath.of(""), changes.modified[0])
            assertEquals(0, changes.removed.size)
        }
    }

    @Test fun whenBuildFileDeletedPackageIsRemovedAndParentIsModified() {
        getPotentiallyAffectedBuildPackages(
            fsChanges = FsChanges(commit = "123", added = listOf(), modified = listOf(),
                removed = listOf(FsChange.Removed(FsAgnosticPath.of("BUCK")))),
            buildFileName = FsAgnosticPath.of("BUCK"), cellPathNormalizer = { it },
            includesProvider = { setOf() }) { it == FsAgnosticPath.of("") }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(0, changes.modified.size)
            assertEquals(1, changes.removed.size)
            assertEquals(FsAgnosticPath.of(""), changes.removed[0])
        }

        getPotentiallyAffectedBuildPackages(
            fsChanges = FsChanges(commit = "123", added = listOf(), modified = listOf(),
                removed = listOf(FsChange.Removed(FsAgnosticPath.of("a/b/c/d/BUCK")))),
            buildFileName = FsAgnosticPath.of("BUCK"), cellPathNormalizer = { it },
            includesProvider = { setOf() }) {
            setOf(FsAgnosticPath.of(""), FsAgnosticPath.of("a/b/c/d")).contains(it)
        }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(1, changes.modified.size)
            assertEquals(FsAgnosticPath.of(""), changes.modified[0])
            assertEquals(1, changes.removed.size)
            assertEquals(FsAgnosticPath.of("a/b/c/d"), changes.removed[0])
        }
    }

    @Test fun whenSourceFileAddedPackageIsModified() {
        getPotentiallyAffectedBuildPackages(fsChanges = FsChanges(commit = "123",
            added = listOf(FsChange.Added(FsAgnosticPath.of("a/file.cpp"), null)),
            modified = listOf(), removed = listOf()), buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { it }, includesProvider = { setOf() }) {
            setOf(FsAgnosticPath.of(""), FsAgnosticPath.of("a"), FsAgnosticPath.of("b")).contains(
                it)
        }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(1, changes.modified.size)
            assertEquals(FsAgnosticPath.of("a"), changes.modified[0])
            assertEquals(0, changes.removed.size)
        }

        getPotentiallyAffectedBuildPackages(fsChanges = FsChanges(commit = "123",
            added = listOf(FsChange.Added(FsAgnosticPath.of("d/c/b/a/file.cpp"), null)),
            modified = listOf(), removed = listOf()), buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { it }, includesProvider = { setOf() }) {
            setOf(FsAgnosticPath.of(""), FsAgnosticPath.of("a"), FsAgnosticPath.of("b")).contains(
                it)
        }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(1, changes.modified.size)
            assertEquals(FsAgnosticPath.of(""), changes.modified[0])
            assertEquals(0, changes.removed.size)
        }
    }

    @Test fun whenSourceFileRemovedPackageIsModified() {
        getPotentiallyAffectedBuildPackages(
            fsChanges = FsChanges(commit = "123", added = listOf(), modified = listOf(),
                removed = listOf(FsChange.Removed(FsAgnosticPath.of("a/file.cpp")))),
            buildFileName = FsAgnosticPath.of("BUCK"), cellPathNormalizer = { it },
            includesProvider = { setOf() }) {
            setOf(FsAgnosticPath.of(""), FsAgnosticPath.of("a"), FsAgnosticPath.of("b")).contains(
                it)
        }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(1, changes.modified.size)
            assertEquals(FsAgnosticPath.of("a"), changes.modified[0])
            assertEquals(0, changes.removed.size)
        }

        getPotentiallyAffectedBuildPackages(
            fsChanges = FsChanges(commit = "123", added = listOf(), modified = listOf(),
                removed = listOf(FsChange.Removed(FsAgnosticPath.of("d/c/b/a/file.cpp")))),
            buildFileName = FsAgnosticPath.of("BUCK"), cellPathNormalizer = { it },
            includesProvider = { setOf() }) {
            setOf(FsAgnosticPath.of(""), FsAgnosticPath.of("a"), FsAgnosticPath.of("b")).contains(
                it)
        }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(1, changes.modified.size)
            assertEquals(FsAgnosticPath.of(""), changes.modified[0])
            assertEquals(0, changes.removed.size)
        }
    }

    @Test fun whenSourceFileModifiedPackageIsNotChanged() {
        getPotentiallyAffectedBuildPackages(fsChanges = FsChanges(commit = "123", added = listOf(),
            modified = listOf(FsChange.Modified(FsAgnosticPath.of("a/file.cpp"), null)),
            removed = listOf()), buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { it }, includesProvider = { setOf() }) {
            setOf(FsAgnosticPath.of(""), FsAgnosticPath.of("a"), FsAgnosticPath.of("b")).contains(
                it)
        }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(0, changes.modified.size)
            assertEquals(0, changes.removed.size)
        }
    }

    @Test fun whenDependencyModifiedPackageIsModified() {
        getPotentiallyAffectedBuildPackages(fsChanges = FsChanges(commit = "123", added = listOf(),
            modified = listOf(FsChange.Modified(FsAgnosticPath.of("defs.bzl"), null)),
            removed = listOf()), buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { it }, includesProvider = { path ->
                mapOf(FsAgnosticPath.of("defs.bzl") to setOf(FsAgnosticPath.of(""),
                    FsAgnosticPath.of("a"))).getOrDefault(path, setOf())
            }) {
            setOf(FsAgnosticPath.of(""), FsAgnosticPath.of("a"), FsAgnosticPath.of("b")).contains(
                it)
        }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(2, changes.modified.size)
            assertTrue(changes.modified.contains(FsAgnosticPath.of("")))
            assertTrue(changes.modified.contains(FsAgnosticPath.of("a")))
            assertEquals(0, changes.removed.size)
        }
    }

    @Test fun whenDependencyRemovedPackageIsModified() {
        getPotentiallyAffectedBuildPackages(
            fsChanges = FsChanges(commit = "123", added = listOf(), modified = listOf(),
                removed = listOf(FsChange.Removed(FsAgnosticPath.of("defs.bzl")))),
            buildFileName = FsAgnosticPath.of("BUCK"), cellPathNormalizer = { it },
            includesProvider = { path ->
                mapOf(FsAgnosticPath.of("defs.bzl") to setOf(FsAgnosticPath.of(""),
                    FsAgnosticPath.of("a"))).getOrDefault(path, setOf())
            }) {
            setOf(FsAgnosticPath.of(""), FsAgnosticPath.of("a"), FsAgnosticPath.of("b")).contains(
                it)
        }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(2, changes.modified.size)
            assertTrue(changes.modified.contains(FsAgnosticPath.of("")))
            assertTrue(changes.modified.contains(FsAgnosticPath.of("a")))
            assertEquals(0, changes.removed.size)
        }
    }

    @Test fun whenRandomFileAddedPackageIsNotChanged() {
        getPotentiallyAffectedBuildPackages(fsChanges = FsChanges(commit = "123",
            added = listOf(FsChange.Added(FsAgnosticPath.of("d/defs.bzl"), null)),
            modified = listOf(), removed = listOf()), buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { it }, includesProvider = { path ->
                mapOf(FsAgnosticPath.of("defs.bzl") to setOf(FsAgnosticPath.of("a"),
                    FsAgnosticPath.of("b"))).getOrDefault(path, setOf())
            }) {
            setOf(FsAgnosticPath.of("a"), FsAgnosticPath.of("b"), FsAgnosticPath.of("c")).contains(
                it)
        }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(0, changes.modified.size)
            assertEquals(0, changes.removed.size)
        }
    }

    @Test fun whenFilesFromAnotherCellsArePresentTheyAreIgnored() {
        getPotentiallyAffectedBuildPackages(fsChanges = FsChanges(commit = "123",
            added = listOf(FsChange.Added(FsAgnosticPath.of("root.cpp"), null)),
            modified = listOf(FsChange.Modified(FsAgnosticPath.of("cell2/BUCK"), null)),
            removed = listOf(FsChange.Removed(FsAgnosticPath.of("cell1/somefile.cpp")))),
            buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { normalizeCellPath("cell1", it) },
            includesProvider = { setOf() }) {
            setOf(FsAgnosticPath.of(""), FsAgnosticPath.of("a"), FsAgnosticPath.of("b")).contains(
                it)
        }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(1, changes.modified.size)
            assertEquals(0, changes.removed.size)
        }
    }

    @Test fun whenParseDependencyFromAnotherCellThenItIsHonored() {
        getPotentiallyAffectedBuildPackages(
            fsChanges = FsChanges(commit = "123", added = listOf(), modified = listOf(),
                removed = listOf(FsChange.Removed(FsAgnosticPath.of("cell1/bar.bzl")))),
            buildFileName = FsAgnosticPath.of("BUCK"),
            cellPathNormalizer = { normalizeCellPath("cell2", it) }, includesProvider = { path ->
                mapOf(FsAgnosticPath.of("cell1/bar.bzl") to setOf(FsAgnosticPath.of("a"),
                    FsAgnosticPath.of("b"))).getOrDefault(path, setOf())
            }) {
            setOf(FsAgnosticPath.of(""), FsAgnosticPath.of("a"), FsAgnosticPath.of("b")).contains(
                it)
        }.let { changes ->
            assertEquals(0, changes.added.size)
            assertEquals(2, changes.modified.size)
            assertEquals(0, changes.removed.size)
        }
    }

    private fun normalizeCellPath(cellPrefix: String, path: ForwardRelativePath): ForwardRelativePath? {
        return if (path.startsWith(FsAgnosticPath.of(cellPrefix))) {
            FsAgnosticPath.of(path.toString().removePrefix("$cellPrefix/"))
        } else {
            null
        }
    }
}
