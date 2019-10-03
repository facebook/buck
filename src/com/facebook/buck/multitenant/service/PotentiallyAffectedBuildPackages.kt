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

import com.facebook.buck.multitenant.fs.FsAgnosticPath

/**
 * A collection of packages (package is represented by the relative path to its root) that may have
 * changed because of some input change and thus need to be reparsed
 */
data class PotentiallyAffectedBuildPackages(
    val commit: Commit,
    val added: List<FsAgnosticPath> = emptyList(),
    val modified: List<FsAgnosticPath> = emptyList(),
    val removed: List<FsAgnosticPath> = emptyList()
) {

    /**
     * True if no packages are affected by commit
     */
    fun isEmpty() = added.isEmpty() && modified.isEmpty() && removed.isEmpty()

    /**
     * True if at least one package is affected by commit
     */
    fun isNotEmpty() = !isEmpty()
}

/**
 * Based on changed input files, determines packages that might have changed. We use defensive
 * approach to detect if package is changed because we overschedule reparsing rather than
 * sacrifice correctness.
 *
 * @param fsChanges Changes in files relative to the root of the repo
 * @param buildFileName Name of the build file (like 'BUCK'), expressed as [FsAgnosticPath], for
 * current cell being parsed
 * @param depToPackageIndex An index from all files which are parse-time dependencies, including
 * transitive ones, to packages (expressed as [FsAgnosticPath]s) which load or otherwise use
 * those dependencies during parsing for that specific universe. Parse-time dependencies are usually
 * extension (.bzl) files. This should not include configuration files.
 * TODO: change to a function
 * @param cellPathNormalizer Function that transforms a path relative to the root of repo, to the
 * path relative to the root of the cell. Returns null if path does not belong to the cell.
 * @param packageExists Predicate used to check if some package exists in the universe of a current
 * cell
 * @return Paths to packages which may be affected by [fsChanges], relative to the cell
 */
fun getPotentiallyAffectedBuildPackages(
    fsChanges: FsChanges,
    buildFileName: FsAgnosticPath,
    depToPackageIndex: Map<FsAgnosticPath, Set<FsAgnosticPath>>,
    cellPathNormalizer: (path: FsAgnosticPath) -> FsAgnosticPath?,
    packageExists: (path: FsAgnosticPath) -> Boolean
): PotentiallyAffectedBuildPackages {
    val newPackages = mutableSetOf<FsAgnosticPath>()
    val changedPackages = mutableSetOf<FsAgnosticPath>()
    val removedPackages = mutableSetOf<FsAgnosticPath>()

    fsChanges.added.forEach { added ->
        // Adding a new file cannot be a parse-time dependency, as they have to be explicitly
        // declared, so nothing to do here

        // Only if added file belongs to the cell
        cellPathNormalizer(added.path)?.let { path ->

            // Adding a new package: need to parse it and also its parent, because a parent may lose
            // targets
            if (path.name() == buildFileName) {
                val newPackagePath = path.dirname()
                newPackages.add(newPackagePath)
                getContainingPackage(newPackagePath.dirname(),
                    packageExists)?.let { changedPackages.add(it) }
                return@forEach
            }

            // Adding a new source file: reparse package that potentially owns the file because
            // some target contents may change
            getContainingPackage(path.dirname(), packageExists)?.let { changedPackages.add(it) }
        }
    }

    fsChanges.modified.forEach { modified ->
        // Modifying a parse-time dependency: detect and reparse affected packages only
        // Modified file may belong to a different cell!
        depToPackageIndex.get(modified.path)?.let {
            changedPackages.addAll(it)
        }

        // Only if modified file belongs to the cell
        cellPathNormalizer(modified.path)?.let { path ->
            // Modifying existing package: need to reparse it
            if (path.name() == buildFileName) {
                val packagePath = path.dirname()
                changedPackages.add(packagePath)
            }

            // Modifying a source file: it does not yield to changes in packages, so nothing more
            // to do
        }
    }

    fsChanges.removed.forEach { removed ->
        // Removing a parse-time dependency: detect and reparse affected packages only
        // Removed file may belong to a different cell!
        depToPackageIndex.get(removed.path)?.let {
            changedPackages.addAll(it)
        }

        // Only if removed file belongs to the cell
        cellPathNormalizer(removed.path)?.let { path ->
            // Removing a package: need to remove the package itself and reparse the parent as its
            // targets can change because parent package may start to own more files
            if (path.name() == buildFileName) {
                val packagePath = path.dirname()
                removedPackages.add(packagePath)
                getContainingPackage(packagePath.dirname(),
                    packageExists)?.let { changedPackages.add(it) }
                return@forEach
            }

            // Removing a source file: reparse package that potentially owns the file because
            // some target contents may change
            getContainingPackage(path.dirname(), packageExists)?.let { changedPackages.add(it) }
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
 * @param allKnownPackages All packages in the universe for the same base commit
 */
private fun getContainingPackage(
    dir: FsAgnosticPath,
    packageExists: (path: FsAgnosticPath) -> Boolean
): FsAgnosticPath? {
    var current = dir
    while (true) {
        if (packageExists(current)) {
            return current
        }

        if (current.isEmpty()) {
            return null
        }

        // traverse up the directory tree
        current = current.dirname()
    }
}
