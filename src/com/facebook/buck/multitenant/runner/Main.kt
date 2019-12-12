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

package com.facebook.buck.multitenant.runner

import com.facebook.buck.multitenant.fs.FsAgnosticPath
import com.facebook.buck.multitenant.service.FsChanges
import com.facebook.buck.multitenant.service.IndexComponents
import com.facebook.buck.multitenant.service.IndexFactory
import com.facebook.buck.multitenant.service.InputSource
import com.facebook.buck.multitenant.service.multitenantJsonToBuildPackageParser
import com.facebook.buck.multitenant.service.populateIndexFromStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/**
 * Entry point for multitenant executable
 */
fun main(args: Array<String>) {

    // TODO: proper command line options

    if (args.isEmpty()) {
        msg("Missing parameters, example index1@/tmp/index.json@/path/to/checkout1 index2@http://www.some.site/index2.json@/path/to/checkout2")
        return
    }

    msg("Loading ${args.size} index(es) asynchronously")
    val start = System.nanoTime()
    val corpusToIndex = runBlocking {
        args.asList().map { arg ->
            // Have to explicitly call [Dispatchers.Default] to execute on shared pool because
            // [runBlocking] executes on main thread
            async(Dispatchers.Default) {
                val (corpus, source, checkout) = arg.split('@', limit = 3)
                val sourceUri = URI.create(source)

                val start = System.nanoTime()
                val indexComponents = InputSource.from(sourceUri).use { inputSource ->
                    msg("Loading index '$corpus' from $source (${inputSource.getHumanReadableSize()})")
                    createIndex(inputSource.getInputStream())
                }

                msg("Index '$corpus' loaded in ${nanosToNowInSec(start)} sec.")

                Pair(corpus, Pair(indexComponents, Paths.get(checkout)))
            }
            // Explicitly call toList() to avoid potential lazy evaluation of a map for
            // coroutine creation
        }.toList().map { loader -> loader.await() }.toMap()
    }

    msg("Loading all indices completed in ${nanosToNowInSec(start)} sec.")

    msg("Collecting garbage...")

    @Suppress("ExplicitGarbageCollectionCall") System.gc()

    msg("Service started. Available commands are: basecommit, corpus, query, out, quit.")

    var indexComponents: IndexComponents? = null
    var projectPath: Path? = null
    var basecommit = ""
    var out = "stdout"

    mainloop@ while (true) {
        System.err.print("> ")
        val line = readLine() ?: break@mainloop // this happens if stdin sends eof

        try {
            val parts = line.split(" ", limit = 2)
            val cmd = parts.getOrElse(0) { "" }
            val data = parts.getOrElse(1) { "" }.trim()
            when (cmd) {
                "basecommit" -> basecommit = data
                "corpus" -> {
                    val index = requireNotNull(corpusToIndex[data]) {
                        "corpus '$data' is not found, available corpuses are '${corpusToIndex.keys.joinToString(
                            ", ")}'"
                    }

                    indexComponents = index.first
                    projectPath = index.second
                }
                "query" -> {
                    val changes = FsChanges(basecommit)
                    var result = listOf<String>()
                    val timeToQueryMs = measureTimeMillis {
                        result = execute(data, changes, requireNotNull(indexComponents) {
                            "corpus is not specified - use 'corpus' command"
                        }, requireNotNull(projectPath) {
                            "path to checkout is not specified"
                        })
                    }
                    msg("query returned ${result.size} results in $timeToQueryMs ms")
                    output(result, out)
                }
                // TODO: add user changes
                "changes" -> throw IllegalArgumentException("'changes' is not yet supported")
                "out" -> out = data.ifEmpty { "stdout" }
                "quit" -> break@mainloop
                // just hitting enter is fine
                "" -> {
                }
                else -> throw IllegalArgumentException("command '$cmd' is not supported")
            }
        } catch (t: Throwable) {
            msg(t.message ?: "error ${t.javaClass.name}")
        }
    }
}

private fun execute(
    query: String,
    changes: FsChanges,
    indexComponents: IndexComponents,
    projectPath: Path
): List<String> {
    require(changes.commit.isNotEmpty()) { "should specify basecommit" }
    require(query.isNotEmpty()) { "should specify query" }

    val service = FakeMultitenantService(indexComponents.index, indexComponents.appender,
        FsAgnosticPath.of("BUCK"), projectPath)
    return service.handleBuckQueryRequest(query, changes)
}

const val NANOS_IN_SEC = 1_000_000_000

private fun nanosToNowInSec(start: Long): Long {
    return (System.nanoTime() - start) / NANOS_IN_SEC
}

private fun msg(message: String) {
    System.err.println("[${LocalDateTime.now()}] $message")
    System.err.flush()
}

private fun createOutputFromDescriptor(out: String): Output {
    return when (out) {
        "stdout" -> Output.Stdout()
        "stderr" -> Output.Stderr()
        else -> Output.File(out)
    }
}

private fun output(data: List<String>, where: String) {
    createOutputFromDescriptor(where).print(data)
}

/**
 * Creates a map with a single Index based on the data from the specified file.
 */
private fun createIndex(stream: InputStream): IndexComponents {
    val (index, appender) = IndexFactory.createIndex()
    populateIndexFromStream(appender, stream, ::multitenantJsonToBuildPackageParser)
    return IndexComponents(index, appender, mapOf("" to "BUCK"))
}
