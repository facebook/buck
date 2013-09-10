/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.dalvik.ZipSplitter;
import com.facebook.buck.rules.RuleKey;
import com.google.common.base.Preconditions;

/**
 * Bundles together some information about whether and how we should split up dex files.
 */
class DexSplitMode {
  private final boolean shouldSplitDex;
  private final DexStore dexStore;
  private final ZipSplitter.DexSplitStrategy dexSplitStrategy;
  private final boolean useLinearAllocSplitDex;

  public DexSplitMode(
      boolean shouldSplitDex,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      DexStore dexStore,
      boolean useLinearAllocSplitDex) {
    this.shouldSplitDex = shouldSplitDex;
    this.dexSplitStrategy = Preconditions.checkNotNull(dexSplitStrategy);
    this.dexStore = Preconditions.checkNotNull(dexStore);
    this.useLinearAllocSplitDex = useLinearAllocSplitDex;
  }

  public DexStore getDexStore() {
    return dexStore;
  }

  public boolean isShouldSplitDex() {
    return shouldSplitDex;
  }

  ZipSplitter.DexSplitStrategy getDexSplitStrategy() {
    Preconditions.checkState(isShouldSplitDex());
    return dexSplitStrategy;
  }

  public boolean useLinearAllocSplitDex() {
    return useLinearAllocSplitDex;
  }

  public RuleKey.Builder appendToRuleKey(String prefix, RuleKey.Builder builder) {
    builder.set(prefix + ".shouldSplitDex", shouldSplitDex);
    builder.set(prefix + ".dexStore", dexStore.name());
    builder.set(prefix + ".dexSplitStrategy", dexSplitStrategy.name());
    builder.set(prefix + ".useLinearAllocSplitDex", useLinearAllocSplitDex);
    return builder;
  }
}
