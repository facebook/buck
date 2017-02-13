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
   * Duration of time we spent in Python.
   */
  private long pythonInitTime = 0;
  /**
   * Duration of time we spent initializing Buck.
   */
  private long javaInitTime = 0;
  /**
   * Time from when command is ready to start after java_init_time to the moment when parsing
   * starts.
   */
  private long commandPreflightTime = 0;
  /**
   * Duration of parsing and processing of the BUCK files.
   */
  private long parseTime = 0;
  /**
   * Duration of the action graph generation.
   */
  private long actionGraphTime = 0;
  /**
   * Duration of the rule keys computation, from moment when we start computing them to the moment
   * when we start fetching first artifact from the remote cache.
   */
  private long ruleKeyComputationTime = 0;
  /**
   * Duration of the fetch operation, from moment when first fetch event happens to the moment when
   * we start building locally for the first time.
   */
  private long fetchWithoutLocalBuildTimeInParallel = 0;
  /**
   * Duration of the local build phase, from the moment we start building locally for the first time
   * to the moment when build completes.
   */
  private long timeToFinishBuild = 0;
  /**
   * Duration of the rest of the command, from the moment when build finished.
   */
  private long timeToFinishCommand = 0;

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
    this.pythonInitTime = Long.valueOf(
        executionEnvironment.getenv(
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
    javaInitTime = event.getDuration();
    buildPhasesLastEvent.set(event.getTimestamp());
  }

  @Subscribe
  public synchronized void parseStarted(ParseEvent.Started started) {
    commandPreflightTime = getTimeDifferenceSinceLastEventToEvent(started);
  }

  @Subscribe
  public synchronized void parseFinished(ParseEvent.Finished finished) {
    parseTime = getTimeDifferenceSinceLastEventToEvent(finished);
  }

  @Subscribe
  public void actionGraphFinished(ActionGraphEvent.Finished finished) {
    actionGraphTime = getTimeDifferenceSinceLastEventToEvent(finished);
  }

  @Subscribe
  public void onHttpArtifactCacheStartedEvent(HttpArtifactCacheEvent.Started event) {
    if (firstCacheFetchEvent.compareAndSet(false, true)) {
      ruleKeyComputationTime = getTimeDifferenceSinceLastEventToEvent(event);
    }
  }

  @Subscribe
  public void buildRuleWillBuildLocally(BuildRuleEvent.WillBuildLocally event) {
    if (firstLocalBuildEvent.compareAndSet(false, true)) {
      fetchWithoutLocalBuildTimeInParallel = getTimeDifferenceSinceLastEventToEvent(event);
    }
  }

  @Subscribe
  public synchronized void buildFinished(BuildEvent.Finished finished) {
    timeToFinishBuild = getTimeDifferenceSinceLastEventToEvent(finished);
  }

  @Subscribe
  public synchronized void commandFinished(CommandEvent.Finished finished) {
    timeToFinishCommand = getTimeDifferenceSinceLastEventToEvent(finished);

    PerfTimesStats stats = PerfTimesStats.builder()
        .setPythonTimeMs(pythonInitTime)
        .setInitTimeMs(javaInitTime)
        .setParseTimeMs(commandPreflightTime)
        .setProcessingTimeMs(parseTime)
        .setActionGraphTimeMs(actionGraphTime)
        .setRulekeyTimeMs(ruleKeyComputationTime)
        .setFetchTimeMs(fetchWithoutLocalBuildTimeInParallel)
        .setBuildTimeMs(timeToFinishBuild)
        .setInstallTimeMs(timeToFinishCommand)
        .build();
    eventBus.post(new PerfTimesEvent(stats));
  }


  public class PerfTimesEvent extends AbstractBuckEvent {

    @JsonView(JsonViews.MachineReadableLog.class)
    private PerfTimesStats perfTimesStats;

    public PerfTimesEvent(PerfTimesStats perfTimesStats) {
      super(EventKey.unique());
      this.perfTimesStats = perfTimesStats;
    }

    public PerfTimesStats getPerfTimesStats() {
      return perfTimesStats;
    }

    @Override
    protected String getValueString() {
      return perfTimesStats.toString();
    }

    @Override
    public String getEventName() {
      return "PerfTimesStatsEvent";
    }
  }
}
