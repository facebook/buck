package com.facebook.buck.datascience.traces

import com.facebook.buck.util.ObjectMappers
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.io.Closer
import java.io.File
import java.io.InputStream
import java.net.URL
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
 * Current state of the trace.  Will reflect the event being processed.
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

    processTraces(
            args.asList().subList(2, args.size),
            File(infile),
            logicClass as Class<TraceAnalysisVisitor<Any>>)
}

private fun <SummaryT> processTraces(
        args: List<String>,
        traceListFile: File,
        visitorClass: Class<TraceAnalysisVisitor<SummaryT>>) {
    val summaries = mutableListOf<SummaryT>()

    // TODO: Parallelize.
    traceListFile.forEachLine {
        val traceBytes = openTrace(it)
        val visitor = visitorClass.newInstance()
        val summary = processOneTrace(args, traceBytes, visitor)
        summaries.add(summary)
    }

    val finisher = visitorClass.newInstance()
    finisher.finishAnalysis(args, summaries)
}

private fun openTrace(pathOrUrl: String): ByteArray {
    Closer.create().use { closer ->
        val inputStream =
                if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
                    val url = URL(pathOrUrl)
                    val connection = url.openConnection()
                    val rawStream = closer.register(connection.getInputStream())
                    closer.register(maybeGunzip(rawStream, url.path))
                } else {
                    val rawStream = closer.register(File(pathOrUrl).inputStream())
                    closer.register(maybeGunzip(rawStream, pathOrUrl))
                }
        return inputStream.readBytes()
    }
}

private fun maybeGunzip(rawStream: InputStream, path: String) =
    if (path.endsWith(".gz")) GZIPInputStream(rawStream)
    else rawStream

private fun <SummaryT> processOneTrace(
        args: List<String>,
        traceBytes: ByteArray,
        visitor: TraceAnalysisVisitor<SummaryT>): SummaryT {
    visitor.init(args)

    val parser = ObjectMappers.createParser(traceBytes)
    if (parser.nextToken() != JsonToken.START_ARRAY) {
        throw IllegalStateException("Invalid token: " + parser.currentToken)
    }

    loop@ while (true) {
        when (parser.nextToken()) {
            JsonToken.START_OBJECT -> {
                val event = ObjectMappers.READER.readTree<JsonNode>(parser)
                processOneEvent(event, visitor)
            }
            JsonToken.END_ARRAY -> {
                break@loop
            }
            else -> {
                throw IllegalStateException("Invalid token: " + parser.currentToken)
            }
        }
    }

    return visitor.traceComplete()
}

private fun processOneEvent(node: JsonNode, visitor: TraceAnalysisVisitor<*>) {
    val event = parseTraceEvent(node)

    // TODO: Be smart.
    visitor.eventBegin(event, TraceState(mapOf()))
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
