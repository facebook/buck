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

package com.facebook.buck.rules;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.rules.BuildResult.Builder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

/**
 * This is a union type that represents either a success or a failure. This exists so that {@code
 * com.facebook.buck.rules.CachingBuildEngine#buildOnceDepsAreBuilt()} can return a strongly typed
 * value.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractBuildResult {

  abstract BuildRule getRule();

  abstract BuildRuleStatus getStatus();

  abstract Optional<CacheResult> getCacheResult();

  /** Signals that the cache upload for this rule (if there were one) has completed. */
  abstract Optional<ListenableFuture<Void>> getUploadCompleteFuture();

  abstract Optional<BuildRuleSuccessType> getSuccessOptional();

  abstract Optional<Throwable> getFailureOptional();

  abstract Optional<Set<String>> getDepsWithCacheMisses();

  @Value.Check
  void check() {
    Preconditions.checkArgument(
        (getStatus() == BuildRuleStatus.SUCCESS) == getSuccessOptional().isPresent(),
        "must provide success type exclusively for successes");
    Preconditions.checkArgument(
        getStatus() != BuildRuleStatus.SUCCESS || getCacheResult().isPresent(),
        "must set a cache result for successes");
    Preconditions.checkArgument(
        !getUploadCompleteFuture().isPresent() || getStatus() == BuildRuleStatus.SUCCESS,
        "upload completion future should only be provided for successes");
    Preconditions.checkArgument(
        (getStatus() == BuildRuleStatus.FAIL || getStatus() == BuildRuleStatus.CANCELED)
            == getFailureOptional().isPresent(),
        "must provide failure value exclusively for failures/cancellations");
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

  public static BuildResult success(
      BuildRule rule,
      BuildRuleSuccessType success,
      CacheResult cacheResult,
      ListenableFuture<Void> uploadCompleteFuture) {
    return successBuilder(rule, success, cacheResult)
        .setUploadCompleteFuture(uploadCompleteFuture)
        .build();
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
}
