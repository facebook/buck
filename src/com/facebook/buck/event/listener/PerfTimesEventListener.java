/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.artifact_cache.HttpArtifactCacheEvent;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.BuckInitializationDurationEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.log.PerfTimesStats;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.eventbus.Subscribe;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class PerfTimesEventListener implements BuckEventListener {

  private final BuckEventBus eventBus;

  private final AtomicLong buildPhasesLastEvent = new AtomicLong();
  private final AtomicLong accumulatedParseTime = new AtomicLong();
  private final AtomicBoolean firstCacheFetchEvent = new AtomicBoolean(false);
  private final AtomicBoolean firstLocalBuildEvent = new AtomicBoolean(false);

  /**
   * Calculation of rule key computation time is complicated since we have multiple types and it
   * happens ad hoc. In order to calculate it we measure start and finish events.
   */
  private Map<BuildRule, TimeCostEntry<BuildRuleEvent>> ruleKeysCosts = new HashMap<>();

  private AtomicLong ruleKeyCalculationTotalTimeMs = new AtomicLong(0L);

  private PerfTimesStats.Builder perfTimesStatsBuilder = PerfTimesStats.builder();

  /**
   * @param eventBus When we finish gather all data points, we will post the result as event back
   *     into event bus.
   * @param executionEnvironment We need this in order to get Python_init_time, as it is provided by
   *     our Python wrapper.
   */
  public PerfTimesEventListener(BuckEventBus eventBus, ExecutionEnvironment executionEnvironment) {
    this.eventBus = eventBus;
    perfTimesStatsBuilder.setPythonTimeMs(
        executionEnvironment.getenv("BUCK_PYTHON_SPACE_INIT_TIME").map(Long::valueOf).orElse(0L));
  }

  @Subscribe
  public synchronized void initializationFinished(BuckInitializationDurationEvent event) {
    buildPhasesLastEvent.set(event.getTimestamp());
    perfTimesStatsBuilder.setInitTimeMs(event.getDuration());
    eventBus.post(PerfTimesEvent.update(perfTimesStatsBuilder.build()));
  }

  @Subscribe
  public synchronized void parseStarted(ParseEvent.Started started) {
    perfTimesStatsBuilder.setProcessingTimeMs(getTimeDifferenceSinceLastEventToEvent(started));
    eventBus.post(PerfTimesEvent.update(perfTimesStatsBuilder.build()));
  }

  @Subscribe
  public synchronized void parseFinished(ParseEvent.Finished finished) {
    long parseTime = getTimeDifferenceSinceLastEventToEvent(finished);
    perfTimesStatsBuilder.setParseTimeMs(accumulatedParseTime.addAndGet(parseTime));
    eventBus.post(PerfTimesEvent.update(perfTimesStatsBuilder.build()));
  }

  @Subscribe
  public synchronized void actionGraphFinished(ActionGraphEvent.Finished finished) {
    perfTimesStatsBuilder.setActionGraphTimeMs(getTimeDifferenceSinceLastEventToEvent(finished));
    eventBus.post(PerfTimesEvent.update(perfTimesStatsBuilder.build()));
  }

  /**
   * Records when we start calculating a rulekey.
   *
   * @param event the event that sings the start.
   */
  @Subscribe
  public synchronized void onRuleKeyCalculationStarted(BuildRuleEvent.StartedRuleKeyCalc event) {
    ruleKeysCosts.computeIfAbsent(event.getBuildRule(), buildRule -> new TimeCostEntry<>(event));
  }

  /**
   * Records when a rulekey finished calculation and it is time to extract time it took.
   *
   * @param event the event that signs the end of calculation.
   */
  @Subscribe
  public synchronized void onRuleKeyCalculationFinished(BuildRuleEvent.FinishedRuleKeyCalc event) {
    TimeCostEntry<BuildRuleEvent> timeCostEntry = ruleKeysCosts.get(event.getBuildRule());
    if (timeCostEntry != null && timeCostEntry.getStartEvent() != null) {
      long costTimeMs =
          TimeUnit.NANOSECONDS.toMillis(
              event.getNanoTime() - timeCostEntry.getStartEvent().getNanoTime());
      long newValue = ruleKeyCalculationTotalTimeMs.addAndGet(costTimeMs);
      perfTimesStatsBuilder.setTotalRulekeyTimeMs(newValue);
      eventBus.post(PerfTimesEvent.update(perfTimesStatsBuilder.build()));
    }
  }

  @Subscribe
  public synchronized void onHttpArtifactCacheStartedEvent(HttpArtifactCacheEvent.Started event) {
    if (firstCacheFetchEvent.compareAndSet(false, true)) {
      perfTimesStatsBuilder.setRulekeyTimeMs(getTimeDifferenceSinceLastEventToEvent(event));
      eventBus.post(PerfTimesEvent.update(perfTimesStatsBuilder.build()));
    }
  }

  @Subscribe
  public synchronized void buildRuleWillBuildLocally(BuildRuleEvent.WillBuildLocally event) {
    if (firstLocalBuildEvent.compareAndSet(false, true)) {
      perfTimesStatsBuilder.setFetchTimeMs(getTimeDifferenceSinceLastEventToEvent(event));
      eventBus.post(PerfTimesEvent.update(perfTimesStatsBuilder.build()));
    }
  }

  @Subscribe
  public synchronized void buildFinished(BuildEvent.Finished finished) {
    perfTimesStatsBuilder.setBuildTimeMs(getTimeDifferenceSinceLastEventToEvent(finished));
    eventBus.post(PerfTimesEvent.update(perfTimesStatsBuilder.build()));
  }

  @Subscribe
  public synchronized void installFinished(InstallEvent.Finished finished) {
    perfTimesStatsBuilder.setInstallTimeMs(getTimeDifferenceSinceLastEventToEvent(finished));
    eventBus.post(PerfTimesEvent.complete(perfTimesStatsBuilder.build()));
  }

  /** Helper method, returns the time difference from last invocation of this method. */
  private long getTimeDifferenceSinceLastEventToEvent(AbstractBuckEvent event) {
    long diff = event.getTimestamp() - buildPhasesLastEvent.get();
    buildPhasesLastEvent.set(event.getTimestamp());
    return diff;
  }

  public static class PerfTimesEvent extends AbstractBuckEvent {
    private String eventName;

    @JsonView(JsonViews.MachineReadableLog.class)
    private PerfTimesStats perfTimesStats;

    public static PerfTimesEvent.Complete complete(PerfTimesStats stats) {
      return new PerfTimesEvent.Complete(stats);
    }

    public static PerfTimesEvent.Update update(PerfTimesStats stats) {
      return new PerfTimesEvent.Update(stats);
    }

    /** This event is to be used when all of the steps of {@link PerfTimesStats} are present. */
    static class Complete extends PerfTimesEvent {
      Complete(PerfTimesStats stats) {
        super(stats, "PerfTimesStatsEvent.Complete");
      }
    }

    /** This event is to be used as an update, expect some of the fields to be empty. */
    static class Update extends PerfTimesEvent {
      Update(PerfTimesStats stats) {
        super(stats, "PerfTimesStatsEvent.Update");
      }
    }

    PerfTimesEvent(PerfTimesStats perfTimesStats, String eventName) {
      super(EventKey.unique());
      this.perfTimesStats = perfTimesStats;
      this.eventName = eventName;
    }

    PerfTimesStats getPerfTimesStats() {
      return perfTimesStats;
    }

    @Override
    protected String getValueString() {
      return perfTimesStats.toString();
    }

    @Override
    public String getEventName() {
      return eventName;
    }
  }
}
