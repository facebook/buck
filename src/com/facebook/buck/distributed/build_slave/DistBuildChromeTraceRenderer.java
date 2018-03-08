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

import com.facebook.buck.distributed.build_slave.DistBuildTrace.MinionThread;
import com.facebook.buck.distributed.build_slave.DistBuildTrace.MinionTrace;
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

/** Chrome trace generator for {@link DistBuildTrace} object. */
public class DistBuildChromeTraceRenderer {

  private final DistBuildTrace trace;
  private final ChromeTraceWriter chromeTraceWriter;

  private DistBuildChromeTraceRenderer(DistBuildTrace trace, OutputStream outputStream)
      throws IOException {
    this.trace = trace;
    this.chromeTraceWriter = new ChromeTraceWriter(outputStream);
  }

  private void writeTraceFile() throws IOException {
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
    renderMinions();
    chromeTraceWriter.writeEnd();
    chromeTraceWriter.close();
  }

  private void renderMinions() throws IOException {
    for (int minionIndex = 0; minionIndex < trace.minions.size(); minionIndex++) {
      MinionTrace minionTrace = trace.minions.get(minionIndex);

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
              ImmutableMap.of("name", minionTrace.minionId)));

      for (int threadIndex = 0; threadIndex < minionTrace.threads.size(); threadIndex++) {
        MinionThread line = minionTrace.threads.get(threadIndex);

        long lastEventFinishMicros = 0;

        for (RuleTrace ruleEntry : line.ruleTraces) {
          // Work around Chrome trace renderer: it renders new lines
          // for zero width events connected to previous events.
          // So we are adjusting events so that:
          // * each event is at least 1 microsecond
          // * adjusted events do not overlap
          // For example, this sequence event would produce two lines:
          // | 5ms event | 0ms event | 5ms event |
          // So we render these events like this:
          // | 5ms event | 0us event | 4ms499us event |

          long startMicros = Math.max(ruleEntry.startEpochMillis * 1000, lastEventFinishMicros);

          lastEventFinishMicros = Math.max(ruleEntry.finishEpochMillis * 1000, startMicros + 1);

          chromeTraceWriter.writeEvent(
              new ChromeTraceEvent(
                  "buck",
                  ruleEntry.ruleName,
                  Phase.BEGIN,
                  processIndexInTrace,
                  threadIndex,
                  startMicros,
                  0,
                  ImmutableMap.of()));
          chromeTraceWriter.writeEvent(
              new ChromeTraceEvent(
                  "buck",
                  ruleEntry.ruleName,
                  Phase.END,
                  processIndexInTrace,
                  threadIndex,
                  lastEventFinishMicros,
                  0,
                  ImmutableMap.of()));
        }
      }
    }
  }

  /** Generate an HTML Chrome-trace based on the given {@link DistBuildTrace} object. */
  public static void render(DistBuildTrace trace, Path path) throws IOException {
    try (FileOutputStream outputStream = new FileOutputStream(path.toFile())) {
      DistBuildChromeTraceRenderer renderer = new DistBuildChromeTraceRenderer(trace, outputStream);
      renderer.writeTraceFile();
    }
  }
}
