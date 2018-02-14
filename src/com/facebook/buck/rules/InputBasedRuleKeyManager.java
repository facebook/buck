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

package com.facebook.buck.rules;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LeafEvents;
import com.facebook.buck.event.RuleKeyCalculationEvent;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.SizeLimiter;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.util.Discardable;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Supplier;

public class InputBasedRuleKeyManager {
  private final BuckEventBus eventBus;
  private final RuleKeyFactories ruleKeyFactories;
  private final Discardable<BuildInfoRecorder> buildInfoRecorder;
  private final BuildCacheArtifactFetcher buildCacheArtifactFetcher;
  private final ArtifactCache artifactCache;
  private final OnDiskBuildInfo onDiskBuildInfo;
  private final BuildRule rule;
  private final CachingBuildRuleBuilder.BuildRuleScopeManager buildRuleScopeManager;
  private final Supplier<Optional<RuleKey>> inputBasedKey;

  public InputBasedRuleKeyManager(
      BuckEventBus eventBus,
      RuleKeyFactories ruleKeyFactories,
      Discardable<BuildInfoRecorder> buildInfoRecorder,
      BuildCacheArtifactFetcher buildCacheArtifactFetcher,
      ArtifactCache artifactCache,
      OnDiskBuildInfo onDiskBuildInfo,
      BuildRule rule,
      CachingBuildRuleBuilder.BuildRuleScopeManager buildRuleScopeManager,
      Supplier<Optional<RuleKey>> inputBasedKey) {
    this.eventBus = eventBus;
    this.ruleKeyFactories = ruleKeyFactories;
    this.buildInfoRecorder = buildInfoRecorder;
    this.buildCacheArtifactFetcher = buildCacheArtifactFetcher;
    this.artifactCache = artifactCache;
    this.onDiskBuildInfo = onDiskBuildInfo;
    this.rule = rule;
    this.buildRuleScopeManager = buildRuleScopeManager;
    this.inputBasedKey = inputBasedKey;
  }

  public Optional<RuleKey> calculateInputBasedRuleKey() {
    try (Scope ignored =
        RuleKeyCalculationEvent.scope(
            eventBus, RuleKeyCalculationEvent.Type.INPUT, rule.getBuildTarget())) {
      return Optional.of(ruleKeyFactories.getInputBasedRuleKeyFactory().build(rule));
    } catch (SizeLimiter.SizeLimitException ex) {
      return Optional.empty();
    }
  }

  private ListenableFuture<Optional<Pair<BuildRuleSuccessType, CacheResult>>>
      performInputBasedCacheFetch(RuleKey inputRuleKey) throws IOException {
    Preconditions.checkArgument(SupportsInputBasedRuleKey.isSupported(rule));

    getBuildInfoRecorder()
        .addBuildMetadata(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY, inputRuleKey.toString());

    // Check the input-based rule key says we're already built.
    if (checkMatchingInputBasedKey(inputRuleKey)) {
      return Futures.immediateFuture(
          Optional.of(
              new Pair<>(
                  BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY,
                  CacheResult.localKeyUnchangedHit())));
    }

    // Try to fetch the artifact using the input-based rule key.
    return Futures.transform(
        buildCacheArtifactFetcher
            .tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
                inputRuleKey,
                artifactCache,
                // TODO(simons): Share this between all tests, not one per cell.
                rule.getProjectFilesystem()),
        cacheResult -> {
          if (cacheResult.getType().isSuccess()) {
            try (Scope ignored = LeafEvents.scope(eventBus, "handling_cache_result")) {
              return Optional.of(
                  new Pair<>(BuildRuleSuccessType.FETCHED_FROM_CACHE_INPUT_BASED, cacheResult));
            }
          }
          return Optional.empty();
        });
  }

  public ListenableFuture<Optional<Pair<BuildRuleSuccessType, CacheResult>>> checkInputBasedCaches()
      throws IOException {
    Optional<RuleKey> ruleKey;
    try (Scope ignored = buildRuleScopeManager.scope()) {
      // Calculate input-based rule key.
      ruleKey = inputBasedKey.get();
    }
    if (ruleKey.isPresent()) {
      return performInputBasedCacheFetch(ruleKey.get());
    }
    return Futures.immediateFuture(Optional.empty());
  }

  private boolean checkMatchingInputBasedKey(RuleKey inputRuleKey) {
    Optional<RuleKey> lastInputRuleKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY);
    return inputRuleKey.equals(lastInputRuleKey.orElse(null));
  }

  private BuildInfoRecorder getBuildInfoRecorder() {
    return buildInfoRecorder.get();
  }
}
