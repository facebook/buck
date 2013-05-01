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

import com.facebook.buck.util.ZipSplitter;
import com.google.common.base.Preconditions;

/**
 * Bundles together some information about whether and how we should split up dex files.
 */
class DexSplitMode {
  private final boolean shouldSplitDex;
  private final DexStore dexStore;
  private final ZipSplitter.DexSplitStrategy dexSplitStrategy;

  public DexSplitMode(
      boolean shouldSplitDex,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      DexStore dexStore) {
    this.shouldSplitDex = shouldSplitDex;
    this.dexSplitStrategy = Preconditions.checkNotNull(dexSplitStrategy);
    this.dexStore = Preconditions.checkNotNull(dexStore);
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
}
