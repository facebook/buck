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
import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.distributed.DistBuildStatus;
import com.facebook.buck.distributed.DistBuildStatusEvent;
import com.facebook.buck.distributed.build_client.DistBuildRemoteProgressEvent;
import com.facebook.buck.distributed.thrift.CoordinatorBuildProgress;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.json.ProjectBuildFileParseEvents;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.i18n.NumberFormatter;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import com.google.common.eventbus.Subscribe;
import java.io.Closeable;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Base class for {@link BuckEventListener}s responsible for outputting information about the
 * running build to {@code stderr}.
 */
public abstract class AbstractConsoleEventBusListener implements BuckEventListener, Closeable {

  private static final Logger LOG = Logger.get(AbstractConsoleEventBusListener.class);

  private static final NumberFormatter TIME_FORMATTER =
      new NumberFormatter(
          locale1 -> {
            // Yes, this is the only way to apply and localize a pattern to a NumberFormat.
            NumberFormat numberFormat = NumberFormat.getIntegerInstance(locale1);
            Preconditions.checkState(numberFormat instanceof DecimalFormat);
            DecimalFormat decimalFormat = (DecimalFormat) numberFormat;
            decimalFormat.applyPattern("0.0s");
            return decimalFormat;
          });

  protected static final long UNFINISHED_EVENT_PAIR = -1;
  protected final Console console;
  protected final Clock clock;
  protected final Ansi ansi;
  private final Locale locale;
  private final boolean showTextInAllCaps;
  private final int numberOfSlowRulesToShow;
  private final boolean showSlowRulesInConsole;
  private final Map<UnflavoredBuildTarget, Long> timeSpentMillisecondsInRules;

  @Nullable protected volatile ProjectBuildFileParseEvents.Started projectBuildFileParseStarted;
  @Nullable protected volatile ProjectBuildFileParseEvents.Finished projectBuildFileParseFinished;

  @Nullable protected volatile ProjectGenerationEvent.Started projectGenerationStarted;
  @Nullable protected volatile ProjectGenerationEvent.Finished projectGenerationFinished;

  @Nullable protected volatile WatchmanStatusEvent.Started watchmanStarted;
  @Nullable protected volatile WatchmanStatusEvent.Finished watchmanFinished;

  protected ConcurrentLinkedDeque<ParseEvent.Started> parseStarted;
  protected ConcurrentLinkedDeque<ParseEvent.Finished> parseFinished;

  protected ConcurrentLinkedDeque<ActionGraphEvent.Started> actionGraphStarted;
  protected ConcurrentLinkedDeque<ActionGraphEvent.Finished> actionGraphFinished;

  protected ConcurrentHashMap<EventKey, EventPair> actionGraphEvents;
  protected ConcurrentHashMap<EventKey, EventPair> buckFilesParsingEvents;

  @Nullable protected volatile BuildEvent.Started buildStarted;
  @Nullable protected volatile BuildEvent.Finished buildFinished;

  @Nullable protected volatile BuildEvent.DistBuildStarted distBuildStarted;
  @Nullable protected volatile BuildEvent.DistBuildFinished distBuildFinished;

  @Nullable protected volatile InstallEvent.Started installStarted;
  @Nullable protected volatile InstallEvent.Finished installFinished;

  protected AtomicReference<HttpArtifactCacheEvent.Scheduled> firstHttpCacheUploadScheduled =
      new AtomicReference<>();

  protected final AtomicInteger remoteArtifactUploadsScheduledCount = new AtomicInteger(0);
  protected final AtomicInteger remoteArtifactUploadsStartedCount = new AtomicInteger(0);
  protected final AtomicInteger remoteArtifactUploadedCount = new AtomicInteger(0);
  protected final AtomicLong remoteArtifactTotalBytesUploaded = new AtomicLong(0);
  protected final AtomicInteger remoteArtifactUploadFailedCount = new AtomicInteger(0);

  @Nullable protected volatile HttpArtifactCacheEvent.Shutdown httpShutdownEvent;

  protected volatile OptionalInt ruleCount = OptionalInt.empty();
  protected Optional<String> publicAnnouncements = Optional.empty();

  protected final AtomicInteger numRulesCompleted = new AtomicInteger();

  protected Optional<ProgressEstimator> progressEstimator = Optional.empty();

