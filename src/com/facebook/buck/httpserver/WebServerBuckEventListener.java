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

package com.facebook.buck.httpserver;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildEvent;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.test.event.IndividualTestEvent;
import com.facebook.buck.core.test.event.TestRunEvent;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.CompilerErrorEvent;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.ExternalEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.ProgressEvent;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.event.external.events.BuckEventExternalInterface;
import com.facebook.buck.event.external.events.CompilerErrorEventExternalInterface;
import com.facebook.buck.event.listener.stats.cache.CacheRateStatsKeeper;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.GuardedBy;

/**
 * {@link BuckEventListener} that is responsible for reporting events of interest to the {@link
 * StreamingWebSocketServlet}. This class passes high-level objects to the servlet, and the servlet
 * takes responsibility for serializing the objects as JSON down to the client.
 */
public class WebServerBuckEventListener implements BuckEventListener {

  private static final Logger LOG = Logger.get(WebServerBuckEventListener.class);

  private final StreamingWebSocketServlet streamingWebSocketServlet;
  private final Clock clock;
  private final ScheduledExecutorService executorService =
      Executors.newSingleThreadScheduledExecutor();

  @GuardedBy("this")
  private ScheduledFuture<?> buildStatusFuture;

  @GuardedBy("this")
  BuildState buildState = null;

  @GuardedBy("this")
  private int numberOfRules = 0;

  @GuardedBy("this")
  private int numberOfFinishedRules = 0;

  @GuardedBy("this")
  private int numberOfUpdatedRules = 0;

  @GuardedBy("this")
  private int numberOfParsedRules = 0;

  @GuardedBy("this")
  private int numberOfDownloadedCacheRules = 0;

  @GuardedBy("this")
  private int numberOfDownloadFailedCacheRules = 0;

  @GuardedBy("this")
  private int numberOfParsedFiles = 0;

  WebServerBuckEventListener(StreamingWebSocketServlet streamingWebSocketServlet, Clock clock) {
    this.streamingWebSocketServlet = streamingWebSocketServlet;
    this.clock = clock;
  }

  @VisibleForTesting
  Pair<Integer, Integer> downloadedCacheRulesPairResult() {
    return new Pair<>(numberOfDownloadedCacheRules, numberOfDownloadFailedCacheRules);
  }

  // Stateless pass-throughs: these events are passed through to WebSocket listeners unchanged.
  // (Some of them update internal state for the stateful BuildStatusEvent.)

  @Subscribe
  public void parseStarted(ParseEvent.Started started) {
    streamingWebSocketServlet.tellClients(started);
    synchronized (this) {
      buildState = BuildState.PARSING;
      scheduleBuildStatusEvent();
    }
  }

  @Subscribe
  public void parseFinished(ParseEvent.Finished finished) {
    streamingWebSocketServlet.tellClients(finished);
    synchronized (this) {
      buildState = BuildState.BUILDING_ACTION_GRAPH;
      scheduleBuildStatusEvent();
    }
  }

  @Subscribe
  public void buildStarted(BuildEvent.Started started) {
    streamingWebSocketServlet.tellClients(started);

    resetBuildState(BuildState.STARTING);
    scheduleBuildStatusEvent();
  }

  @Subscribe
  public void cacheRateStatsUpdate(
      CacheRateStatsKeeper.CacheRateStatsUpdateEvent cacheRateStatsUpdate) {
    streamingWebSocketServlet.tellClients(cacheRateStatsUpdate);
  }

  @Subscribe
  public void buildFinished(BuildEvent.Finished finished) {
    synchronized (this) {
      if (buildStatusFuture != null) {
        buildStatusFuture.cancel(false);
        buildStatusFuture = null;
      }
    }
    resetBuildState(null);

    streamingWebSocketServlet.tellClients(finished);
  }

