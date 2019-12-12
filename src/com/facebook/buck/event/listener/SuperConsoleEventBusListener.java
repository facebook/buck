/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.event.listener;

import com.facebook.buck.artifact_cache.ArtifactCacheEvent;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.test.event.TestRunEvent;
import com.facebook.buck.core.test.event.TestStatusMessageEvent;
import com.facebook.buck.core.test.event.TestSummaryEvent;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.CommandEvent.Finished;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.FlushConsoleEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.event.ParsingEvent;
import com.facebook.buck.event.RuleKeyCalculationEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.event.listener.interfaces.AdditionalConsoleLineProvider;
import com.facebook.buck.event.listener.util.EventInterval;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent;
import com.facebook.buck.rules.modern.builders.HybridLocalEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.test.config.TestResultSummaryVerbosity;
import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.timing.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.eventbus.Subscribe;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
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

  private static final StringBuilder LINE_BUILDER =
      new StringBuilder(EXPECTED_MAXIMUM_RENDERED_LINE_LENGTH);

  private static final Logger LOG = Logger.get(SuperConsoleEventBusListener.class);

  private final Locale locale;
  private final Function<Long, String> formatTimeFunction;

  private final ConcurrentMap<Long, ConcurrentLinkedDeque<LeafEvent>> threadsToRunningStep;

  @GuardedBy("this")
  private final ConcurrentMap<BuildTarget, RemoteExecutionActionEvent.Started> eventsByTargets;

  @GuardedBy("this")
  private final Set<BuildTarget> stolenTargets;

  private final ConcurrentMap<Long, Optional<? extends TestSummaryEvent>>
      threadsToRunningTestSummaryEvent;
  private final ConcurrentMap<Long, Optional<? extends TestStatusMessageEvent>>
      threadsToRunningTestStatusMessageEvent;

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
  private final int maxConcurrentReExecutions;

  // Save if Watchman reported zero file changes in case we receive an ActionGraphProvider hit. This
  // way the user can know that their changes, if they made any, were not picked up from Watchman.
  private boolean isZeroFileChanges = false;

  private long minimumDurationMillisecondsToShowParse;
  private long minimumDurationMillisecondsToShowActionGraph;
  private long minimumDurationMillisecondsToShowWatchman;
  private boolean hideEmptyDownload;

  private final Set<String> actionGraphCacheMessage = new HashSet<>();

  /** Maximum width of the terminal. */
  private final int outputMaxColumns;

  private final Optional<String> buildIdLine;
  private final Optional<String> buildDetailsLine;
  private final ImmutableList<AdditionalConsoleLineProvider> additionalConsoleLineProviders;

  private final RenderingConsole renderingConsole;

  public SuperConsoleEventBusListener(
      SuperConsoleConfig config,
      RenderingConsole renderingConsole,
      Clock clock,
      TestResultSummaryVerbosity summaryVerbosity,
      ExecutionEnvironment executionEnvironment,
      Locale locale,
      Path testLogPath,
      BuildId buildId,
      boolean printBuildId,
      Optional<String> buildDetailsTemplate,
      ImmutableSet<String> buildDetailsCommands,
      ImmutableList<AdditionalConsoleLineProvider> additionalConsoleLineProviders,
      int maxConcurrentReExecutions) {
    this(
        config,
        renderingConsole,
        clock,
        summaryVerbosity,
        executionEnvironment,
        locale,
        testLogPath,
        500L,
        500L,
        1000L,
        true,
        buildId,
        printBuildId,
        buildDetailsTemplate,
        buildDetailsCommands,
        additionalConsoleLineProviders,
        maxConcurrentReExecutions);
  }

  @VisibleForTesting
  public SuperConsoleEventBusListener(
      SuperConsoleConfig config,
      RenderingConsole renderingConsole,
      Clock clock,
      TestResultSummaryVerbosity summaryVerbosity,
      ExecutionEnvironment executionEnvironment,
      Locale locale,
      Path testLogPath,
      long minimumDurationMillisecondsToShowParse,
      long minimumDurationMillisecondsToShowActionGraph,
      long minimumDurationMillisecondsToShowWatchman,
      boolean hideEmptyDownload,
      BuildId buildId,
      boolean printBuildId,
      Optional<String> buildDetailsTemplate,
      ImmutableSet<String> buildDetailsCommands,
      ImmutableList<AdditionalConsoleLineProvider> additionalConsoleLineProviders,
      int maxConcurrentReExecutions) {
    super(
        renderingConsole,
        clock,
        locale,
        executionEnvironment,
        false,
        config.getNumberOfSlowRulesToShow(),
        config.shouldShowSlowRulesInConsole(),
        buildDetailsCommands);
    this.additionalConsoleLineProviders = additionalConsoleLineProviders;
    this.locale = locale;
    this.formatTimeFunction = this::formatElapsedTime;
    this.threadsToRunningTestSummaryEvent =
        new ConcurrentHashMap<>(executionEnvironment.getAvailableCores());
    this.threadsToRunningTestStatusMessageEvent =
        new ConcurrentHashMap<>(executionEnvironment.getAvailableCores());
    this.threadsToRunningStep = new ConcurrentHashMap<>(executionEnvironment.getAvailableCores());
    this.eventsByTargets = new ConcurrentHashMap<>();
    this.stolenTargets = ConcurrentHashMap.newKeySet();

    this.testFormatter =
        new TestResultFormatter(
            renderingConsole.getAnsi(),
            renderingConsole.getVerbosity(),
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

    int outputMaxColumns = 80;
    if (config.getThreadLineOutputMaxColumns().isPresent()) {
      outputMaxColumns = config.getThreadLineOutputMaxColumns().getAsInt();
    } else {
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
      // If the parsed value is zero, we reset the value to the default 80.
      if (outputMaxColumns == 0) {
        outputMaxColumns = 80;
      }
    }
    this.outputMaxColumns = outputMaxColumns;
    this.buildIdLine = printBuildId ? Optional.of(getBuildLogLine(buildId)) : Optional.empty();
    this.buildDetailsLine =
        buildDetailsTemplate.map(
            template -> AbstractConsoleEventBusListener.getBuildDetailsLine(buildId, template));
    this.renderingConsole = renderingConsole;
    this.renderingConsole.registerDelegate(this::createRenderLinesAtTime);
    this.renderingConsole.startRenderScheduler();
    this.maxConcurrentReExecutions = maxConcurrentReExecutions;
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

    logEventInterval(
        "Processing filesystem changes",
        Optional.empty(),
        currentTimeMillis,
        /* offsetMs */ 0L,
        watchmanStarted,
        watchmanFinished,
        Optional.empty(),
        Optional.of(this.minimumDurationMillisecondsToShowWatchman),
        lines);

    boolean parseFinished =
        addLineFromEventInterval(
            "Parsing buck files",
            /* suffix */ Optional.empty(),
            currentTimeMillis,
            parseStats.getInterval(),
            getEstimatedProgressOfParsingBuckFiles(),
            Optional.of(this.minimumDurationMillisecondsToShowParse),
            lines);

    boolean actionGraphFinished =
        addLineFromEvents(
            "Creating action graph",
            /* suffix */ Optional.empty(),
            currentTimeMillis,
            actionGraphEvents.values(),
            getEstimatedProgressOfCreatingActionGraph(),
            Optional.of(this.minimumDurationMillisecondsToShowActionGraph),
            lines);

    logEventInterval(
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
    if (buildStarted == null || !parseFinished || !actionGraphFinished) {
      return lines.build();
    }

    int maxThreadLines = defaultThreadLineLimit;
    if (anyWarningsPrinted.get() && threadLineLimitOnWarning < maxThreadLines) {
      maxThreadLines = threadLineLimitOnWarning;
    }
    if (anyErrorsPrinted.get() && threadLineLimitOnError < maxThreadLines) {
      maxThreadLines = threadLineLimitOnError;
    }

    for (AdditionalConsoleLineProvider provider : additionalConsoleLineProviders) {
      lines.addAll(provider.createConsoleLinesAtTime(currentTimeMillis));
    }

    if (networkStatsTracker.getRemoteDownloadStats().getArtifacts() > 0
        || !this.hideEmptyDownload) {
      lines.add(getNetworkStatsLine(buildFinished));
    }

    // Check to see if the build encompasses the time spent parsing. This is true for runs of
    // buck build but not so for runs of e.g. buck project. If so, subtract parse times
    // from the build time.
    long buildStartedTime = buildStarted.getTimestampMillis();
    long buildFinishedTime =
        buildFinished != null ? buildFinished.getTimestampMillis() : currentTimeMillis;
    Collection<EventInterval> filteredBuckFilesParsingEvents =
        getEventsBetween(
            buildStartedTime, buildFinishedTime, ImmutableList.of(parseStats.getInterval()));
    Collection<EventInterval> filteredActionGraphEvents =
        getEventsBetween(buildStartedTime, buildFinishedTime, actionGraphEvents.values());
    long offsetMs =
        getTotalCompletedTimeFromEventIntervals(filteredBuckFilesParsingEvents)
            + getTotalCompletedTimeFromEventIntervals(filteredActionGraphEvents);

    long totalBuildMs =
        logEventInterval(
            "Building",
            getOptionalBuildLineSuffix(),
            currentTimeMillis,
            offsetMs, // parseTime,
            this.buildStarted,
            this.buildFinished,
            getApproximateBuildProgress(),
            Optional.empty(),
            lines);

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
              getCurrentThreadsToStep(),
              buildRuleThreadTracker);
      int numLinesRenderedFromLocalBuild =
          renderLinesWithMaybeCompression(
              renderer, lines, maxThreadLines, shouldAlwaysSortThreadsByTime);
      renderLinesWithMaybeTruncation(
          numLinesRenderedFromLocalBuild,
          new RemoteExecutionStateRenderer(
              ansi,
              formatTimeFunction,
              currentTimeMillis,
              outputMaxColumns,
              buildRuleMinimumDurationMillis,
              maxConcurrentReExecutions,
              ImmutableList.copyOf(eventsByTargets.values())),
          lines,
          maxThreadLines);
    }

    long testRunTime =
        logEventInterval(
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
              getCurrentThreadsToStep(),
              buildRuleThreadTracker);
      renderLinesWithMaybeCompression(
          renderer, lines, maxThreadLines, shouldAlwaysSortThreadsByTime);
    }

    logEventInterval(
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

    maybePrintBuildDetails(lines);

    return lines.build();
  }

  private Map<Long, Optional<? extends LeafEvent>> getCurrentThreadsToStep() {
    return Maps.transformValues(
        threadsToRunningStep, list -> Optional.ofNullable(Objects.requireNonNull(list).peekLast()));
  }

  private void maybePrintBuildDetails(Builder<String> lines) {
    Finished commandFinishedEvent = commandFinished;
    if (commandFinishedEvent != null
        && buildDetailsCommands.contains(commandFinishedEvent.getCommandName())
        && buildDetailsLine.isPresent()) {
      lines.add(buildDetailsLine.get());
    }
  }

  private void getTotalTimeLine(ImmutableList.Builder<String> lines) {
    if (projectGenerationStarted == null) {
      // project generation never started
      // we only output total time if build started and finished
      if (buildStarted != null && buildFinished != null) {
        long durationMs = buildFinished.getTimestampMillis() - buildStarted.getTimestampMillis();
        String finalLine = "  Total time: " + formatElapsedTime(durationMs);
        lines.add(finalLine);
      }
    } else {
      // project generation started, it may or may not contain a build
      // we wait for generation to finish to output time
      if (projectGenerationFinished != null) {
        long durationMs =
            projectGenerationFinished.getTimestampMillis()
                - projectGenerationStarted.getTimestampMillis();
        lines.add("  Total time: " + formatElapsedTime(durationMs));
      }
    }
  }

  /**
   * Returns the number of lines created. If the number of lines to be created exceeds the given
   * {@code maxLines}, compress the extraneous lines into a single line. See also {@code
   * #renderLinesWithMaybeTruncation}.
   *
   * @param renderer the renderer to use for rendering lines
   * @param lines the builder to add the rendered lines to
   * @param maxLines the maximum number of lines to render
   * @param alwaysSortByTime true if the rendered lines should be sorted by time
   */
  public int renderLinesWithMaybeCompression(
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
    ImmutableList<Long> threadIds = renderer.getSortedIds(sortByTime);
    for (int i = 0; i < fullLines; ++i) {
      long threadId = threadIds.get(i);
      lines.add(renderer.renderStatusLine(threadId));
      numLinesRendered++;
    }
    if (useCompressedLine) {
      LINE_BUILDER.setLength(0);
      LINE_BUILDER.append(" - ");
      LINE_BUILDER.append(threadsWithShortStatus);
      if (fullLines == 0) {
        LINE_BUILDER.append(String.format(" %s:", renderer.getExecutorCollectionLabel()));
      } else {
        LINE_BUILDER.append(String.format(" MORE %s:", renderer.getExecutorCollectionLabel()));
      }
      for (int i = fullLines; i < threadIds.size(); ++i) {
        long threadId = threadIds.get(i);
        LINE_BUILDER.append(" ");
        LINE_BUILDER.append(renderer.renderShortStatus(threadId));
      }
      lines.add(LINE_BUILDER.toString());
      numLinesRendered++;
    }

    return numLinesRendered;
  }

  /**
   * Returns the number of lines created. If the number of lines to be created exceeds the given
   * {@code maxLines}, return early and ignore the other lines that were to be rendered. See also
   * {@code #renderLinesWithMaybeCompression}.
   *
   * @param numLinesAlreadyRendered the number of lines already previously rendered; used to
   *     calculate if {@code maxLines} has been reached
   * @param renderer the renderer to use for rendering lines
   * @param lines the builder to add the rendered lines to
   * @param maxLines the maximum number of lines to render
   */
  public int renderLinesWithMaybeTruncation(
      int numLinesAlreadyRendered,
      MultiStateRenderer renderer,
      ImmutableList.Builder<String> lines,
      int maxLines) {
    int numNewLinesRendered = 0;
    for (long id : renderer.getSortedIds(/* sortByTime= */ false)) {
      if (numLinesAlreadyRendered + numNewLinesRendered >= maxLines) {
        return numNewLinesRendered;
      }
      lines.add(renderer.renderStatusLine(id));
      numNewLinesRendered++;
    }

    return numNewLinesRendered;
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
    runningStepStarted(started);
  }

  private void runningStepStarted(LeafEvent started) {
    Objects.requireNonNull(started, "event was null.");
    Objects.requireNonNull(
            Objects.requireNonNull(threadsToRunningStep, "map was null.")
                .computeIfAbsent(started.getThreadId(), ignored -> new ConcurrentLinkedDeque<>()),
            "value was null.")
        .add(started);
  }

  @Subscribe
  public void stepFinished(StepEvent.Finished finished) {
    runningStepFinished(finished.getThreadId());
  }

  private void runningStepFinished(long threadId) {
    Objects.requireNonNull(threadsToRunningStep, "map was null.")
        .computeIfAbsent(threadId, ignored -> new ConcurrentLinkedDeque<>())
        .pollLast();
  }

  // TODO(cjhopman): We should introduce a simple LeafEvent-like thing that everything that logs
  // step-like things can subscribe to.
  @Subscribe
  public void simpleLeafEventStarted(LeafEvents.SimpleLeafEvent.Started started) {
    runningStepStarted(started);
  }

  @Subscribe
  public void simpleLeafEventFinished(LeafEvents.SimpleLeafEvent.Finished finished) {
    runningStepFinished(finished.getThreadId());
  }

  @Subscribe
  public void ruleKeyCalculationStarted(RuleKeyCalculationEvent.Started started) {
    runningStepStarted(started);
  }

  @Subscribe
  public void ruleKeyCalculationFinished(RuleKeyCalculationEvent.Finished finished) {
    runningStepFinished(finished.getThreadId());
  }

  /** When a new cache event is about to start. */
  @Subscribe
  public void artifactCacheStarted(ArtifactCacheEvent.Started started) {
    if (started.getInvocationType() == ArtifactCacheEvent.InvocationType.SYNCHRONOUS) {
      runningStepStarted(started);
    }
  }

  @Subscribe
  public void artifactCacheFinished(ArtifactCacheEvent.Finished finished) {
    if (finished.getInvocationType() == ArtifactCacheEvent.InvocationType.SYNCHRONOUS) {
      runningStepFinished(finished.getThreadId());
    }
  }

  @Subscribe
  public void artifactCompressionStarted(ArtifactCompressionEvent.Started started) {
    runningStepStarted(started);
  }

  @Subscribe
  public void artifactCompressionFinished(ArtifactCompressionEvent.Finished finished) {
    runningStepFinished(finished.getThreadId());
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
      testOutput = String.join(System.lineSeparator(), testReportBuilder.build());
    }
    renderingConsole.printToStdOut(testOutput);
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
        logEvent(
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
    logEventDirectly(event);
  }

  private void logEventDirectly(ConsoleEvent logEvent) {
    renderingConsole.logLines(formatConsoleEvent(logEvent));
    if (logEvent.getLevel().equals(Level.WARNING)) {
      anyWarningsPrinted.set(true);
    } else if (logEvent.getLevel().equals(Level.SEVERE)) {
      anyErrorsPrinted.set(true);
    }
  }

  @Subscribe
  public void forceRender(@SuppressWarnings("unused") FlushConsoleEvent event) {
    renderingConsole.render();
  }

  @Override
  public void printSevereWarningDirectly(String line) {
    logEventDirectly(ConsoleEvent.severe(line));
  }

  private void printInfoDirectlyOnce(String line) {
    if (console.getVerbosity().isSilent()) {
      return;
    }
    if (!actionGraphCacheMessage.contains(line)) {
      logEventDirectly(ConsoleEvent.info(line));
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
  }

  @Subscribe
  public void watchmanOverflow(WatchmanStatusEvent.Overflow event) {
    printInfoDirectlyOnce(
        "Action graph will be rebuilt because there was an issue with watchman:"
            + System.lineSeparator()
            + event.getReason());
  }

  private void printFileAddedOrRemoved() {
    printInfoDirectlyOnce("Action graph will be rebuilt because files have been added or removed.");
  }

  @Subscribe
  public void watchmanFileCreation(WatchmanStatusEvent.FileCreation event) {
    LOG.debug("Watchman notified about file addition: " + event.getFilename());
    printFileAddedOrRemoved();
  }

  @Subscribe
  public void watchmanFileDeletion(WatchmanStatusEvent.FileDeletion event) {
    LOG.debug("Watchman notified about file deletion: " + event.getFilename());
    printFileAddedOrRemoved();
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void watchmanZeroFileChanges(WatchmanStatusEvent.ZeroFileChanges event) {
    isZeroFileChanges = true;
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void symlinkInvalidation(ParsingEvent.SymlinkInvalidation event) {
    printInfoDirectlyOnce("Action graph will be rebuilt because symlinks are used.");
  }

  @Subscribe
  @SuppressWarnings("unused")
  public void envVariableChange(ParsingEvent.EnvVariableChange event) {
    printInfoDirectlyOnce("Action graph will be rebuilt because environment variables changed.");
  }

  @Subscribe
  public synchronized void onActionEventStarted(RemoteExecutionActionEvent.Started event) {
    if (!stolenTargets.contains(event.getBuildTarget())) {
      eventsByTargets.put(event.getBuildTarget(), event);
    }
  }

  @Subscribe
  public void onActionEventTerminated(RemoteExecutionActionEvent.Terminal event) {
    eventsByTargets.remove(event.getBuildTarget());
  }

  @Subscribe
  public synchronized void onActionEventStolen(HybridLocalEvent.Stolen event) {
    stolenTargets.add(event.getBuildTarget());
    eventsByTargets.remove(event.getBuildTarget());
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

  @Override
  public synchronized void close() throws IOException {
    super.close();
    renderingConsole.close();
  }

  @Override
  public boolean displaysEstimatedProgress() {
    return true;
  }
}
