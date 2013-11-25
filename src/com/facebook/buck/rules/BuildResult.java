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

import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

/**
 * This is a union type that represents either a success or a failure. This exists so that
 * {@link AbstractCachingBuildRule#buildOnceDepsAreBuilt(BuildContext, OnDiskBuildInfo, BuildInfoRecorder)}
 * can return a strongly typed value.
 */
public class BuildResult {

  private final BuildRuleStatus status;
  private final CacheResult cacheResult;

  @Nullable private final BuildRuleSuccess.Type success;
  @Nullable private final Throwable failure;

  public BuildResult(BuildRuleSuccess.Type success, CacheResult cacheResult) {
    this.status = BuildRuleStatus.SUCCESS;
    this.cacheResult = Preconditions.checkNotNull(cacheResult);
    this.success = Preconditions.checkNotNull(success);
    this.failure = null;
  }

  BuildResult(Throwable failure) {
    this.status = BuildRuleStatus.FAIL;
    this.cacheResult = CacheResult.MISS;
    this.success = null;
    this.failure = Preconditions.checkNotNull(failure);
  }

  BuildRuleStatus getStatus() {
    return status;
  }

  CacheResult getCacheResult() {
    return cacheResult;
  }

  @Nullable
  BuildRuleSuccess.Type getSuccess() {
    return success;
  }

  @Nullable
  Throwable getFailure() {
    return failure;
  }
}