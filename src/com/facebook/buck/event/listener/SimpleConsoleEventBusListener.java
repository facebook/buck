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

import static com.facebook.buck.core.build.engine.BuildRuleSuccessType.BUILT_LOCALLY;

import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.test.event.IndividualTestEvent;
import com.facebook.buck.core.test.event.TestRunEvent;
import com.facebook.buck.core.test.event.TestStatusMessageEvent;
import com.facebook.buck.distributed.DistBuildCreatedEvent;
import com.facebook.buck.distributed.DistBuildRunEvent;
import com.facebook.buck.distributed.build_client.StampedeConsoleEvent;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.timing.Clock;
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
  private final boolean hideSucceededRules;

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
  }

  public static String getBuildLogLine(BuildId buildId) {
    return "Build UUID: " + buildId;
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

    String httpStatus = renderRemoteUploads();
    if (remoteArtifactUploadsScheduledCount.get() > 0) {
      lines.add("WAITING FOR HTTP CACHE UPLOADS " + httpStatus);
    }

    showTopSlowBuildRules(lines);

    lines.add(finished.getExitCode() == ExitCode.SUCCESS ? "BUILD SUCCEEDED" : "BUILD FAILED");

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

  @Subscribe
  public void logStampedeConsoleEvent(StampedeConsoleEvent event) {
    logEvent(event.getConsoleEvent());
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
      jobsInfo = String.format(locale, "%d/%d JOBS", numRulesCompleted.get(), ruleCount.getAsInt());
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
        ImmutableList.<String>builder().add(distBuildCreatedEvent.getConsoleLogLine());
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

  @Override
  public boolean displaysEstimatedProgress() {
    return true;
  }
}
