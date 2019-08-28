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
import java.lang.IllegalStateException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Defines a translation from a path to a package to a parsed package
 */
interface BuildPackageParser {
    fun parsePackages(packagePaths: List<FsAgnosticPath>): List<BuildPackage>
}

/**
 * Parses packages by invoking Buck from command line
 */
class BuckShellBuildPackageParser(private val root: Path) : BuildPackageParser {
    override fun parsePackages(packagePaths: List<FsAgnosticPath>): List<BuildPackage> {

        // not using NamedTemporaryFile to reduce dependency set in multitenant
        val patternsFilePath = Files.createTempFile("patterns", "")
        try {
            PrintWriter(FileWriter(patternsFilePath.toFile())).use { writer ->
                // each package path can be represented as `//path/to/package:` pattern specification
                packagePaths.asSequence().map { path -> "//$path:" }.forEach(writer::println)
            }

            val outputFilePath = Files.createTempFile("bucktargets", "json")
            try {
                execBuck(patternsFilePath, outputFilePath)

                return BufferedInputStream(Files.newInputStream(outputFilePath)).use { stream ->
                    parsePackagesFromStream(stream)
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
            "@" + patternsFile.toString()).redirectOutput(outputFile.toFile())
            .directory(root.toFile())
        builder.environment()["BUCK_EXTRA_JAVA_ARGS"] = "-Xmx24G"
        val process = builder.start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw IllegalStateException(
                "Buck exited with status $exitCode:" + System.lineSeparator() +
                        process.errorStream.bufferedReader().lineSequence().joinToString(System.lineSeparator()))
        }
    }
}
