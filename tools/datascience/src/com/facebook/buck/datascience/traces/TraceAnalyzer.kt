/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.datascience.traces

import com.facebook.buck.util.ObjectMappers
import com.facebook.buck.util.concurrent.MostExecutors
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.PriorityQueue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.zip.GZIPInputStream

/**
 * See the Chrome Trace Event Format doc.
 */
enum class EventPhase {
    B,
    E,
    I,
    C,
    M,
}

/**
 * A single trace event.
 */
data class TraceEvent(
        val name: String,
        val cat: List<String>,
        val ph: EventPhase,
        val ts: Long,
        val tts: Long,
        val pid: Int,
        val tid: Int,
        val args: ObjectNode
)

/**
 * Current state of the trace.
 */
data class TraceState(
        /**
         * Current stack of active events for each thread.
         * Events in each thread will appear in the same order that they began.
         */
        val threadStacks: Map<Int, List<TraceEvent>>
)

/**
 * Business logic class that will analyze traces.
 * A separate instance will be created for each trace.
 * A final instance will be created to combine the results.
 */
interface TraceAnalysisVisitor<SummaryT> {
    /**
     * Initialize this visitor before using it to process a trace.
     * Args are from the command line.
     */
    fun init(args: List<String>) {}

    /**
     * Process a begin ("B") event.
     *
     * @param event  The event.
     * @param state  The state after incorporating the event.
     */
    fun eventBegin(event: TraceEvent, state: TraceState) {}

    /**
     * Process an end ("E") event.
     *
     * @param event  The event.
     * @param state  The state before removing the corresponding begin event.
     */
    fun eventEnd(event: TraceEvent, state: TraceState) {}

    fun eventMisc(event: TraceEvent, state: TraceState) {}

    /**
     * Finish analyzing a trace and return data to be processed by the final analysis.
     */
    fun traceComplete(): SummaryT

    /**
     * Combine results from each trace and print analysis to the console.
     * This will be called on a fresh instance.
     */
    fun finishAnalysis(args: List<String>, intermediates: List<SummaryT>)
}


/**
 * Main entry point.  To use this tool to analyze traces:
 *
 * - Get a bunch of traces and put their filenames or urls in a file.
 * - Create a subclass of TraceAnalysisVisitor and compile it along with this tool.
 * - Run the tool with the visitor class name as the first argument (minus package)
 *   and the path to the file as the second argument.
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("usage: TraceAnalyzer {VisitorClassSimpleName} {TraceListFile}")
        System.exit(2)
    }

    val visitorName = args[0]
    val infile = args[1]

    val logicClass = Class.forName("com.facebook.buck.datascience.traces." + visitorName)

    val processThreadCount = Runtime.getRuntime().availableProcessors();
    val fetchThreadCount = processThreadCount / 2;

    val fetchThreadPool = MoreExecutors.listeningDecorator(
            MostExecutors.newMultiThreadExecutor("fetch", fetchThreadCount))
    val processThreadPool = MoreExecutors.listeningDecorator(
            MostExecutors.newMultiThreadExecutor("process", processThreadCount))

    val eventBufferDepth = 1 shl 16

    processTraces(
            args.asList().subList(2, args.size),
            File(infile),
            logicClass as Class<TraceAnalysisVisitor<Any>>,
            eventBufferDepth,
            fetchThreadPool,
            processThreadPool)
}

private data class QueuedTrace(
        val name: String,
        val stream: () -> InputStream)

private fun <SummaryT : Any> processTraces(
        args: List<String>,
        traceListFile: File,
        visitorClass: Class<TraceAnalysisVisitor<SummaryT>>,
        eventBufferDepth: Int,
        fetchThreadPool: ListeningExecutorService,
        processThreadPool: ListeningExecutorService) {
    val blobQueue = ArrayBlockingQueue<QueuedTrace>(1)

    val processFutures = traceListFile.readLines().map {
        val fetchFuture = fetchThreadPool.submit {
            System.err.println(it)
            val lazyStream = openTrace(it)
            blobQueue.put(QueuedTrace(it, lazyStream))
        }
        val processFuture = Futures.transform(
                fetchFuture,
                com.google.common.base.Function<Any, SummaryT> {
                    val (name, lazyStream) = blobQueue.poll()
                    val visitor = visitorClass.newInstance()
                    val summary = processOneTrace(args, name, lazyStream, visitor, eventBufferDepth)
                    summary
                },
                processThreadPool)
        processFuture.addListener(Runnable {
            try {
                processFuture.get()
            } catch (e: ExecutionException) {
                // The ExecutionException isn't interesting.  Log the cause.
                e.cause!!.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
        processFuture
    }

    try {
        val summaries = Futures.successfulAsList(processFutures).get().filterNotNull()

        val finisher = visitorClass.newInstance()
        finisher.finishAnalysis(args, summaries)
    } finally {
        fetchThreadPool.shutdownNow()
        processThreadPool.shutdownNow()
    }

}

/**
 * Open a path or URL, slurp the contents into memory, and return a lazy InputStream
 * that will gunzip the contents if necessary (inferred from the path).
 * The return value is lazy to ensure we don't create a GZIPInputStream
 * (which allocates native resources) until we're in a position to close it.
 */
private fun openTrace(pathOrUrl: String): () -> InputStream {
    val bytes: ByteArray
    val path: String
    if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
        val url = URL(pathOrUrl)
        path = url.path
        val connection = url.openConnection()
        bytes = connection.getInputStream().use { it.readBytes() }
    } else {
        path = pathOrUrl
        bytes = File(path).inputStream().use { it.readBytes() }
    }
    if (path.endsWith(".gz")) {
        return { BufferedInputStream(GZIPInputStream(ByteArrayInputStream(bytes))) }
    } else {
        return { ByteArrayInputStream(bytes) }
    }
}

