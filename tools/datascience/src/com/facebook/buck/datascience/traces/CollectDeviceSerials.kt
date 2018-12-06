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

class CollectDeviceSerials : TraceAnalysisVisitor<Map<String, Long>> {
    companion object {
        val PATTERN_EMULATOR = Regex("^emulator-\\d+$")
        val PATTERN_GENYMOTION = Regex("^[\\d.:\\[\\]]+:5\\d\\d\\d$")
    }

    /**
     * Map of serial number to number of installations.
     * It seems unlikely that we'd ever have more than one for a single trace, but be safe.
     * Multiple keys can be present with "buck install -x".
     */
    private val serialCounts = mutableMapOf<String, Long>()

    override fun traceComplete() = serialCounts

    override fun finishAnalysis(args: List<String>, intermediates: List<Map<String, Long>>) {
        // Add up the maps from each trace.
        val totals = mutableMapOf<String, Long>()
        intermediates.forEach {
            it.forEach { k, v ->
                totals[k] = v + totals.getOrDefault(k, 0)
            }
        }

        totals.toSortedMap().forEach { serial, count ->
            System.out.println("%24s %d".format(serial, count))
        }
        System.out.println()

        // Group and count by device type.
        val kinds = mutableMapOf<String, Long>()
        totals.forEach { k, v ->
            val kind = when {
                k.matches(PATTERN_EMULATOR) -> "emulator"
                k.matches(PATTERN_GENYMOTION) -> "genymotion"
                else -> "device"
            }
            kinds[kind] = v + kinds.getOrDefault(kind, 0)
        }
        kinds.toSortedMap().forEach { kind, count ->
            System.out.println("%12s %d".format(kind, count))
        }

    }

    override fun eventBegin(event: TraceEvent, state: TraceState) {
        if (event.name == "adb_call install exopackage apk") {
            val serial = event.args["device_serial"].textValue()
            if (serial != null) {
                serialCounts[serial] = 1 + serialCounts.getOrDefault(serial, 0)
            }
        }
    }
}
