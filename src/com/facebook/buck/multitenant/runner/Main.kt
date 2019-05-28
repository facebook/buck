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

import com.facebook.buck.multitenant.service.FsChanges
import java.io.File
import java.io.PrintStream
import kotlin.system.measureTimeMillis

/**
 * Entry point for multitenant executable
 */
fun main(args: Array<String>) {
    // TODO: proper command line options
    val jsonFile = args[0]

    msg("Loading index from $jsonFile (${File(jsonFile).length()} bytes)")

    var corpusToIndex = mapOf<String, IndexComponents>()
    val timeIndexLoadMs = measureTimeMillis {
        corpusToIndex = createIndexes(jsonFile)
    }

    msg("Index loaded in ${timeIndexLoadMs / 1000} sec. Collecting garbage...")

    System.gc()

    msg("Service started. Available commands are: basecommit, corpus, query, out, quit.")

    var indexComponents: IndexComponents? = null
    var basecommit = ""
    var out = "stdout"

    mainloop@ while (true) {
        System.err.print("> ")
        val line = readLine()
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

private fun msg(message: String) = System.err.println(message)

internal sealed class Output {
    abstract fun print(data: List<String>)
    class Stdout : Output() {
        override fun print(data: List<String>) {
            writeToStream(data, System.out)
        }
    }

    class Stderr : Output() {
        override fun print(data: List<String>) {
            writeToStream(data, System.err)
        }
    }

    class File(val path: String) : Output() {
        override fun print(data: List<String>) {
            PrintStream(path).use { writeToStream(data, it) }
        }
    }
}

private fun writeToStream(data: List<String>, stream: PrintStream) = data.map { stream.println(it) }

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
