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

package com.facebook.buck.multitenant.runner

import com.facebook.buck.multitenant.importer.InputSource
import com.facebook.buck.multitenant.service.FsChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.system.measureTimeMillis

/**
 * Entry point for multitenant executable
 */
fun main(args: Array<String>) {

    // TODO: proper command line options

    if (args.isEmpty()) {
        msg("Missing parameters, example index1@/tmp/index.json index2@http://www.some.site/index2.json")
        return
    }

    msg("Loading ${args.size} index(es) asynchronously")
    val start = System.nanoTime()
    val corpusToIndex = runBlocking {
            args.asList().map { arg ->
                // Have to explicitly call [Dispatchers.Default] to execute on shared pool because
                // [runBlocking] executes on main thread
                async(Dispatchers.Default) {
                    val (corpus, source) = arg.split('@', limit = 2)
                    val sourceUri = URI.create(source)

                    val start = System.nanoTime()
                    val indexComponents = InputSource.from(sourceUri).use { inputSource ->
                        msg("Loading index '$corpus' from $source (${inputSource.getSize()} bytes)")
                        createIndex(inputSource.getInputStream())
                    }

                    msg("Index '$corpus' loaded in ${nanosToNowInSec(start)} sec.")

                    Pair(corpus, indexComponents)
                }
                // Explicitly call toList() to avoid potential lazy evaluation of a map for
                // coroutine creation
            }.toList().map { loader -> loader.await() }.toMap()
    }

    msg("Loading all indexes completed in ${nanosToNowInSec(start)} sec.")

    msg("Collecting garbage...")

    @Suppress("ExplicitGarbageCollectionCall")
    System.gc()

    msg("Service started. Available commands are: basecommit, corpus, query, out, quit.")

    var indexComponents: IndexComponents? = null
    var basecommit = ""
    var out = "stdout"

    mainloop@ while (true) {
        System.err.print("> ")
        val line = readLine()

        if (line == null) {
            // this happens if stdin sends eof
            break@mainloop
        }

        try {
            val parts = (line ?: "").split(" ", limit = 2)
            val cmd = parts.getOrElse(0) { "" }
            val data = parts.getOrElse(1) { "" }.trim()
            when (cmd) {
                "basecommit" -> basecommit = data
                "corpus" -> indexComponents = corpusToIndex.getOrElse(data) {
                    throw IllegalArgumentException(
                            "corpus '$data' is not found, available corpuses are '${corpusToIndex.keys.joinToString(
                                    ", ")}'")
                }
                "query" -> {
                    val changes = FsChanges(basecommit)
                    var result = listOf<String>()
                    val timeToQueryMs = measureTimeMillis {
                        result = execute(data, changes,
                                indexComponents ?: throw IllegalArgumentException(
                                        "corpus is not specified - use 'corpus' command"))
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
    indexComponents: IndexComponents
): List<String> {
    require(changes.commit.isNotEmpty()) { "should specify basecommit" }
    require(query.isNotEmpty()) { "should specify query" }

    val service = FakeMultitenantService(indexComponents.index, indexComponents.appender,
            indexComponents.changeTranslator)
    return service.handleBuckQueryRequest(query, changes)
}

const val NANOS_IN_SEC = 1_000_000_000

private fun nanosToNowInSec(start: Long): Long {
    return (System.nanoTime() - start) / NANOS_IN_SEC
}

private fun msg(message: String) = System.err.println(message)

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
