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

package com.facebook.buck.event.listener;

import com.facebook.buck.cli.CommandEvent;
import com.facebook.buck.cli.InstallEvent;
import com.facebook.buck.cli.StartActivityEvent;
import com.facebook.buck.cli.UninstallEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ChromeTraceEvent;
import com.facebook.buck.event.TraceEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.io.PathListing;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.ActionGraphEvent;
import com.facebook.buck.rules.ArtifactCacheConnectEvent;
import com.facebook.buck.rules.ArtifactCacheEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Optionals;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Logs events to a json file formatted to be viewed in Chrome Trace View (chrome://tracing).
 */
public class ChromeTraceBuildListener implements BuckEventListener {
  private static final Logger LOG = Logger.get(ChromeTraceBuildListener.class);

  private final ProjectFilesystem projectFilesystem;
  private final Clock clock;
  private final int tracesToKeep;
  private final ObjectMapper mapper;
  private final ThreadLocal<SimpleDateFormat> dateFormat;
  private ConcurrentLinkedQueue<ChromeTraceEvent> eventList =
      new ConcurrentLinkedQueue<ChromeTraceEvent>();

  public ChromeTraceBuildListener(
      ProjectFilesystem projectFilesystem,
      Clock clock,
      ObjectMapper objectMapper,
      int tracesToKeep) {
    this(projectFilesystem, clock, objectMapper, Locale.US, TimeZone.getDefault(), tracesToKeep);
  }

  @VisibleForTesting
  ChromeTraceBuildListener(
      ProjectFilesystem projectFilesystem,
      Clock clock,
      ObjectMapper objectMapper,
      final Locale locale,
      final TimeZone timeZone,
      int tracesToKeep) {
    this.projectFilesystem = projectFilesystem;
    this.clock = clock;
    this.mapper = objectMapper;
    this.dateFormat = new ThreadLocal<SimpleDateFormat>() {
      @Override
      protected SimpleDateFormat initialValue() {
          SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd.HH-mm-ss", locale);
          dateFormat.setTimeZone(timeZone);
          return dateFormat;
      }
    };
    this.tracesToKeep = tracesToKeep;
    addProcessMetadataEvent();
  }

  private void addProcessMetadataEvent() {
    eventList.add(
        new ChromeTraceEvent(
            "buck",
            "process_name",
            ChromeTraceEvent.Phase.METADATA,
            /* processId */ 0,
            /* threadId */ 0,
            /* microTime */ 0,
            ImmutableMap.of("name", "buck")));
  }

  @VisibleForTesting
  void deleteOldTraces() {
    if (!projectFilesystem.exists(BuckConstant.BUCK_TRACE_DIR)) {
      return;
    }

    Path traceDirectory = projectFilesystem.getPathForRelativePath(BuckConstant.BUCK_TRACE_DIR);

    try {
      for (Path path : PathListing.listMatchingPathsWithFilters(
               traceDirectory,
               "build.*.trace",
               PathListing.GET_PATH_MODIFIED_TIME,
               PathListing.FilterMode.EXCLUDE,
               Optional.of(tracesToKeep),
               Optional.<Long>absent())) {
        projectFilesystem.deleteFileAtPath(path);
      }
    } catch (IOException e) {
      LOG.error(e, "Couldn't list paths in trace directory %s", traceDirectory);
    }
  }

  @Override
  public void outputTrace(BuildId buildId) {
    try {
      String filenameTime = dateFormat.get().format(new Date(clock.currentTimeMillis()));
      String tracePath = String.format("%s/build.%s.%s.trace",
          BuckConstant.BUCK_TRACE_DIR,
          filenameTime,
          buildId);
      File traceOutput = projectFilesystem.getFileForRelativePath(tracePath);
      projectFilesystem.createParentDirs(tracePath);

      LOG.debug("Writing Chrome trace to %s", tracePath);
      mapper.writeValue(traceOutput, eventList);

      String symlinkPath = String.format("%s/build.trace",
          BuckConstant.BUCK_TRACE_DIR);
      File symlinkFile = projectFilesystem.getFileForRelativePath(symlinkPath);
      projectFilesystem.createSymLink(Paths.get(traceOutput.toURI()),
          Paths.get(symlinkFile.toURI()),
          true);

      deleteOldTraces();
    } catch (IOException e) {
      throw new HumanReadableException(e, "Unable to write trace file: " + e);
    }
  }

  @Subscribe
  public void commandStarted(CommandEvent.Started started) {
    writeChromeTraceEvent("buck",
        started.getCommandName(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of(
            "command_args", Joiner.on(' ').join(started.getArgs())
        ),
        started);
  }

  @Subscribe
  public void commandFinished(CommandEvent.Finished finished) {
    writeChromeTraceEvent("buck",
        finished.getCommandName(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "command_args", Joiner.on(' ').join(finished.getArgs()),
            "daemon", Boolean.toString(finished.isDaemon())),
        finished);
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
  }

  @Subscribe
  public void ruleStarted(BuildRuleEvent.Started started) {
    BuildRule buildRule = started.getBuildRule();

    writeChromeTraceEvent("buck",
        buildRule.getFullyQualifiedName(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("rule_key", started.getRuleKeySafe()),
        started);
  }

  @Subscribe
  public void ruleFinished(BuildRuleEvent.Finished finished) {
    writeChromeTraceEvent("buck",
        finished.getBuildRule().getFullyQualifiedName(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "cache_result", finished.getCacheResult().toString().toLowerCase(),
            "success_type",
            finished.getSuccessType().transform(Functions.toStringFunction()).or("failed")
        ),
        finished);
  }

  @Subscribe
  public void stepStarted(StepEvent.Started started) {
    writeChromeTraceEvent("buck",
        started.getStep().getShortName(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.<String, String>of(),
        started);
  }

  @Subscribe
  public void stepFinished(StepEvent.Finished finished) {
    writeChromeTraceEvent("buck",
        finished.getStep().getShortName(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "description", finished.getDescription(),
            "exit_code", Integer.toString(finished.getExitCode())),
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
        ImmutableMap.of(
            "targets",
            Joiner.on(",").join(finished.getBuildTargets())),
        finished);
  }

  @Subscribe
  public void actionGraphStarted(ActionGraphEvent.Started started) {
    writeChromeTraceEvent(
        "buck",
        "action_graph",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.<String, String>of(),
        started);
  }

  @Subscribe
  public void actionGraphFinished(ActionGraphEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
        "action_graph",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.<String, String>of(),
        finished);
  }

  @Subscribe
  public void installStarted(InstallEvent.Started started) {
    writeChromeTraceEvent("buck",
        "install",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.<String, String>of(),
        started);
  }

  @Subscribe
  public void installFinished(InstallEvent.Finished finished) {
    writeChromeTraceEvent("buck",
        "install",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "target", finished.getBuildTarget().getFullyQualifiedName(),
            "success", Boolean.toString(finished.isSuccess())),
        finished);
  }

  @Subscribe
  public void startActivityStarted(StartActivityEvent.Started started) {
    writeChromeTraceEvent("buck",
        "start_activity",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.<String, String>of(),
        started);
  }

  @Subscribe
  public void startActivityFinished(StartActivityEvent.Finished finished) {
    writeChromeTraceEvent("buck",
        "start_activity",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "target", finished.getBuildTarget().getFullyQualifiedName(),
            "activity_name", finished.getActivityName(),
            "success", Boolean.toString(finished.isSuccess())),
        finished);
  }

  @Subscribe
  public void uninstallStarted(UninstallEvent.Started started) {
    writeChromeTraceEvent("buck",
        "uninstall",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.<String, String>of(),
        started);
  }

  @Subscribe
  public void uninstallFinished(UninstallEvent.Finished finished) {
    writeChromeTraceEvent("buck",
        "uninstall",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "package_name", finished.getPackageName(),
            "success", Boolean.toString(finished.isSuccess())),
        finished);
  }

  @Subscribe
  public void artifactFetchStarted(ArtifactCacheEvent.Started started) {
    writeChromeTraceEvent("buck",
        started.getCategory(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of(
            "rule_key", started.getRuleKey().toString()),
        started);
  }

  @Subscribe
  public void artifactFetchFinished(ArtifactCacheEvent.Finished finished) {
    ImmutableMap.Builder<String, String> argumentsBuilder = ImmutableMap.<String, String>builder()
        .put("success", Boolean.toString(finished.isSuccess()))
        .put("rule_key", finished.getRuleKey().toString());
    Optionals.putIfPresent(finished.getCacheResult().transform(Functions.toStringFunction()),
        "cache_result",
        argumentsBuilder);

    writeChromeTraceEvent("buck",
        finished.getCategory(),
        ChromeTraceEvent.Phase.END,
        argumentsBuilder.build(),
        finished);
  }

  @Subscribe
  public void artifactConnectStarted(ArtifactCacheConnectEvent.Started started) {
    writeChromeTraceEvent("buck",
        "artifact_connect",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.<String, String>of(),
        started);
  }

  @Subscribe
  public void artifactConnectFinished(ArtifactCacheConnectEvent.Finished finished) {
    writeChromeTraceEvent("buck",
        "artifact_connect",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.<String, String>of(),
        finished);
  }

  @Subscribe
  public void traceEvent(TraceEvent event) {
    writeChromeTraceEvent("buck",
        event.getEventName(),
        event.getPhase(),
        event.getProperties(),
        event);
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