  protected final CacheRateStatsKeeper cacheRateStatsKeeper;

  protected final NetworkStatsKeeper networkStatsKeeper;

  protected volatile int distBuildTotalRulesCount = 0;
  protected volatile int distBuildFinishedRulesCount = 0;

  protected BuildRuleThreadTracker buildRuleThreadTracker;

  protected final Object distBuildStatusLock = new Object();

  @GuardedBy("distBuildStatusLock")
  protected Optional<DistBuildStatus> distBuildStatus = Optional.empty();

  public AbstractConsoleEventBusListener(
      Console console,
      Clock clock,
      Locale locale,
      ExecutionEnvironment executionEnvironment,
      boolean showTextInAllCaps,
      int numberOfSlowRulesToShow,
      boolean showSlowRulesInConsole) {
    this.console = console;
    this.clock = clock;
    this.locale = locale;
    this.ansi = console.getAnsi();
    this.showTextInAllCaps = showTextInAllCaps;
    this.numberOfSlowRulesToShow = numberOfSlowRulesToShow;
    this.showSlowRulesInConsole = showSlowRulesInConsole;
    this.timeSpentMillisecondsInRules = new HashMap<>();

    this.projectBuildFileParseStarted = null;
    this.projectBuildFileParseFinished = null;

    this.projectGenerationStarted = null;
    this.projectGenerationFinished = null;

    this.watchmanStarted = null;
    this.watchmanFinished = null;

    this.parseStarted = new ConcurrentLinkedDeque<>();
    this.parseFinished = new ConcurrentLinkedDeque<>();

    this.actionGraphStarted = new ConcurrentLinkedDeque<>();
    this.actionGraphFinished = new ConcurrentLinkedDeque<>();

    this.actionGraphEvents = new ConcurrentHashMap<>();
    this.buckFilesParsingEvents = new ConcurrentHashMap<>();

    this.buildStarted = null;
    this.buildFinished = null;

    this.installStarted = null;
    this.installFinished = null;

    this.cacheRateStatsKeeper = new CacheRateStatsKeeper();
    this.networkStatsKeeper = new NetworkStatsKeeper();

    this.buildRuleThreadTracker = new BuildRuleThreadTracker(executionEnvironment);
  }

  @VisibleForTesting
  Optional<String> getPublicAnnouncements() {
    return publicAnnouncements;
  }

  public boolean displaysEstimatedProgress() {
    return false;
  }

  public void setProgressEstimator(ProgressEstimator estimator) {
    if (displaysEstimatedProgress()) {
      progressEstimator = Optional.of(estimator);
    }
  }

  protected String formatElapsedTime(long elapsedTimeMs) {
    long minutes = elapsedTimeMs / 60_000L;
    String seconds = TIME_FORMATTER.format(locale, elapsedTimeMs / 1000.0 - (minutes * 60));
    return String.format(minutes == 0 ? "%s" : "%2$dm %1$s", seconds, minutes);
  }

  protected Optional<Double> getApproximateDistBuildProgress() {
    if (distBuildTotalRulesCount == 0) {
      return Optional.of(0.0);
    }

    double buildRatio = (double) distBuildFinishedRulesCount / distBuildTotalRulesCount;
    return Optional.of(Math.floor(100 * buildRatio) / 100.0);
  }

  /** Local build progress. */
  protected Optional<Double> getApproximateLocalBuildProgress() {
    if (progressEstimator.isPresent()) {
      return progressEstimator.get().getApproximateBuildProgress();
    } else {
      return Optional.empty();
    }
  }

  protected Optional<Double> getApproximateBuildProgress() {
    if (distBuildStarted != null && distBuildFinished == null) {
      return getApproximateDistBuildProgress();
    } else {
      return getApproximateLocalBuildProgress();
    }
  }

  protected Optional<Double> getEstimatedProgressOfGeneratingProjectFiles() {
    if (progressEstimator.isPresent()) {
      return progressEstimator.get().getEstimatedProgressOfGeneratingProjectFiles();
    } else {
      return Optional.empty();
    }
  }

  protected Optional<Double> getEstimatedProgressOfParsingBuckFiles() {
    if (progressEstimator.isPresent()) {
      return progressEstimator.get().getEstimatedProgressOfParsingBuckFiles();
    } else {
      return Optional.empty();
    }
  }

