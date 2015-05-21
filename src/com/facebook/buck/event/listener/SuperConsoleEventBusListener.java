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

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ArtifactCacheEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.rules.CacheResult;
import com.facebook.buck.rules.IndividualTestEvent;
import com.facebook.buck.rules.TestRunEvent;
import com.facebook.buck.step.StepEvent;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Console that provides rich, updating ansi output about the current build.
 */
public class SuperConsoleEventBusListener extends AbstractConsoleEventBusListener {
  /**
   * Amount of time a rule can run before we render it with as a warning.
   */
  private static final long WARNING_THRESHOLD_MS = 15000;

  /**
   * Amount of time a rule can run before we render it with as an error.
   */
  private static final long ERROR_THRESHOLD_MS = 30000;

  private static final Logger LOG = Logger.get(SuperConsoleEventBusListener.class);

  private final Optional<WebServer> webServer;
  private final ConcurrentMap<Long, Optional<? extends BuildRuleEvent>> threadsToRunningEvent;
  private final ConcurrentMap<Long, Optional<? extends LeafEvent>> threadsToRunningStep;

  // Time previously suspended runs of this rule.
  private final ConcurrentMap<BuildTarget, AtomicLong> accumulatedBuildRuleTime;

  // Counts the rules that have updated rule keys.
  private final AtomicInteger updated = new AtomicInteger(0);

  // Counts the number of cache hits and errors, respectively.
  private final AtomicInteger cacheHits = new AtomicInteger(0);
  private final AtomicInteger cacheErrors = new AtomicInteger(0);
  private final AtomicInteger cacheFetches = new AtomicInteger(0);

  private final ConcurrentLinkedQueue<ConsoleEvent> logEvents;

  private final ScheduledExecutorService renderScheduler;

  private final TestResultFormatter testFormatter;

  private int lastNumLinesPrinted;

  public SuperConsoleEventBusListener(
      Console console,
      Clock clock,
      ExecutionEnvironment executionEnvironment,
      Optional<WebServer> webServer) {
    super(console, clock);
    this.webServer = webServer;
    this.threadsToRunningEvent = new ConcurrentHashMap<>(executionEnvironment.getAvailableCores());
    this.threadsToRunningStep = new ConcurrentHashMap<>(executionEnvironment.getAvailableCores());
    this.accumulatedBuildRuleTime = new ConcurrentHashMap<>();

    this.logEvents = new ConcurrentLinkedQueue<>();

    this.renderScheduler = Executors.newScheduledThreadPool(1,
        new ThreadFactoryBuilder().setNameFormat(getClass().getSimpleName() + "-%d").build());
    this.testFormatter = new TestResultFormatter(console.getAnsi(), console.getVerbosity());
  }

  /**
   * Schedules a runnable that updates the console output at a fixed interval.
   */
  public void startRenderScheduler(long renderInterval, TimeUnit timeUnit) {
    LOG.debug("Starting render scheduler (interval %d ms)", timeUnit.toMillis(renderInterval));
    renderScheduler.scheduleAtFixedRate(new Runnable() {
      @Override
      public void run() {
        SuperConsoleEventBusListener.this.render();
      }
    }, /* initialDelay */ renderInterval, /* period */ renderInterval, timeUnit);
  }

  /**
   * Shuts down the thread pool and cancels the fixed interval runnable.
   */
  private synchronized void stopRenderScheduler() {
    LOG.debug("Stopping render scheduler");
    renderScheduler.shutdownNow();
  }

