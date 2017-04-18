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
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.log.Logger;
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
import java.util.Iterator;
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

  /**
   * Wait this long (at least) before printing that a task is still running.
   */
  private static final long LONG_RUNNING_TASK_HEARTBEAT = TimeUnit.SECONDS.toMillis(15);

  private final Locale locale;
  private final AtomicLong parseTime;
  private final TestResultFormatter testFormatter;
  private final ImmutableList.Builder<TestStatusMessage> testStatusMessageBuilder =
      ImmutableList.builder();
  private final ScheduledExecutorService renderScheduler;
  private final Set<RunningTarget> runningTasks = new HashSet<>();

  public SimpleConsoleEventBusListener(
      Console console,
      Clock clock,
      TestResultSummaryVerbosity summaryVerbosity,
      Locale locale,
      Path testLogPath,
      ExecutionEnvironment executionEnvironment) {
    super(console, clock, locale, executionEnvironment);
    this.locale = locale;
    this.parseTime = new AtomicLong(0);

    this.testFormatter = new TestResultFormatter(
        console.getAnsi(),
        console.getVerbosity(),
        summaryVerbosity,
        locale,
        Optional.of(testLogPath));

    this.renderScheduler = Executors.newScheduledThreadPool(1,
        new ThreadFactoryBuilder().setNameFormat(getClass().getSimpleName() + "-%d").build());
  }

  public void startRenderScheduler(long renderInterval, TimeUnit timeUnit) {
    LOG.debug("Starting render scheduler (interval %d ms)", timeUnit.toMillis(renderInterval));
    renderScheduler.scheduleAtFixedRate(new Runnable() {
      @Override
      public void run() {
        try {
          SimpleConsoleEventBusListener.this.render();
        } catch (Error | RuntimeException e) {
          LOG.error(e, "Rendering exception");
          throw e;
        }
      }
    }, /* initialDelay */ renderInterval, /* period */ renderInterval, timeUnit);
  }

  @Override
  public void parseStarted(ParseEvent.Started started) {
    super.parseStarted(started);
    synchronized (runningTasks) {
      runningTasks.clear();  // We can only have one thing running, right?
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
    this.parseTime.set(logEventPair(
        "PARSING BUCK FILES",
        /* suffix */ Optional.empty(),
        clock.currentTimeMillis(),
        0L,
        buckFilesProcessing.values(),
        getEstimatedProgressOfProcessingBuckFiles(),
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
    long currentMillis = clock.currentTimeMillis();
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    long buildStartedTime = buildStarted != null
        ? buildStarted.getTimestamp()
        : Long.MAX_VALUE;
    long buildFinishedTime = buildFinished != null
        ? buildFinished.getTimestamp()
        : currentMillis;
    Collection<EventPair> processingEvents = getEventsBetween(buildStartedTime,
        buildFinishedTime,
        buckFilesProcessing.values());
    long offsetMs = getTotalCompletedTimeFromEventPairs(processingEvents);
    logEventPair(
        "BUILDING",
        /* suffix */ Optional.empty(),
        currentMillis,
        offsetMs,
        buildStarted,
        buildFinished,
        getApproximateBuildProgress(),
        lines);

    String httpStatus = renderHttpUploads();
    if (httpArtifactUploadsScheduledCount.get() > 0) {
      lines.add("WAITING FOR HTTP CACHE UPLOADS " + httpStatus);
    }

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
        lines);
    printLines(lines);
  }

  @Subscribe
  public void logEvent(ConsoleEvent event) {
    if (console.getVerbosity().isSilent() &&
        !event.getLevel().equals(Level.WARNING) &&
        !event.getLevel().equals(Level.SEVERE)) {
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

    addRunningTarget(new RunningTarget(event, "STILL RUNNING"));
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
    // The running tasks are synchronized or safe to modify *sigh*
    synchronized (runningTasks) {
      Iterator<RunningTarget> tasks = runningTasks.iterator();
      while (tasks.hasNext()) {
        RunningTarget task =  tasks.next();
        if (task.isPairedWith(event)) {
          tasks.remove();
        }
      }
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
    addRunningTarget(new RunningTarget(started, "STILL BUILDING"));
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

    if (finished.getStatus() != BuildRuleStatus.SUCCESS ||
        console.getVerbosity().isSilent()) {
      return;
    }

    long timeToRender = finished.getDuration().getWallMillisDuration();

    String jobsInfo = "";
    if (ruleCount.isPresent()) {
      jobsInfo = String.format(
          locale,
          "%d/%d JOBS",
          numRulesCompleted.get(),
          ruleCount.get());
    }
    String line = String.format(
        locale,
        "%s %s %s %s",
        finished.getResultString(),
        jobsInfo,
        formatElapsedTime(timeToRender),
        finished.getBuildRule().getFullyQualifiedName());

    if (BUILT_LOCALLY.equals(finished.getSuccessType().orElse(null)) ||
        console.getVerbosity().shouldPrintBinaryRunInformation()) {
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

    LOG.verbose("Rendering");

    ImmutableList.Builder<String> lines = ImmutableList.builder();
    synchronized (runningTasks) {
      long now = System.currentTimeMillis();
      for (RunningTarget task : runningTasks) {
        task.render(now, LONG_RUNNING_TASK_HEARTBEAT, lines);
      }
    }

    // Synchronize on the DirtyPrintStreamDecorator to prevent interlacing of output.
    // We don't log immediately so we avoid locking the console handler to avoid deadlocks.
    synchronized (console.getStdOut()) {
      synchronized (console.getStdErr()) {
        StringBuilder output = new StringBuilder();
        for (String line : lines.build()) {
          output.append(line).append("\n");
        }
        console.getStdErr().getRawStream().print(output.toString());
      }
    }
  }

  private class RunningTarget {
    private final AbstractBuckEvent startEvent;
    private final String grumble;
    private final long startTime;
    private long lastRenderedTime;

    protected RunningTarget(AbstractBuckEvent startEvent, String grumble) {
      this.startEvent = startEvent;
      this.grumble = grumble;
      this.startTime = startEvent.getTimestamp();
      this.lastRenderedTime = this.startTime;
    }

    public void render(
        long now,
        long quiescentDuration,
        ImmutableList.Builder<String> lines) {
      if (now < lastRenderedTime + quiescentDuration) {
        return;
      }

      lastRenderedTime = now;

      String jobsInfo = "";
      if (ruleCount.isPresent()) {
        jobsInfo = String.format(
            locale,
            "%d/%d JOBS",
            numRulesCompleted.get(),
            ruleCount.get());
      }
      lines.add(String.format(
          locale,
          "%s %s %s %s",
          grumble,
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