  public void setPublicAnnouncements(BuckEventBus eventBus, Optional<String> announcements) {
    this.publicAnnouncements = announcements;
    announcements.ifPresent(
        announcement ->
            eventBus.post(
                ConsoleEvent.createForMessageWithAnsiEscapeCodes(
                    Level.INFO, ansi.asInformationText(announcement))));
  }

  // This is used by the logging infrastructure to add a line to the console in a way that doesn't
  // break rendering.
  public abstract void printSevereWarningDirectly(String line);

  /**
   * Filter a list of events and return the subset that fall between the given start and end
   * timestamps. Preserves ordering if the given iterable was ordered. Will replace event pairs that
   * straddle the boundary with {@link com.facebook.buck.event.listener.ProxyBuckEvent} instances,
   * so that the resulting collection is strictly contained within the boundaries.
   *
   * @param start the start timestamp (inclusive)
   * @param end the end timestamp (also inclusive)
   * @param eventPairs the events to filter.
   * @return a list of all events from the given iterable that fall between the given start and end
   *     times. If an event straddles the given start or end, it will be replaced with a proxy event
   *     pair that cuts off at exactly the start or end.
   */
  protected static Collection<EventPair> getEventsBetween(
      long start, long end, Iterable<EventPair> eventPairs) {
    List<EventPair> outEvents = new ArrayList<>();
    for (EventPair ep : eventPairs) {
      long startTime = ep.getStartTime();
      long endTime = ep.getEndTime();

      if (ep.isComplete()) {
        if (startTime >= start && endTime <= end) {
          outEvents.add(ep);
        } else if (startTime >= start && startTime <= end) {
          // If the start time is within bounds, but the end time is not, replace with a proxy
          outEvents.add(EventPair.proxy(startTime, end));
        } else if (endTime <= end && endTime >= start) {
          // If the end time is within bounds, but the start time is not, replace with a proxy
          outEvents.add(EventPair.proxy(start, endTime));
        }
      } else if (ep.isOngoing()) {
        // If the event is ongoing, replace with a proxy
        outEvents.add(EventPair.proxy(startTime, end));
      } // Ignore the case where we have an end event but not a start. Just drop that EventPair.
    }
    return outEvents;
  }

  protected String convertToAllCapsIfNeeded(String str) {
    if (showTextInAllCaps) {
      return str.toUpperCase();
    } else {
      return str;
    }
  }

  /**
   * Adds a line about a pair of start and finished events to lines.
   *
   * @param prefix Prefix to print for this event pair.
   * @param suffix Suffix to print for this event pair.
   * @param currentMillis The current time in milliseconds.
   * @param offsetMs Offset to remove from calculated time. Set this to a non-zero value if the
   *     event pair would contain another event. For example, build time includes parse time, but to
   *     make the events easier to reason about it makes sense to pull parse time out of build time.
   * @param startEvent The started event.
   * @param finishedEvent The finished event.
   * @param lines The builder to append lines to.
   * @return The amount of time between start and finished if finished is present, otherwise {@link
   *     AbstractConsoleEventBusListener#UNFINISHED_EVENT_PAIR}.
   */
  protected long logEventPair(
      String prefix,
      Optional<String> suffix,
      long currentMillis,
      long offsetMs,
      @Nullable BuckEvent startEvent,
      @Nullable BuckEvent finishedEvent,
      Optional<Double> progress,
      Optional<Long> minimum,
      ImmutableList.Builder<String> lines) {
    if (startEvent == null) {
      return UNFINISHED_EVENT_PAIR;
    }
    EventPair pair =
        EventPair.builder()
            .setStart(startEvent)
            .setFinish(Optional.ofNullable(finishedEvent))
            .build();
    return logEventPair(
        prefix, suffix, currentMillis, offsetMs, ImmutableList.of(pair), progress, minimum, lines);
  }

  /**
   * Adds a line about a the state of cache uploads to lines.
   *
   * @param lines The builder to append lines to.
   */
  protected void logHttpCacheUploads(ImmutableList.Builder<String> lines) {
    if (firstHttpCacheUploadScheduled.get() != null) {
      boolean isFinished = httpShutdownEvent != null;
      String line = "HTTP CACHE UPLOAD" + (isFinished ? ": FINISHED " : "... ");
      line += renderRemoteUploads();
      lines.add(line);
    }
  }

