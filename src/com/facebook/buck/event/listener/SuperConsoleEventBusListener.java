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

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.distributed.thrift.RunId;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.DaemonEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildRuleCacheEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.rules.TestStatusMessageEvent;
import com.facebook.buck.rules.TestSummaryEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.autosparse.AutoSparseStateEvents;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.versioncontrol.SparseSummary;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import javax.annotation.concurrent.GuardedBy;

/** Console that provides rich, updating ansi output about the current build. */
public class SuperConsoleEventBusListener extends AbstractConsoleEventBusListener {
  /**
   * Maximum expected rendered line length so we can start with a decent size of line rendering
   * buffer.
   */
  private static final int EXPECTED_MAXIMUM_RENDERED_LINE_LENGTH = 128;

  private static final Logger LOG = Logger.get(SuperConsoleEventBusListener.class);

  @VisibleForTesting static final String EMOJI_BUNNY = "\uD83D\uDC07";
  @VisibleForTesting static final String EMOJI_SNAIL = "\uD83D\uDC0C";
  @VisibleForTesting static final String EMOJI_WHALE = "\uD83D\uDC33";
  @VisibleForTesting static final String EMOJI_BEACH = "\uD83C\uDFD6";
  @VisibleForTesting static final String EMOJI_DESERT = "\uD83C\uDFDD";
  @VisibleForTesting static final String EMOJI_ROLODEX = "\uD83D\uDCC7";

  @VisibleForTesting
  static final Optional<String> NEW_DAEMON_INSTANCE_MSG =
      createParsingMessage(EMOJI_WHALE, "New buck daemon");

  private final Locale locale;
  private final Function<Long, String> formatTimeFunction;
  private final Optional<WebServer> webServer;
  private final ConcurrentMap<Long, Optional<? extends TestSummaryEvent>>
      threadsToRunningTestSummaryEvent;
  private final ConcurrentMap<Long, Optional<? extends TestStatusMessageEvent>>
      threadsToRunningTestStatusMessageEvent;
  private final ConcurrentMap<Long, Optional<? extends LeafEvent>> threadsToRunningStep;

  private final ConcurrentLinkedQueue<ConsoleEvent> logEvents;

  private final ScheduledExecutorService renderScheduler;

  private final TestResultFormatter testFormatter;

  private final AtomicInteger numPassingTests = new AtomicInteger(0);
  private final AtomicInteger numFailingTests = new AtomicInteger(0);
  private final AtomicInteger numExcludedTests = new AtomicInteger(0);
  private final AtomicInteger numDisabledTests = new AtomicInteger(0);
  private final AtomicInteger numAssumptionViolationTests = new AtomicInteger(0);
  private final AtomicInteger numDryRunTests = new AtomicInteger(0);

  private final AtomicReference<TestRunEvent.Started> testRunStarted;
  private final AtomicReference<TestRunEvent.Finished> testRunFinished;

  private final ImmutableList.Builder<String> testReportBuilder = ImmutableList.builder();
  private final ImmutableList.Builder<TestStatusMessage> testStatusMessageBuilder =
      ImmutableList.builder();

  private final AtomicBoolean anyWarningsPrinted = new AtomicBoolean(false);
  private final AtomicBoolean anyErrorsPrinted = new AtomicBoolean(false);

  private final int defaultThreadLineLimit;
  private final int threadLineLimitOnWarning;
  private final int threadLineLimitOnError;
  private final boolean shouldAlwaysSortThreadsByTime;

  private final DateFormat dateFormat;
  private int lastNumLinesPrinted;

  private Optional<String> parsingStatus = Optional.empty();
  private Optional<SparseSummary> autoSparseSummary = Optional.empty();
  // Save if Watchman reported zero file changes in case we receive an ActionGraphCache hit. This
  // way the user can know that their changes, if they made any, were not picked up from Watchman.
  private boolean isZeroFileChanges = false;

  private final Object distBuildSlaveTrackerLock = new Object();

  @GuardedBy("distBuildSlaveTrackerLock")
  private final Map<RunId, BuildSlaveStatus> distBuildSlaveTracker;

