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
import java.nio.file.Path

/**
 * The client reports changes in terms of modified files ([FsChanges]), but the service needs
 * to operate on changes to the build graph, so we must create a mapping between the two.
 */
interface FsToBuildPackageChangeTranslator {
    /**
     * Parse packages which changed because of changes in filesystem
     * @param fsChanges Changes in filesystem, like modification of a file
     */
    fun translateChanges(fsChanges: FsChanges): BuildPackageChanges
}

/**
 * Simple implementation of [FsToBuildPackageChangeTranslator] that subshells to Buck to parse
 * packages
 *
 * @param buildFileName Name of a build file (for example, `BUCK`) that defines targets
 * @param projectRoot Absolute path to a directory that is a project root for BUCK. Generally
 *   speaking, this is the folder should contain .buckconfig. This Path does not necessarily need to
 *   be physical and can potentially point to virtual filesystem.
 * @param existenceChecker Should return true if a package with the same base path exists on base
 *   revision
 * @param equalityChecker Should return true if exactly same package exists on base revision
 * @param includesProvider returns paths to build packages that transitively includes passed include path
 */
class DefaultFsToBuildPackageChangeTranslator(
    private val buildFileName: ForwardRelativePath,
    private val projectRoot: Path,
    private val existenceChecker: (packagePath: ForwardRelativePath) -> Boolean,
    private val equalityChecker: (buildPackage: BuildPackage) -> Boolean,
    private val includesProvider: (includePath: Include) -> Iterable<ForwardRelativePath>
) : FsToBuildPackageChangeTranslator {
    override fun translateChanges(fsChanges: FsChanges): BuildPackageChanges {

        val affectedPackagePaths =
            getPotentiallyAffectedBuildPackages(
                fsChanges = fsChanges,
                buildFileName = buildFileName,
                includesProvider = includesProvider,
                cellPathNormalizer = { it },
                packageExists = existenceChecker
            )

        val parser = BuckShellBuildPackageParser(projectRoot)
        val addedPackages = parser.parsePackages(affectedPackagePaths.added)
        val modifiedPackages = parser.parsePackages(affectedPackagePaths.modified)

        return BuildPackageChanges(addedBuildPackages = addedPackages,
            // Sometimes reparsing of a package does not really yield to a change in a package
            // contents. Compare parsed packages to the ones existing at base revision and only
            // take those that really changed.
            modifiedBuildPackages = modifiedPackages.filterNot(equalityChecker),
            removedBuildPackages = affectedPackagePaths.removed)
    }
}