  /**
   * Adds a line about a set of start and finished events to lines.
   *
   * @param prefix Prefix to print for this event pair.
   * @param suffix Suffix to print for this event pair.
   * @param currentMillis The current time in milliseconds.
   * @param offsetMs Offset to remove from calculated time. Set this to a non-zero value if the
   *     event pair would contain another event. For example, build time includes parse time, but to
   *     make the events easier to reason about it makes sense to pull parse time out of build time.
   * @param eventPairs the collection of start/end events to sum up when calculating elapsed time.
   * @param lines The builder to append lines to.
   * @return The summed time between start and finished events if each start event has a matching
   *     finished event, otherwise {@link AbstractConsoleEventBusListener#UNFINISHED_EVENT_PAIR}.
   */
  protected long logEventPair(
      String prefix,
      Optional<String> suffix,
      long currentMillis,
      long offsetMs,
      Collection<EventPair> eventPairs,
      Optional<Double> progress,
      Optional<Long> minimum,
      ImmutableList.Builder<String> lines) {
    if (eventPairs.isEmpty()) {
      return UNFINISHED_EVENT_PAIR;
    }

    long completedRunTimesMs = getTotalCompletedTimeFromEventPairs(eventPairs);
    long currentlyRunningTime = getWorkingTimeFromLastStartUntilNow(eventPairs, currentMillis);
    boolean stillRunning = currentlyRunningTime >= 0;
    String parseLine = prefix;
    long elapsedTimeMs = completedRunTimesMs - offsetMs;
    if (stillRunning) {
      parseLine += "... ";
      elapsedTimeMs += currentlyRunningTime;
    } else {
      parseLine += convertToAllCapsIfNeeded(": finished in ");
      if (progress.isPresent()) {
        progress = Optional.of(1.0);
      }
    }
    if (minimum.isPresent() && elapsedTimeMs < minimum.get()) {
      return elapsedTimeMs;
    }
    parseLine += formatElapsedTime(elapsedTimeMs);
    if (progress.isPresent()) {
      parseLine += " (" + Math.round(progress.get() * 100) + "%)";
    }
    if (suffix.isPresent()) {
      parseLine += " " + suffix.get();
    }
    lines.add(parseLine);
    return stillRunning ? UNFINISHED_EVENT_PAIR : elapsedTimeMs;
  }

  /**
   * Takes a collection of start and finished events. If there are any events that have a start, but
   * no finished time, the collection is considered ongoing.
   *
   * @param eventPairs the collection of event starts/stops.
   * @param currentMillis the current time.
   * @return -1 if all events are completed, otherwise the time elapsed between the latest event and
   *     currentMillis.
   */
  protected static long getWorkingTimeFromLastStartUntilNow(
      Collection<EventPair> eventPairs, long currentMillis) {
    // We examine all events to determine whether we have any incomplete events and also
    // to get the latest timestamp available (start or stop).
    long latestTimestamp = 0L;
    long earliestOngoingStart = Long.MAX_VALUE;
    boolean anyEventIsOngoing = false;
    for (EventPair pair : eventPairs) {
      if (pair.isOngoing()) {
        anyEventIsOngoing = true;
        if (pair.getStartTime() < earliestOngoingStart) {
          latestTimestamp = pair.getStartTime();
        }
      } else if (pair.getEndTime() > latestTimestamp) {
        latestTimestamp = pair.getEndTime();
      }
    }
    // If any events are unpaired, the whole collection is considered ongoing and we return
    // the difference between the latest time in the collection and the current time.
    return anyEventIsOngoing ? currentMillis - latestTimestamp : -1;
  }

  /**
   * Get the summed elapsed time from all matched event pairs. Does not consider unmatched event
   * pairs. Pairs are determined by their {@link com.facebook.buck.event.EventKey}.
   *
   * @param eventPairs a set of paired events (incomplete events are okay).
   * @return the sum of all times between matched event pairs.
   */
  protected static long getTotalCompletedTimeFromEventPairs(Collection<EventPair> eventPairs) {
    long totalTime = 0L;
    // Flatten the event groupings into a timeline, so that we don't over count parallel work.
    RangeSet<Long> timeline = TreeRangeSet.create();
    for (EventPair pair : eventPairs) {
      if (pair.isComplete() && pair.getElapsedTimeMs() > 0) {
        timeline.add(Range.open(pair.getStartTime(), pair.getEndTime()));
      }
    }
    for (Range<Long> range : timeline.asRanges()) {
      totalTime += range.upperEndpoint() - range.lowerEndpoint();
    }
    return totalTime;
  }

