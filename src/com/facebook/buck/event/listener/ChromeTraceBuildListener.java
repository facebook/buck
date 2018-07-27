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

import com.facebook.buck.artifact_cache.ArtifactCacheConnectEvent;
import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.test.event.TestSummaryEvent;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.CompilerPluginDurationEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.event.RuleKeyCalculationEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.StartActivityEvent;
import com.facebook.buck.event.UninstallEvent;
import com.facebook.buck.event.chrome_trace.ChromeTraceBuckConfig;
import com.facebook.buck.event.chrome_trace.ChromeTraceEvent;
import com.facebook.buck.event.chrome_trace.ChromeTraceEvent.Phase;
import com.facebook.buck.event.chrome_trace.ChromeTraceWriter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.jvm.java.AnnotationProcessingEvent;
import com.facebook.buck.jvm.java.tracing.JavacPhaseEvent;
import com.facebook.buck.log.CommandThreadFactory;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.support.bgtasks.BackgroundTask;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.bgtasks.ImmutableBackgroundTask;
import com.facebook.buck.test.external.ExternalTestRunEvent;
import com.facebook.buck.test.external.ExternalTestSpecCalculationEvent;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.ProcessResourceConsumption;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.facebook.buck.util.perf.PerfStatsTracking;
import com.facebook.buck.util.perf.ProcessTracker;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.unit.SizeUnit;
import com.facebook.buck.util.zip.BestCompressionGZIPOutputStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Logs events to a json file formatted to be viewed in Chrome Trace View (chrome://tracing). */
public class ChromeTraceBuildListener implements BuckEventListener {

  private static final LoadingCache<String, String> CONVERTED_EVENT_ID_CACHE =
      CacheBuilder.newBuilder()
          .weakValues()
          .build(
              new CacheLoader<String, String>() {
                @Override
                public String load(String key) {
                  return CaseFormat.UPPER_CAMEL
                      .converterTo(CaseFormat.LOWER_UNDERSCORE)
                      .convert(key)
                      .intern();
                }
              });

  private static final Logger LOG = Logger.get(ChromeTraceBuildListener.class);

  private final ProjectFilesystem projectFilesystem;
  private final Clock clock;
  private final ThreadLocal<SimpleDateFormat> dateFormat;
  private final Path tracePath;
  private final OutputStream traceStream;
  private final ChromeTraceWriter chromeTraceWriter;
  private final Path logDirectoryPath;
  private final ChromeTraceBuckConfig config;
  private final Set<Long> threadNamesRecorded = new HashSet<>();
  private final ThreadMXBean threadMXBean;

  private final ExecutorService outputExecutor;
  private final BackgroundTaskManager bgTaskManager;

  private final BuildId buildId;

  public ChromeTraceBuildListener(
      ProjectFilesystem projectFilesystem,
      InvocationInfo invocationInfo,
      Clock clock,
      ChromeTraceBuckConfig config,
      BackgroundTaskManager bgTaskManager)
      throws IOException {
    this(
        projectFilesystem,
        invocationInfo,
        clock,
        Locale.US,
        TimeZone.getDefault(),
        ManagementFactory.getThreadMXBean(),
        config,
        bgTaskManager);
  }

  @VisibleForTesting
  ChromeTraceBuildListener(
      ProjectFilesystem projectFilesystem,
      InvocationInfo invocationInfo,
      Clock clock,
      Locale locale,
      TimeZone timeZone,
      ThreadMXBean threadMXBean,
      ChromeTraceBuckConfig config,
      BackgroundTaskManager bgTaskManager)
      throws IOException {
    this.logDirectoryPath = invocationInfo.getLogDirectoryPath();
    this.projectFilesystem = projectFilesystem;
    this.clock = clock;
    this.buildId = invocationInfo.getBuildId();
    this.dateFormat =
        new ThreadLocal<SimpleDateFormat>() {
          @Override
          protected SimpleDateFormat initialValue() {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd.HH-mm-ss", locale);
            dateFormat.setTimeZone(timeZone);
            return dateFormat;
          }
        };
    this.threadMXBean = threadMXBean;
    this.config = config;
    this.bgTaskManager = bgTaskManager;
    this.outputExecutor =
        MostExecutors.newSingleThreadExecutor(new CommandThreadFactory(getClass().getName()));
    TracePathAndStream tracePathAndStream = createPathAndStream(invocationInfo.getBuildId());
    this.tracePath = tracePathAndStream.getPath();
    this.traceStream = tracePathAndStream.getStream();
    this.chromeTraceWriter = new ChromeTraceWriter(this.traceStream);
    this.chromeTraceWriter.writeStart();
    addProcessMetadataEvent(invocationInfo);
    addProjectFilesystemDelegateMetadataEvent(projectFilesystem);
  }

  private void addProcessMetadataEvent(InvocationInfo invocationInfo) {
    writeChromeTraceMetadataEvent(
        "process_name",
        ImmutableMap.<String, Object>builder()
            // Unlike process_labels, each value is its own field so it can be extracted from a
            // JSON parser or a tool like jq.
            .put("name", invocationInfo.getBuildId())
            .put("user_args", invocationInfo.getUnexpandedCommandArgs())
            .put("is_daemon", invocationInfo.getIsDaemon())
            .put("timestamp", invocationInfo.getTimestampMillis())
            .build());
    writeChromeTraceMetadataEvent(
        "process_labels",
        ImmutableMap.<String, Object>builder()
            .put(
                "labels",
                String.format(
                    "user_args=%s, is_daemon=%b, timestamp=%d",
                    invocationInfo.getUnexpandedCommandArgs(),
                    invocationInfo.getIsDaemon(),
                    invocationInfo.getTimestampMillis()))
            .build());
  }

  private void addProjectFilesystemDelegateMetadataEvent(ProjectFilesystem projectFilesystem) {
    writeChromeTraceMetadataEvent(
        "ProjectFilesystemDelegate", projectFilesystem.getDelegateDetails());
  }

  private TracePathAndStream createPathAndStream(BuildId buildId) {
    String filenameTime = dateFormat.get().format(new Date(clock.currentTimeMillis()));
    String traceName = String.format("build.%s.%s.trace", filenameTime, buildId);
    if (config.getCompressTraces()) {
      traceName = traceName + ".gz";
    }
    Path tracePath = logDirectoryPath.resolve(traceName);
    try {
      projectFilesystem.createParentDirs(tracePath);
      OutputStream stream = projectFilesystem.newFileOutputStream(tracePath);
      if (config.getCompressTraces()) {
        stream = new BestCompressionGZIPOutputStream(stream, true);
      }
      return new TracePathAndStream(tracePath, stream);
    } catch (IOException e) {
      throw new HumanReadableException(e, "Unable to write trace file: " + e);
    }
  }

  @Override
  public void close() {
    ChromeTraceBuildListenerCloseArgs args =
        ChromeTraceBuildListenerCloseArgs.of(
            outputExecutor,
            tracePath,
            chromeTraceWriter,
            traceStream,
            config,
            logDirectoryPath,
            buildId,
            projectFilesystem);

    ChromeTraceBuildListenerCloseAction closeAction = new ChromeTraceBuildListenerCloseAction();

    BackgroundTask<ChromeTraceBuildListenerCloseArgs> task =
        ImmutableBackgroundTask.<ChromeTraceBuildListenerCloseArgs>builder()
            .setAction(closeAction)
            .setActionArgs(args)
            .build();

    bgTaskManager.schedule(task, "ChromeTraceBuildListener_close");
  }

  @Subscribe
  public void commandStarted(CommandEvent.Started started) {
    writeChromeTraceEvent(
        "buck",
        started.getCommandName(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("command_args", Joiner.on(' ').join(started.getArgs())),
        started);
  }

  @Subscribe
  public void commandFinished(CommandEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
        finished.getCommandName(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "command_args", Joiner.on(' ').join(finished.getArgs()),
            "daemon", Boolean.toString(finished.isDaemon())),
        finished);
  }

  @Subscribe
  public void buildStarted(BuildEvent.Started started) {
    writeChromeTraceEvent(
        "buck", "build", ChromeTraceEvent.Phase.BEGIN, ImmutableMap.of(), started);
  }

  @Subscribe
  public synchronized void buildFinished(BuildEvent.Finished finished) {
    writeChromeTraceEvent("buck", "build", ChromeTraceEvent.Phase.END, ImmutableMap.of(), finished);
  }

  @Subscribe
  public void ruleStarted(BuildRuleEvent.Started started) {
    BuildRule buildRule = started.getBuildRule();
    writeChromeTraceEvent(
        "buck",
        buildRule.getFullyQualifiedName(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of(),
        started);
  }

  @Subscribe
  public void ruleFinished(BuildRuleEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
        finished.getBuildRule().getFullyQualifiedName(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "cache_result", finished.getCacheResult().toString().toLowerCase(),
            "success_type", finished.getSuccessType().map(Object::toString).orElse("failed")),
        finished);
  }

  @Subscribe
  public void ruleResumed(BuildRuleEvent.Resumed resumed) {
    BuildRule buildRule = resumed.getBuildRule();
    writeChromeTraceEvent(
        "buck",
        buildRule.getFullyQualifiedName(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("rule_key", resumed.getRuleKey()),
        resumed);
  }

  @Subscribe
  public void ruleSuspended(BuildRuleEvent.Suspended suspended) {
    // Because RuleKeyCalculationEvent.Finished is a subclass of BuildRuleEvent.Suspended, both
    // events get issued. If we wrote the trace events in the order they come in on the event bus,
    // we'd create an incorrect trace where the rule section ends before the rule_key_calc section
    // it encloses. Instead, when we see a BuildRuleEvent.Suspended that is also a
    // RuleKeyCalculationEvent.Finished, we let ruleKeyCalculationFinished log them both in the
    // correct order. TODO(jkeljo): Fix this in a less hacky way.
    if (suspended instanceof RuleKeyCalculationEvent.Finished) {
      return;
    }
    writeRuleSuspended(suspended);
  }

  private void writeRuleSuspended(BuildRuleEvent.Suspended suspended) {
    BuildRule buildRule = suspended.getBuildRule();
    writeChromeTraceEvent(
        "buck",
        buildRule.getFullyQualifiedName(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of("rule_key", suspended.getRuleKey()),
        suspended);
  }

  @Subscribe
  public void stepStarted(StepEvent.Started started) {
    writeChromeTraceEvent(
        "buck",
        started.getShortStepName(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of(),
        started);
  }

  @Subscribe
  public void stepFinished(StepEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
        finished.getShortStepName(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "description", finished.getDescription(),
            "exit_code", Integer.toString(finished.getExitCode())),
        finished);
  }

  // TODO(cjhopman): We should introduce a simple LeafEvent-like thing that everything that logs
  // step-like things can subscribe to.
  @Subscribe
  public void simpleLeafEventStarted(LeafEvents.SimpleLeafEvent.Started started) {
    writeChromeTraceEvent(
        "buck",
        started.getEventName(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("description", started.toString()),
        started);
  }

  @Subscribe
  public void simpleLeafEventFinished(LeafEvents.SimpleLeafEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
        finished.getEventName(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of("description", finished.toString()),
        finished);
  }

  @Subscribe
  public void parseStarted(ParseEvent.Started started) {
    writeChromeTraceEvent(
        "buck", "parse", ChromeTraceEvent.Phase.BEGIN, ImmutableMap.of(), started);
  }

  @Subscribe
  public void parseFinished(ParseEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
        "parse",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of("targets", Joiner.on(",").join(finished.getBuildTargets())),
        finished);
  }

  @Subscribe
  public void simplePerfEvent(SimplePerfEvent perfEvent) {
    ChromeTraceEvent.Phase phase = null;
    switch (perfEvent.getEventType()) {
      case STARTED:
        phase = ChromeTraceEvent.Phase.BEGIN;
        break;
      case FINISHED:
        phase = ChromeTraceEvent.Phase.END;
        break;
      case UPDATED:
        phase = ChromeTraceEvent.Phase.IMMEDIATE;
        break;
    }
    if (phase == null) {
      throw new IllegalStateException("Unsupported perf event type: " + perfEvent.getEventType());
    }

    try {
      writeChromeTraceEvent(
          "buck",
          CONVERTED_EVENT_ID_CACHE.get(perfEvent.getEventId().getValue().intern()),
          phase,
          ImmutableMap.copyOf(perfEvent.getEventInfo()),
          perfEvent);
    } catch (ExecutionException e) {
      LOG.warn("Unable to log perf event " + perfEvent, e);
    }
  }

  @Subscribe
  public void parseBuckFileStarted(ParseBuckFileEvent.Started started) {
    writeChromeTraceEvent(
        "buck",
        "parse_file",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("path", started.getBuckFilePath().toString()),
        started);
  }

  @Subscribe
  public void parseBuckFileFinished(ParseBuckFileEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
        "parse_file",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "path",
            finished.getBuckFilePath().toString(),
            "num_rules",
            Integer.toString(finished.getNumRules()),
            "processed_bytes",
            Long.toString(finished.getProcessedBytes()),
            "python_profile",
            finished.getProfile().orElse("")),
        finished);
  }

  @Subscribe
  public void actionGraphStarted(ActionGraphEvent.Started started) {
    writeChromeTraceEvent(
        "buck", "action_graph", ChromeTraceEvent.Phase.BEGIN, ImmutableMap.of(), started);
  }

  @Subscribe
  public void actionGraphFinished(ActionGraphEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck", "action_graph", ChromeTraceEvent.Phase.END, ImmutableMap.of(), finished);
  }

  @Subscribe
  public void actionGraphCacheHit(ActionGraphEvent.Cache.Hit hit) {
    writeChromeTraceEvent(
        "buck",
        "action_graph_cache",
        ChromeTraceEvent.Phase.IMMEDIATE,
        ImmutableMap.of("hit", true),
        hit);
  }

  @Subscribe
  public void actionGraphCacheMiss(ActionGraphEvent.Cache.Miss miss) {
    writeChromeTraceEvent(
        "buck",
        "action_graph_cache",
        ChromeTraceEvent.Phase.IMMEDIATE,
        ImmutableMap.of("hit", false, "cacheWasEmpty", miss.cacheWasEmpty),
        miss);
  }

  @Subscribe
  public void installStarted(InstallEvent.Started started) {
    writeChromeTraceEvent(
        "buck", "install", ChromeTraceEvent.Phase.BEGIN, ImmutableMap.of(), started);
  }

  @Subscribe
  public void installFinished(InstallEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
        "install",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "target", finished.getBuildTarget().getFullyQualifiedName(),
            "success", Boolean.toString(finished.isSuccess())),
        finished);
  }

  @Subscribe
  public void startActivityStarted(StartActivityEvent.Started started) {
    writeChromeTraceEvent(
        "buck", "start_activity", ChromeTraceEvent.Phase.BEGIN, ImmutableMap.of(), started);
  }

  @Subscribe
  public void startActivityFinished(StartActivityEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
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
    writeChromeTraceEvent(
        "buck", "uninstall", ChromeTraceEvent.Phase.BEGIN, ImmutableMap.of(), started);
  }

  @Subscribe
  public void uninstallFinished(UninstallEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
        "uninstall",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "package_name", finished.getPackageName(),
            "success", Boolean.toString(finished.isSuccess())),
        finished);
  }

  @Subscribe
  public void artifactCacheEventStarted(ArtifactCacheEvent.Started started) {
    writeChromeTraceEvent(
        "buck",
        started.getCategory(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of(
            "rule_key", Joiner.on(", ").join(started.getRuleKeys()),
            "rule", started.getTarget().orElse("unknown")),
        started);
  }

  @Subscribe
  public void artifactCacheEventFinished(ArtifactCacheEvent.Finished finished) {
    ImmutableMap.Builder<String, String> argumentsBuilder =
        ImmutableMap.<String, String>builder()
            .put("success", Boolean.toString(finished.isSuccess()))
            .put("rule_key", Joiner.on(", ").join(finished.getRuleKeys()))
            .put("rule", finished.getTarget().orElse("unknown"));
    Optionals.putIfPresent(
        finished.getCacheResult().map(Object::toString), "cache_result", argumentsBuilder);

    writeChromeTraceEvent(
        "buck",
        finished.getCategory(),
        ChromeTraceEvent.Phase.END,
        argumentsBuilder.build(),
        finished);
  }

  @Subscribe
  public void artifactCompressionStarted(ArtifactCompressionEvent.Started started) {
    writeArtifactCompressionEvent(started, ChromeTraceEvent.Phase.BEGIN);
  }

  @Subscribe
  public void artifactCompressionFinished(ArtifactCompressionEvent.Finished finished) {
    writeArtifactCompressionEvent(finished, ChromeTraceEvent.Phase.END);
  }

  public void writeArtifactCompressionEvent(
      ArtifactCompressionEvent event, ChromeTraceEvent.Phase phase) {
    writeChromeTraceEvent(
        "buck",
        event.getCategory(),
        phase,
        ImmutableMap.of("rule_key", Joiner.on(", ").join(event.getRuleKeys())),
        event);
  }

  @Subscribe
  public void artifactConnectStarted(ArtifactCacheConnectEvent.Started started) {
    writeChromeTraceEvent(
        "buck", "artifact_connect", ChromeTraceEvent.Phase.BEGIN, ImmutableMap.of(), started);
  }

  @Subscribe
  public void artifactConnectFinished(ArtifactCacheConnectEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck", "artifact_connect", ChromeTraceEvent.Phase.END, ImmutableMap.of(), finished);
  }

  @Subscribe
  public void javacPhaseStarted(JavacPhaseEvent.Started started) {
    writeChromeTraceEvent(
        "javac",
        started.getPhase().toString(),
        ChromeTraceEvent.Phase.BEGIN,
        started.getArgs(),
        started);
  }

  @Subscribe
  public void javacPhaseFinished(JavacPhaseEvent.Finished finished) {
    writeChromeTraceEvent(
        "javac",
        finished.getPhase().toString(),
        ChromeTraceEvent.Phase.END,
        finished.getArgs(),
        finished);
  }

  @Subscribe
  public void annotationProcessingStarted(AnnotationProcessingEvent.Started started) {
    writeChromeTraceEvent(
        started.getAnnotationProcessorName(),
        started.getCategory(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of(),
        started);
  }

  @Subscribe
  public void annotationProcessingFinished(AnnotationProcessingEvent.Finished finished) {
    writeChromeTraceEvent(
        finished.getAnnotationProcessorName(),
        finished.getCategory(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(),
        finished);
  }

  @Subscribe
  public void compilerPluginDurationEventStarted(CompilerPluginDurationEvent.Started started) {
    writeChromeTraceEvent(
        started.getPluginName(),
        started.getDurationName(),
        ChromeTraceEvent.Phase.BEGIN,
        started.getArgs(),
        started);
  }

  @Subscribe
  public void compilerPluginDurationEventFinished(CompilerPluginDurationEvent.Finished finished) {
    writeChromeTraceEvent(
        finished.getPluginName(),
        finished.getDurationName(),
        ChromeTraceEvent.Phase.END,
        finished.getArgs(),
        finished);
  }

  @Subscribe
  public void memoryPerfStats(PerfStatsTracking.MemoryPerfStatsEvent memory) {
    writeChromeTraceEvent(
        "perf",
        "memory",
        ChromeTraceEvent.Phase.COUNTER,
        ImmutableMap.<String, String>builder()
            .put(
                "used_memory_mb",
                Long.toString(
                    SizeUnit.BYTES.toMegabytes(
                        memory.getTotalMemoryBytes() - memory.getFreeMemoryBytes())))
            .put(
                "free_memory_mb",
                Long.toString(SizeUnit.BYTES.toMegabytes(memory.getFreeMemoryBytes())))
            .put(
                "total_memory_mb",
                Long.toString(SizeUnit.BYTES.toMegabytes(memory.getTotalMemoryBytes())))
            .put(
                "max_memory_mb",
                Long.toString(SizeUnit.BYTES.toMegabytes(memory.getMaxMemoryBytes())))
            .put(
                "time_spent_in_gc_sec",
                Long.toString(TimeUnit.MILLISECONDS.toSeconds(memory.getTimeSpentInGcMs())))
            .putAll(
                memory
                    .getCurrentMemoryBytesUsageByPool()
                    .entrySet()
                    .stream()
                    .map(
                        e ->
                            Maps.immutableEntry(
                                "pool_" + e.getKey() + "_mb",
                                Long.toString(SizeUnit.BYTES.toMegabytes(e.getValue()))))
                    .collect(Collectors.toList()))
            .build(),
        memory);
  }

  @Subscribe
  public void processResourceConsumption(ProcessTracker.ProcessResourceConsumptionEvent event) {
    Optional<ProcessResourceConsumption> resourceConsumption = event.getResourceConsumption();
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    builder.put("executable", event.getExecutableName());
    if (event.getContext().isPresent()) {
      builder.put("context", event.getContext().get().toString());
    }
    if (resourceConsumption.isPresent()) {
      ProcessResourceConsumption res = resourceConsumption.get();
      builder.put("mem_size_mb", Long.toString(SizeUnit.BYTES.toMegabytes(res.getMemSize())));
      builder.put(
          "mem_resident_mb", Long.toString(SizeUnit.BYTES.toMegabytes(res.getMemResident())));
      builder.put("cpu_real_ms", Long.toString(res.getCpuReal()));
      builder.put("cpu_user_ms", Long.toString(res.getCpuUser()));
      builder.put("cpu_sys_ms", Long.toString(res.getCpuSys()));
      builder.put("bytes_read_mb", Long.toString(SizeUnit.BYTES.toMegabytes(res.getIoBytesRead())));
      builder.put(
          "bytes_written_mb", Long.toString(SizeUnit.BYTES.toMegabytes(res.getIoBytesWritten())));
    }
    writeChromeTraceEvent(
        "perf", "process", ChromeTraceEvent.Phase.COUNTER, builder.build(), event);
  }

  @Subscribe
  public void testStartedEvent(TestSummaryEvent.Started started) {
    writeChromeTraceEvent(
        "buck",
        "test",
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of(
            "test_case_name", started.getTestCaseName(),
            "test_name", started.getTestName()),
        started);
  }

  @Subscribe
  public void testFinishedEvent(TestSummaryEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
        "test",
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of(
            "test_case_name", finished.getTestCaseName(),
            "test_name", finished.getTestName()),
        finished);
  }

  @Subscribe
  public void ruleKeyCalculationStarted(RuleKeyCalculationEvent.Started started) {
    writeChromeTraceEvent(
        "buck", started.getCategory(), ChromeTraceEvent.Phase.BEGIN, ImmutableMap.of(), started);
  }

  @Subscribe
  public void ruleKeyCalculationFinished(RuleKeyCalculationEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck", finished.getCategory(), ChromeTraceEvent.Phase.END, ImmutableMap.of(), finished);
    if (finished instanceof BuildRuleEvent.Suspended) {
      writeRuleSuspended((BuildRuleEvent.Suspended) finished);
    }
  }

  @Subscribe
  public void externalTestSpecCalculationStarted(ExternalTestSpecCalculationEvent.Started started) {
    writeChromeTraceEvent(
        "buck",
        started.getCategory(),
        ChromeTraceEvent.Phase.BEGIN,
        ImmutableMap.of("target", started.getBuildTarget().getFullyQualifiedName()),
        started);
  }

  @Subscribe
  public void externalTestSpecCalculationFinished(
      ExternalTestSpecCalculationEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck",
        finished.getCategory(),
        ChromeTraceEvent.Phase.END,
        ImmutableMap.of("target", finished.getBuildTarget().getFullyQualifiedName()),
        finished);
  }

  @Subscribe
  public void externalTestRunStarted(ExternalTestRunEvent.Started started) {
    writeChromeTraceEvent(
        "buck", started.getCategory(), ChromeTraceEvent.Phase.BEGIN, ImmutableMap.of(), started);
  }

  @Subscribe
  public void externalTestRunFinished(ExternalTestRunEvent.Finished finished) {
    writeChromeTraceEvent(
        "buck", finished.getCategory(), ChromeTraceEvent.Phase.END, ImmutableMap.of(), finished);
  }

  @Subscribe
  public void onWatchmanOverflow(WatchmanOverflowEvent event) {
    writeChromeTraceMetadataEvent(
        "watchman_overflow",
        ImmutableMap.of("cellPath", event.getCellPath().toString(), "reason", event.getReason()));
  }

  @VisibleForTesting
  void writeChromeTraceEvent(
      String category,
      String name,
      ChromeTraceEvent.Phase phase,
      ImmutableMap<String, ? extends Object> arguments,
      BuckEvent event) {
    long threadId = event.getThreadId();
    long timestampInMicroseconds = TimeUnit.NANOSECONDS.toMicros(event.getNanoTime());
    long threadTimestampInMicroseconds =
        TimeUnit.NANOSECONDS.toMicros(event.getThreadUserNanoTime());

    ChromeTraceEvent chromeTraceEvent =
        new ChromeTraceEvent(
            category,
            name,
            phase,
            0,
            threadId,
            timestampInMicroseconds,
            threadTimestampInMicroseconds,
            arguments);
    submitTraceEvent(chromeTraceEvent);
    writeThreadNameIfNeeded(chromeTraceEvent);
  }

  void writeThreadNameIfNeeded(ChromeTraceEvent triggeringEvent) {
    long threadId = triggeringEvent.getThreadId();
    long timestampInMicroseconds = triggeringEvent.getMicroTime();
    long threadTimestampInMicroseconds = triggeringEvent.getMicroThreadUserTime();

    Long boxedThreadId = Long.valueOf(threadId);
    if (!threadNamesRecorded.contains(boxedThreadId)) {
      ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId);

      if (threadInfo != null) {
        submitTraceEvent(
            new ChromeTraceEvent(
                "buck",
                "thread_name",
                Phase.METADATA,
                0,
                threadId,
                timestampInMicroseconds,
                threadTimestampInMicroseconds,
                ImmutableMap.of("name", threadInfo.getThreadName())));
      }

      // Force sort by thread ID so that the sort order is in creation order. This produces the
      // most readable traces.
      submitTraceEvent(
          new ChromeTraceEvent(
              "buck",
              "thread_sort_index",
              Phase.METADATA,
              0,
              threadId,
              timestampInMicroseconds,
              threadTimestampInMicroseconds,
              ImmutableMap.of("sort_index", boxedThreadId)));

      threadNamesRecorded.add(threadId);
    }
  }

  @VisibleForTesting
  void writeChromeTraceMetadataEvent(
      String name, ImmutableMap<String, ? extends Object> arguments) {
    long timestampInMicroseconds = TimeUnit.NANOSECONDS.toMicros(clock.nanoTime());
    long threadTimestampInMicroseconds =
        TimeUnit.NANOSECONDS.toMicros(clock.threadUserNanoTime(Thread.currentThread().getId()));
    ChromeTraceEvent chromeTraceEvent =
        new ChromeTraceEvent(
            /* category */ "buck",
            name,
            ChromeTraceEvent.Phase.METADATA,
            /* processId */ 0,
            /* threadId */ 0,
            /* microTime */ timestampInMicroseconds,
            /* microThreadUserTime */ threadTimestampInMicroseconds,
            arguments);
    submitTraceEvent(chromeTraceEvent);
  }

  @SuppressWarnings("PMD.EmptyCatchBlock")
  private void submitTraceEvent(ChromeTraceEvent chromeTraceEvent) {
    @SuppressWarnings("unused")
    Future<?> unused =
        outputExecutor.submit(
            () -> {
              try {
                chromeTraceWriter.writeEvent(chromeTraceEvent);
              } catch (IOException e) {
                // Swallow any failures to write.
              }
              return null;
            });
  }

  private static class TracePathAndStream {
    private final Path path;
    private final OutputStream stream;

    public TracePathAndStream(Path path, OutputStream stream) {
      this.path = path;
      this.stream = stream;
    }

    public Path getPath() {
      return path;
    }

    public OutputStream getStream() {
      return stream;
    }
  }
}
