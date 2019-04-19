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
import com.facebook.buck.distributed.thrift.BuildSlaveInfo;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.listener.interfaces.AdditionalConsoleLineProvider;
import com.facebook.buck.event.listener.stats.stampede.DistBuildStatsTracker;
import com.facebook.buck.event.listener.util.EventInterval;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.test.config.TestResultSummaryVerbosity;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.timing.Clock;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * Implementation of {@code AbstractConsoleEventBusListener} for terminals that don't support ansi.
 */
public class SimpleConsoleEventBusListener extends AbstractConsoleEventBusListener {
  private final Locale locale;
  private final BuildId buildId;
  private final Optional<String> buildDetailsTemplate;
  private final TestResultFormatter testFormatter;
  private final ImmutableList.Builder<TestStatusMessage> testStatusMessageBuilder =
      ImmutableList.builder();
  private final boolean hideSucceededRules;
  public final ImmutableList<AdditionalConsoleLineProvider> buildFinishedLineProvider;

  public SimpleConsoleEventBusListener(
      RenderingConsole console,
      Clock clock,
      TestResultSummaryVerbosity summaryVerbosity,
      boolean hideSucceededRules,
      int numberOfSlowRulesToShow,
      boolean showSlowRulesInConsole,
      Locale locale,
      Path testLogPath,
      ExecutionEnvironment executionEnvironment,
      BuildId buildId,
      boolean printBuildId,
      Optional<String> buildDetailsTemplate,
      ImmutableSet<String> buildDetailsCommands,
      Optional<String> reSessionIDInfo,
      ImmutableList<AdditionalConsoleLineProvider> buildFinishedLineProvider) {
    super(
        console,
        clock,
        locale,
        executionEnvironment,
        true,
        numberOfSlowRulesToShow,
        showSlowRulesInConsole,
        buildDetailsCommands);
    Preconditions.checkArgument(!console.getVerbosity().isSilent());
    this.locale = locale;
    this.buildId = buildId;
    this.buildDetailsTemplate = buildDetailsTemplate;
    this.hideSucceededRules = hideSucceededRules;
    this.buildFinishedLineProvider = buildFinishedLineProvider;

    this.testFormatter =
        new TestResultFormatter(
            console.getAnsi(),
            console.getVerbosity(),
            summaryVerbosity,
            locale,
            Optional.of(testLogPath));

    if (printBuildId) {
      printLines(ImmutableList.of(getBuildLogLine(buildId)));
    }

    if (reSessionIDInfo.isPresent()) {
      printLines(ImmutableList.of(String.format("[RE] SessionInfo: [%s].", reSessionIDInfo.get())));
    }

    this.parseStats.registerListener(this::parseFinished);
    this.networkStatsTracker.registerListener(this::onCacheUploadsFinished);
    this.distStatsTracker.registerListener(
        new DistBuildStatsTracker.Listener() {
          @Override
          public void onWorkerJoined(BuildSlaveInfo slaveInfo) {
            printLine(
                "STAMPEDE WORKER [%s][%s] JOINED BUILD WITH STATUS [%s]",
                slaveInfo.getHostname(),
                slaveInfo.getBuildSlaveRunId().getId(),
                slaveInfo.getStatus().name());
          }

          @Override
          public void onWorkerStatusChanged(BuildSlaveInfo slaveInfo) {
            printLine(
                "STAMPEDE WORKER [%s][%s] CHANGED STATUS TO [%s]",
                slaveInfo.getHostname(),
                slaveInfo.getBuildSlaveRunId().getId(),
                slaveInfo.getStatus().name());
          }

          @Override
          public void onDistBuildStateChanged(BuildStatus distBuildState) {
            printLine("STAMPEDE JOB STATUS CHANGED TO [%s]", distBuildState);
          }
        });
  }

  private void parseFinished() {
    printLine(
        "PARSING BUCK FILES: FINISHED IN %s",
        formatElapsedTime(parseStats.getInterval().getElapsedTimeMs()));
  }