  @VisibleForTesting
  synchronized void render() {
    ImmutableList<String> lines = createRenderLinesAtTime(clock.currentTimeMillis());
    String nextFrame = clearLastRender() + Joiner.on("\n").join(lines);
    lastNumLinesPrinted = lines.size();

    // Synchronize on the DirtyPrintStreamDecorator to prevent interlacing of output.
    synchronized (console.getStdOut()) {
      synchronized (console.getStdErr()) {
        // If another source has written to stderr or stdout, stop rendering with the SuperConsole.
        // We need to do this to keep our updates consistent.
        boolean stdoutDirty = console.getStdOut().isDirty();
        boolean stderrDirty = console.getStdErr().isDirty();
        if (stdoutDirty || stderrDirty) {
          LOG.debug(
              "Stopping console output (stdout dirty %s, stderr dirty %s).",
              stdoutDirty, stderrDirty);
          stopRenderScheduler();
        } else if (!nextFrame.isEmpty()) {
          nextFrame = ansi.asNoWrap(nextFrame);
          console.getStdErr().getRawStream().println(nextFrame);
        }
      }
    }
  }

  /**
   * Creates a list of lines to be rendered at a given time.
   * @param currentTimeMillis The time in ms to use when computing elapsed times.
   */
  @VisibleForTesting
  ImmutableList<String> createRenderLinesAtTime(long currentTimeMillis) {
    ImmutableList.Builder<String> lines = ImmutableList.builder();

    if (parseStarted == null && parseFinished == null) {
      logEventPair(
          "PARSING BUCK FILES",
          /* suffix */ Optional.<String>absent(),
          currentTimeMillis,
          0L,
          projectBuildFileParseStarted,
          projectBuildFileParseFinished,
          lines);
    }

    long parseTime = logEventPair("PROCESSING BUCK FILES",
        /* suffix */ Optional.<String>absent(),
        currentTimeMillis,
        0L,
        parseStarted,
        actionGraphFinished,
        lines);

    // If parsing has not finished, then there is no build rule information to print yet.
    if (parseTime != UNFINISHED_EVENT_PAIR) {
      // Log build time, excluding time spent in parsing.
      String jobSummary = null;
      if (ruleCount.isPresent()) {
        List<String> columns = Lists.newArrayList();
        columns.add(String.format("%d/%d JOBS", numRulesCompleted.get(), ruleCount.get()));
        columns.add(String.format("%d UPDATED", updated.get()));
        if (cacheFetches.get() > 0) {
          columns.add(
              String.format(
                  "%.1f%% CACHE HITS",
                  100 * (double) cacheHits.get() / cacheFetches.get()));
          if (cacheErrors.get() > 0) {
            columns.add(
                String.format(
                    "%.1f%% CACHE ERRORS",
                    100 * (double) cacheErrors.get() / cacheFetches.get()));
          }
        }
        jobSummary = "(" + Joiner.on(", ").join(columns) + ")";
      }

      // If the Daemon is running and serving web traffic, print the URL to the Chrome Trace.
      String buildTrace = null;
      if (buildFinished != null && webServer.isPresent()) {
        Optional<Integer> port = webServer.get().getPort();
        if (port.isPresent()) {
          buildTrace = String.format(
               "Details: http://localhost:%s/trace/%s",
               port.get(),
               buildFinished.getBuildId());
        }
      }

      String suffix = Joiner.on(" ")
          .join(FluentIterable.of(new String[] {jobSummary, buildTrace})
              .filter(Predicates.notNull()));
      Optional<String> suffixOptional =
          suffix.isEmpty() ? Optional.<String>absent() : Optional.of(suffix);

      long buildTime = logEventPair("BUILDING",
          suffixOptional,
          currentTimeMillis,
          parseTime,
          buildStarted,
          buildFinished,
          lines);

      if (buildTime == UNFINISHED_EVENT_PAIR) {
        renderRules(currentTimeMillis, lines);
      }

      logEventPair("INSTALLING",
          /* suffix */ Optional.<String>absent(),
          currentTimeMillis,
          0L,
          installStarted,
          installFinished,
          lines);
    }
    renderLogMessages(lines);
    return lines.build();
  }

