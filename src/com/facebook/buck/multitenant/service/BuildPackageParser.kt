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
import java.io.BufferedInputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Defines a translation from a path to a package to a parsed package
 */
interface BuildPackageParser {
    /**
     * Parse packages at selected paths
     */
    fun parsePackages(packagePaths: List<FsAgnosticPath>): List<BuildPackage>

    /**
     * Parse all packages known in current universe
     */
    fun parseUniverse(): List<BuildPackage>
}

/**
 * Parses packages by invoking Buck from command line
 * @param root Path to a folder that is a root of the cell being parsed
 * @param daemon Whether to run Buck as daemon or not. Running with daemon may help improve
 * performance if checkout is reused for other commands sharing the same build graph. This parameter
 * is ignored if NO_BUCKD environmental variable is set explicitly
  */
class BuckShellBuildPackageParser(private val root: Path, private val daemon: Boolean = true) :
    BuildPackageParser {
    override fun parsePackages(packagePaths: List<FsAgnosticPath>): List<BuildPackage> {
        return if (packagePaths.isEmpty()) listOf() else parse(packagePaths)
    }

    override fun parseUniverse(): List<BuildPackage> {
        return parse()
    }

    private fun parse(packagePaths: List<FsAgnosticPath> = listOf()): List<BuildPackage> {
        // not using NamedTemporaryFile to reduce dependency set in multitenant
        val patternsFilePath = Files.createTempFile("patterns", "")
        try {
            PrintWriter(FileWriter(patternsFilePath.toFile())).use { writer ->
                if (packagePaths.isEmpty()) {
                    // recursive specification to parse all packages under root
                    writer.println("//...")
                } else {
                    // each package path can be represented as `//path/to/package:` pattern specification
                    packagePaths.asSequence().map { path -> "//$path:" }.forEach(writer::println)
                }
            }

            val outputFilePath = Files.createTempFile("bucktargets", "json")
            try {
                execBuck(patternsFilePath, outputFilePath)

                return BufferedInputStream(Files.newInputStream(outputFilePath)).use { stream ->
                    parsePackagesFromStream(stream, ::buckJsonToBuildPackageParser)
                }
            } finally {
                Files.delete(outputFilePath)
            }
        } finally {
            Files.delete(patternsFilePath)
        }
    }

    private fun execBuck(patternsFile: Path, outputFile: Path) {
        val builder = ProcessBuilder("buck", "targets", "--show-parse-state",
            "@" + patternsFile.toString()).redirectOutput(outputFile.toFile()).redirectError(
                ProcessBuilder.Redirect.INHERIT)
            .directory(root.toFile())
        builder.environment().putIfAbsent("BUCK_EXTRA_JAVA_ARGS", "-Xmx24G")
        if (!daemon) {
            builder.environment().putIfAbsent("NO_BUCKD", "1")
        }
        val process = builder.start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(
                "Buck exited with status $exitCode")
        }
    }
}
