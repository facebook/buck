/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.distributed.build_slave.DistBuildTrace.RuleTrace;
import com.facebook.buck.event.chrome_trace.ChromeTraceEvent;
import com.facebook.buck.event.chrome_trace.ChromeTraceEvent.Phase;
import com.facebook.buck.event.chrome_trace.ChromeTraceWriter;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.google.common.collect.ImmutableMap;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Chrome trace generator for {@link DistBuildTrace} object. */
public class DistBuildChromeTraceRenderer {

  private static class MinionRenderLine {
    List<RuleTrace> ruleTraces = new ArrayList<>();
  }

  private static class MinionTraceIntermediate {
    final String id;
    List<MinionRenderLine> lines = new ArrayList<>();

    MinionTraceIntermediate(String id) {
      this.id = id;
    }

    void addItem(RuleTrace historyEntry) {
      for (MinionRenderLine line : lines) {
        RuleTrace lineLastEntry = line.ruleTraces.get(line.ruleTraces.size() - 1);
        if (lineLastEntry.finishEpochMillis <= historyEntry.startEpochMillis) {
          line.ruleTraces.add(historyEntry);
          return;
        }
      }
      MinionRenderLine newLine = new MinionRenderLine();
      lines.add(newLine);
      newLine.ruleTraces.add(historyEntry);
    }
  }

  private static class TraceIntermediate {
    List<MinionTraceIntermediate> minionTraceIntermediates = new ArrayList<>();
  }

  private static class Writer {

    private final DistBuildTrace trace;
    private final TraceIntermediate traceIntermediateIntermediate;
    private final ChromeTraceWriter chromeTraceWriter;

    Writer(
        DistBuildTrace trace,
        TraceIntermediate traceIntermediateIntermediate,
        OutputStream outputStream)
        throws IOException {
      this.trace = trace;
      this.traceIntermediateIntermediate = traceIntermediateIntermediate;
      this.chromeTraceWriter = new ChromeTraceWriter(outputStream);
    }

    public void write() throws IOException {
      chromeTraceWriter.writeStart();
      // This metadata is not visible in Chrome trace display
      // but can be extracted with tools like jq
      chromeTraceWriter.writeEvent(
          new ChromeTraceEvent(
              "buck",
              "global_metadata",
              Phase.METADATA,
              0,
              0,
              0,
              0,
              ImmutableMap.of(
                  "stampedeId", trace.stampedeId,
                  "hostname", HostnameFetching.getHostname(),
                  "timestamp", Instant.now().toString())));
      renderContent();
      chromeTraceWriter.writeEnd();
      chromeTraceWriter.close();
    }

    private void renderContent() throws IOException {
      for (int minionIndex = 0;
          minionIndex < traceIntermediateIntermediate.minionTraceIntermediates.size();
          minionIndex++) {
        MinionTraceIntermediate minionTraceIntermediate =
            traceIntermediateIntermediate.minionTraceIntermediates.get(minionIndex);

        int processIndexInTrace = minionIndex + 1;

        chromeTraceWriter.writeEvent(
            new ChromeTraceEvent(
                "buck",
                "process_name",
                Phase.METADATA,
                processIndexInTrace,
                0,
                0,
                0,
                ImmutableMap.of("name", minionTraceIntermediate.id)));

        for (int lineIndex = 0; lineIndex < minionTraceIntermediate.lines.size(); lineIndex++) {
          MinionRenderLine line = minionTraceIntermediate.lines.get(lineIndex);

          long lastEventFinishMicros = 0;

          for (RuleTrace historyEntry : line.ruleTraces) {
            // Work around Chrome trace renderer: it renders new lines
            // for zero width events connected to previous events.
            // So we are adjusting events so that:
            // * each event is at least 1 microsecond
            // * adjusted events do not overlap
            // For example, this sequence event would produce two lines:
            // | 5ms event | 0ms event | 5ms event |
            // So we render these events like this:
            // | 5ms event | 0us event | 4ms499us event |

            long startMicros =
                Math.max(historyEntry.startEpochMillis * 1000, lastEventFinishMicros);

            lastEventFinishMicros =
                Math.max(historyEntry.finishEpochMillis * 1000, startMicros + 1);

            chromeTraceWriter.writeEvent(
                new ChromeTraceEvent(
                    "buck",
                    historyEntry.ruleName,
                    Phase.BEGIN,
                    processIndexInTrace,
                    lineIndex,
                    startMicros,
                    0,
                    ImmutableMap.of()));
            chromeTraceWriter.writeEvent(
                new ChromeTraceEvent(
                    "buck",
                    historyEntry.ruleName,
                    Phase.END,
                    processIndexInTrace,
                    lineIndex,
                    lastEventFinishMicros,
                    0,
                    ImmutableMap.of()));
          }
        }
      }
    }
  }

  /** Generate self-contains HTML for trace snapshot. */
  public static void render(DistBuildTrace history, Path path) throws IOException {
    TraceIntermediate traceIntermediateIntermediate = new TraceIntermediate();

    for (Map.Entry<String, List<RuleTrace>> entry : history.rulesByMinionId.entrySet()) {
      MinionTraceIntermediate minionTraceIntermediate = new MinionTraceIntermediate(entry.getKey());
      for (RuleTrace historyEntry : entry.getValue()) {
        minionTraceIntermediate.addItem(historyEntry);
      }

      traceIntermediateIntermediate.minionTraceIntermediates.add(minionTraceIntermediate);
    }

    try (FileOutputStream outputStream = new FileOutputStream(path.toFile())) {
      Writer writer = new Writer(history, traceIntermediateIntermediate, outputStream);
      writer.write();
    }
  }
}
