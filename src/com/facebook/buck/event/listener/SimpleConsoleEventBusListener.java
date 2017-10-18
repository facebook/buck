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

import static com.facebook.buck.rules.BuildRuleSuccessType.BUILT_LOCALLY;

import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.distributed.DistBuildCreatedEvent;
import com.facebook.buck.distributed.DistBuildRunEvent;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.IndividualTestEvent;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.rules.TestStatusMessageEvent;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Implementation of {@code AbstractConsoleEventBusListener} for terminals that don't support ansi.
 */
public class SimpleConsoleEventBusListener extends AbstractConsoleEventBusListener {

  private static final Logger LOG = Logger.get(SuperConsoleEventBusListener.class);

  /** Wait this long (at least) before printing that a task is still running. */
  private static final long LONG_RUNNING_TASK_HEARTBEAT = TimeUnit.SECONDS.toMillis(15);

  private final Locale locale;
  private final AtomicLong parseTime;
  private final TestResultFormatter testFormatter;
  private final ImmutableList.Builder<TestStatusMessage> testStatusMessageBuilder =
      ImmutableList.builder();
  private final boolean hideSucceededRules;
  private final ScheduledExecutorService renderScheduler;
  private final Set<RunningTarget> runningTasks = new HashSet<>();

  public SimpleConsoleEventBusListener(
      Console console,
      Clock clock,
      TestResultSummaryVerbosity summaryVerbosity,
      boolean hideSucceededRules,
      int numberOfSlowRulesToShow,
      boolean showSlowRulesInConsole,
      Locale locale,
      Path testLogPath,
      ExecutionEnvironment executionEnvironment,
      Optional<BuildId> buildId) {
    super(
        console,
        clock,
        locale,
        executionEnvironment,
        true,
        numberOfSlowRulesToShow,
        showSlowRulesInConsole);
    this.locale = locale;
    this.parseTime = new AtomicLong(0);
    this.hideSucceededRules = hideSucceededRules;

    this.testFormatter =
        new TestResultFormatter(
            console.getAnsi(),
            console.getVerbosity(),
            summaryVerbosity,
            locale,
            Optional.of(testLogPath));

    if (buildId.isPresent()) {
      printLines(ImmutableList.<String>builder().add(getBuildLogLine(buildId.get())));
    }

    this.renderScheduler =
        Executors.newScheduledThreadPool(
            1,
            new ThreadFactoryBuilder().setNameFormat(getClass().getSimpleName() + "-%d").build());
  }

  public static String getBuildLogLine(BuildId buildId) {
    return "Build UUID: " + buildId.toString();
  }

  /** Print information regarding the current distributed build. */
  @Subscribe
  public void onDistbuildRunEvent(DistBuildRunEvent event) {
    String line =
        String.format(
            "StampedeId=[%s] BuildSlaveRunId=[%s]",
            event.getStampedeId().id, event.getBuildSlaveRunId().id);
    printLines(ImmutableList.<String>builder().add(line));
  }

  public void startRenderScheduler(long renderInterval, TimeUnit timeUnit) {
    LOG.debug("Starting render scheduler (interval %d ms)", timeUnit.toMillis(renderInterval));
    renderScheduler.scheduleAtFixedRate(
        SimpleConsoleEventBusListener.this::render, renderInterval, renderInterval, timeUnit);
  }

  @Override
  public void parseStarted(ParseEvent.Started started) {
    super.parseStarted(started);
    synchronized (runningTasks) {
      runningTasks.clear(); // We can only have one thing running, right?
    }
  }

  @Override
  @Subscribe
  public void parseFinished(ParseEvent.Finished finished) {
    super.parseFinished(finished);
    if (console.getVerbosity().isSilent()) {
      return;
    }
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    this.parseTime.set(
        logEventPair(
            "PARSING BUCK FILES",
            /* suffix */ Optional.empty(),
            clock.currentTimeMillis(),
            0L,
            buckFilesParsingEvents.values(),
            getEstimatedProgressOfParsingBuckFiles(),
            Optional.empty(),
            lines));
    printLines(lines);
  }

  @Override
  @Subscribe
  public void actionGraphFinished(ActionGraphEvent.Finished finished) {
    super.actionGraphFinished(finished);
    if (console.getVerbosity().isSilent()) {
      return;
    }
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    this.parseTime.set(
        logEventPair(
            "CREATING ACTION GRAPH",
            /* suffix */ Optional.empty(),
            clock.currentTimeMillis(),
            0L,
            actionGraphEvents.values(),
            Optional.empty(),
            Optional.empty(),
            lines));
    printLines(lines);
  }

  @Override
  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    super.buildFinished(finished);
    if (console.getVerbosity().isSilent()) {
      return;
    }

