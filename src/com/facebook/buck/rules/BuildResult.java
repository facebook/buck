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

import javax.annotation.Nullable;

/**
 * This is a union type that represents either a success or a failure. This exists so that
 * {@code com.facebook.buck.rules.CachingBuildEngine#buildOnceDepsAreBuilt()}
 * can return a strongly typed value.
 */
public class BuildResult {

  private final BuildRule rule;
  private final BuildRuleStatus status;
  private final CacheResult cacheResult;

  @Nullable private final BuildRuleSuccessType success;
  @Nullable private final Throwable failure;

  private BuildResult(
      BuildRule rule,
      BuildRuleStatus status,
      CacheResult cacheResult,
      @Nullable BuildRuleSuccessType success,
      @Nullable Throwable failure) {
    this.rule = rule;
    this.status = status;
    this.cacheResult = cacheResult;
    this.success = success;
    this.failure = failure;
  }

  public static BuildResult success(
      BuildRule rule,
      BuildRuleSuccessType success,
      CacheResult cacheResult) {
    return new BuildResult(rule, BuildRuleStatus.SUCCESS, cacheResult, success, null);
  }

  public static BuildResult failure(
      BuildRule rule,
      Throwable failure) {
    return new BuildResult(rule, BuildRuleStatus.FAIL, CacheResult.miss(), null, failure);
  }

  public static BuildResult canceled(BuildRule rule, Throwable failure) {
    return new BuildResult(rule, BuildRuleStatus.CANCELED, CacheResult.miss(), null, failure);
  }

  public BuildRule getRule() {
    return rule;
  }

  BuildRuleStatus getStatus() {
    return status;
  }

  CacheResult getCacheResult() {
    return cacheResult;
  }

  @Nullable
  public BuildRuleSuccessType getSuccess() {
    return success;
  }

  @Nullable
  public Throwable getFailure() {
    return failure;
  }
}
