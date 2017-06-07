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
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
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
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.Subscribe;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Implementation of {@code AbstractConsoleEventBusListener} for terminals that don't support ansi.
 */
public class SimpleConsoleEventBusListener extends AbstractConsoleEventBusListener {
  private final Locale locale;
  private final AtomicLong parseTime;
  private final TestResultFormatter testFormatter;
  private final ImmutableList.Builder<TestStatusMessage> testStatusMessageBuilder =
      ImmutableList.builder();

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

    this.testFormatter =
        new TestResultFormatter(
            console.getAnsi(),
            console.getVerbosity(),
            summaryVerbosity,
            locale,
            Optional.of(testLogPath));
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
    long buildStartedTime = buildStarted != null ? buildStarted.getTimestamp() : Long.MAX_VALUE;
    long buildFinishedTime = buildFinished != null ? buildFinished.getTimestamp() : currentMillis;
    Collection<EventPair> processingEvents =
        getEventsBetween(buildStartedTime, buildFinishedTime, buckFilesProcessing.values());
    long offsetMs = getTotalCompletedTimeFromEventPairs(processingEvents);
    logEventPair(
        "BUILDING",
        getOptionalBuildLineSuffix(),
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

    lines.add(getNetworkStatsLine(finished));

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
    if (console.getVerbosity().isSilent()
        && !event.getLevel().equals(Level.WARNING)
        && !event.getLevel().equals(Level.SEVERE)) {
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
  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    super.buildRuleFinished(finished);

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

    if (BUILT_LOCALLY.equals(finished.getSuccessType().orElse(null))
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
}