  /**
   * Adds log messages for rendering.
   * @param lines Builder of lines to render this frame.
   */
  private void renderLogMessages(ImmutableList.Builder<String> lines) {
    if (logEvents.isEmpty()) {
      return;
    }

    lines.add("Log:");
    for (ConsoleEvent logEvent : logEvents) {
      formatConsoleEvent(logEvent, lines);
    }
  }

  /**
   * Adds lines for rendering the rules that are currently running.
   * @param currentMillis The time in ms to use when computing elapsed times.
   * @param lines Builder of lines to render this frame.
   */
  private void renderRules(long currentMillis, ImmutableList.Builder<String> lines) {
    // Sort events by thread id.
    ImmutableList<Map.Entry<Long, Optional<? extends BuildRuleEvent>>> eventsByThread =
        FluentIterable.from(threadsToRunningEvent.entrySet())
          .toSortedList(new Comparator<Map.Entry<Long, Optional<? extends BuildRuleEvent>>>() {
            @Override
            public int compare(Map.Entry<Long, Optional<? extends BuildRuleEvent>> a,
                               Map.Entry<Long, Optional<? extends BuildRuleEvent>> b) {
              return Long.signum(a.getKey() - b.getKey());
            }
          });

    // For each thread that has ever run a rule, render information about that thread.
    for (Map.Entry<Long, Optional<? extends BuildRuleEvent>> entry : eventsByThread) {
      String threadLine = " |=> ";
      Optional<? extends BuildRuleEvent> startedEvent = entry.getValue();

      if (!startedEvent.isPresent()) {
        threadLine += "IDLE";
        threadLine = ansi.asSubtleText(threadLine);
      } else {
        AtomicLong accumulatedTime = accumulatedBuildRuleTime.get(
            startedEvent.get().getBuildRule().getBuildTarget());
        long elapsedTimeMs =
            (currentMillis - startedEvent.get().getTimestamp()) +
            (accumulatedTime != null ? accumulatedTime.get() : 0);
        Optional<? extends LeafEvent> leafEvent = threadsToRunningStep.get(entry.getKey());

        threadLine += String.format("%s...  %s",
            startedEvent.get().getBuildRule().getFullyQualifiedName(),
            formatElapsedTime(elapsedTimeMs));

        if (leafEvent != null && leafEvent.isPresent()) {
          threadLine += String.format(" (running %s[%s])",
              leafEvent.get().getCategory(),
              formatElapsedTime(currentMillis - leafEvent.get().getTimestamp()));

          if (elapsedTimeMs > WARNING_THRESHOLD_MS) {
            if (elapsedTimeMs > ERROR_THRESHOLD_MS) {
              threadLine = ansi.asErrorText(threadLine);
            } else {
              threadLine = ansi.asWarningText(threadLine);
            }
          }
        } else {
          // If a rule is scheduled on a thread but no steps have been scheduled yet, we are still
          // in the code checking to see if the rule has been cached locally.
          // Show "CHECKING LOCAL CACHE" to prevent thrashing the UI with super fast rules.
          threadLine += " (checking local cache)";
          threadLine = ansi.asSubtleText(threadLine);
        }
      }
      lines.add(threadLine);
    }
  }

