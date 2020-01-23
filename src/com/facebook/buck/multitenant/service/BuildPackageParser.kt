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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.max
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Defines a translation from a path to a package to a parsed package
 */
interface BuildPackageParser {
    /**
     * Parse packages at selected paths
     */
    fun parsePackages(packagePaths: List<ForwardRelativePath>): List<BuildPackage>

    /**
     * Parse all packages known in current universe
     */
    fun parseUniverse(): List<BuildPackage>
}

private val LOG = Logger.getLogger(BuckShellBuildPackageParser::class.java.canonicalName)
private const val NUMBER_OF_PACKAGES_TO_PRINT = 3

/**
 * Parses packages by invoking Buck from command line
 * @param root Path to a folder that is a root of the cell being parsed
 * @param daemon Whether to run Buck as daemon or not. Running with daemon may help improve
 * performance if checkout is reused for other commands sharing the same build graph. This parameter
 * is ignored if NO_BUCKD environmental variable is set explicitly
 * @param timeout the maximum time to wait
 * @param timeUnit the time unit of the [timeout] argument
 */
class BuckShellBuildPackageParser(
    private val root: Path,
    private val daemon: Boolean = true,
    private val timeout: Long = 90,
    private val timeUnit: TimeUnit = TimeUnit.MINUTES
) :
    BuildPackageParser {
    override fun parsePackages(packagePaths: List<ForwardRelativePath>): List<BuildPackage> {
        return if (packagePaths.isEmpty()) listOf() else parse(packagePaths)
    }

    override fun parseUniverse(): List<BuildPackage> = parse()

    private fun parse(packagePaths: List<ForwardRelativePath> = listOf()): List<BuildPackage> {
        // not using NamedTemporaryFile to reduce dependency set in multitenant
        val patternsFilePath = Files.createTempFile("patterns", "")
        try {
            patternsFilePath.toFile().printWriter().use { writer ->
                if (packagePaths.isEmpty()) {
                    // recursive specification to parse all packages under root
                    writer.println("//...")
                } else {
                    // each package path can be represented as `//path/to/package:` pattern specification
                    packagePaths.asSequence().map { path -> "//$path:" }.forEach(writer::println)
                }
            }

            val outputFilePath = Files.createTempFile("buck_targets", "json")
            try {
                val packagesString =
                    if (packagePaths.isEmpty()) "//..." else {
                        val remainingPackagesSize = max(0, packagePaths.size - NUMBER_OF_PACKAGES_TO_PRINT)
                        packagePaths.asSequence()
                            .take(NUMBER_OF_PACKAGES_TO_PRINT)
                            .map { "//$it:" }
                            .joinToString { it } +
                            if (remainingPackagesSize > 0) " and $remainingPackagesSize more..." else ""
                    }
                LOG.info("Parsing packages: $packagesString")
                execBuck(patternsFilePath, outputFilePath)

                return outputFilePath.toFile().inputStream().buffered().use {
                    parsePackagesFromStream(it, ::buckJsonToBuildPackageParser)
                }
            } finally {
                Files.delete(outputFilePath)
            }
        } finally {
            Files.delete(patternsFilePath)
        }
    }

    private val readErrorStreamCoroutineExceptionHandler =
        CoroutineExceptionHandler { _, exception ->
            // Process.destroy() can be invoked for a still running process (Ex. process.waitFor(timeout, timeUnit) returned false).
            // If process is killed then `readLine` could throw IOException("Stream closed") that is handled here
            if (exception is IOException && exception.message == "Stream closed") {
                LOG.log(Level.FINE, "Error stream closed")
            } else {
                LOG.log(Level.SEVERE, "CoroutineException:", exception)
            }
        }

    private fun execBuck(patternsFile: Path, outputFile: Path) {
        val builder = ProcessBuilder("buck", "targets", "--show-parse-state", "-c",
            "log.log_upload_mode=never", "@$patternsFile").redirectOutput(outputFile.toFile())
            .redirectError(ProcessBuilder.Redirect.PIPE).directory(root.toFile())
        val environment = builder.environment()
        environment.putIfAbsent("BUCK_EXTRA_JAVA_ARGS", "-Xmx24G")
        if (!daemon) {
            environment.putIfAbsent("NO_BUCKD", "1")
        }
        val process = builder.start()

        val readErrorOutputJob =
            GlobalScope.launch(Dispatchers.IO + readErrorStreamCoroutineExceptionHandler) {
                process.errorStream.bufferedReader().forEachLine { LOG.log(Level.FINE, it) }
            }

        try {
            val hasExited = process.waitFor(timeout, timeUnit)
            check(hasExited) {
                "Buck process finished with timeout exception  after $timeout $timeUnit waiting"
            }
            val exitCode = process.exitValue()
            check(exitCode == 0) { "Buck exited with status $exitCode" }
        } finally {
            process.destroy()
            runBlocking { readErrorOutputJob.join() }
        }
    }
}
