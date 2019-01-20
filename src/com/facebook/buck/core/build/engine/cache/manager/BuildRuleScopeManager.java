/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.build.engine.cache.manager;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.engine.BuildResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.RuleDepsCache;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfo;
import com.facebook.buck.core.build.engine.buildinfo.OnDiskBuildInfo;
import com.facebook.buck.core.build.engine.manifest.ManifestFetchResult;
import com.facebook.buck.core.build.engine.manifest.ManifestStoreResult;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDiagnosticData;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.RuleKeyDiagnosticsMode;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.rules.keys.RuleKeyDiagnostics;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.RuleKeyFactoryWithDiagnostics;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Handles BuildRule resumed/suspended/finished scopes. Only one scope can be active at a time.
 * Scopes nested on the same thread are allowed.
 *
 * <p>Once the rule has been marked as finished, any further scope() calls will fail.
 */
public class BuildRuleScopeManager {

  private static final Logger LOG = Logger.get(BuildRuleScopeManager.class);
  private final RuleKeyFactories ruleKeyFactories;
  private final RuleKeyFactoryWithDiagnostics<RuleKey> ruleKeyFactory;
  private final OnDiskBuildInfo onDiskBuildInfo;
  private final BuildRuleDurationTracker buildRuleDurationTracker;
  private final RuleKeyDiagnostics<RuleKey, String> defaultRuleKeyDiagnostics;
  private final RuleKeyDiagnosticsMode ruleKeyDiagnosticsMode;
  private final BuildRule rule;
  private final RuleDepsCache ruleDeps;
  private final RuleKey defaultKey;
  private final BuckEventBus eventBus;

  private volatile @Nullable Thread currentBuildRuleScopeThread = null;
  private @Nullable FinishedData finishedData = null;
  @Nullable private volatile ManifestFetchResult manifestFetchResult = null;
  @Nullable private volatile ManifestStoreResult manifestStoreResult = null;

  public BuildRuleScopeManager(
      RuleKeyFactories ruleKeyFactories,
      OnDiskBuildInfo onDiskBuildInfo,
      BuildRuleDurationTracker buildRuleDurationTracker,
      RuleKeyDiagnostics<RuleKey, String> defaultRuleKeyDiagnostics,
      RuleKeyDiagnosticsMode ruleKeyDiagnosticsMode,
      BuildRule rule,
      RuleDepsCache ruleDeps,
      RuleKey defaultKey,
      BuckEventBus eventBus) {
    this.ruleKeyFactories = ruleKeyFactories;
    this.ruleKeyFactory = ruleKeyFactories.getDefaultRuleKeyFactory();
    this.onDiskBuildInfo = onDiskBuildInfo;
    this.buildRuleDurationTracker = buildRuleDurationTracker;
    this.defaultRuleKeyDiagnostics = defaultRuleKeyDiagnostics;
    this.ruleKeyDiagnosticsMode = ruleKeyDiagnosticsMode;
    this.rule = rule;
    this.ruleDeps = ruleDeps;
    this.defaultKey = defaultKey;
    this.eventBus = eventBus;
  }

  public Scope scope() {
    synchronized (this) {
      Preconditions.checkState(
          finishedData == null, "RuleScope started after rule marked as finished.");
      if (currentBuildRuleScopeThread != null) {
        Preconditions.checkState(Thread.currentThread() == currentBuildRuleScopeThread);
        return () -> {};
      }
      BuildRuleEvent.Resumed resumed = postResumed();
      currentBuildRuleScopeThread = Thread.currentThread();
      return () -> {
        synchronized (this) {
          currentBuildRuleScopeThread = null;
          if (finishedData != null) {
            postFinished(resumed);
          } else {
            postSuspended(resumed);
          }
        }
      };
    }
  }

  public synchronized void finished(
      BuildResult input,
      Optional<Long> outputSize,
      Optional<HashCode> outputHash,
      Optional<BuildRuleSuccessType> successType,
      UploadToCacheResultType UploadToCacheResultType,
      Optional<Pair<Long, Long>> ruleKeyCacheCheckTimestamps,
      Optional<Pair<Long, Long>> inputRuleKeyCacheCheckTimestamps,
      Optional<Pair<Long, Long>> manifestRuleKeyCacheCheckTimestamps,
      Optional<Pair<Long, Long>> buildTimestamps) {
    Preconditions.checkState(finishedData == null, "Build rule already marked finished.");
    Preconditions.checkState(
        currentBuildRuleScopeThread != null,
        "finished() can only be called within a buildrule scope.");
    Preconditions.checkState(
        currentBuildRuleScopeThread == Thread.currentThread(),
        "finished() should be called from the same thread as the current buildrule scope.");
    finishedData =
        new FinishedData(
            input,
            outputSize,
            outputHash,
            successType,
            UploadToCacheResultType,
            ruleKeyCacheCheckTimestamps,
            inputRuleKeyCacheCheckTimestamps,
            manifestRuleKeyCacheCheckTimestamps,
            buildTimestamps);
  }

  private void post(BuildRuleEvent event) {
    LOG.verbose(event.toString());
    eventBus.post(event);
  }