  private void onCacheUploadsFinished() {
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    logHttpCacheUploads(lines);
    printLines(lines);
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
  public void actionGraphFinished(ActionGraphEvent.Finished finished) {
    super.actionGraphFinished(finished);
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    addLineFromEvents(
        "CREATING ACTION GRAPH",
        /* suffix */ Optional.empty(),
        clock.currentTimeMillis(),
        actionGraphEvents.values(),
        Optional.empty(),
        Optional.empty(),
        lines);
    printLines(lines);
  }

  @Override
  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    super.buildFinished(finished);

    ImmutableList.Builder<String> lines = ImmutableList.builder();

    for (AdditionalConsoleLineProvider lineProvider : buildFinishedLineProvider) {
      lines.addAll(lineProvider.createConsoleLinesAtTime(finished.getTimestampMillis()));
    }

    lines.add(getNetworkStatsLine(finished));

    long currentMillis = clock.currentTimeMillis();
    long buildStartedTime =
        buildStarted != null ? buildStarted.getTimestampMillis() : Long.MAX_VALUE;
    long buildFinishedTime =
        buildFinished != null ? buildFinished.getTimestampMillis() : currentMillis;
    Collection<EventInterval> processingEvents =
        getEventsBetween(buildStartedTime, buildFinishedTime, actionGraphEvents.values());
    long offsetMs = getTotalCompletedTimeFromEventIntervals(processingEvents);
    logEventInterval(
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
    if (networkStatsTracker.haveUploadsStarted() && !networkStatsTracker.haveUploadsFinished()) {
      lines.add("WAITING FOR HTTP CACHE UPLOADS " + httpStatus);
    }

    showTopSlowBuildRules(lines);

    lines.add(finished.getExitCode() == ExitCode.SUCCESS ? "BUILD SUCCEEDED" : "BUILD FAILED");

    printLines(lines);
  }

  @Override
  @Subscribe
  public void commandFinished(CommandEvent.Finished event) {
    if (buildDetailsTemplate.isPresent() && buildDetailsCommands.contains(event.getCommandName())) {
      printLines(ImmutableList.of(getBuildDetailsLine(buildId, buildDetailsTemplate.get())));
    }
  }

  @Override
  @Subscribe
  public void installFinished(InstallEvent.Finished finished) {
    super.installFinished(finished);
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    logEventInterval(
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
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    lines.addAll(formatConsoleEvent(event));
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
    ImmutableList.Builder<String> lines = ImmutableList.builder();
    testFormatter.reportResult(lines, event.getResults());
    printLines(lines);
  }

  @Override
  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    super.buildRuleFinished(finished);

    if (finished.getStatus() != BuildRuleStatus.SUCCESS) {
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
      console.logLines(line);
    }
  }

  @Subscribe
  public void testStatusMessageStarted(TestStatusMessageEvent.Started started) {
    synchronized (testStatusMessageBuilder) {
      testStatusMessageBuilder.add(started.getTestStatusMessage());
    }
  }

  @Subscribe
  public void testStatusMessageFinished(TestStatusMessageEvent.Finished finished) {
    synchronized (testStatusMessageBuilder) {
      testStatusMessageBuilder.add(finished.getTestStatusMessage());
    }
  }

  @Subscribe
  public void onDistBuildCreatedEvent(DistBuildCreatedEvent distBuildCreatedEvent) {
    ImmutableList.Builder<String> lines =
        ImmutableList.<String>builder().add(distBuildCreatedEvent.getConsoleLogLine());
    printLines(lines);
  }

  private void printLine(String format, Object... args) {
    printLines(ImmutableList.of(String.format(format, args)));
  }

  private void printLines(ImmutableList.Builder<String> lines) {
    printLines(lines.build());
  }

  private void printLines(ImmutableList<String> lines) {
    // Print through the {@code DirtyPrintStreamDecorator} so printing from the simple console
    // is considered to dirty stderr and stdout and so it gets synchronized to avoid interlacing
    // output.
    if (lines.isEmpty()) {
      return;
    }
    if (console.getVerbosity().shouldPrintStandardInformation()) {
      console.logLines(lines);
    }
  }

  @Override
  public boolean displaysEstimatedProgress() {
    return true;
  }
}
