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

/**
 * A collection of packages (package is represented by the relative path to its root) that may have
 * changed because of some input change and thus need to be reparsed
 */
data class PotentiallyAffectedBuildPackages(
    val commit: Commit,
    val added: List<ForwardRelativePath> = emptyList(),
    val modified: List<ForwardRelativePath> = emptyList(),
    val removed: List<ForwardRelativePath> = emptyList()
) {
    /**
     * True if no packages are affected by commit
     */
    fun isEmpty() = added.isEmpty() && modified.isEmpty() && removed.isEmpty()

    /**
     * True if at least one package is affected by commit
     */
    fun isNotEmpty() = !isEmpty()

    /**
     * Size of potentially affected packages
     */
    fun size() = added.size + modified.size + removed.size
}

/**
 * Based on changed input files, determines packages that might have changed. We use defensive
 * approach to detect if package is changed because we overschedule reparsing rather than
 * sacrifice correctness.
 *
 * @param fsChanges Changes in files relative to the root of the repo
 * @param buildFileName Name of the build file (like 'BUCK'), expressed as [ForwardRelativePath], for
 * current cell being parsed
 * @param includesProvider returns paths to build packages that transitively includes passed include path
 * TODO: change to a function
 * @param cellPathNormalizer Function that transforms a path relative to the root of repo, to the
 * path relative to the root of the cell. Returns null if path does not belong to the cell.
 * @param packageExists Predicate used to check if some package exists in the universe of a current
 * cell
 * @return Paths to packages which may be affected by [fsChanges], relative to the cell
 */
fun getPotentiallyAffectedBuildPackages(
    fsChanges: FsChanges,
    buildFileName: ForwardRelativePath,
    includesProvider: (includePath: Include) -> Iterable<ForwardRelativePath>,
    cellPathNormalizer: (path: ForwardRelativePath) -> ForwardRelativePath?,
    packageExists: (path: ForwardRelativePath) -> Boolean
): PotentiallyAffectedBuildPackages {
    val newPackages = mutableSetOf<ForwardRelativePath>()
    val changedPackages = mutableSetOf<ForwardRelativePath>()
    val removedPackages = mutableSetOf<ForwardRelativePath>()

    fsChanges.added.forEach { added ->
        // Adding a new file cannot be a parse-time dependency, as they have to be explicitly
        // declared, so nothing to do here

        // Only if added file belongs to the cell
        cellPathNormalizer(added.path)?.let { path ->

            // Adding a new package: need to parse it and also its parent, because a parent may lose
            // targets
            if (path.nameAsPath().orElse(ForwardRelativePath.EMPTY) == buildFileName) {
                val newPackagePath = path.parent().orElse(ForwardRelativePath.EMPTY)
                newPackages.add(newPackagePath)
                getContainingPackage(newPackagePath.parent().orElse(ForwardRelativePath.EMPTY),
                    packageExists)?.let { changedPackages.add(it) }
                return@forEach
            }

            // Adding a new source file: reparse package that potentially owns the file because
            // some target contents may change
            getContainingPackage(path.parent().orElse(ForwardRelativePath.EMPTY), packageExists)?.let { changedPackages.add(it) }
        }
    }

    fsChanges.modified.forEach { modified ->
        // Modifying a parse-time dependency: detect and reparse affected packages only
        // Modified file may belong to a different cell!
        changedPackages.addAll(includesProvider(modified.path))

        // Only if modified file belongs to the cell
        cellPathNormalizer(modified.path)?.let { path ->
            // Modifying existing package: need to reparse it
            if (path.nameAsPath().orElse(ForwardRelativePath.EMPTY) == buildFileName) {
                val packagePath = path.parent().orElse(ForwardRelativePath.EMPTY)
                changedPackages.add(packagePath)
            }

            // Modifying a source file: it does not yield to changes in packages, so nothing more
            // to do
        }
    }

    fsChanges.removed.forEach { removed ->
        // Removing a parse-time dependency: detect and reparse affected packages only
        // Removed file may belong to a different cell!
        changedPackages.addAll(includesProvider(removed.path))

        // Only if removed file belongs to the cell
        cellPathNormalizer(removed.path)?.let { path ->
            // Removing a package: need to remove the package itself and reparse the parent as its
            // targets can change because parent package may start to own more files
            if (path.nameAsPath().orElse(ForwardRelativePath.EMPTY) == buildFileName) {
                val packagePath = path.parent().orElse(ForwardRelativePath.EMPTY)
                removedPackages.add(packagePath)
                getContainingPackage(packagePath.parent().orElse(ForwardRelativePath.EMPTY),
                    packageExists)?.let { changedPackages.add(it) }
                return@forEach
            }

            // Removing a source file: reparse package that potentially owns the file because
            // some target contents may change
            getContainingPackage(path.parent().orElse(ForwardRelativePath.EMPTY), packageExists)?.let { changedPackages.add(it) }
        }
    }

    return PotentiallyAffectedBuildPackages(commit = fsChanges.commit, added = newPackages.toList(),
        removed = removedPackages.toList(),
        modified = (changedPackages - newPackages - removedPackages).toList())
}

/**
 * Return a package (by path) that potentially owns the specified path. There should be only one
 * package, or none, in which case null is returned.
 *
 * @param dir Path to a folder that might belong to some package; this folder
 * will be either directly a package root folder (folder that has build file) or some folder below
 * package root it in a directory tree
 * @param packageExists Predicate used to check if some package exists in the universe
 */
private fun getContainingPackage(
    dir: ForwardRelativePath,
    packageExists: (path: ForwardRelativePath) -> Boolean
): ForwardRelativePath? {
    var current = dir
    while (true) {
        if (packageExists(current)) {
            return current
        }

        if (current.isEmpty()) {
            return null
        }

        // traverse up the directory tree
        current = current.parent().orElse(ForwardRelativePath.EMPTY)
    }
}
