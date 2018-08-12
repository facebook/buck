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
package com.facebook.buck.core.build.distributed.synchronization.impl;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.distributed.synchronization.RemoteBuildRuleCompletionWaiter;
import com.facebook.buck.core.rules.BuildRule;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.function.Supplier;

/** Use this implementation when running a standalone Buck build without Stampede */
public class NoOpRemoteBuildRuleCompletionWaiter implements RemoteBuildRuleCompletionWaiter {

  @Override
  public boolean shouldWaitForRemoteCompletionOfBuildRule(String buildTarget) {
    return false;
  }

  @Override
  public ListenableFuture<CacheResult> waitForBuildRuleToAppearInCache(
      BuildRule buildRule, Supplier<ListenableFuture<CacheResult>> cacheCheck) {
    return Futures.immediateFuture(CacheResult.ignored());
  }

  @Override
  public ListenableFuture<Boolean> waitForMostBuildRulesToFinishRemotely() {
    return Futures.immediateFuture(true);
  }
}