  /** Formats a {@link ConsoleEvent} and adds it to {@code lines}. */
  protected void formatConsoleEvent(ConsoleEvent logEvent, ImmutableList.Builder<String> lines) {
    if (logEvent.getMessage() == null) {
      LOG.error("Got logEvent with null message");
      return;
    }
    String formattedLine = "";
    if (logEvent.containsAnsiEscapeCodes() || logEvent.getLevel().equals(Level.INFO)) {
      formattedLine = logEvent.getMessage();
    } else if (logEvent.getLevel().equals(Level.WARNING)) {
      formattedLine = ansi.asWarningText(logEvent.getMessage());
    } else if (logEvent.getLevel().equals(Level.SEVERE)) {
      formattedLine = ansi.asHighlightedFailureText(logEvent.getMessage());
    }
    if (!formattedLine.isEmpty()) {
      // Split log messages at newlines and add each line individually to keep the line count
      // consistent.
      lines.addAll(Splitter.on("\n").split(formattedLine));
    }
  }

  @Subscribe
  public void commandStartedEvent(CommandEvent.Started startedEvent) {
    try {
      LOG.warn("Command.Started event received at %d", System.currentTimeMillis());
      progressEstimator.ifPresent(
          estimator ->
              estimator.setCurrentCommand(startedEvent.getCommandName(), startedEvent.getArgs()));
    } finally {
      LOG.warn("Command.Started event done processing at %d", System.currentTimeMillis());
    }
  }

  public static void aggregateStartedEvent(
      ConcurrentHashMap<EventKey, EventPair> map, BuckEvent started) {
    map.compute(
        started.getEventKey(),
        (key, pair) ->
            pair == null ? EventPair.builder().setStart(started).build() : pair.withStart(started));
  }

  public static void aggregateFinishedEvent(
      ConcurrentHashMap<EventKey, EventPair> map, BuckEvent finished) {
    map.compute(
        finished.getEventKey(),
        (key, pair) ->
            pair == null
                ? EventPair.builder().setFinish(finished).build()
                : pair.withFinish(finished));
  }

  @Subscribe
  public void projectBuildFileParseStarted(ProjectBuildFileParseEvents.Started started) {
    if (projectBuildFileParseStarted == null) {
      projectBuildFileParseStarted = started;
    }
    aggregateStartedEvent(buckFilesParsingEvents, started);
  }

  @Subscribe
  public void projectBuildFileParseFinished(ProjectBuildFileParseEvents.Finished finished) {
    projectBuildFileParseFinished = finished;
    aggregateFinishedEvent(buckFilesParsingEvents, finished);
  }