  public SuperConsoleEventBusListener(
      SuperConsoleConfig config,
      Console console,
      Clock clock,
      TestResultSummaryVerbosity summaryVerbosity,
      ExecutionEnvironment executionEnvironment,
      Optional<WebServer> webServer,
      Locale locale,
      Path testLogPath,
      TimeZone timeZone) {
    super(console, clock, locale, executionEnvironment);
    this.locale = locale;
    this.formatTimeFunction = this::formatElapsedTime;
    this.webServer = webServer;
    this.threadsToRunningTestSummaryEvent =
        new ConcurrentHashMap<>(executionEnvironment.getAvailableCores());
    this.threadsToRunningTestStatusMessageEvent =
        new ConcurrentHashMap<>(executionEnvironment.getAvailableCores());
    this.threadsToRunningStep = new ConcurrentHashMap<>(executionEnvironment.getAvailableCores());

    this.logEvents = new ConcurrentLinkedQueue<>();

    this.renderScheduler =
        Executors.newScheduledThreadPool(
            1,
            new ThreadFactoryBuilder().setNameFormat(getClass().getSimpleName() + "-%d").build());
    this.testFormatter =
        new TestResultFormatter(
            console.getAnsi(),
            console.getVerbosity(),
            summaryVerbosity,
            locale,
            Optional.of(testLogPath));
    this.testRunStarted = new AtomicReference<>();
    this.testRunFinished = new AtomicReference<>();

    this.defaultThreadLineLimit = config.getThreadLineLimit();
    this.threadLineLimitOnWarning = config.getThreadLineLimitOnWarning();
    this.threadLineLimitOnError = config.getThreadLineLimitOnError();
    this.shouldAlwaysSortThreadsByTime = config.shouldAlwaysSortThreadsByTime();

    this.dateFormat = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]", this.locale);
    this.dateFormat.setTimeZone(timeZone);

