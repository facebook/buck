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

package com.facebook.buck.core.build.engine;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.base.Preconditions;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

/**
 * This is a union type that represents either a success or a failure. This exists so that {@code
 * com.facebook.buck.core.build.engine.impl.CachingBuildEngine#buildOnceDepsAreBuilt()} can return a
 * strongly typed value.
 */
@BuckStyleValueWithBuilder
public abstract class BuildResult {

  public abstract BuildRule getRule();

  public abstract BuildRuleStatus getStatus();

  public abstract Optional<CacheResult> getCacheResult();

  public abstract Optional<BuildRuleSuccessType> getSuccessOptional();

  public abstract Optional<String> getStrategyResult();

  public abstract Optional<Throwable> getFailureOptional();

  public abstract Optional<Set<String>> getDepsWithCacheMisses();

  @Value.Check
  void check() {
    Preconditions.checkArgument(
        (getStatus() == BuildRuleStatus.SUCCESS) == getSuccessOptional().isPresent(),
        "must provide success type exclusively for successes");
    Preconditions.checkArgument(
        getStatus() != BuildRuleStatus.SUCCESS || getCacheResult().isPresent(),
        "must set a cache result for successes");
    Preconditions.checkArgument(
        (getStatus() == BuildRuleStatus.FAIL || getStatus() == BuildRuleStatus.CANCELED)
            == getFailureOptional().isPresent(),
        "must provide failure value exclusively for failures/cancellations");
    getCacheResult().ifPresent(result -> result.getType().verifyValidFinalType());
  }

  private static Builder successBuilder(
      BuildRule rule, BuildRuleSuccessType successType, CacheResult cacheResult) {
    return BuildResult.builder()
        .setRule(rule)
        .setStatus(BuildRuleStatus.SUCCESS)
        .setCacheResult(cacheResult)
        .setSuccessOptional(successType);
  }

  public static BuildResult success(
      BuildRule rule, BuildRuleSuccessType success, CacheResult cacheResult) {
    return successBuilder(rule, success, cacheResult).build();
  }

  public static BuildResult failure(BuildRule rule, Throwable failure) {
    return BuildResult.builder()
        .setRule(rule)
        .setStatus(BuildRuleStatus.FAIL)
        .setFailureOptional(failure)
        .build();
  }

  public static BuildResult canceled(BuildRule rule, Throwable failure) {
    return BuildResult.builder()
        .setRule(rule)
        .setStatus(BuildRuleStatus.CANCELED)
        .setFailureOptional(failure)
        .build();
  }

  public BuildRuleSuccessType getSuccess() {
    return getSuccessOptional()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    String.format(
                        "%s: unexpected result %s", getRule().getBuildTarget(), getStatus()),
                    getFailure()));
  }

  public Throwable getFailure() {
    return getFailureOptional()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    String.format(
                        "%s: unexpected result %s", getRule().getBuildTarget(), getStatus())));
  }

  public boolean isSuccess() {
    return getStatus() == BuildRuleStatus.SUCCESS;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableBuildResult.Builder {}
}