  @Subscribe
  public void projectGenerationStarted(ProjectGenerationEvent.Started started) {
    projectGenerationStarted = started;
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void projectGenerationProcessedTarget(ProjectGenerationEvent.Processed processed) {
    progressEstimator.ifPresent(ProgressEstimator::didGenerateProjectForTarget);
  }

  @Subscribe
  public void projectGenerationFinished(ProjectGenerationEvent.Finished finished) {
    projectGenerationFinished = finished;
    progressEstimator.ifPresent(ProgressEstimator::didFinishProjectGeneration);
  }

  @Subscribe
  public void parseStarted(ParseEvent.Started started) {
    parseStarted.add(started);
    aggregateStartedEvent(buckFilesParsingEvents, started);
  }

  @Subscribe
  public void ruleParseFinished(ParseBuckFileEvent.Finished ruleParseFinished) {
    progressEstimator.ifPresent(
        estimator -> estimator.didParseBuckRules(ruleParseFinished.getNumRules()));
  }

  @Subscribe
  public void parseFinished(ParseEvent.Finished finished) {
    parseFinished.add(finished);
    progressEstimator.ifPresent(ProgressEstimator::didFinishParsing);
    aggregateFinishedEvent(buckFilesParsingEvents, finished);
  }

  @Subscribe
  public void watchmanStarted(WatchmanStatusEvent.Started started) {
    watchmanStarted = started;
  }

  @Subscribe
  public void watchmanFinished(WatchmanStatusEvent.Finished finished) {
    watchmanFinished = finished;
  }

  @Subscribe
  public void actionGraphStarted(ActionGraphEvent.Started started) {
    actionGraphStarted.add(started);
    aggregateStartedEvent(actionGraphEvents, started);
  }

  @Subscribe
  public void actionGraphFinished(ActionGraphEvent.Finished finished) {
    actionGraphFinished.add(finished);
    aggregateFinishedEvent(actionGraphEvents, finished);
  }

  @Subscribe
  public void buildStarted(BuildEvent.Started started) {
    buildStarted = started;
    progressEstimator.ifPresent(ProgressEstimator::didStartBuild);
  }

  @Subscribe
  public void distBuildStarted(BuildEvent.DistBuildStarted started) {
    distBuildStarted = started;
  }

  @Subscribe
  public void ruleCountCalculated(BuildEvent.RuleCountCalculated calculated) {
    ruleCount = OptionalInt.of(calculated.getNumRules());
    progressEstimator.ifPresent(estimator -> estimator.setNumberOfRules(calculated.getNumRules()));
    cacheRateStatsKeeper.ruleCountCalculated(calculated);
  }

  @Subscribe
  public void ruleCountUpdated(BuildEvent.UnskippedRuleCountUpdated updated) {
    ruleCount = OptionalInt.of(updated.getNumRules());
    progressEstimator.ifPresent(estimator -> estimator.setNumberOfRules(updated.getNumRules()));
    cacheRateStatsKeeper.ruleCountUpdated(updated);
  }

  protected Optional<String> getOptionalBuildLineSuffix() {
    // Log build time, excluding time spent in parsing.
    String jobSummary = null;
    if (ruleCount.isPresent()) {
      List<String> columns = new ArrayList<>();
      columns.add(
          String.format(
              locale,
              "%d/%d " + convertToAllCapsIfNeeded("jobs"),
              numRulesCompleted.get(),
              ruleCount.getAsInt()));
      CacheRateStatsKeeper.CacheRateStatsUpdateEvent cacheRateStats =
          cacheRateStatsKeeper.getStats();
      columns.add(
          String.format(
              locale,
              "%d " + convertToAllCapsIfNeeded("updated"),
              cacheRateStats.getUpdatedRulesCount()));
      jobSummary = Joiner.on(", ").join(columns);
    }

    return Strings.isNullOrEmpty(jobSummary) ? Optional.empty() : Optional.of(jobSummary);
  }

  protected String getNetworkStatsLine(@Nullable BuildEvent.Finished finishedEvent) {
    String parseLine =
        finishedEvent != null
            ? convertToAllCapsIfNeeded("Downloaded")
            : convertToAllCapsIfNeeded("Downloading") + "...";
    List<String> columns = new ArrayList<>();

    Pair<Long, SizeUnit> remoteDownloadedBytes =
        networkStatsKeeper.getRemoteDownloadedArtifactsBytes();
    Pair<Double, SizeUnit> redableRemoteDownloadedBytes =
        SizeUnit.getHumanReadableSize(
            remoteDownloadedBytes.getFirst(), remoteDownloadedBytes.getSecond());
    columns.add(
        String.format(
            locale,
            "%d " + convertToAllCapsIfNeeded("artifacts"),
            networkStatsKeeper.getRemoteDownloadedArtifactsCount()));
    columns.add(
        String.format(
            locale,
            "%s",
            convertToAllCapsIfNeeded(
                SizeUnit.toHumanReadableString(redableRemoteDownloadedBytes, locale))));
    CacheRateStatsKeeper.CacheRateStatsUpdateEvent cacheRateStats = cacheRateStatsKeeper.getStats();
    columns.add(
        String.format(
            locale,
            "%.1f%% " + convertToAllCapsIfNeeded("cache miss"),
            cacheRateStats.getCacheMissRate()));
    if (cacheRateStats.getCacheErrorCount() > 0) {
      columns.add(
          String.format(
              locale,
              "%.1f%% " + convertToAllCapsIfNeeded("cache errors"),
              cacheRateStats.getCacheErrorRate()));
    }
    return parseLine + " " + Joiner.on(", ").join(columns);
  }

  @Subscribe
  public void buildRuleStarted(BuildRuleEvent.Started started) {
    progressEstimator.ifPresent(ProgressEstimator::didStartRule);
    buildRuleThreadTracker.didStartBuildRule(started);
  }

  @Subscribe
  public void buildRuleResumed(BuildRuleEvent.Resumed resumed) {
    progressEstimator.ifPresent(ProgressEstimator::didResumeRule);
    buildRuleThreadTracker.didResumeBuildRule(resumed);
  }

  @SuppressWarnings("unused")
  @Subscribe
  private void resetLocalBuildStats(BuildEvent.Reset reset) {
    buildRuleThreadTracker.reset();
    progressEstimator.ifPresent(ProgressEstimator::resetBuildData);
    numRulesCompleted.set(0);
  }

  @Subscribe
  public void buildRuleSuspended(BuildRuleEvent.Suspended suspended) {
    progressEstimator.ifPresent(ProgressEstimator::didSuspendRule);
    buildRuleThreadTracker.didSuspendBuildRule(suspended);
  }

  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    if (numberOfSlowRulesToShow != 0) {
      synchronized (timeSpentMillisecondsInRules) {
        UnflavoredBuildTarget unflavoredTarget =
            finished.getBuildRule().getBuildTarget().getUnflavoredBuildTarget();
        Long value = timeSpentMillisecondsInRules.get(unflavoredTarget);
        if (value == null) {
          value = 0L;
        }
        value = value + finished.getDuration().getWallMillisDuration();
        timeSpentMillisecondsInRules.put(unflavoredTarget, value);
      }
    }

    if (finished.getStatus() != BuildRuleStatus.CANCELED) {
      progressEstimator.ifPresent(ProgressEstimator::didFinishRule);
      numRulesCompleted.getAndIncrement();
    }

    buildRuleThreadTracker.didFinishBuildRule(finished);
    cacheRateStatsKeeper.buildRuleFinished(finished);
  }