    // Using LinkedHashMap because we want a predictable iteration order.
    this.distBuildSlaveTracker = new LinkedHashMap<>();
  }

  /** Schedules a runnable that updates the console output at a fixed interval. */
  public void startRenderScheduler(long renderInterval, TimeUnit timeUnit) {
    LOG.debug("Starting render scheduler (interval %d ms)", timeUnit.toMillis(renderInterval));
    renderScheduler.scheduleAtFixedRate(
        () -> {
          try {
            SuperConsoleEventBusListener.this.render();
          } catch (Error | RuntimeException e) {
            LOG.error(e, "Rendering exception");
            throw e;
          }
        }, /* initialDelay */
        renderInterval, /* period */
        renderInterval,
        timeUnit);
  }

  /** Shuts down the thread pool and cancels the fixed interval runnable. */
  private synchronized void stopRenderScheduler() {
    LOG.debug("Stopping render scheduler");
    renderScheduler.shutdownNow();
  }

  @VisibleForTesting
  synchronized void render() {
    LOG.verbose("Rendering");
    String lastRenderClear = clearLastRender();
    ImmutableList<String> lines = createRenderLinesAtTime(clock.currentTimeMillis());
    ImmutableList<String> logLines = createLogRenderLines();
    lastNumLinesPrinted = lines.size();

    // Synchronize on the DirtyPrintStreamDecorator to prevent interlacing of output.
    // We don't log immediately so we avoid locking the console handler to avoid deadlocks.
    boolean stdoutDirty;
    boolean stderrDirty;
    synchronized (console.getStdOut()) {
      synchronized (console.getStdErr()) {
        // If another source has written to stderr or stdout, stop rendering with the SuperConsole.
        // We need to do this to keep our updates consistent.
        stdoutDirty = console.getStdOut().isDirty();
        stderrDirty = console.getStdErr().isDirty();
        if (stdoutDirty || stderrDirty) {
          stopRenderScheduler();
        } else if (!lastRenderClear.isEmpty() || !lines.isEmpty() || !logLines.isEmpty()) {
          Iterable<String> renderedLines =
              Iterables.concat(
                  MoreIterables.zipAndConcat(logLines, Iterables.cycle("\n")),
                  ansi.asNoWrap(MoreIterables.zipAndConcat(lines, Iterables.cycle("\n"))));
          StringBuilder fullFrame = new StringBuilder(lastRenderClear);
          for (String part : renderedLines) {
            fullFrame.append(part);
          }
          console.getStdErr().getRawStream().print(fullFrame);
        }
      }
    }
    if (stdoutDirty || stderrDirty) {
      LOG.debug(
          "Stopping console output (stdout dirty %s, stderr dirty %s).", stdoutDirty, stderrDirty);
    }
  }

  /**
   * Creates a list of lines to be rendered at a given time.
   *
   * @param currentTimeMillis The time in ms to use when computing elapsed times.
   */
  @VisibleForTesting
  ImmutableList<String> createRenderLinesAtTime(long currentTimeMillis) {
    ImmutableList.Builder<String> lines = ImmutableList.builder();

    // If we have not yet started processing the BUCK files, show parse times
    if (parseStarted.isEmpty() && parseFinished.isEmpty()) {
      logEventPair(
          "PARSING BUCK FILES",
          /* suffix */ Optional.empty(),
          currentTimeMillis,
          /* offsetMs */ 0L,
          projectBuildFileParseStarted,
          projectBuildFileParseFinished,
          getEstimatedProgressOfProcessingBuckFiles(),
          lines);
    }

    long parseTime =
        logEventPair(
            "PROCESSING BUCK FILES",
            /* suffix */ parsingStatus,
            currentTimeMillis,
            /* offsetMs */ 0L,
            buckFilesProcessing.values(),
            getEstimatedProgressOfProcessingBuckFiles(),
            lines);

    logEventPair(
        "GENERATING PROJECT",
        Optional.empty(),
        currentTimeMillis,
        /* offsetMs */ 0L,
        projectGenerationStarted,
        projectGenerationFinished,
        getEstimatedProgressOfGeneratingProjectFiles(),
        lines);

    logEventPair(
        "REFRESHING SPARSE CHECKOUT",
        createAutoSparseStatusMessage(autoSparseSummary),
        currentTimeMillis,
        /* offsetMs */ 0L,
        autoSparseState.values(),
        /* progress*/ Optional.empty(),
        lines);

    // If parsing has not finished, then there is no build rule information to print yet.
    if (buildStarted == null || parseTime == UNFINISHED_EVENT_PAIR) {
      return lines.build();
    }

    int maxThreadLines = defaultThreadLineLimit;
    if (anyWarningsPrinted.get() && threadLineLimitOnWarning < maxThreadLines) {
      maxThreadLines = threadLineLimitOnWarning;
    }
    if (anyErrorsPrinted.get() && threadLineLimitOnError < maxThreadLines) {
      maxThreadLines = threadLineLimitOnError;
    }

    if (distBuildStarted != null) {
      long distBuildMs =
          logEventPair(
              "DISTBUILD",
              getOptionalDistBuildLineSuffix(),
              currentTimeMillis,
              0,
              this.distBuildStarted,
              this.distBuildFinished,
              getApproximateDistBuildProgress(),
              lines);

      if (distBuildMs == UNFINISHED_EVENT_PAIR) {
        MultiStateRenderer renderer;
        synchronized (distBuildSlaveTrackerLock) {
          renderer =
              new DistBuildSlaveStateRenderer(
                  ansi, currentTimeMillis, ImmutableList.copyOf(distBuildSlaveTracker.values()));
        }
        renderLines(renderer, lines, maxThreadLines, shouldAlwaysSortThreadsByTime);

        // We don't want to print anything else while dist-build is going on.
        return lines.build();
      }
    }

    // TODO(shivanker): Add a similar source file upload line for distributed build.
    lines.add(getNetworkStatsLine(buildFinished));

    // Check to see if the build encompasses the time spent parsing. This is true for runs of
    // buck build but not so for runs of e.g. buck project. If so, subtract parse times
    // from the build time.
    long buildStartedTime = buildStarted.getTimestamp();
    long buildFinishedTime =
        buildFinished != null ? buildFinished.getTimestamp() : currentTimeMillis;
    Collection<EventPair> processingEvents =
        getEventsBetween(buildStartedTime, buildFinishedTime, buckFilesProcessing.values());
    long offsetMs = getTotalCompletedTimeFromEventPairs(processingEvents);

    long totalBuildMs =
        logEventPair(
            "BUILDING",
            getOptionalBuildLineSuffix(),
            currentTimeMillis,
            offsetMs, // parseTime,
            this.buildStarted,
            this.buildFinished,
            getApproximateBuildProgress(),
            lines);

    // If the Daemon is running and serving web traffic, print the URL to the Chrome Trace.
    getBuildTraceURLLine(lines);

    if (totalBuildMs == UNFINISHED_EVENT_PAIR) {
      MultiStateRenderer renderer =
          new BuildThreadStateRenderer(
              ansi,
              formatTimeFunction,
              currentTimeMillis,
              threadsToRunningStep,
              buildRuleThreadTracker);
      renderLines(renderer, lines, maxThreadLines, shouldAlwaysSortThreadsByTime);
    }

    long testRunTime =
        logEventPair(
            "TESTING",
            renderTestSuffix(),
            currentTimeMillis,
            0, /* offsetMs */
            testRunStarted.get(),
            testRunFinished.get(),
            Optional.empty(),
            lines);

    if (testRunTime == UNFINISHED_EVENT_PAIR) {
      MultiStateRenderer renderer =
          new TestThreadStateRenderer(
              ansi,
              formatTimeFunction,
              currentTimeMillis,
              threadsToRunningTestSummaryEvent,
              threadsToRunningTestStatusMessageEvent,
              threadsToRunningStep,
              buildRuleThreadTracker);
      renderLines(renderer, lines, maxThreadLines, shouldAlwaysSortThreadsByTime);
    }

    logEventPair(
        "INSTALLING",
        /* suffix */ Optional.empty(),
        currentTimeMillis,
        0L,
        installStarted,
        installFinished,
        Optional.empty(),
        lines);

    logHttpCacheUploads(lines);
    return lines.build();
  }

  private void getBuildTraceURLLine(ImmutableList.Builder<String> lines) {
    if (buildFinished != null && webServer.isPresent()) {
      Optional<Integer> port = webServer.get().getPort();
      if (port.isPresent()) {
        lines.add(
            String.format(
                locale,
                "    Details: http://localhost:%s/trace/%s",
                port.get(),
                buildFinished.getBuildId()));
      }
    }
  }

  private Optional<String> getOptionalDistBuildLineSuffix() {
    String parseLine;
    List<String> columns = new ArrayList<>();

    synchronized (distBuildStatusLock) {
      if (!distBuildStatus.isPresent()) {
        columns.add("STATUS: INIT");
      } else {
        columns.add("STATUS: " + distBuildStatus.get().getStatus());

        int totalUploadErrorsCount = 0;
        ImmutableList.Builder<CacheRateStatsKeeper.CacheRateStatsUpdateEvent> slaveCacheStats =
            new ImmutableList.Builder<>();

        for (BuildSlaveStatus slaveStatus : distBuildStatus.get().getSlaveStatuses()) {
          totalUploadErrorsCount += slaveStatus.getHttpArtifactUploadsFailureCount();

          if (slaveStatus.isSetCacheRateStats()) {
            slaveCacheStats.add(
                CacheRateStatsKeeper.getCacheRateStatsUpdateEventFromSerializedStats(
                    slaveStatus.getCacheRateStats()));
          }
        }

        CacheRateStatsKeeper.CacheRateStatsUpdateEvent aggregatedCacheStats =
            CacheRateStatsKeeper.getAggregatedCacheRateStats(slaveCacheStats.build());

        if (aggregatedCacheStats.getTotalRulesCount() != 0) {
          columns.add(
              String.format(
                  "%d [%.1f%%] CACHE MISS",
                  aggregatedCacheStats.getCacheMissCount(),
                  aggregatedCacheStats.getCacheMissRate()));

          if (aggregatedCacheStats.getCacheErrorCount() != 0) {
            columns.add(
                String.format(
                    "%d [%.1f%%] CACHE ERRORS",
                    aggregatedCacheStats.getCacheErrorCount(),
                    aggregatedCacheStats.getCacheErrorRate()));
          }
        }

        if (totalUploadErrorsCount > 0) {
          columns.add(String.format("%d UPLOAD ERRORS", totalUploadErrorsCount));
        }

        if (distBuildStatus.get().getMessage().isPresent()) {
          columns.add("[" + distBuildStatus.get().getMessage().get() + "]");
        }
      }
    }

    parseLine = "(" + Joiner.on(", ").join(columns) + ")";
    return Strings.isNullOrEmpty(parseLine) ? Optional.empty() : Optional.of(parseLine);
  }

  /** Adds log messages for rendering. */
  @VisibleForTesting
  ImmutableList<String> createLogRenderLines() {
    ImmutableList.Builder<String> logEventLinesBuilder = ImmutableList.builder();
    ConsoleEvent logEvent;
    while ((logEvent = logEvents.poll()) != null) {
      formatConsoleEvent(logEvent, logEventLinesBuilder);
      if (logEvent.getLevel().equals(Level.WARNING)) {
        anyWarningsPrinted.set(true);
      } else if (logEvent.getLevel().equals(Level.SEVERE)) {
        anyErrorsPrinted.set(true);
      }
    }
    return logEventLinesBuilder.build();
  }

  public void renderLines(
      MultiStateRenderer renderer,
      ImmutableList.Builder<String> lines,
      int maxLines,
      boolean alwaysSortByTime) {
    int threadCount = renderer.getExecutorCount();
    int fullLines = threadCount;
    boolean useCompressedLine = false;
    if (threadCount > maxLines) {
      // One line will be used for the remaining threads that don't get their own line.
      fullLines = maxLines - 1;
      useCompressedLine = true;
    }
    int threadsWithShortStatus = threadCount - fullLines;
    boolean sortByTime = alwaysSortByTime || useCompressedLine;
    ImmutableList<Long> threadIds = renderer.getSortedExecutorIds(sortByTime);
    StringBuilder lineBuilder = new StringBuilder(EXPECTED_MAXIMUM_RENDERED_LINE_LENGTH);
    for (int i = 0; i < fullLines; ++i) {
      long threadId = threadIds.get(i);
      lineBuilder.delete(0, lineBuilder.length());
      lines.add(renderer.renderStatusLine(threadId, lineBuilder));
    }
    if (useCompressedLine) {
      lineBuilder.delete(0, lineBuilder.length());
      lineBuilder.append(" |=> ");
      lineBuilder.append(threadsWithShortStatus);
      if (fullLines == 0) {
        lineBuilder.append(String.format(" %s:", renderer.getExecutorCollectionLabel()));
      } else {
        lineBuilder.append(String.format(" MORE %s:", renderer.getExecutorCollectionLabel()));
      }
      for (int i = fullLines; i < threadIds.size(); ++i) {
        long threadId = threadIds.get(i);
        lineBuilder.append(" ");
        lineBuilder.append(renderer.renderShortStatus(threadId));
      }
      lines.add(lineBuilder.toString());
    }
  }

  private Optional<String> renderTestSuffix() {
    int testPassesVal = numPassingTests.get();
    int testFailuresVal = numFailingTests.get();
    int testSkipsVal =
        numDisabledTests.get()
            + numAssumptionViolationTests.get()
            +
            // don't count: numExcludedTests.get() +
            numDryRunTests.get();
    if (testSkipsVal > 0) {
      return Optional.of(
          String.format(
              locale, "(%d PASS/%d SKIP/%d FAIL)", testPassesVal, testSkipsVal, testFailuresVal));
    } else if (testPassesVal > 0 || testFailuresVal > 0) {
      return Optional.of(
          String.format(locale, "(%d PASS/%d FAIL)", testPassesVal, testFailuresVal));
    } else {
      return Optional.empty();
    }
  }

  /**
   * @return A string of ansi characters that will clear the last set of lines printed by {@link
   *     SuperConsoleEventBusListener#createRenderLinesAtTime(long)}.
   */
  private String clearLastRender() {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < lastNumLinesPrinted; ++i) {
      result.append(ansi.cursorPreviousLine(1));
      result.append(ansi.clearLine());
    }
    return result.toString();
  }

  @Override
  @Subscribe
  public void autoSparseStateSparseRefreshFinished(
      AutoSparseStateEvents.SparseRefreshFinished finished) {
    super.autoSparseStateSparseRefreshFinished(finished);
    autoSparseSummary =
        Optional.of(
            autoSparseSummary
                .map(s -> s.combineSummaries(finished.summary))
                .orElse(finished.summary));
  }

  @Override
  @Subscribe
  public void buildRuleStarted(BuildRuleEvent.Started started) {
    super.buildRuleStarted(started);
  }

  @Override
  @Subscribe
  public void buildRuleSuspended(BuildRuleEvent.Suspended suspended) {
    super.buildRuleSuspended(suspended);
  }

  @Override
  @Subscribe
  public void buildRuleResumed(BuildRuleEvent.Resumed resumed) {
    super.buildRuleResumed(resumed);
  }

  @Subscribe
  public void stepStarted(StepEvent.Started started) {
    threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void stepFinished(StepEvent.Finished finished) {
    threadsToRunningStep.put(finished.getThreadId(), Optional.empty());
  }

  @Override
  @Subscribe
  public void onDistBuildStatusEvent(DistBuildStatusEvent event) {
    super.onDistBuildStatusEvent(event);
    synchronized (distBuildSlaveTrackerLock) {
      for (BuildSlaveStatus status : event.getStatus().getSlaveStatuses()) {
        distBuildSlaveTracker.put(status.runId, status);
      }
    }
  }

  @Subscribe
  public void artifactCacheStarted(ArtifactCacheEvent.Started started) {
    if (started.getInvocationType() == ArtifactCacheEvent.InvocationType.SYNCHRONOUS) {
      threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
    }
  }

  @Subscribe
  public void artifactCacheFinished(ArtifactCacheEvent.Finished finished) {
    if (finished.getInvocationType() == ArtifactCacheEvent.InvocationType.SYNCHRONOUS) {
      threadsToRunningStep.put(finished.getThreadId(), Optional.empty());
    }
  }

  @Subscribe
  public void cacheCheckStarted(BuildRuleCacheEvent.CacheStepStarted started) {
    threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void cacheCheckFinished(BuildRuleCacheEvent.CacheStepFinished finished) {
    threadsToRunningStep.put(finished.getThreadId(), Optional.empty());
  }

  @Subscribe
  public void artifactCompressionStarted(ArtifactCompressionEvent.Started started) {
    threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void artifactCompressionFinished(ArtifactCompressionEvent.Finished finished) {
    threadsToRunningStep.put(finished.getThreadId(), Optional.empty());
  }

  @Subscribe
  public void testRunStarted(TestRunEvent.Started event) {
    boolean set = testRunStarted.compareAndSet(null, event);
    Preconditions.checkState(set, "Test run should not start while test run in progress");
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    testFormatter.runStarted(
        builder,
        event.isRunAllTests(),
        event.getTestSelectorList(),
        event.shouldExplainTestSelectorList(),
        event.getTargetNames(),
        TestResultFormatter.FormatMode.AFTER_TEST_RUN);
    synchronized (testReportBuilder) {
      testReportBuilder.addAll(builder.build());
    }
  }

  @Subscribe
  public void testRunFinished(TestRunEvent.Finished finished) {
    boolean set = testRunFinished.compareAndSet(null, finished);
    Preconditions.checkState(set, "Test run should not finish after test run already finished");

    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (TestResults results : finished.getResults()) {
      testFormatter.reportResult(builder, results);
    }
    ImmutableList<TestStatusMessage> testStatusMessages;
    synchronized (testStatusMessageBuilder) {
      testStatusMessages = testStatusMessageBuilder.build();
    }
    testFormatter.runComplete(builder, finished.getResults(), testStatusMessages);
    String testOutput;
    synchronized (testReportBuilder) {
      testReportBuilder.addAll(builder.build());
      testOutput = Joiner.on('\n').join(testReportBuilder.build());
    }
    // We're about to write to stdout, so make sure we render the final frame before we do.
    render();
    synchronized (console.getStdOut()) {
      console.getStdOut().println(testOutput);
    }
  }

  @Subscribe
  public void testStatusMessageStarted(TestStatusMessageEvent.Started started) {
    threadsToRunningTestStatusMessageEvent.put(started.getThreadId(), Optional.of(started));
    synchronized (testStatusMessageBuilder) {
      testStatusMessageBuilder.add(started.getTestStatusMessage());
    }
  }

  @Subscribe
  public void testStatusMessageFinished(TestStatusMessageEvent.Finished finished) {
    threadsToRunningTestStatusMessageEvent.put(finished.getThreadId(), Optional.empty());
    synchronized (testStatusMessageBuilder) {
      testStatusMessageBuilder.add(finished.getTestStatusMessage());
    }
  }

  @Subscribe
  public void testSummaryStarted(TestSummaryEvent.Started started) {
    threadsToRunningTestSummaryEvent.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void testSummaryFinished(TestSummaryEvent.Finished finished) {
    threadsToRunningTestSummaryEvent.put(finished.getThreadId(), Optional.empty());
    TestResultSummary testResult = finished.getTestResultSummary();
    ResultType resultType = testResult.getType();
    switch (resultType) {
      case SUCCESS:
        numPassingTests.incrementAndGet();
        break;
      case FAILURE:
        numFailingTests.incrementAndGet();
        // We don't use TestResultFormatter.reportResultSummary() here since that also
        // includes the stack trace and stdout/stderr.
        logEvents.add(
            ConsoleEvent.severe(
                String.format(
                    locale,
                    "%s %s %s: %s",
                    testResult.getType().toString(),
                    testResult.getTestCaseName(),
                    testResult.getTestName(),
                    testResult.getMessage())));
        break;
      case ASSUMPTION_VIOLATION:
        numAssumptionViolationTests.incrementAndGet();
        break;
      case DISABLED:
        numDisabledTests.incrementAndGet();
        break;
      case DRY_RUN:
        numDryRunTests.incrementAndGet();
        break;
      case EXCLUDED:
        numExcludedTests.incrementAndGet();
        break;
    }
  }

  @Subscribe
  public void logEvent(ConsoleEvent event) {
    logEvents.add(event);
  }

  @Override
  public void printSevereWarningDirectly(String line) {
    logEvents.add(ConsoleEvent.severe(line));
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void actionGraphCacheHit(ActionGraphEvent.Cache.Hit event) {
    parsingStatus =
        isZeroFileChanges
            ? createParsingMessage(EMOJI_BEACH, "(Watchman reported no changes)")
            : createParsingMessage(EMOJI_BUNNY, "");
  }

  @Subscribe
  public void watchmanOverflow(WatchmanStatusEvent.Overflow event) {
    parsingStatus = createParsingMessage(EMOJI_SNAIL, event.getReason());
  }

  @Subscribe
  public void watchmanFileCreation(WatchmanStatusEvent.FileCreation event) {
    parsingStatus = createParsingMessage(EMOJI_SNAIL, "File added: " + event.getFilename());
  }

  @Subscribe
  public void watchmanFileDeletion(WatchmanStatusEvent.FileDeletion event) {
    parsingStatus = createParsingMessage(EMOJI_SNAIL, "File removed: " + event.getFilename());
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void watchmanZeroFileChanges(WatchmanStatusEvent.ZeroFileChanges event) {
    isZeroFileChanges = true;
    parsingStatus = createParsingMessage(EMOJI_BEACH, "Watchman reported no changes");
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void daemonNewInstance(DaemonEvent.NewDaemonInstance event) {
    parsingStatus = NEW_DAEMON_INSTANCE_MSG;
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void symlinkInvalidation(ParsingEvent.SymlinkInvalidation event) {
    parsingStatus = createParsingMessage(EMOJI_WHALE, "Symlink caused cache invalidation");
  }

  @Subscribe
  public void envVariableChange(ParsingEvent.EnvVariableChange event) {
    parsingStatus =
        createParsingMessage(EMOJI_SNAIL, "Environment variable changes: " + event.getDiff());
  }

  @VisibleForTesting
  static Optional<String> createParsingMessage(String emoji, String reason) {
    if (Charset.defaultCharset().equals(Charsets.UTF_8)) {
      return Optional.of(emoji + "  " + reason);
    } else {
      if (emoji.equals(EMOJI_BUNNY)) {
        return Optional.of("(FAST)");
      } else {
        return Optional.of("(SLOW) " + reason);
      }
    }
  }

  static Optional<String> createAutoSparseStatusMessage(Optional<SparseSummary> summary) {
    if (!summary.isPresent()) {
      return Optional.empty();
    }
    SparseSummary sparse_summary = summary.get();
    // autosparse only ever exports include rules, we are only interested in added include rules and
    // the files added count.
    if (sparse_summary.getIncludeRulesAdded() == 0 && sparse_summary.getFilesAdded() == 0) {
      return createParsingMessage(EMOJI_DESERT, "Working copy size unchanged");
    }
    return createParsingMessage(
        EMOJI_ROLODEX,
        String.format(
            "%d new sparse rules imported, %d files added to the working copy",
            sparse_summary.getIncludeRulesAdded(), sparse_summary.getFilesAdded()));
  }

  @VisibleForTesting
  Optional<String> getParsingStatus() {
    return parsingStatus;
  }

  @Override
  public synchronized void close() throws IOException {
    super.close();
    stopRenderScheduler();
    render(); // Ensure final frame is rendered.
  }
}
