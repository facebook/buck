package com.facebook.buck.datascience.traces

class CountApRounds : TraceAnalysisVisitor<CountApRounds.Summary> {

    private val roundCounts = mutableMapOf<Int, Int>()
    private val distribution = mutableMapOf<Int, Int>()
    private val maxByRule = mutableMapOf<String, Int>()

    class Summary(
            val distribution: Map<Int, Int>,
            val maxByRule: Map<String, Int>)

    override fun traceComplete() = Summary(distribution, maxByRule)

    override fun finishAnalysis(args: List<String>, intermediates: List<Summary>) {
        for (partial in intermediates) {
            for ((rounds, samples) in partial.distribution) {
                distribution[rounds] = samples + distribution.getOrDefault(rounds, 0)
            }
            for ((rule, max) in partial.maxByRule) {
                maxByRule[rule] = Math.max(max, maxByRule.getOrDefault(rule, 0))
            }
        }

        maxByRule.entries.sortedWith(compareBy({it.value}, {it.key})).forEach {
            println("%s %d".format(it.key, it.value))
        }
        println()

        distribution.toSortedMap().forEach { (count, samples) ->
            println("%s %d".format(count, samples))
        }
    }

    override fun eventBegin(event: TraceEvent, state: TraceState) {
        when (event.name) {
            "annotation processing round" -> {
                roundCounts[event.tid] = 1 + roundCounts.getOrDefault(event.tid, 0)
            }
        }
    }

    override fun eventEnd(event: TraceEvent, state: TraceState) {
        when (event.name) {
            "javac_jar" -> {
                val count = roundCounts.getOrDefault(event.tid, 0)
                roundCounts.remove(event.tid)
                distribution[count] = 1 + distribution.getOrDefault(event.tid, 0)
                val ruleName = state.threadStacks[event.tid]!![0].name
                maxByRule[ruleName] = Math.max(count, maxByRule.getOrDefault(ruleName, 0))
            }
        }
    }
}