  @Subscribe
  public void distBuildFinished(BuildEvent.DistBuildFinished finished) {
    if (distBuildFinished == null) {
      distBuildFinished = finished;
    }
  }

  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    buildFinished = finished;
    progressEstimator.ifPresent(ProgressEstimator::didFinishBuild);
  }

  @Subscribe
  public void testRuleStarted(TestRuleEvent.Started started) {
    buildRuleThreadTracker.didStartTestRule(started);
  }

  @Subscribe
  public void testRuleFinished(TestRuleEvent.Finished finished) {
    buildRuleThreadTracker.didFinishTestRule(finished);
  }

  @Subscribe
  public void installStarted(InstallEvent.Started started) {
    installStarted = started;
  }

  @Subscribe
  public void installFinished(InstallEvent.Finished finished) {
    installFinished = finished;
  }

  @Subscribe
  public void onHttpArtifactCacheScheduledEvent(HttpArtifactCacheEvent.Scheduled event) {
    if (event.getOperation() == ArtifactCacheEvent.Operation.STORE) {
      firstHttpCacheUploadScheduled.compareAndSet(null, event);
      remoteArtifactUploadsScheduledCount.incrementAndGet();
    }
  }

  @Subscribe
  public void onHttpArtifactCacheStartedEvent(HttpArtifactCacheEvent.Started event) {
    if (event.getOperation() == ArtifactCacheEvent.Operation.STORE) {
      remoteArtifactUploadsStartedCount.incrementAndGet();
    }
  }

  @Subscribe
  public void onHttpArtifactCacheFinishedEvent(HttpArtifactCacheEvent.Finished event) {
    switch (event.getOperation()) {
      case MULTI_FETCH:
      case FETCH:
        if (event.getCacheResult().isPresent()
            && event.getCacheResult().get().getType().isSuccess()) {
          networkStatsKeeper.incrementRemoteDownloadedArtifactsCount();
          event
              .getCacheResult()
              .get()
              .artifactSizeBytes()
              .ifPresent(networkStatsKeeper::addRemoteDownloadedArtifactsBytes);
        }
        break;
      case STORE:
        if (event.getStoreData().wasStoreSuccessful().orElse(false)) {
          remoteArtifactUploadedCount.incrementAndGet();
          event
              .getStoreData()
              .getArtifactSizeBytes()
              .ifPresent(remoteArtifactTotalBytesUploaded::addAndGet);
        } else {
          remoteArtifactUploadFailedCount.incrementAndGet();
        }
        break;
      case MULTI_CONTAINS:
        break;
    }
  }

  @Subscribe
  public void onHttpArtifactCacheShutdownEvent(HttpArtifactCacheEvent.Shutdown event) {
    httpShutdownEvent = event;
  }

  @Subscribe
  public void onDistBuildStatusEvent(DistBuildStatusEvent event) {
    synchronized (distBuildStatusLock) {
      distBuildStatus = Optional.of(event.getStatus());
    }
  }

  /** Update distributed build progress. */
  @Subscribe
  public void onDistBuildProgressEvent(DistBuildRemoteProgressEvent event) {
    CoordinatorBuildProgress buildProgress = event.getBuildProgress();
    distBuildTotalRulesCount =
        buildProgress.getTotalRulesCount() - buildProgress.getSkippedRulesCount();
    distBuildFinishedRulesCount = buildProgress.getBuiltRulesCount();
  }

  /**
   * A method to print the line responsible to show how our remote cache upload goes.
   *
   * @return the line
   */
  protected String renderRemoteUploads() {
    long bytesUploaded = remoteArtifactTotalBytesUploaded.longValue();
    String humanReadableBytesUploaded =
        convertToAllCapsIfNeeded(
            SizeUnit.toHumanReadableString(
                SizeUnit.getHumanReadableSize(bytesUploaded, SizeUnit.BYTES), locale));
    int scheduled = remoteArtifactUploadsScheduledCount.get();
    int complete = remoteArtifactUploadedCount.get();
    int failed = remoteArtifactUploadFailedCount.get();
    int uploading = remoteArtifactUploadsStartedCount.get() - (complete + failed);
    int pending = scheduled - (uploading + complete + failed);
    if (scheduled > 0) {
      return String.format(
          "%s (%d COMPLETE/%d FAILED/%d UPLOADING/%d PENDING)",
          humanReadableBytesUploaded, complete, failed, uploading, pending);
    } else {
      return humanReadableBytesUploaded;
    }
  }

  void showTopSlowBuildRules(ImmutableList.Builder<String> lines) {
    if (numberOfSlowRulesToShow == 0 || buildFinished == null) {
      return;
    }

    Comparator<UnflavoredBuildTarget> comparator =
        (target1, target2) -> {
          Long elapsedTime1 = Preconditions.checkNotNull(timeSpentMillisecondsInRules.get(target1));
          Long elapsedTime2 = Preconditions.checkNotNull(timeSpentMillisecondsInRules.get(target2));
          long delta = elapsedTime2 - elapsedTime1;
          return Long.compare(delta, 0L);
        };

    ImmutableList.Builder<String> slowRulesLogsBuilder = ImmutableList.builder();
    slowRulesLogsBuilder.add("");
    synchronized (timeSpentMillisecondsInRules) {
      if (timeSpentMillisecondsInRules.isEmpty()) {
        slowRulesLogsBuilder.add("Top slow rules: Buck didn't spend time in rules.");
      } else {
        slowRulesLogsBuilder.add("Top slow rules");
        Stream<UnflavoredBuildTarget> keys =
            timeSpentMillisecondsInRules.keySet().stream().sorted(comparator);
        keys.limit(numberOfSlowRulesToShow)
            .forEachOrdered(
                target -> {
                  if (timeSpentMillisecondsInRules.containsKey(target)) {
                    slowRulesLogsBuilder.add(
                        String.format(
                            "    %s: %s",
                            target, formatElapsedTime(timeSpentMillisecondsInRules.get(target))));
                  }
                });
      }
    }
    ImmutableList<String> slowRulesLogs = slowRulesLogsBuilder.build();
    LOG.info(slowRulesLogs.stream().collect(Collectors.joining("\n")));
    if (showSlowRulesInConsole) {
      lines.addAll(slowRulesLogs);
    }
  }

  @Override
  public void outputTrace(BuildId buildId) {}

  @Override
  public void close() throws IOException {
    progressEstimator.ifPresent(ProgressEstimator::close);
  }
}