/**
 * Wrapper for TraceEvent that ensures stable sorting.
 */
private data class BufferedTraceEvent(
        val ts: Long,
        val order: Long,
        val event: TraceEvent
)

private fun <SummaryT> processOneTrace(
        args: List<String>,
        name: String,
        lazyStream: () -> InputStream,
        visitor: TraceAnalysisVisitor<SummaryT>,
        eventBufferDepth: Int): SummaryT {
    try {
        lazyStream().use { stream ->
            return processOneTraceStream(args, stream, visitor, eventBufferDepth)
        }
    } catch (e: Exception) {
        throw RuntimeException("Exception while processing " + name, e)
    }
}

private fun <SummaryT> processOneTraceStream(
        args: List<String>,
        stream: InputStream,
        visitor: TraceAnalysisVisitor<SummaryT>,
        eventBufferDepth: Int): SummaryT {
    visitor.init(args)

    val parser = ObjectMappers.createParser(stream)
    if (parser.nextToken() != JsonToken.START_ARRAY) {
        throw IllegalStateException("Invalid token: " + parser.currentToken)
    }

    // Timestamps are not monotonic in trace files.
    // Part of this is because events from different threads can be reordered,
    // But I've also seen re-orderings within a thread.
    // In theory, we could load the entire trace into memory and sort by timestamp.
    // Instead, let's prematurely optimize and use a priority queue.
    val pq = PriorityQueue<BufferedTraceEvent>(
            eventBufferDepth + 4,  // +4 so I don't need to think about math.
            compareBy({it.ts}, {it.order}))
    var lastTimestamp = 0L
    var order = 1L

    val threadStacks = mutableMapOf<Int, MutableList<TraceEvent>>()

    while (true) {
        when (parser.nextToken()) {
            null -> {
                // At EOF.  Nothing left to do.
            }
            JsonToken.START_OBJECT -> {
                // Got an object, enqueue it.
                val node = ObjectMappers.READER.readTree<JsonNode>(parser)
                val event = parseTraceEvent(node)
                pq.add(BufferedTraceEvent(event.ts, order++, event))
            }
            JsonToken.END_ARRAY -> {
                // End of our array of events.
                val nextNextToken = parser.nextToken()
                if (nextNextToken != null) {
                    throw IllegalStateException("Got token after END_ARRAY: " + nextNextToken)
                }
            }
            else -> {
                throw IllegalStateException("Invalid token: " + parser.currentToken)
            }
        }

        if (pq.isEmpty() && parser.isClosed) {
            // No more events in queue or parser.
            break
        } else if (parser.isClosed || pq.size > eventBufferDepth) {
            // Parser is closed (so we need to train the queue),
            // OR the queue is "full" so we need to take something out.
            val nextEvent = pq.poll()
            if (nextEvent.ts < lastTimestamp) {
                throw IllegalStateException(
                        "Event went back in time.  Try a bigger queue.\n" + nextEvent)
            }
            lastTimestamp = nextEvent.ts
            processOneEvent(visitor, nextEvent.event, threadStacks)
        }
    }

    return visitor.traceComplete()
}

private fun processOneEvent(
        visitor: TraceAnalysisVisitor<*>,
        event: TraceEvent,
        threadStacks: MutableMap<Int, MutableList<TraceEvent>>) {
    when (event.ph) {
        EventPhase.B -> {
            threadStacks
                    .getOrPut(event.tid, { mutableListOf() })
                    .add(event)
            visitor.eventBegin(event, TraceState(threadStacks))
        }
        EventPhase.E -> {
            val stack = threadStacks[event.tid]
                    ?: throw IllegalStateException("Couldn't find stack for thread " + event.tid)
            if (stack.isEmpty()) {
                throw IllegalStateException("Empty stack for thread " + event.tid)
            }
            prepareStackForEndEvent(stack, event)
            val topOfStack = stack.last()
            if (topOfStack.name != event.name) {
                throw IllegalStateException(
                        "Event name mismatch on thread %d at time %d: %s != %s".format(
                                event.tid, event.ts, topOfStack.name, event.name))
            }
            visitor.eventEnd(event, TraceState(threadStacks))
            stack.removeAt(stack.lastIndex)
        }
        else -> {
            visitor.eventMisc(event, TraceState(threadStacks))
        }
    }

}

private fun prepareStackForEndEvent(stack: MutableList<TraceEvent>, event: TraceEvent) {
    if (event.name == "javac_jar") {
        // Events inside javac_jar aren't always closed on compile errors.
        // Just silently remove them from the stack.
        val idx = stack.indexOfLast { it.name == "javac_jar" }
        if (idx > 0) {
            // Found start event.  Remove everything after it.
            while (idx < stack.lastIndex) {
                stack.removeAt(stack.lastIndex)
            }
        }
    }
}

private fun parseTraceEvent(node: JsonNode): TraceEvent {
    // TODO: Switch to ig-json-parser or jackson-module-kotlin or something more type-safe.
    return TraceEvent(
            node["name"].textValue()!!,
            (node["cat"].textValue() ?: "").split(","),
            EventPhase.valueOf(node["ph"].textValue()!!),
            node["ts"].longValue(),
            node["tts"].longValue(),
            node["pid"].intValue(),
            node["tid"].intValue(),
            node["args"] as ObjectNode
    )
}
