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
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.ActionGraphEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.BuckInitializationDurationEvent;
import com.facebook.buck.event.CommandEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.log.PerfTimesStats;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.rules.BuildEvent;
import com.facebook.buck.rules.BuildRuleEvent;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.eventbus.Subscribe;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class PerfTimesEventListener implements BuckEventListener {

  private final BuckEventBus eventBus;

  /**
   * Duration of time we spent in Python, in milliseconds.
   */
  private long pythonInitTimeMs = 0;

  /**
   * Duration of time we spent initializing Buck, in milliseconds.
   */
  private long javaInitTimeMs = 0;

  /**
   * Time from initialization time to when parsing starts, in milliseconds.
   */
  private long commandPreFlightTimeMs = 0;

  /**
   * Duration of parsing and processing of the BUCK files, in milliseconds.
   */
  private long parseTimeMs = 0;

  /**
   * Duration of the action graph generation, in milliseconds.
   */
  private long actionGraphTimeMs = 0;

  /**
   * Duration of the rule keys computation, from the start of rule key calculation to the fetching
   * of the first artifact from the remote cache, in milliseconds.
   */
  private long ruleKeyComputationTimeMs = 0;

  /**
   * Duration of the fetch operation, from the first fetch event to the start of the local build,
   * in milliseconds.
   */
  private long fetchWithoutLocalBuildTimeInParallelMs = 0;

  /**
   * Duration of the local build phase, from start of the local build to when the build completes,
   * in milliseconds.
   */
  private long timeToFinishBuildMs = 0;

  private final AtomicLong buildPhasesLastEvent = new AtomicLong();
  private final AtomicBoolean firstCacheFetchEvent = new AtomicBoolean(false);
  private final AtomicBoolean firstLocalBuildEvent = new AtomicBoolean(false);

  /**
   * @param eventBus When we finish gather all data points, we will post the result as event back
   *                 into event bus.
   * @param executionEnvironment We need this in order to get Python_init_time, as it is provided
   *                             by our Python wrapper.
   */
  public PerfTimesEventListener(
      BuckEventBus eventBus,
      ExecutionEnvironment executionEnvironment) {
    this.eventBus = eventBus;
    this.pythonInitTimeMs = Long.valueOf(executionEnvironment.getenv(
            "BUCK_PYTHON_SPACE_INIT_TIME",
            "0"));
  }

  /**
   * Helper method, returns the time difference from last invocation of this method.
   */
  private long getTimeDifferenceSinceLastEventToEvent(AbstractBuckEvent event) {
    long diff = event.getTimestamp() - buildPhasesLastEvent.get();
    buildPhasesLastEvent.set(event.getTimestamp());
    return diff;
  }

  @Override
  public void outputTrace(BuildId buildId) throws InterruptedException {}

  @Subscribe
  public synchronized void initializationFinished(BuckInitializationDurationEvent event) {
    javaInitTimeMs = event.getDuration();
    buildPhasesLastEvent.set(event.getTimestamp());
  }

  @Subscribe
  public synchronized void parseStarted(ParseEvent.Started started) {
    commandPreFlightTimeMs = getTimeDifferenceSinceLastEventToEvent(started);
  }

  @Subscribe
  public synchronized void parseFinished(ParseEvent.Finished finished) {
    parseTimeMs = getTimeDifferenceSinceLastEventToEvent(finished);
  }

  @Subscribe
  public void actionGraphFinished(ActionGraphEvent.Finished finished) {
    actionGraphTimeMs = getTimeDifferenceSinceLastEventToEvent(finished);
  }

  @Subscribe
  public void onHttpArtifactCacheStartedEvent(HttpArtifactCacheEvent.Started event) {
    if (firstCacheFetchEvent.compareAndSet(false, true)) {
      ruleKeyComputationTimeMs = getTimeDifferenceSinceLastEventToEvent(event);
    }
  }

  @Subscribe
  public void buildRuleWillBuildLocally(BuildRuleEvent.WillBuildLocally event) {
    if (firstLocalBuildEvent.compareAndSet(false, true)) {
      fetchWithoutLocalBuildTimeInParallelMs = getTimeDifferenceSinceLastEventToEvent(event);
    }
  }

  @Subscribe
  public synchronized void buildFinished(BuildEvent.Finished finished) {
    timeToFinishBuildMs = getTimeDifferenceSinceLastEventToEvent(finished);
  }

  @Subscribe
  public synchronized void commandFinished(CommandEvent.Finished finished) {
    long timeToFinishCommandMs = getTimeDifferenceSinceLastEventToEvent(finished);

    PerfTimesStats stats = PerfTimesStats.builder()
        .setPythonTimeMs(pythonInitTimeMs)
        .setInitTimeMs(javaInitTimeMs)
        .setParseTimeMs(commandPreFlightTimeMs)
        .setProcessingTimeMs(parseTimeMs)
        .setActionGraphTimeMs(actionGraphTimeMs)
        .setRulekeyTimeMs(ruleKeyComputationTimeMs)
        .setFetchTimeMs(fetchWithoutLocalBuildTimeInParallelMs)
        .setBuildTimeMs(timeToFinishBuildMs)
        .setInstallTimeMs(timeToFinishCommandMs)
        .build();
    eventBus.post(PerfTimesEvent.complete(stats));
  }


  public static class PerfTimesEvent extends AbstractBuckEvent {
    private String eventName;

    @JsonView(JsonViews.MachineReadableLog.class)
    private PerfTimesStats perfTimesStats;

    public static PerfTimesEvent.Complete complete(PerfTimesStats stats) {
      return new PerfTimesEvent.Complete(stats);
    }

    static class Complete extends PerfTimesEvent {
      Complete(PerfTimesStats stats) {
        super(stats, "PerfTimesStatsEvent.Complete");
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
