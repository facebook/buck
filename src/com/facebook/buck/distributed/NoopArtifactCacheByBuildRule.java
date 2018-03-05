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

package com.facebook.buck.distributed;

import com.facebook.buck.rules.BuildRule;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.LinkedList;
import java.util.List;

/** No-op implementation of {@link ArtifactCacheByBuildRule}. */
public class NoopArtifactCacheByBuildRule implements ArtifactCacheByBuildRule {

  @Override
  public boolean isLocalCachePresent() {
    return true;
  }

  @Override
  public boolean remoteContains(BuildRule rule) {
    return false;
  }

  @Override
  public boolean localContains(BuildRule rule) {
    return false;
  }

  @Override
  public ListenableFuture<BuildRule> uploadFromLocal(BuildRule rule) {
    return Futures.immediateFuture(rule);
  }

  @Override
  public void prewarmRemoteContainsForAllKnownRules() {}

  @Override
  public void prewarmRemoteContains(ImmutableSet<BuildRule> rules) {}

  @Override
  public List<ListenableFuture<BuildRule>> getAllUploadRuleFutures() {
    return new LinkedList<>();
  }

  @Override
  public void close() {}
}
