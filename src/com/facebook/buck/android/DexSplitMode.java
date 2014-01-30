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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.Optionals;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.Collection;

import javax.annotation.concurrent.Immutable;

/**
 * Bundles together some information about whether and how we should split up dex files.
 */
@Immutable
class DexSplitMode {
  public static final DexSplitMode NO_SPLIT = new DexSplitMode(
      /* shouldSplitDex */ false,
      ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
      DexStore.JAR,
      /* useLinearAllocSplitDex */ false,
      /* linearAllocHardLimit */ 0,
      /* primaryDexPatterns */ ImmutableSet.<String>of(),
      /* primaryDexClassesFile */ Optional.<SourcePath>absent());

  private final boolean shouldSplitDex;
  private final DexStore dexStore;
  private final ZipSplitter.DexSplitStrategy dexSplitStrategy;
  private final boolean useLinearAllocSplitDex;
  private final long linearAllocHardLimit;
  private final ImmutableSet<String> primaryDexPatterns;

  /**
   * File that whitelists the class files that should be in the primary dex.
   * <p>
   * Values in this file must match JAR entries (without the .class suffix),
   * so they should contain path separators.
   * For example:
   * <pre>
   * java/util/Map$Entry
   * </pre>
   */
  private final Optional<SourcePath> primaryDexClassesFile;


  public DexSplitMode(
      boolean shouldSplitDex,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      DexStore dexStore,
      boolean useLinearAllocSplitDex,
      long linearAllocHardLimit,
      Collection<String> primaryDexPatterns,
      Optional<SourcePath> primaryDexClassesFile) {
    this.shouldSplitDex = shouldSplitDex;
    this.dexSplitStrategy = Preconditions.checkNotNull(dexSplitStrategy);
    this.dexStore = Preconditions.checkNotNull(dexStore);
    this.useLinearAllocSplitDex = useLinearAllocSplitDex;
    this.linearAllocHardLimit = linearAllocHardLimit;
    this.primaryDexPatterns = ImmutableSet.copyOf(primaryDexPatterns);
    this.primaryDexClassesFile = Preconditions.checkNotNull(primaryDexClassesFile);
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

  public long getLinearAllocHardLimit() {
    return linearAllocHardLimit;
  }

  public ImmutableSet<String> getPrimaryDexPatterns() {
    return primaryDexPatterns;
  }

  public Optional<SourcePath> getPrimaryDexClassesFile() {
    return primaryDexClassesFile;
  }

  /**
   * @return All {@link SourcePath}s referenced by this object, for use in
   *     {@link com.facebook.buck.rules.Buildable#getInputsToCompareToOutput()}.
   */
  public Collection<SourcePath> getSourcePaths() {
    ImmutableList.Builder<SourcePath> paths = ImmutableList.builder();
    Optionals.addIfPresent(primaryDexClassesFile, paths);
    return paths.build();
  }

  public RuleKey.Builder appendToRuleKey(String prefix, RuleKey.Builder builder) {
    builder.set(prefix + ".shouldSplitDex", shouldSplitDex);
    builder.set(prefix + ".dexStore", dexStore.name());
    builder.set(prefix + ".dexSplitStrategy", dexSplitStrategy.name());
    builder.set(prefix + ".useLinearAllocSplitDex", useLinearAllocSplitDex);
    builder.set(prefix + ".primaryDexPatterns", primaryDexPatterns);
    builder.set(prefix + ".linearAllocHardLimit", linearAllocHardLimit);
    return builder;
  }
}
