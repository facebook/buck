/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.ChromeTraceEvent;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Logs events to a json file formatted to be viewed in Chrome Trace View (chrome://tracing).
 */
public class ChromeTraceBuildListener {
  private final ProjectFilesystem projectFilesystem;
  private ConcurrentLinkedQueue<ChromeTraceEvent> eventList =
      new ConcurrentLinkedQueue<ChromeTraceEvent>();

  public ChromeTraceBuildListener(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
  }

  @Subscribe
  public void buildStarted(BuildEvent.Started started) {
    writeChromeTraceEvent("buck",
        "build",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.<String, String>of(),
        started);
  }

  @Subscribe
  public synchronized void buildFinished(BuildEvent.Finished finished) {
    writeChromeTraceEvent("buck",
        "build",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.<String, String>of(),
        finished);

    try {
      String tracePath = String.format("%s/%s", BuckConstant.BIN_DIR, "build.trace");
      File traceOutput = projectFilesystem.getFileForRelativePath(tracePath);
      projectFilesystem.createParentDirs(tracePath);

      ImmutableList<ChromeTraceEvent> tsSortedEvents = FluentIterable.
          from(eventList).
          toSortedList(new Comparator<ChromeTraceEvent>() {
            @Override
            public int compare(ChromeTraceEvent a, ChromeTraceEvent b) {
              return Long.signum(a.getMicroTime() - b.getMicroTime());
            }
          });

      ObjectMapper mapper = new ObjectMapper();
      mapper.writeValue(traceOutput, tsSortedEvents);
    } catch (IOException e) {
      throw new HumanReadableException("Unable to write trace file.");
    }
  }

  @Subscribe
  public void ruleStarted(BuildRuleEvent.Started started) {
    writeChromeTraceEvent("buck",
        started.getBuildRule().getFullyQualifiedName(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.<String, String>of(),
        started);
  }

  @Subscribe
  public void ruleFinished(BuildRuleEvent.Finished finished) {
    writeChromeTraceEvent("buck",
        finished.getBuildRule().getFullyQualifiedName(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.<String, String>of(),
        finished);
  }

  @Subscribe
  public void stepStarted(StepEvent.Started started) {
    writeChromeTraceEvent("buck",
        started.getShortName(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.<String, String>of(),
        started);
  }

  @Subscribe
  public void stepFinished(StepEvent.Finished finished) {
    writeChromeTraceEvent("buck",
        finished.getShortName(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of("description", finished.getDescription()),
        finished);
  }

  @Subscribe
  public void parseStarted(ParseEvent.Started started) {
    writeChromeTraceEvent("buck",
        "parse",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.<String, String>of(),
        started);
  }

  @Subscribe
  public void parseFinished(ParseEvent.Finished finished) {
    writeChromeTraceEvent("buck",
        "parse",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.<String, String>of("targets",
            Joiner.on(",").join(finished.getBuildTargets())),
        finished);
  }

  private void writeChromeTraceEvent(String category,
                                     String name,
                                     ChromeTraceEvent.Phase phase,
                                     ImmutableMap<String, String> arguments,
                                     BuckEvent event) {
    eventList.add(new ChromeTraceEvent(category,
        name,
        phase,
        0,
        event.getThreadId(),
        TimeUnit.NANOSECONDS.toMicros(event.getNanoTime()),
        arguments));
  }
}