    ImmutableList.Builder<String> lines = ImmutableList.builder();

    lines.add(getNetworkStatsLine(finished));

    long currentMillis = clock.currentTimeMillis();
    long buildStartedTime = buildStarted != null ? buildStarted.getTimestamp() : Long.MAX_VALUE;
    long buildFinishedTime = buildFinished != null ? buildFinished.getTimestamp() : currentMillis;
    Collection<EventPair> processingEvents =
        getEventsBetween(buildStartedTime, buildFinishedTime, actionGraphEvents.values());
    long offsetMs = getTotalCompletedTimeFromEventPairs(processingEvents);
    logEventPair(
        "BUILDING",
        getOptionalBuildLineSuffix(),
        currentMillis,
        offsetMs,
        buildStarted,
        buildFinished,
        getApproximateBuildProgress(),
        Optional.empty(),
        lines);

    String httpStatus = renderHttpUploads();
    if (httpArtifactUploadsScheduledCount.get() > 0) {
      lines.add("WAITING FOR HTTP CACHE UPLOADS " + httpStatus);
    }

    showTopSlowBuildRules(lines);

    lines.add(finished.getExitCode() == 0 ? "BUILD SUCCEEDED" : "BUILD FAILED");

    printLines(lines);
  }

  @Override
  @Subscribe
  public void installFinished(InstallEvent.Finished finished) {
    super.installFinished(finished);
    if (console.getVerbosity().isSilent()) {
      return;
    }
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    logEventPair(
        "INSTALLING",
        /* suffix */ Optional.empty(),
        clock.currentTimeMillis(),
        0L,
        installStarted,
        installFinished,
        Optional.empty(),
        Optional.empty(),
        lines);
    printLines(lines);
  }

  @Subscribe
  public void logEvent(ConsoleEvent event) {
    if (console.getVerbosity().isSilent() && !event.getLevel().equals(Level.SEVERE)) {
      return;
    }
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    formatConsoleEvent(event, lines);
    printLines(lines);
  }

  @Override
  public void printSevereWarningDirectly(String line) {
    logEvent(ConsoleEvent.severe(line));
  }

  @Subscribe
  public void testRunStarted(TestRunEvent.Started event) {
    if (console.getVerbosity().isSilent()) {
      return;
    }
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    testFormatter.runStarted(
        lines,
        event.isRunAllTests(),
        event.getTestSelectorList(),
        event.shouldExplainTestSelectorList(),
        event.getTargetNames(),
        TestResultFormatter.FormatMode.BEFORE_TEST_RUN);
    printLines(lines);

    addRunningTarget(new RunningTarget(event, "Still running"));
  }

  @Subscribe
  public void testRunCompleted(TestRunEvent.Finished event) {
    if (console.getVerbosity().isSilent()) {
      return;
    }
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    ImmutableList<TestStatusMessage> testStatusMessages;
    synchronized (testStatusMessageBuilder) {
      testStatusMessages = testStatusMessageBuilder.build();
    }
    testFormatter.runComplete(lines, event.getResults(), testStatusMessages);
    printLines(lines);
  }

  private void addRunningTarget(RunningTarget task) {
    synchronized (runningTasks) {
      runningTasks.add(task);
    }
  }

  private void removeRunningTarget(BuckEvent event) {
    synchronized (runningTasks) {
      runningTasks.removeIf(task -> task.isPairedWith(event));
    }
  }

  @Subscribe
  public void testResultsAvailable(IndividualTestEvent.Finished event) {
    if (console.getVerbosity().isSilent()) {
      return;
    }
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    testFormatter.reportResult(lines, event.getResults());
    printLines(lines);
  }

  @Override
  public void buildRuleStarted(final BuildRuleEvent.Started started) {
    addRunningTarget(new RunningTarget(started, "Still building"));
    super.buildRuleStarted(started);
  }

  @Override
  public void buildRuleResumed(BuildRuleEvent.Resumed resumed) {
    super.buildRuleResumed(resumed);
    synchronized (runningTasks) {
      for (RunningTarget task : runningTasks) {
        if (task.isPairedWith(resumed)) {
          task.resume(resumed.getTimestamp());
        }
      }
    }
  }

  @Override
  public void buildRuleSuspended(BuildRuleEvent.Suspended suspended) {
    super.buildRuleSuspended(suspended);
    synchronized (runningTasks) {
      for (RunningTarget task : runningTasks) {
        if (task.isPairedWith(suspended)) {
          task.suspend();
        }
      }
    }
  }

  @Override
  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    super.buildRuleFinished(finished);
    removeRunningTarget(finished);

    if (finished.getStatus() != BuildRuleStatus.SUCCESS || console.getVerbosity().isSilent()) {
      return;
    }

    String jobsInfo = "";
    if (ruleCount.isPresent()) {
      jobsInfo = String.format(locale, "%d/%d JOBS", numRulesCompleted.get(), ruleCount.get());
    }
    String line =
        String.format(
            locale,
            "%s %s %s %s",
            finished.getResultString(),
            jobsInfo,
            formatElapsedTime(finished.getDuration().getWallMillisDuration()),
            finished.getBuildRule().getFullyQualifiedName());

    if ((BUILT_LOCALLY.equals(finished.getSuccessType().orElse(null)) && !hideSucceededRules)
        || console.getVerbosity().shouldPrintBinaryRunInformation()) {
      console.getStdErr().println(line);
    }
  }

  @Override
  @Subscribe
  public void onHttpArtifactCacheShutdownEvent(HttpArtifactCacheEvent.Shutdown event) {
    super.onHttpArtifactCacheShutdownEvent(event);
    if (console.getVerbosity().isSilent()) {
      return;
    }
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    logHttpCacheUploads(lines);

    printLines(lines);
  }

  @Subscribe
  public void testStatusMessageStarted(TestStatusMessageEvent.Started started) {
    synchronized (testStatusMessageBuilder) {
      if (console.getVerbosity().isSilent()) {
        return;
      }
      testStatusMessageBuilder.add(started.getTestStatusMessage());
    }
  }

  @Subscribe
  public void testStatusMessageFinished(TestStatusMessageEvent.Finished finished) {
    synchronized (testStatusMessageBuilder) {
      if (console.getVerbosity().isSilent()) {
        return;
      }
      testStatusMessageBuilder.add(finished.getTestStatusMessage());
    }
  }

  @Subscribe
  public void onDistBuildCreatedEvent(DistBuildCreatedEvent distBuildCreatedEvent) {
    ImmutableList.Builder<String> lines =
        ImmutableList.<String>builder()
            .add("STAMPEDE ID: " + distBuildCreatedEvent.getStampedeId());
    printLines(lines);
  }

  private void printLines(ImmutableList.Builder<String> lines) {
    // Print through the {@code DirtyPrintStreamDecorator} so printing from the simple console
    // is considered to dirty stderr and stdout and so it gets synchronized to avoid interlacing
    // output.
    ImmutableList<String> stringList = lines.build();
    if (stringList.size() == 0) {
      return;
    }
    console.getStdErr().println(Joiner.on("\n").join(stringList));
  }

  @VisibleForTesting
  synchronized void render() {
    if (console.getVerbosity().isSilent()) {
      return;
    }

    long now = System.currentTimeMillis();
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    synchronized (runningTasks) {
      runningTasks.forEach(task -> task.render(now, LONG_RUNNING_TASK_HEARTBEAT, lines));
    }

    String output = Joiner.on('\n').join(lines.build());

    if (output.isEmpty()) {
      return;
    }

    // Synchronize on the DirtyPrintStreamDecorator to prevent interlacing of output.
    // We don't log immediately so we avoid locking the console handler to avoid deadlocks.
    synchronized (console.getStdOut()) {
      synchronized (console.getStdErr()) {
        console.getStdErr().getRawStream().println(output);
      }
    }
  }

  private class RunningTarget {
    private final AbstractBuckEvent startEvent;
    private final String message;
    private final long startTime;
    private long lastRenderedTime;

    protected RunningTarget(AbstractBuckEvent startEvent, String message) {
      this.startEvent = startEvent;
      this.message = message;
      this.startTime = startEvent.getTimestamp();
      this.lastRenderedTime = this.startTime;
    }

    public void render(long now, long quiescentDuration, ImmutableList.Builder<String> lines) {
      if (now < lastRenderedTime + quiescentDuration) {
        return;
      }

      lastRenderedTime = now;

      String jobsInfo = "";
      if (ruleCount.isPresent()) {
        jobsInfo = String.format(locale, "%d/%d jobs", numRulesCompleted.get(), ruleCount.get());
      }
      lines.add(
          String.format(
              locale,
              "%s %s %s %s",
              message,
              jobsInfo,
              formatElapsedTime(now - startTime),
              startEvent.getValueString()));
    }

    public boolean isPairedWith(BuckEvent other) {
      return startEvent.isRelatedTo(other);
    }

    public void resume(long timestamp) {
      // So we don't overflow when calculating durations.
      lastRenderedTime = timestamp;
    }

    public void suspend() {
      lastRenderedTime = Long.MAX_VALUE - TimeUnit.DAYS.toMillis(1);
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this;
    }

    @Override
    public int hashCode() {
      return startEvent.hashCode();
    }
  }
}
