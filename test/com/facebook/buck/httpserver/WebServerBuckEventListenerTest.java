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

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.parser.ParseEvent;
import com.facebook.buck.util.timing.DefaultClock;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class WebServerBuckEventListenerTest {

  private final StreamingWebSocketServlet webSocketServelet = new StreamingWebSocketServlet();
  WebServerBuckEventListener fakeEventListener;
  FakeBuildRule buildRule;
  RuleKey buildRuleKey;

  @Before
  public void setUp() {
    buildRule = new FakeBuildRule(BuildTargetFactory.newInstance("//repo/path:module"));
    buildRuleKey = new RuleKey("1234");
    fakeEventListener = new WebServerBuckEventListener(webSocketServelet, new DefaultClock());
  }

  private void fakeOutParseEvents() {
    ParseEvent.Started parseStart = ParseEvent.started(ImmutableSet.of());
    fakeEventListener.parseStarted(parseStart);
    fakeEventListener.parseFinished(ParseEvent.finished(parseStart, 0, Optional.empty()));
  }

  private BuildRuleEvent.Finished finishedBuildSuccessRuleEventWithCacheResult(
      CacheResult cacheResult, BuildRuleSuccessType successType) {
    return BuildRuleEvent.finished(
        BuildRuleEvent.started(buildRule, new BuildRuleDurationTracker()),
        BuildRuleKeys.of(buildRuleKey),
        BuildRuleStatus.SUCCESS,
        cacheResult,
        Optional.empty(),
        Optional.of(successType),
        UploadToCacheResultType.CACHEABLE,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  @Test
  public void sendsFinalBuildEventNoSuccessTypeAvailable() {
    fakeOutParseEvents();

    BuildRuleEvent.Finished finished =
        BuildRuleEvent.finished(
            BuildRuleEvent.started(buildRule, new BuildRuleDurationTracker()),
            BuildRuleKeys.of(buildRuleKey),
            BuildRuleStatus.SUCCESS,
            CacheResult.skipped(),
            Optional.empty(),
            Optional.empty(),
            UploadToCacheResultType.CACHEABLE,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    fakeEventListener.buildRuleFinished(finished);
    Pair<Integer, Integer> downloadedCacheRulesResult =
        fakeEventListener.downloadedCacheRulesPairResult();
    Integer downloadedRules = downloadedCacheRulesResult.getFirst();
    Integer failedToDownloadRules = downloadedCacheRulesResult.getSecond();

    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        downloadedRules == 0);
    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        failedToDownloadRules == 0);
  }

  @Test
  public void sendsFinalBuildEventFetchedFromCacheSuccess() {
    fakeOutParseEvents();

    BuildRuleEvent.Finished finished =
        finishedBuildSuccessRuleEventWithCacheResult(
            CacheResult.skipped(), BuildRuleSuccessType.FETCHED_FROM_CACHE);

    fakeEventListener.buildRuleFinished(finished);
    Pair<Integer, Integer> downloadedCacheRulesResult =
        fakeEventListener.downloadedCacheRulesPairResult();
    Integer downloadedRules = downloadedCacheRulesResult.getFirst();
    Integer failedToDownloadRules = downloadedCacheRulesResult.getSecond();

    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        downloadedRules == 1);
    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        failedToDownloadRules == 0);
  }

  @Test
  public void sendsFinalBuildEventBuildLocallyCacheSuccess() {
    fakeOutParseEvents();

    BuildRuleEvent.Finished finished =
        finishedBuildSuccessRuleEventWithCacheResult(
            CacheResult.ignored(), BuildRuleSuccessType.BUILT_LOCALLY);

    fakeEventListener.buildRuleFinished(finished);
    Pair<Integer, Integer> downloadedCacheRulesResult =
        fakeEventListener.downloadedCacheRulesPairResult();
    Integer downloadedRules = downloadedCacheRulesResult.getFirst();
    Integer failedToDownloadRules = downloadedCacheRulesResult.getSecond();

    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        downloadedRules == 0);
    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        failedToDownloadRules == 0);
  }

  @Test
  public void sendsFinalBuildEventBuildLocallyCacheError() {
    fakeOutParseEvents();
    BuildRuleEvent.Finished finished =
        finishedBuildSuccessRuleEventWithCacheResult(
            CacheResult.error("cache", ArtifactCacheMode.http, "error"),
            BuildRuleSuccessType.BUILT_LOCALLY);
    fakeEventListener.buildRuleFinished(finished);
    Pair<Integer, Integer> downloadedCacheRulesResult =
        fakeEventListener.downloadedCacheRulesPairResult();

    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        downloadedCacheRulesResult.getFirst() == 0);
    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        downloadedCacheRulesResult.getSecond() == 1);

    finished =
        finishedBuildSuccessRuleEventWithCacheResult(
            CacheResult.softError("cache", ArtifactCacheMode.http, "error"),
            BuildRuleSuccessType.BUILT_LOCALLY);
    fakeEventListener.buildRuleFinished(finished);
    downloadedCacheRulesResult = fakeEventListener.downloadedCacheRulesPairResult();
    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        downloadedCacheRulesResult.getFirst() == 0);
    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        downloadedCacheRulesResult.getSecond() == 2);

    finished =
        finishedBuildSuccessRuleEventWithCacheResult(
            CacheResult.miss(), BuildRuleSuccessType.BUILT_LOCALLY);
    fakeEventListener.buildRuleFinished(finished);
    downloadedCacheRulesResult = fakeEventListener.downloadedCacheRulesPairResult();
    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        downloadedCacheRulesResult.getFirst() == 0);
    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        downloadedCacheRulesResult.getSecond() == 3);
  }

  @Test
  public void sendsFinalBuildEventBuildLocallyCacheIgnored() {
    fakeOutParseEvents();

    BuildRuleEvent.Finished finished =
        finishedBuildSuccessRuleEventWithCacheResult(
            CacheResult.ignored(), BuildRuleSuccessType.BUILT_LOCALLY);
    fakeEventListener.buildRuleFinished(finished);
    Pair<Integer, Integer> downloadedCacheRulesResult =
        fakeEventListener.downloadedCacheRulesPairResult();
    Integer downloadedRules = downloadedCacheRulesResult.getFirst();
    Integer failedToDownloadRules = downloadedCacheRulesResult.getSecond();

    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        downloadedRules == 0);
    assertThat(
        "The number of recorded downloaded artifacts does not match expectations",
        failedToDownloadRules == 0);
  }
}
