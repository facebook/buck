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
import com.facebook.buck.distributed.DistBuildCreatedEvent;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.StampedeLocalBuildStatusEvent;
import com.facebook.buck.distributed.build_client.DistBuildSuperConsoleEvent;
import com.facebook.buck.distributed.build_client.StampedeConsoleEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildSlaveStatus;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.DaemonEvent;
import com.facebook.buck.event.FlushConsoleEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.event.RuleKeyCalculationEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.rules.TestStatusMessageEvent;
import com.facebook.buck.rules.TestSummaryEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.timing.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import java.util.function.Function;
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
  private final long buildRuleMinimumDurationMillis;

  private final DateFormat dateFormat;
  private int lastNumLinesPrinted;

  private Optional<String> parsingStatus = Optional.empty();
  // Save if Watchman reported zero file changes in case we receive an ActionGraphCache hit. This
  // way the user can know that their changes, if they made any, were not picked up from Watchman.
  private boolean isZeroFileChanges = false;

  private final Object distBuildSlaveTrackerLock = new Object();

  private long minimumDurationMillisecondsToShowParse;
  private long minimumDurationMillisecondsToShowActionGraph;
  private long minimumDurationMillisecondsToShowWatchman;
  private boolean hideEmptyDownload;

  @GuardedBy("distBuildSlaveTrackerLock")
  private final Map<BuildSlaveRunId, BuildSlaveStatus> distBuildSlaveTracker;

  private volatile StampedeLocalBuildStatusEvent stampedeLocalBuildStatus =
      new StampedeLocalBuildStatusEvent("init");
  private volatile Optional<DistBuildSuperConsoleEvent> stampedeSuperConsoleEvent =
      Optional.empty();
  private Optional<String> stampedeIdLogLine = Optional.empty();

  private final Set<String> actionGraphCacheMessage = new HashSet<>();

  /** Maximum width of the terminal. */
  private final int outputMaxColumns;

  private final Optional<String> buildIdLine;

  public SuperConsoleEventBusListener(
      SuperConsoleConfig config,
      Console console,
      Clock clock,
      TestResultSummaryVerbosity summaryVerbosity,
      ExecutionEnvironment executionEnvironment,
      Optional<WebServer> webServer,
      Locale locale,
      Path testLogPath,
      TimeZone timeZone,
      Optional<BuildId> buildId) {
    this(
        config,
        console,
        clock,
        summaryVerbosity,
        executionEnvironment,
        webServer,
        locale,
        testLogPath,
        timeZone,
        500L,
        500L,
        1000L,
        true,
        buildId);
  }

  @VisibleForTesting
  public SuperConsoleEventBusListener(
      SuperConsoleConfig config,
      Console console,
      Clock clock,
      TestResultSummaryVerbosity summaryVerbosity,
      ExecutionEnvironment executionEnvironment,
      Optional<WebServer> webServer,
      Locale locale,
      Path testLogPath,
      TimeZone timeZone,
      long minimumDurationMillisecondsToShowParse,
      long minimumDurationMillisecondsToShowActionGraph,
      long minimumDurationMillisecondsToShowWatchman,
      boolean hideEmptyDownload,
      Optional<BuildId> buildId) {
    super(
        console,
        clock,
        locale,
        executionEnvironment,
        false,
        config.getNumberOfSlowRulesToShow(),
        config.shouldShowSlowRulesInConsole());
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
    this.buildRuleMinimumDurationMillis = config.getBuildRuleMinimumDurationMillis();
    this.minimumDurationMillisecondsToShowParse = minimumDurationMillisecondsToShowParse;
    this.minimumDurationMillisecondsToShowActionGraph =
        minimumDurationMillisecondsToShowActionGraph;
    this.minimumDurationMillisecondsToShowWatchman = minimumDurationMillisecondsToShowWatchman;
    this.hideEmptyDownload = hideEmptyDownload;

    this.dateFormat = new SimpleDateFormat("[yyyy-MM-dd HH:mm:ss.SSS]", this.locale);
    this.dateFormat.setTimeZone(timeZone);

    // Using LinkedHashMap because we want a predictable iteration order.
    this.distBuildSlaveTracker = new LinkedHashMap<>();

    int outputMaxColumns = 80;
    Optional<String> columnsStr = executionEnvironment.getenv("BUCK_TERM_COLUMNS");
    if (columnsStr.isPresent()) {
      try {
        outputMaxColumns = Integer.parseInt(columnsStr.get());
      } catch (NumberFormatException e) {
        LOG.debug(
            "the environment variable BUCK_TERM_COLUMNS did not contain a valid value: %s",
            columnsStr.get());
      }
    }
    this.outputMaxColumns = outputMaxColumns;
    this.buildIdLine =
        buildId.isPresent()
            ? Optional.of(SimpleConsoleEventBusListener.getBuildLogLine(buildId.get()))
            : Optional.empty();
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
    int previousNumLinesPrinted = lastNumLinesPrinted;
    ImmutableList<String> lines = createRenderLinesAtTime(clock.currentTimeMillis());
    ImmutableList<String> logLines = createLogRenderLines();
    lastNumLinesPrinted = lines.size();

    // Synchronize on the DirtyPrintStreamDecorator to prevent interlacing of output.
    // We don't log immediately so we avoid locking the console handler to avoid deadlocks.
    boolean stderrDirty;
    boolean stdoutDirty;
    synchronized (console.getStdErr()) {
      synchronized (console.getStdOut()) {
        // If another source has written to stderr, stop rendering with the SuperConsole.
        // We need to do this to keep our updates consistent. We don't do this with stdout
        // because we don't use it directly except in a couple of cases, where the
        // synchronization in DirtyPrintStreamDecorator should be sufficient
        stderrDirty = console.getStdErr().isDirty();
        stdoutDirty = console.getStdOut().isDirty();
        if (stderrDirty || stdoutDirty) {
          stopRenderScheduler();
        } else if (previousNumLinesPrinted != 0 || !lines.isEmpty() || !logLines.isEmpty()) {
          String fullFrame = renderFullFrame(logLines, lines, previousNumLinesPrinted);
          console.getStdErr().getRawStream().print(fullFrame);
        }
      }
    }
    if (stderrDirty) {
      LOG.debug("Stopping console output (stderr was dirty).");
    }
  }

  private String renderFullFrame(
      ImmutableList<String> logLines, ImmutableList<String> lines, int previousNumLinesPrinted) {
    int currentNumLines = lines.size();

    Iterable<String> renderedLines =
        Iterables.concat(
            MoreIterables.zipAndConcat(
                Iterables.cycle(ansi.clearLine()),
                logLines,
                Iterables.cycle(ansi.clearToTheEndOfLine() + "\n")),
            ansi.asNoWrap(
                MoreIterables.zipAndConcat(
                    Iterables.cycle(ansi.clearLine()),
                    lines,
                    Iterables.cycle(ansi.clearToTheEndOfLine() + "\n"))));

    // Number of lines remaining to clear because of old output once we displayed
    // the new output.
    int remainingLinesToClear =
        previousNumLinesPrinted > currentNumLines ? previousNumLinesPrinted - currentNumLines : 0;

    StringBuilder fullFrame = new StringBuilder();
    // We move the cursor back to the top.
    for (int i = 0; i < previousNumLinesPrinted; i++) {
      fullFrame.append(ansi.cursorPreviousLine(1));
    }
    // We display the new output.
    for (String part : renderedLines) {
      fullFrame.append(part);
    }
    // We clear the remaining lines of the old output.
    for (int i = 0; i < remainingLinesToClear; i++) {
      fullFrame.append(ansi.clearLine() + "\n");
    }
    // We move the cursor at the end of the new output.
    for (int i = 0; i < remainingLinesToClear; i++) {
      fullFrame.append(ansi.cursorPreviousLine(1));
    }
    return fullFrame.toString();
  }

  /**
   * Creates a list of lines to be rendered at a given time.
   *
   * @param currentTimeMillis The time in ms to use when computing elapsed times.
   */
  @VisibleForTesting
  public ImmutableList<String> createRenderLinesAtTime(long currentTimeMillis) {
    ImmutableList.Builder<String> lines = ImmutableList.builder();

    if (buildIdLine.isPresent()) {
      lines.add(buildIdLine.get());
    }

    logEventPair(
        "Processing filesystem changes",
        Optional.empty(),
        currentTimeMillis,
        /* offsetMs */ 0L,
        watchmanStarted,
        watchmanFinished,
        Optional.empty(),
        Optional.of(this.minimumDurationMillisecondsToShowWatchman),
        lines);

    long parseTime =
        logEventPair(
            "Parsing buck files",
            /* suffix */ Optional.empty(),
            currentTimeMillis,
            /* offsetMs */ 0L,
            buckFilesParsingEvents.values(),
            getEstimatedProgressOfParsingBuckFiles(),
            Optional.of(this.minimumDurationMillisecondsToShowParse),
            lines);

    long actionGraphTime =
        logEventPair(
            "Creating action graph",
            /* suffix */ Optional.empty(),
            currentTimeMillis,
            /* offsetMs */ 0L,
            actionGraphEvents.values(),
            getEstimatedProgressOfParsingBuckFiles(),
            Optional.of(this.minimumDurationMillisecondsToShowActionGraph),
            lines);

    logEventPair(
        "Generating project",
        Optional.empty(),
        currentTimeMillis,
        /* offsetMs */ 0L,
        projectGenerationStarted,
        projectGenerationFinished,
        getEstimatedProgressOfGeneratingProjectFiles(),
        Optional.empty(),
        lines);

    // If parsing has not finished, then there is no build rule information to print yet.
    if (buildStarted == null
        || parseTime == UNFINISHED_EVENT_PAIR
        || actionGraphTime == UNFINISHED_EVENT_PAIR) {
      return lines.build();
    }

    int maxThreadLines = defaultThreadLineLimit;
    if (anyWarningsPrinted.get() && threadLineLimitOnWarning < maxThreadLines) {
      maxThreadLines = threadLineLimitOnWarning;
    }
    if (anyErrorsPrinted.get() && threadLineLimitOnError < maxThreadLines) {
      maxThreadLines = threadLineLimitOnError;
    }

    String localBuildLinePrefix = "Building";

    if (stampedeSuperConsoleEvent.isPresent()) {
      localBuildLinePrefix = stampedeLocalBuildStatus.getLocalBuildLinePrefix();

      stampedeIdLogLine.ifPresent(lines::add);
      stampedeSuperConsoleEvent
          .get()
          .getMessage()
          .ifPresent(msg -> lines.add(ansi.asInformationText(msg)));
      long distBuildMs =
          logEventPair(
              "Distributed Build",
              getOptionalDistBuildLineSuffix(),
              currentTimeMillis,
              0,
              this.distBuildStarted,
              this.distBuildFinished,
              getApproximateDistBuildProgress(),
              Optional.empty(),
              lines);

      if (distBuildMs == UNFINISHED_EVENT_PAIR) {
        MultiStateRenderer renderer;
        synchronized (distBuildSlaveTrackerLock) {
          renderer =
              new DistBuildSlaveStateRenderer(
                  ansi, currentTimeMillis, ImmutableList.copyOf(distBuildSlaveTracker.values()));
        }

        renderLines(renderer, lines, maxThreadLines, true);
      }
    }

    if (networkStatsKeeper.getRemoteDownloadedArtifactsCount() > 0 || !this.hideEmptyDownload) {
      lines.add(getNetworkStatsLine(buildFinished));
    }

    // Check to see if the build encompasses the time spent parsing. This is true for runs of
    // buck build but not so for runs of e.g. buck project. If so, subtract parse times
    // from the build time.
    long buildStartedTime = buildStarted.getTimestamp();
    long buildFinishedTime =
        buildFinished != null ? buildFinished.getTimestamp() : currentTimeMillis;
    Collection<EventPair> filteredBuckFilesParsingEvents =
        getEventsBetween(buildStartedTime, buildFinishedTime, buckFilesParsingEvents.values());
    Collection<EventPair> filteredActionGraphEvents =
        getEventsBetween(buildStartedTime, buildFinishedTime, actionGraphEvents.values());
    long offsetMs =
        getTotalCompletedTimeFromEventPairs(filteredBuckFilesParsingEvents)
            + getTotalCompletedTimeFromEventPairs(filteredActionGraphEvents);

    long totalBuildMs =
        logEventPair(
            localBuildLinePrefix,
            getOptionalBuildLineSuffix(),
            currentTimeMillis,
            offsetMs, // parseTime,
            this.buildStarted,
            this.buildFinished,
            getApproximateLocalBuildProgress(),
            Optional.empty(),
            lines);

    // If the Daemon is running and serving web traffic, print the URL to the Chrome Trace.
    getBuildTraceURLLine(lines);
    getTotalTimeLine(lines);
    showTopSlowBuildRules(lines);

    if (totalBuildMs == UNFINISHED_EVENT_PAIR) {
      MultiStateRenderer renderer =
          new BuildThreadStateRenderer(
              ansi,
              formatTimeFunction,
              currentTimeMillis,
              outputMaxColumns,
              buildRuleMinimumDurationMillis,
              threadsToRunningStep,
              buildRuleThreadTracker);
      renderLines(renderer, lines, maxThreadLines, shouldAlwaysSortThreadsByTime);
    }

    long testRunTime =
        logEventPair(
            "Testing",
            renderTestSuffix(),
            currentTimeMillis,
            0, /* offsetMs */
            testRunStarted.get(),
            testRunFinished.get(),
            Optional.empty(),
            Optional.empty(),
            lines);

    if (testRunTime == UNFINISHED_EVENT_PAIR) {
      MultiStateRenderer renderer =
          new TestThreadStateRenderer(
              ansi,
              formatTimeFunction,
              currentTimeMillis,
              outputMaxColumns,
              threadsToRunningTestSummaryEvent,
              threadsToRunningTestStatusMessageEvent,
              threadsToRunningStep,
              buildRuleThreadTracker);
      renderLines(renderer, lines, maxThreadLines, shouldAlwaysSortThreadsByTime);
    }

    logEventPair(
        "Installing",
        /* suffix */ Optional.empty(),
        currentTimeMillis,
        0L,
        installStarted,
        installFinished,
        Optional.empty(),
        Optional.empty(),
        lines);

    logHttpCacheUploads(lines);
    return lines.build();
  }

  @SuppressWarnings("unused")
  private void getBuildTraceURLLine(ImmutableList.Builder<String> lines) {
    if (buildFinished != null && webServer.isPresent()) {
      Optional<Integer> port = webServer.get().getPort();
      if (port.isPresent()) {
        LOG.debug(
            "Build logs: http://localhost:%s/trace/%s", port.get(), buildFinished.getBuildId());
      }
    }
  }

  private void getTotalTimeLine(ImmutableList.Builder<String> lines) {
    if (projectGenerationStarted == null) {
      // project generation never started
      // we only output total time if build started and finished
      if (buildStarted != null && buildFinished != null) {
        long durationMs = buildFinished.getTimestamp() - buildStarted.getTimestamp();
        String finalLine = "  Total time: " + formatElapsedTime(durationMs);
        if (distBuildStarted != null) {
          // Stampede build. We need to print final status to reduce confusion from remote errors.
          finalLine += ". ";
          if (buildFinished.getExitCode() == ExitCode.SUCCESS) {
            finalLine += ansi.asGreenText("Build successful.");
          } else {
            finalLine += ansi.asErrorText("Build failed.");
          }
        }
        lines.add(finalLine);
      }
    } else {
      // project generation started, it may or may not contain a build
      // we wait for generation to finish to output time
      if (projectGenerationFinished != null) {
        long durationMs =
            projectGenerationFinished.getTimestamp() - projectGenerationStarted.getTimestamp();
        lines.add("  Total time: " + formatElapsedTime(durationMs));
      }
    }
  }

  private Optional<String> getOptionalDistBuildLineSuffix() {
    List<String> columns = new ArrayList<>();

    synchronized (distBuildStatusLock) {
      if (!distBuildStatus.isPresent()) {
        columns.add("remote status: init");
      } else {
        distBuildStatus
            .get()
            .getStatus()
            .ifPresent(status -> columns.add("remote status: " + status.toLowerCase()));

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

        if (distBuildTotalRulesCount > 0) {
          columns.add(
              String.format("%d/%d jobs", distBuildFinishedRulesCount, distBuildTotalRulesCount));
        }

        CacheRateStatsKeeper.CacheRateStatsUpdateEvent aggregatedCacheStats =
            CacheRateStatsKeeper.getAggregatedCacheRateStats(slaveCacheStats.build());

        if (aggregatedCacheStats.getTotalRulesCount() != 0) {
          columns.add(String.format("%.1f%% cache miss", aggregatedCacheStats.getCacheMissRate()));

          if (aggregatedCacheStats.getCacheErrorCount() != 0) {
            columns.add(
                String.format(
                    "%d [%.1f%%] cache errors",
                    aggregatedCacheStats.getCacheErrorCount(),
                    aggregatedCacheStats.getCacheErrorRate()));
          }
        }

        if (totalUploadErrorsCount > 0) {
          columns.add(String.format("%d upload errors", totalUploadErrorsCount));
        }
      }
    }

    String localStatus = String.format("local status: %s", stampedeLocalBuildStatus.getStatus());
    String remoteStatusAndSummary = Joiner.on(", ").join(columns);
    if (remoteStatusAndSummary.length() == 0) {
      return Optional.of(localStatus);
    }

    String parseLine;
    parseLine = remoteStatusAndSummary + "; " + localStatus;
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

  /**
   * Render lines using the provided {@param renderer}.
   *
   * @return the number of lines created.
   */
  public int renderLines(
      MultiStateRenderer renderer,
      ImmutableList.Builder<String> lines,
      int maxLines,
      boolean alwaysSortByTime) {
    int numLinesRendered = 0;
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
      numLinesRendered++;
    }
    if (useCompressedLine) {
      lineBuilder.delete(0, lineBuilder.length());
      lineBuilder.append(" - ");
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
      numLinesRendered++;
    }

    return numLinesRendered;
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

  @Subscribe
  public void stepStarted(StepEvent.Started started) {
    threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void stepFinished(StepEvent.Finished finished) {
    threadsToRunningStep.put(finished.getThreadId(), Optional.empty());
  }

  // TODO(cjhopman): We should introduce a simple LeafEvent-like thing that everything that logs
  // step-like things can subscribe to.
  @Subscribe
  public void simpleLeafEventStarted(LeafEvents.SimpleLeafEvent.Started started) {
    threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void simpleLeafEventFinished(LeafEvents.SimpleLeafEvent.Finished finished) {
    threadsToRunningStep.put(finished.getThreadId(), Optional.empty());
  }

  @Subscribe
  public void ruleKeyCalculationStarted(RuleKeyCalculationEvent.Started started) {
    threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void ruleKeyCalculationFinished(RuleKeyCalculationEvent.Finished finished) {
    threadsToRunningStep.put(finished.getThreadId(), Optional.empty());
  }

  @Override
  @Subscribe
  public void onDistBuildStatusEvent(DistBuildStatusEvent event) {
    super.onDistBuildStatusEvent(event);
    synchronized (distBuildSlaveTrackerLock) {
      for (BuildSlaveStatus status : event.getStatus().getSlaveStatuses()) {
        distBuildSlaveTracker.put(status.buildSlaveRunId, status);
      }
    }
  }

  @Subscribe
  public void onStampedeLocalBuildStatusEvent(StampedeLocalBuildStatusEvent event) {
    this.stampedeLocalBuildStatus = event;
  }

  @Subscribe
  public void onDistBuildCreatedEvent(DistBuildCreatedEvent event) {
    stampedeIdLogLine = Optional.of(event.getConsoleLogLine());
  }

  @Subscribe
  public void onDistBuildSuperConsoleEvent(DistBuildSuperConsoleEvent event) {
    stampedeSuperConsoleEvent = Optional.of(event);
  }

  /** When a new cache event is about to start. */
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
    if (console.getVerbosity().isSilent() && !event.getLevel().equals(Level.SEVERE)) {
      return;
    }
    logEvents.add(event);
  }

  @Subscribe
  public void logStampedeConsoleEvent(StampedeConsoleEvent event) {
    if (stampedeSuperConsoleEvent.isPresent()) {
      logEvent(event.getConsoleEvent());
    }
  }

  @Subscribe
  public void forceRender(@SuppressWarnings("unused") FlushConsoleEvent event) {
    render();
  }

  @Override
  public void printSevereWarningDirectly(String line) {
    logEvents.add(ConsoleEvent.severe(line));
  }

  private void printInfoDirectlyOnce(String line) {
    if (console.getVerbosity().isSilent()) {
      return;
    }
    if (!actionGraphCacheMessage.contains(line)) {
      logEvents.add(ConsoleEvent.info(line));
      actionGraphCacheMessage.add(line);
    }
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void actionGraphCacheHit(ActionGraphEvent.Cache.Hit event) {
    // We don't need to report when it's fast.
    if (isZeroFileChanges) {
      LOG.debug("Action graph cache hit: Watchman reported no changes");
    } else {
      LOG.debug("Action graph cache hit");
    }
    parsingStatus = Optional.of("actionGraphCacheHit");
  }

  @Subscribe
  public void watchmanOverflow(WatchmanStatusEvent.Overflow event) {
    printInfoDirectlyOnce(
        "Action graph will be rebuilt because there was an issue with watchman:\n"
            + event.getReason());
    parsingStatus = Optional.of("watchmanOverflow: " + event.getReason());
  }

  private void printFileAddedOrRemoved() {
    printInfoDirectlyOnce("Action graph will be rebuilt because files have been added or removed.");
  }

  @Subscribe
  public void watchmanFileCreation(WatchmanStatusEvent.FileCreation event) {
    LOG.debug("Watchman notified about file addition: " + event.getFilename());
    printFileAddedOrRemoved();
    parsingStatus = Optional.of("watchmanFileCreation");
  }

  @Subscribe
  public void watchmanFileDeletion(WatchmanStatusEvent.FileDeletion event) {
    LOG.debug("Watchman notified about file deletion: " + event.getFilename());
    printFileAddedOrRemoved();
    parsingStatus = Optional.of("watchmanFileDeletion");
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void watchmanZeroFileChanges(WatchmanStatusEvent.ZeroFileChanges event) {
    isZeroFileChanges = true;
    parsingStatus = Optional.of("watchmanZeroFileChanges");
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void daemonNewInstance(DaemonEvent.NewDaemonInstance event) {
    parsingStatus = Optional.of("daemonNewInstance");
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void symlinkInvalidation(ParsingEvent.SymlinkInvalidation event) {
    printInfoDirectlyOnce("Action graph will be rebuilt because symlinks are used.");
    parsingStatus = Optional.of("symlinkInvalidation");
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void envVariableChange(ParsingEvent.EnvVariableChange event) {
    printInfoDirectlyOnce("Action graph will be rebuilt because environment variables changed.");
    parsingStatus = Optional.of("envVariableChange");
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

  @Override
  protected String formatElapsedTime(long elapsedTimeMs) {
    long minutes = elapsedTimeMs / 60_000L;
    long seconds = elapsedTimeMs / 1000 - (minutes * 60);
    long milliseconds = elapsedTimeMs % 1000;
    if (elapsedTimeMs >= 60_000L) {
      return String.format("%02d:%02d.%d min", minutes, seconds, milliseconds / 100);
    } else {
      return String.format("%d.%d sec", seconds, milliseconds / 100);
    }
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
