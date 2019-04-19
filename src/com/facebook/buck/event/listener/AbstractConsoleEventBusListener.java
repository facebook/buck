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

import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.core.util.log.Logger;
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
import com.facebook.buck.event.listener.stats.cache.CacheRateStatsKeeper;
import com.facebook.buck.event.listener.stats.cache.NetworkStatsTracker;
import com.facebook.buck.event.listener.stats.cache.RemoteArtifactUploadStats;
import com.facebook.buck.event.listener.stats.cache.RemoteDownloadStats;
import com.facebook.buck.event.listener.stats.parse.ParseStatsTracker;
import com.facebook.buck.event.listener.stats.stampede.DistBuildStatsTracker;
import com.facebook.buck.event.listener.util.EventInterval;
import com.facebook.buck.event.listener.util.ProgressEstimator;
import com.facebook.buck.test.TestRuleEvent;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.i18n.NumberFormatter;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.util.unit.SizeUnit;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import com.google.common.eventbus.Subscribe;
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
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.stringtemplate.v4.ST;

/**
 * Base class for {@link BuckEventListener}s responsible for outputting information about the
 * running build to {@code stderr}.
 */
public abstract class AbstractConsoleEventBusListener implements BuckEventListener {

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
  protected final RenderingConsole console;
  protected final Clock clock;
  protected final Verbosity verbosity;
  protected final Ansi ansi;
  private final Locale locale;
  private final boolean showTextInAllCaps;
  private final int numberOfSlowRulesToShow;
  private final boolean showSlowRulesInConsole;
  private final Map<UnflavoredBuildTargetView, Long> timeSpentMillisecondsInRules;

  @Nullable protected volatile ProjectGenerationEvent.Started projectGenerationStarted;
  @Nullable protected volatile ProjectGenerationEvent.Finished projectGenerationFinished;

  @Nullable protected volatile WatchmanStatusEvent.Started watchmanStarted;
  @Nullable protected volatile WatchmanStatusEvent.Finished watchmanFinished;

  protected ConcurrentLinkedDeque<ActionGraphEvent.Started> actionGraphStarted;
  protected ConcurrentLinkedDeque<ActionGraphEvent.Finished> actionGraphFinished;

  protected ConcurrentHashMap<EventKey, EventInterval> actionGraphEvents;

  @Nullable protected volatile BuildEvent.Started buildStarted;
  @Nullable protected volatile BuildEvent.Finished buildFinished;

  @Nullable protected volatile BuildEvent.DistBuildStarted distBuildStarted;
  @Nullable protected volatile BuildEvent.DistBuildFinished distBuildFinished;

  @Nullable protected volatile InstallEvent.Started installStarted;
  @Nullable protected volatile InstallEvent.Finished installFinished;

  @Nullable protected volatile CommandEvent.Finished commandFinished;

  protected volatile OptionalInt ruleCount = OptionalInt.empty();
  protected Optional<String> publicAnnouncements = Optional.empty();

  protected final AtomicInteger numRulesCompleted = new AtomicInteger();

  protected Optional<ProgressEstimator> progressEstimator = Optional.empty();

  protected final NetworkStatsTracker networkStatsTracker;
  protected final ParseStatsTracker parseStats;
  protected final DistBuildStatsTracker distStatsTracker;

  protected BuildRuleThreadTracker buildRuleThreadTracker;

  /** Commands that should print out the build details, if provided */
  protected final ImmutableSet<String> buildDetailsCommands;

  private final AtomicBoolean topSlowestRulesLogged = new AtomicBoolean(false);

  public AbstractConsoleEventBusListener(
      RenderingConsole console,
      Clock clock,
      Locale locale,
      ExecutionEnvironment executionEnvironment,
      boolean showTextInAllCaps,
      int numberOfSlowRulesToShow,
      boolean showSlowRulesInConsole,
      ImmutableSet<String> buildDetailsCommands) {
    this.console = console;
    this.parseStats = new ParseStatsTracker();
    this.networkStatsTracker = new NetworkStatsTracker();
    this.distStatsTracker = new DistBuildStatsTracker();
    this.clock = clock;
    this.locale = locale;
    this.ansi = console.getAnsi();
    this.verbosity = console.getVerbosity();
    this.showTextInAllCaps = showTextInAllCaps;
    this.numberOfSlowRulesToShow = numberOfSlowRulesToShow;
    this.showSlowRulesInConsole = showSlowRulesInConsole;
    this.timeSpentMillisecondsInRules = new HashMap<>();

    this.projectGenerationStarted = null;
    this.projectGenerationFinished = null;

    this.watchmanStarted = null;
    this.watchmanFinished = null;

    this.actionGraphStarted = new ConcurrentLinkedDeque<>();
    this.actionGraphFinished = new ConcurrentLinkedDeque<>();

    this.actionGraphEvents = new ConcurrentHashMap<>();

    this.buildStarted = null;
    this.buildFinished = null;

    this.installStarted = null;
    this.installFinished = null;

    this.buildRuleThreadTracker = new BuildRuleThreadTracker(executionEnvironment);
    this.buildDetailsCommands = buildDetailsCommands;
  }