  private BuildRuleEvent.Resumed postResumed() {
    BuildRuleEvent.Resumed resumedEvent =
        BuildRuleEvent.resumed(rule, buildRuleDurationTracker, ruleKeyFactory);
    post(resumedEvent);
    return resumedEvent;
  }

  private void postSuspended(BuildRuleEvent.Resumed resumed) {
    post(BuildRuleEvent.suspended(resumed, ruleKeyFactories.getDefaultRuleKeyFactory()));
  }

  private void postFinished(BuildRuleEvent.Resumed resumed) {
    Objects.requireNonNull(finishedData);
    post(finishedData.getEvent(resumed));
  }

  private BuildRuleKeys getBuildRuleKeys() {
    Optional<RuleKey> inputKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY);
    Optional<RuleKey> depFileKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY);
    Optional<RuleKey> manifestKey = onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.MANIFEST_KEY);
    return BuildRuleKeys.builder()
        .setRuleKey(defaultKey)
        .setInputRuleKey(inputKey)
        .setDepFileRuleKey(depFileKey)
        .setManifestRuleKey(manifestKey)
        .build();
  }

  private Optional<BuildRuleDiagnosticData> getBuildRuleDiagnosticData(
      boolean failureOrBuiltLocally) {
    if (ruleKeyDiagnosticsMode == RuleKeyDiagnosticsMode.NEVER
        || (ruleKeyDiagnosticsMode == RuleKeyDiagnosticsMode.BUILT_LOCALLY
            && !failureOrBuiltLocally)) {
      return Optional.empty();
    }
    ImmutableList.Builder<RuleKeyDiagnostics.Result<?, ?>> diagnosticKeysBuilder =
        ImmutableList.builder();
    defaultRuleKeyDiagnostics.processRule(rule, diagnosticKeysBuilder::add);
    return Optional.of(
        new BuildRuleDiagnosticData(ruleDeps.get(rule), diagnosticKeysBuilder.build()));
  }

  public void setManifestStoreResult(@Nullable ManifestStoreResult manifestStoreResult) {
    this.manifestStoreResult = manifestStoreResult;
  }

  public void setManifestFetchResult(@Nullable ManifestFetchResult manifestFetchResult) {
    this.manifestFetchResult = manifestFetchResult;
  }

  private class FinishedData {
    private final BuildResult input;
    private final Optional<Long> outputSize;
    private final Optional<HashCode> outputHash;
    private final Optional<BuildRuleSuccessType> successType;
    private final UploadToCacheResultType uploadToCacheResultType;
    private final Optional<Pair<Long, Long>> ruleKeyCacheCheckTimestamps;
    private final Optional<Pair<Long, Long>> inputRuleKeyCacheCheckTimestamps;
    private final Optional<Pair<Long, Long>> manifestRuleKeyCacheCheckTimestamps;
    private final Optional<Pair<Long, Long>> buildTimestamps;

    public FinishedData(
        BuildResult input,
        Optional<Long> outputSize,
        Optional<HashCode> outputHash,
        Optional<BuildRuleSuccessType> successType,
        UploadToCacheResultType uploadToCacheResultType,
        Optional<Pair<Long, Long>> ruleKeyCacheCheckTimestamps,
        Optional<Pair<Long, Long>> inputRuleKeyCacheCheckTimestamps,
        Optional<Pair<Long, Long>> manifestRuleKeyCacheCheckTimestamps,
        Optional<Pair<Long, Long>> buildTimestamps) {
      this.input = input;
      this.outputSize = outputSize;
      this.outputHash = outputHash;
      this.successType = successType;
      this.uploadToCacheResultType = uploadToCacheResultType;
      this.ruleKeyCacheCheckTimestamps = ruleKeyCacheCheckTimestamps;
      this.inputRuleKeyCacheCheckTimestamps = inputRuleKeyCacheCheckTimestamps;
      this.manifestRuleKeyCacheCheckTimestamps = manifestRuleKeyCacheCheckTimestamps;
      this.buildTimestamps = buildTimestamps;
    }

    private BuildRuleEvent.Finished getEvent(BuildRuleEvent.Resumed resumedEvent) {
      boolean failureOrBuiltLocally =
          input.getStatus() == BuildRuleStatus.FAIL
              || (input.isSuccess() && input.getSuccess() == BuildRuleSuccessType.BUILT_LOCALLY);
      // Log the result to the event bus.
      return BuildRuleEvent.finished(
          resumedEvent,
          getBuildRuleKeys(),
          input.getStatus(),
          input.getCacheResult().orElse(CacheResult.miss()),
          onDiskBuildInfo.getBuildValue(BuildInfo.MetadataKey.ORIGIN_BUILD_ID).map(BuildId::new),
          successType,
          uploadToCacheResultType,
          outputHash,
          outputSize,
          getBuildRuleDiagnosticData(failureOrBuiltLocally),
          Optional.ofNullable(manifestFetchResult),
          Optional.ofNullable(manifestStoreResult),
          ruleKeyCacheCheckTimestamps,
          inputRuleKeyCacheCheckTimestamps,
          manifestRuleKeyCacheCheckTimestamps,
          buildTimestamps);
    }
  }
}