  @Subscribe
  public void testRunStarted(TestRunEvent.Started event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void testRunCompleted(TestRunEvent.Finished event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void testAwaitingResults(IndividualTestEvent.Started event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void testResultsAvailable(IndividualTestEvent.Finished event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void installEventFinished(InstallEvent.Finished event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void externalEvent(ExternalEvent event) {
    String eventType = event.getData().getOrDefault(BuckEventExternalInterface.EVENT_TYPE_KEY, "");
    if (eventType.equals(CompilerErrorEventExternalInterface.COMPILER_ERROR_EVENT)) {
      processCompilerErrorEvent(event);
    } else {
      LOG.debug(
          "Skipping handling of event type: %s for received external event id: %s",
          eventType, event.getEventKey().getValue());
    }
  }

  private void processCompilerErrorEvent(ExternalEvent event) {
    ImmutableMap<String, String> eventData = event.getData();

    String target =
        eventData.getOrDefault(
            CompilerErrorEventExternalInterface.BUILD_TARGET_NAME_KEY, "UNKNOWN");
    String compilerName =
        eventData.getOrDefault(CompilerErrorEventExternalInterface.COMPILER_NAME_KEY, "");
    String errorMessage =
        eventData.getOrDefault(CompilerErrorEventExternalInterface.ERROR_MESSAGE_KEY, "");

    streamingWebSocketServlet.tellClients(
        CompilerErrorEvent.builder()
            .setError(errorMessage)
            .setTarget(target)
            .setTimestampMillis(event.getTimestampMillis())
            .setCompilerType(CompilerErrorEvent.CompilerType.determineCompilerType(compilerName))
            .setSuggestions(ImmutableSet.of())
            .build());
  }

  @Subscribe
  public void consoleEvent(ConsoleEvent event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void buildProgressUpdated(ProgressEvent.BuildProgressUpdated event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void parsingProgressUpdated(ProgressEvent.ParsingProgressUpdated event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void projectGenerationProgressUpdated(
      ProgressEvent.ProjectGenerationProgressUpdated event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void projectGenerationStarted(ProjectGenerationEvent.Started event) {
    streamingWebSocketServlet.tellClients(event);
  }

  @Subscribe
  public void projectGenerationFinished(ProjectGenerationEvent.Finished event) {
    streamingWebSocketServlet.tellClients(event);
  }

  // Stateful data tracking for BuildStatusEvent. These events are not passed through;
  // instead, their data is aggregated and used to generate BuildStatusEvent.

  @Subscribe
  private synchronized void ruleParseFinished(ParseBuckFileEvent.Finished ruleParseFinished) {
    numberOfParsedFiles++;
    numberOfParsedRules += ruleParseFinished.getNumRules();
    scheduleBuildStatusEvent();
  }

  @Subscribe
  public synchronized void ruleCountCalculated(BuildEvent.RuleCountCalculated calculated) {
    numberOfRules = calculated.getNumRules();
    scheduleBuildStatusEvent();
  }

  @Subscribe
  public synchronized void ruleCountUpdated(BuildEvent.UnskippedRuleCountUpdated updated) {
    numberOfRules = updated.getNumRules();
    scheduleBuildStatusEvent();
  }

  @Subscribe
  public synchronized void buildRuleFinished(BuildRuleEvent.Finished finished) {
    if (buildState == BuildState.BUILDING_ACTION_GRAPH) {
      buildState = BuildState.BUILDING;
    }
    numberOfFinishedRules++;
    if (finished.getCacheResult().getType() != CacheResultType.LOCAL_KEY_UNCHANGED_HIT) {
      numberOfUpdatedRules++;
    }
    processBuildRuleSuccessType(finished);
    scheduleBuildStatusEvent();
  }

  private synchronized void processBuildRuleSuccessType(BuildRuleEvent.Finished finished) {
    if (finished.getSuccessType().isPresent()) {
      BuildRuleSuccessType ruleSuccessType = finished.getSuccessType().get();
      switch (ruleSuccessType) {
        case FETCHED_FROM_CACHE:
        case FETCHED_FROM_CACHE_INPUT_BASED:
        case FETCHED_FROM_CACHE_MANIFEST_BASED:
          numberOfDownloadedCacheRules++;
          break;
        case BUILT_LOCALLY:
        case MATCHING_DEP_FILE_RULE_KEY:
        case MATCHING_INPUT_BASED_RULE_KEY:
        case MATCHING_RULE_KEY:
          // We are only interested in rules that have cache information and were eligible for
          // being downloaded from cache
          CacheResult cacheResult = finished.getCacheResult();
          boolean isCacheable =
              ((finished.getUploadToCacheResultType() == UploadToCacheResultType.CACHEABLE)
                  || (finished.getUploadToCacheResultType()
                      == UploadToCacheResultType.CACHEABLE_READONLY_CACHE));
          if (!(cacheResult.getType().isSuccess()) && isCacheable) {
            // Rule's cache was not processed with success and it was eligible to be downloaded from
            // cache, let's dive into the possible reasons for error
            switch (cacheResult.getType()) {
              case ERROR:
              case SOFT_ERROR:
              case MISS:
                numberOfDownloadFailedCacheRules++;
                break;
              case IGNORED:
                // The artifact was ignored most probably intentionally (e.g. --no-cache is used) at
                //  this point hence we dont process it as a failure
                break;
              case CONTAINS:
              case SKIPPED:
              case HIT:
              case LOCAL_KEY_UNCHANGED_HIT:
                LOG.warn(
                    "Unexpected cache result type value cacheable result: %s",
                    cacheResult.getType());
            }
          }
          break;
        default:
          LOG.warn("Unexpected success type value: %s", ruleSuccessType);
      }
    }
  }

  private synchronized void resetBuildState(BuildState newBuildState) {
    buildState = newBuildState;
    numberOfRules = 0;
    numberOfFinishedRules = 0;
    numberOfUpdatedRules = 0;
    numberOfParsedRules = 0;
    numberOfParsedFiles = 0;
  }

  /** Should be called whenever any build status state changes. */
  private synchronized void scheduleBuildStatusEvent() {
    if (buildStatusFuture != null) {
      return; // already scheduled
    }
    try {
      buildStatusFuture =
          executorService.schedule(this::sendBuildStatusEventInternal, 500, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      // This is expected if shutting down, so only log at verbose level:
      LOG.verbose("Rejected scheduling sendBuildStatusEventInternal", e);
    }
  }

  /** Internal implementation detail of scheduleBuildStatusEvent. */
  private void sendBuildStatusEventInternal() {
    BuildStatusEvent event;
    synchronized (this) {
      if (buildState == null) {
        return; // No build in progress.
      }
      // Clear the buildStatusFuture to indicate that we've sent
      // a BuildStatusEvent with the current state. If the state
      // subsequently changes, we'll schedule another future.
      buildStatusFuture = null;
      event =
          new BuildStatusEvent(
              clock.currentTimeMillis(),
              buildState,
              numberOfRules,
              numberOfFinishedRules,
              numberOfUpdatedRules,
              numberOfParsedRules,
              numberOfDownloadedCacheRules,
              numberOfDownloadFailedCacheRules,
              numberOfParsedFiles);
    } // avoid holding lock while calling tellClients()
    streamingWebSocketServlet.tellClients(event);
  }

  @Override
  public void close() {
    executorService.shutdown();
    try {
      executorService.awaitTermination(1000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      LOG.verbose("Failed to await termination of executor");
    }
  }

  /** NOT posted to the Buck event bus; only sent to WebSocket clients. */
  @SuppressWarnings("unused") // Jackson JSON introspection uses the fields
  private static class BuildStatusEvent implements BuckEventExternalInterface {

    private final long timestamp;
    public final BuildState state;
    public final int totalRulesCount;
    public final int finishedRulesCount;
    public final int updatedRulesCount;
    public final int parsedRulesCount;
    public final int downloadedRulesCount;
    public final int downloadFailedRulesCount;
    public final int parsedFilesCount;

    public BuildStatusEvent(
        long timestamp,
        BuildState state,
        int totalRulesCount,
        int finishedRulesCount,
        int updatedRulesCount,
        int parsedRulesCount,
        int downloadedRulesCount,
        int downloadFailedRulesCount,
        int parsedFilesCount) {
      this.timestamp = timestamp;
      this.state = state;
      this.totalRulesCount = totalRulesCount;
      this.finishedRulesCount = finishedRulesCount;
      this.updatedRulesCount = updatedRulesCount;
      this.parsedRulesCount = parsedRulesCount;
      this.downloadedRulesCount = downloadedRulesCount;
      this.downloadFailedRulesCount = downloadFailedRulesCount;
      this.parsedFilesCount = parsedFilesCount;
    }

    @Override
    public long getTimestampMillis() {
      return timestamp;
    }

    @Override
    public String getEventName() {
      return BuckEventExternalInterface.BUILD_STATUS_EVENT;
    }

    @Override
    public boolean storeLastInstanceAndReplayForNewClients() {
      // Because this event represents a snapshot of the build state, we want new clients to
      // immediately receive the latest snapshot when they connect. This ensures that new
      // clients immediately get the current build status even during periods where there are
      // no relevant changes to build state (e.g. action graph computation, during which no
      // events may arrive for a minute or two).
      return true;
    }
  }

  private enum BuildState {
    STARTING,
    PARSING,
    BUILDING_ACTION_GRAPH,
    BUILDING
  }
}
