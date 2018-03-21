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
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.network.hostname.HostnameFetching;
import com.google.common.collect.ImmutableMap;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Stack;

/** Chrome trace generator for {@link DistBuildTrace} object. */
public class DistBuildChromeTraceRenderer {
  private static final Logger LOG = Logger.get(DistBuildChromeTraceRenderer.class);

  private static final String kTraceCategory = "buck";
  private static final String kNewSectionKey = "process_name";
  private static final String kNewSectionNameLabel = "name";

  private final DistBuildTrace trace;
  private final ChromeTraceWriter chromeTraceWriter;
  private int pidForTrace = 0;

  private DistBuildChromeTraceRenderer(DistBuildTrace trace, OutputStream outputStream)
      throws IOException {
    this.trace = trace;
    this.chromeTraceWriter = new ChromeTraceWriter(outputStream);
  }

  private void writeTraceFile() throws IOException {
    LOG.info("Starting to write Stampede ChromeTrace.");
    chromeTraceWriter.writeStart();
    // This metadata is not visible in Chrome trace display
    // but can be extracted with tools like jq
    chromeTraceWriter.writeEvent(
        new ChromeTraceEvent(
            kTraceCategory,
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
    renderCriticalPath();
    renderMinions();
    chromeTraceWriter.writeEnd();
    chromeTraceWriter.close();
    LOG.info("Finished writing Stampede ChromeTrace.");
  }

  private void renderCriticalPath() throws IOException {
    Optional<RuleTrace> optionalRule = trace.criticalNode;
    if (!optionalRule.isPresent()) {
      return;
    }

    pidForTrace++;
    chromeTraceWriter.writeEvent(
        new ChromeTraceEvent(
            kTraceCategory,
            kNewSectionKey,
            Phase.METADATA,
            pidForTrace,
            0,
            0,
            0,
            ImmutableMap.of(kNewSectionNameLabel, "Critical Path")));

    Stack<RuleTrace> criticalPath = new Stack<>();
    do {
      criticalPath.push(optionalRule.get());
      optionalRule = optionalRule.get().previousRuleInLongestDependencyChain;
    } while (optionalRule.isPresent());

    long lastEventFinishMicros = 0;
    while (!criticalPath.empty()) {
      RuleTrace ruleTrace = criticalPath.pop();
      lastEventFinishMicros = renderRule(ruleTrace, 0, lastEventFinishMicros);
    }
  }

  private void renderMinions() throws IOException {
    for (int minionIndex = 0; minionIndex < trace.minions.size(); minionIndex++) {
      MinionTrace minionTrace = trace.minions.get(minionIndex);

      pidForTrace++;
      chromeTraceWriter.writeEvent(
          new ChromeTraceEvent(
              kTraceCategory,
              kNewSectionKey,
              Phase.METADATA,
              pidForTrace,
              0,
              0,
              0,
              ImmutableMap.of(kNewSectionNameLabel, minionTrace.minionId)));

      for (int threadId = 0; threadId < minionTrace.threads.size(); threadId++) {
        MinionThread line = minionTrace.threads.get(threadId);

        long lastEventFinishMicros = 0;
        for (RuleTrace ruleEntry : line.ruleTraces) {
          lastEventFinishMicros = renderRule(ruleEntry, threadId, lastEventFinishMicros);
        }
      }
    }
  }

  private long renderRule(RuleTrace ruleEntry, int threadId, long lastEventFinishMicros)
      throws IOException {
    // Work around Chrome trace renderer: it renders new lines
    // for zero width events connected to previous events.
    // So we are adjusting events so that:
    // * each event is at least 1 microsecond
    // * adjusted events do not overlap
    // For example, this sequence event would produce two lines:
    // | 5ms event | 0ms event | 5ms event |
    // So we render these events like this:
    // | 5ms event | 1us event | 4ms499us event |

    long startMicros = Math.max(ruleEntry.startEpochMillis * 1000, lastEventFinishMicros);

    lastEventFinishMicros = Math.max(ruleEntry.finishEpochMillis * 1000, startMicros + 1);

    ImmutableMap.Builder<String, String> startArgs = ImmutableMap.builder();

    startArgs.put(
        "critical_blocking_rule",
        ruleEntry.previousRuleInLongestDependencyChain.map(rule -> rule.ruleName).orElse("None"));
    startArgs.put(
        "length_of_critical_path", String.format("%dms", ruleEntry.longestDependencyChainMillis));
    startArgs.put("number_of_dependents", String.format("%d", ruleEntry.numberOfDependents));

    // This formatting helps in searching for the rule in the Chrome Trace.
    // Otherwise the same name might appear in critical dependencies of other rules.
    String ruleTitle = String.format("%s - title", ruleEntry.ruleName);
    chromeTraceWriter.writeEvent(
        new ChromeTraceEvent(
            kTraceCategory,
            ruleTitle,
            Phase.BEGIN,
            pidForTrace,
            threadId,
            startMicros,
            0,
            startArgs.build()));
    chromeTraceWriter.writeEvent(
        new ChromeTraceEvent(
            kTraceCategory,
            ruleTitle,
            Phase.END,
            pidForTrace,
            threadId,
            lastEventFinishMicros,
            0,
            ImmutableMap.of()));

    return lastEventFinishMicros;
  }

  /** Generate an HTML Chrome-trace based on the given {@link DistBuildTrace} object. */
  public static void render(DistBuildTrace trace, Path path) throws IOException {
    try (FileOutputStream outputStream = new FileOutputStream(path.toFile())) {
      DistBuildChromeTraceRenderer renderer = new DistBuildChromeTraceRenderer(trace, outputStream);
      renderer.writeTraceFile();
    }
  }
}