  /**
   * @return A string of ansi characters that will clear the last set of lines printed by
   *     {@link SuperConsoleEventBusListener#createRenderLinesAtTime(long)}.
   */
  private String clearLastRender() {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < lastNumLinesPrinted; ++i) {
      result.append(ansi.cursorPreviousLine(1));
      result.append(ansi.clearLine());
    }
    return result.toString();
  }

  @Subscribe
  public void buildRuleStarted(BuildRuleEvent.Started started) {
    threadsToRunningEvent.put(started.getThreadId(), Optional.of(started));
    accumulatedBuildRuleTime.put(started.getBuildRule().getBuildTarget(), new AtomicLong(0));
  }

  @Subscribe
  public void buildRuleFinished(BuildRuleEvent.Finished finished) {
    threadsToRunningEvent.put(finished.getThreadId(), Optional.<BuildRuleEvent>absent());
    accumulatedBuildRuleTime.remove(finished.getBuildRule().getBuildTarget());
    CacheResult cacheResult = finished.getCacheResult();
    if (cacheResult.getType() != CacheResult.Type.LOCAL_KEY_UNCHANGED_HIT) {
      updated.incrementAndGet();
    }
  }

  @Subscribe
  public void buildRuleSuspended(BuildRuleEvent.Suspended suspended) {
    Optional<? extends BuildRuleEvent> started =
        Preconditions.checkNotNull(
            threadsToRunningEvent.put(suspended.getThreadId(), Optional.<BuildRuleEvent>absent()));
    Preconditions.checkState(started.isPresent());
    Preconditions.checkState(suspended.getBuildRule().equals(started.get().getBuildRule()));
    AtomicLong current = accumulatedBuildRuleTime.get(suspended.getBuildRule().getBuildTarget());
    // It's technically possible that another thread receives resumed and finished events
    // while we're processing this one, so we have to check that the current counter exists.
    if (current != null) {
      current.getAndAdd(suspended.getTimestamp() - started.get().getTimestamp());
    }
  }

  @Subscribe
  public void buildRuleResumed(BuildRuleEvent.Resumed resumed) {
    threadsToRunningEvent.put(resumed.getThreadId(), Optional.of(resumed));
  }

  @Subscribe
  public void stepStarted(StepEvent.Started started) {
    threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void stepFinished(StepEvent.Finished finished) {
    threadsToRunningStep.put(finished.getThreadId(), Optional.<StepEvent>absent());
  }

  @Subscribe
  public void artifactStarted(ArtifactCacheEvent.Started started) {
    threadsToRunningStep.put(started.getThreadId(), Optional.of(started));
  }

  @Subscribe
  public void artifactFinished(ArtifactCacheEvent.Finished finished) {
    threadsToRunningStep.put(finished.getThreadId(), Optional.<StepEvent>absent());
    if (finished.getOperation() == ArtifactCacheEvent.Operation.FETCH) {
      Preconditions.checkState(finished.getCacheResult().isPresent());
      cacheFetches.incrementAndGet();
      if (finished.getCacheResult().get().getType() == CacheResult.Type.HIT) {
        cacheHits.incrementAndGet();
      } else if (finished.getCacheResult().get().getType() == CacheResult.Type.ERROR) {
        cacheErrors.incrementAndGet();
      }
    }
  }

  @Subscribe
  public void testRunStarted(TestRunEvent.Started event) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    testFormatter.runStarted(builder,
        event.isRunAllTests(),
        event.getTestSelectorList(),
        event.shouldExplainTestSelectorList(),
        event.getTargetNames());
    logEvents.add(ConsoleEvent.info(Joiner.on('\n').join(builder.build())));
  }

  @Subscribe
  public void testResultsAvailable(IndividualTestEvent.Finished event) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    testFormatter.reportResult(builder, event.getResults());

    // We could just format as if this was a log entry, but doing so causes the console to flicker
    // obscenely and isn't conducive to running tests. Since this is the last step of the build, the
    // output will still render just fine if we write directly to stderr.
    ImmutableList<String> lines = builder.build();
    // When using TestSelectors, we may have a target that produces no test results and therefore
    // no lines of output, so we should not print anything at all.
    if (!lines.isEmpty()) {
      console.getStdErr().println(Joiner.on('\n').join(lines));
    }
  }

  @Subscribe
  public void testRunComplete(TestRunEvent.Finished event) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    testFormatter.runComplete(builder, event.getResults());
    console.getStdErr().println(Joiner.on('\n').join(builder.build()));
  }

  @Subscribe
  public void logEvent(ConsoleEvent event) {
    logEvents.add(event);
  }

  @Override
  public synchronized void close() throws IOException {
    stopRenderScheduler();
    render(); // Ensure final frame is rendered.
  }
}