  public void register(BuckEventBus buildEventBus) {
    buildEventBus.register(this);
    buildEventBus.register(parseStats);
    buildEventBus.register(networkStatsTracker);
    buildEventBus.register(distStatsTracker);
  }

  public static String getBuildDetailsLine(BuildId buildId, String buildDetailsTemplate) {
    return new ST(buildDetailsTemplate, '{', '}').add("build_id", buildId).render();
  }

  public static String getBuildLogLine(BuildId buildId) {
    return "Build UUID: " + buildId;
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
      parseStats.setProgressEstimator(estimator);
    }
  }

  protected String formatElapsedTime(long elapsedTimeMs) {
    long minutes = elapsedTimeMs / 60_000L;
    String seconds = TIME_FORMATTER.format(locale, elapsedTimeMs / 1000.0 - (minutes * 60));
    return minutes == 0 ? String.valueOf(seconds) : String.format("%2$dm %1$s", seconds, minutes);
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
      return distStatsTracker.getApproximateProgress();
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
   * @param eventIntervals the events to filter.
   * @return a list of all events from the given iterable that fall between the given start and end
   *     times. If an event straddles the given start or end, it will be replaced with a proxy event
   *     pair that cuts off at exactly the start or end.
   */
  protected static Collection<EventInterval> getEventsBetween(
      long start, long end, Iterable<EventInterval> eventIntervals) {
    List<EventInterval> outEvents = new ArrayList<>();
    for (EventInterval ep : eventIntervals) {
      long startTime = ep.getStartTime();
      long endTime = ep.getEndTime();

      if (ep.isComplete()) {
        if (startTime >= start && endTime <= end) {
          outEvents.add(ep);
        } else if (startTime >= start && startTime <= end) {
          // If the start time is within bounds, but the end time is not, replace with a proxy
          outEvents.add(EventInterval.proxy(startTime, end));
        } else if (endTime <= end && endTime >= start) {
          // If the end time is within bounds, but the start time is not, replace with a proxy
          outEvents.add(EventInterval.proxy(start, endTime));
        }
      } else if (ep.isOngoing()) {
        // If the event is ongoing, replace with a proxy
        outEvents.add(EventInterval.proxy(startTime, end));
      } // Ignore the case where we have an end event but not a start. Just drop that EventInterval.
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
  protected long logEventInterval(
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
    EventInterval interval =
        EventInterval.builder()
            .setStart(startEvent.getTimestampMillis())
            .setFinish(
                finishedEvent == null
                    ? OptionalLong.empty()
                    : OptionalLong.of(finishedEvent.getTimestampMillis()))
            .build();
    return logEventInterval(
        prefix,
        suffix,
        currentMillis,
        offsetMs,
        ImmutableList.of(interval),
        progress,
        minimum,
        lines);
  }

  /**
   * Adds a line about a the state of cache uploads to lines.
   *
   * @param lines The builder to append lines to.
   */
  protected void logHttpCacheUploads(ImmutableList.Builder<String> lines) {
    if (networkStatsTracker.haveUploadsStarted()) {
      boolean isFinished = networkStatsTracker.haveUploadsFinished();
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
   * @param eventIntervals the collection of start/end events to measure elapsed time.
   * @param lines The builder to append lines to.
   * @return True if all events are finished, false otherwise
   */
  protected boolean addLineFromEvents(
      String prefix,
      Optional<String> suffix,
      long currentMillis,
      Collection<EventInterval> eventIntervals,
      Optional<Double> progress,
      Optional<Long> minimum,
      ImmutableList.Builder<String> lines) {
    return addLineFromEventInterval(
        prefix, suffix, currentMillis, getStartAndFinish(eventIntervals), progress, minimum, lines);
  }

  /**
   * Adds a line about an {@link EventInterval} to lines.
   *
   * @param prefix Prefix to print for this event pair.
   * @param suffix Suffix to print for this event pair.
   * @param currentMillis The current time in milliseconds.
   * @param startAndFinish the event interval to measure elapsed time.
   * @param lines The builder to append lines to.
   * @return True if all events are finished, false otherwise
   */
  protected boolean addLineFromEventInterval(
      String prefix,
      Optional<String> suffix,
      long currentMillis,
      EventInterval startAndFinish,
      Optional<Double> progress,
      Optional<Long> minimum,
      ImmutableList.Builder<String> lines) {
    if (!startAndFinish.getStart().isPresent()) {
      // nothing to display, event has not even started yet
      return false;
    }

    boolean isFinished = startAndFinish.getFinish().isPresent();
    long startTime = startAndFinish.getStartTime();
    long endTime = isFinished ? startAndFinish.getEndTime() : currentMillis;
    long elapsedTime = endTime - startTime;

    if (minimum.isPresent() && elapsedTime < minimum.get()) {
      return isFinished;
    }

    String result = prefix;
    if (!isFinished) {
      result += "... ";
    } else {
      result += showTextInAllCaps ? ": FINISHED IN " : ": finished in ";
    }
    result += formatElapsedTime(elapsedTime);

    if (progress.isPresent()) {
      result += isFinished ? " (100%)" : " (" + Math.round(progress.get() * 100) + "%)";
    }

    if (suffix.isPresent()) {
      result += " " + suffix.get();
    }

    lines.add(result);
    return isFinished;
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
   * @param eventIntervals the collection of start/end events to sum up when calculating elapsed
   *     time.
   * @param lines The builder to append lines to.
   * @return The summed time between start and finished events if each start event has a matching
   *     finished event, otherwise {@link AbstractConsoleEventBusListener#UNFINISHED_EVENT_PAIR}.
   */
  @Deprecated
  protected long logEventInterval(
      String prefix,
      Optional<String> suffix,
      long currentMillis,
      long offsetMs,
      Collection<EventInterval> eventIntervals,
      Optional<Double> progress,
      Optional<Long> minimum,
      ImmutableList.Builder<String> lines) {
    if (eventIntervals.isEmpty()) {
      return UNFINISHED_EVENT_PAIR;
    }

    long completedRunTimesMs = getTotalCompletedTimeFromEventIntervals(eventIntervals);
    long currentlyRunningTime = getWorkingTimeFromLastStartUntilNow(eventIntervals, currentMillis);
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
   * Calculate event pair that start and end the sequence. If there is any ongoing event, end event
   * would be empty.
   *
   * @param eventIntervals the collection of event starts/stops.
   * @return The pair of events, start event is the earliest start event, end event is the latest
   *     finish event, or empty if there are ongoing events, i.e. not completed pairs
   */
  private static EventInterval getStartAndFinish(Collection<EventInterval> eventIntervals) {

    OptionalLong start = OptionalLong.empty();
    OptionalLong end = OptionalLong.empty();
    boolean anyOngoing = false;

    for (EventInterval pair : eventIntervals) {
      OptionalLong candidate = pair.getStart();
      if (!start.isPresent()
          || (candidate.isPresent() && candidate.getAsLong() < start.getAsLong())) {
        start = candidate;
      }

      if (anyOngoing) {
        continue;
      }

      candidate = pair.getFinish();
      if (!candidate.isPresent()) {
        anyOngoing = true;
        end = OptionalLong.empty();
        continue;
      }

      if (!end.isPresent() || candidate.getAsLong() > end.getAsLong()) {
        end = candidate;
      }
    }
    return EventInterval.of(start, end);
  }

  /**
   * Takes a collection of start and finished events. If there are any events that have a start, but
   * no finished time, the collection is considered ongoing.
   *
   * @param eventIntervals the collection of event starts/stops.
   * @param currentMillis the current time.
   * @return -1 if all events are completed, otherwise the time elapsed between the latest event and
   *     currentMillis.
   */
  @Deprecated
  protected static long getWorkingTimeFromLastStartUntilNow(
      Collection<EventInterval> eventIntervals, long currentMillis) {
    // We examine all events to determine whether we have any incomplete events and also
    // to get the latest timestamp available (start or stop).
    long latestTimestamp = 0L;
    long earliestOngoingStart = Long.MAX_VALUE;
    boolean anyEventIsOngoing = false;
    for (EventInterval pair : eventIntervals) {
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
   * @param eventIntervals a set of paired events (incomplete events are okay).
   * @return the sum of all times between matched event pairs.
   */
  protected static long getTotalCompletedTimeFromEventIntervals(
      Collection<EventInterval> eventIntervals) {
    long totalTime = 0L;
    // Flatten the event groupings into a timeline, so that we don't over count parallel work.
    RangeSet<Long> timeline = TreeRangeSet.create();
    for (EventInterval pair : eventIntervals) {
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
  protected ImmutableList<String> formatConsoleEvent(ConsoleEvent logEvent) {
    if (logEvent.getMessage() == null) {
      LOG.error("Got logEvent with null message");
      return ImmutableList.of();
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
      return ImmutableList.copyOf(Splitter.on(System.lineSeparator()).split(formattedLine));
    }
    return ImmutableList.of();
  }

  @Subscribe
  public void commandStartedEvent(CommandEvent.Started startedEvent) {
    progressEstimator.ifPresent(
        estimator ->
            estimator.setCurrentCommand(startedEvent.getCommandName(), startedEvent.getArgs()));
  }

  public static void aggregateStartedEvent(
      ConcurrentHashMap<EventKey, EventInterval> map, BuckEvent started) {
    map.compute(
        started.getEventKey(),
        (key, pair) ->
            pair == null
                ? EventInterval.builder().setStart(started.getTimestampMillis()).build()
                : pair.withStart(started.getTimestampMillis()));
  }

  public static void aggregateFinishedEvent(
      ConcurrentHashMap<EventKey, EventInterval> map, BuckEvent finished) {
    map.compute(
        finished.getEventKey(),
        (key, pair) ->
            pair == null
                ? EventInterval.builder().setFinish(finished.getTimestampMillis()).build()
                : pair.withFinish(finished.getTimestampMillis()));
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
  }

  @Subscribe
  public void ruleCountUpdated(BuildEvent.UnskippedRuleCountUpdated updated) {
    ruleCount = OptionalInt.of(updated.getNumRules());
    progressEstimator.ifPresent(estimator -> estimator.setNumberOfRules(updated.getNumRules()));
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
          networkStatsTracker.getCacheRateStats();
      columns.add(
          String.format(
              locale,
              "%d " + convertToAllCapsIfNeeded("updated"),
              cacheRateStats.getUpdatedRulesCount()));
      jobSummary = String.join(", ", columns);
    }

    return Strings.isNullOrEmpty(jobSummary) ? Optional.empty() : Optional.of(jobSummary);
  }

  protected String getNetworkStatsLine(@Nullable BuildEvent.Finished finishedEvent) {
    String parseLine =
        finishedEvent != null
            ? convertToAllCapsIfNeeded("Downloaded")
            : convertToAllCapsIfNeeded("Downloading") + "...";
    List<String> columns = new ArrayList<>();

    RemoteDownloadStats downloadStats = networkStatsTracker.getRemoteDownloadStats();

    long remoteDownloadedBytes = downloadStats.getBytes();
    Pair<Double, SizeUnit> redableRemoteDownloadedBytes =
        SizeUnit.getHumanReadableSize(remoteDownloadedBytes, SizeUnit.BYTES);
    columns.add(
        String.format(
            locale, "%d " + convertToAllCapsIfNeeded("artifacts"), downloadStats.getArtifacts()));
    columns.add(
        String.format(
            locale,
            "%s",
            convertToAllCapsIfNeeded(
                SizeUnit.toHumanReadableString(redableRemoteDownloadedBytes, locale))));
    CacheRateStatsKeeper.CacheRateStatsUpdateEvent cacheRateStats =
        networkStatsTracker.getCacheRateStats();
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
    return parseLine + " " + String.join(", ", columns);
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
        UnflavoredBuildTargetView unflavoredTarget =
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
  public void commandFinished(CommandEvent.Finished event) {
    commandFinished = event;
  }

  /**
   * A method to print the line responsible to show how our remote cache upload goes.
   *
   * @return the line
   */
  protected String renderRemoteUploads() {
    RemoteArtifactUploadStats uploadStats = networkStatsTracker.getRemoteArtifactUploadStats();

    long bytesUploaded = uploadStats.getTotalBytes();
    String humanReadableBytesUploaded =
        convertToAllCapsIfNeeded(
            SizeUnit.toHumanReadableString(
                SizeUnit.getHumanReadableSize(bytesUploaded, SizeUnit.BYTES), locale));
    int scheduled = uploadStats.getScheduled();
    int complete = uploadStats.getUploaded();
    int failed = uploadStats.getFailed();
    int uploading = uploadStats.getStarted() - (complete + failed);
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

    Comparator<UnflavoredBuildTargetView> comparator =
        (target1, target2) -> {
          Long elapsedTime1 = Objects.requireNonNull(timeSpentMillisecondsInRules.get(target1));
          Long elapsedTime2 = Objects.requireNonNull(timeSpentMillisecondsInRules.get(target2));
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
        Stream<UnflavoredBuildTargetView> keys =
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
    logTopSlowBuildRulesIfNotLogged(slowRulesLogs);

    if (showSlowRulesInConsole) {
      lines.addAll(slowRulesLogs);
    }
  }

  private void logTopSlowBuildRulesIfNotLogged(ImmutableList<String> slowRulesLogs) {
    if (topSlowestRulesLogged.compareAndSet(false, true)) {
      LOG.info(String.join(System.lineSeparator(), slowRulesLogs));
    }
  }

  @Override
  public void close() throws IOException {
    progressEstimator.ifPresent(ProgressEstimator::close);
  }
}
