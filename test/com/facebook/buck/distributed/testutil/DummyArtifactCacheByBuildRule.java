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

package com.facebook.buck.distributed.testutil;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.distributed.ArtifactCacheByBuildRule;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.LinkedList;
import java.util.List;

public class DummyArtifactCacheByBuildRule implements ArtifactCacheByBuildRule {
  private final List<BuildRule> localRules;
  private final List<BuildRule> remoteRules;
  private final List<ListenableFuture<BuildRule>> uploadFutures;

  public DummyArtifactCacheByBuildRule(List<BuildRule> remoteRules, List<BuildRule> localRules) {
    this.remoteRules = new LinkedList<>(remoteRules);
    this.localRules = new LinkedList<>(localRules);
    this.uploadFutures = new LinkedList<>();
  }

  @Override
  public boolean isLocalCachePresent() {
    return !localRules.isEmpty();
  }

  @Override
  public boolean remoteContains(BuildRule rule) {
    return remoteRules.contains(rule);
  }

  @Override
  public boolean localContains(BuildRule rule) {
    return localRules.contains(rule);
  }

  @Override
  public ListenableFuture<BuildRule> uploadFromLocal(BuildRule rule) {
    if (localRules.contains(rule)) {
      remoteRules.add(rule);
    }

    ListenableFuture<BuildRule> future = Futures.immediateFuture(rule);
    uploadFutures.add(future);
    return future;
  }

  @Override
  public void prewarmRemoteContainsForAllKnownRules() {}

  @Override
  public void prewarmRemoteContains(ImmutableSet<BuildRule> rules) {}

  @Override
  public List<ListenableFuture<BuildRule>> getAllUploadRuleFutures() {
    return uploadFutures;
  }

  @Override
  public void close() throws Exception {
    Futures.allAsList(uploadFutures).get();
  }
}
