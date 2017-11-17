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

import com.facebook.buck.android.dalvik.ZipSplitter;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Optional;

/** Bundles together some information about whether and how we should split up dex files. */
class DexSplitMode implements AddsToRuleKey {
  public static final DexSplitMode NO_SPLIT =
      new DexSplitMode(
          /* shouldSplitDex */ false,
          ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
          DexStore.JAR,
          /* linearAllocHardLimit */ 0,
          /* primaryDexPatterns */ ImmutableSet.of(),
          /* primaryDexClassesFile */ Optional.empty(),
          /* primaryDexScenarioFile */ Optional.empty(),
          /* isPrimaryDexScenarioOverflowAllowed */ false,
          /* secondaryDexHeadClassesFile */ Optional.empty(),
          /* secondaryDexTailClassesFile */ Optional.empty());

  @AddToRuleKey private final boolean shouldSplitDex;

  @AddToRuleKey private final DexStore dexStore;

  @AddToRuleKey private final ZipSplitter.DexSplitStrategy dexSplitStrategy;

  @AddToRuleKey private final long linearAllocHardLimit;

  @AddToRuleKey private final ImmutableSet<String> primaryDexPatterns;

  /**
   * File that whitelists the class files that should be in the primary dex.
   *
   * <p>Values in this file must match JAR entries (without the .class suffix), so they should
   * contain path separators. For example:
   *
   * <pre>
   * java/util/Map$Entry
   * </pre>
   */
  @AddToRuleKey private final Optional<SourcePath> primaryDexClassesFile;

  /**
   * File identifying the class files used in scenarios we want to fit in the primary dex. We will
   * add these classes and their dependencies, as well as base classes/interfaces thereof to the
   * primary dex.
   *
   * <p>Values in this file must match JAR entries (without the .class suffix), so they should
   * contain path separators. For example:
   *
   * <pre>
   *   java/util/Map$Entry
   * </pre>
   */
  @AddToRuleKey private final Optional<SourcePath> primaryDexScenarioFile;

  /**
   * Boolean identifying whether we should allow the build to succeed if all the classes identified
   * by primaryDexScenarioFile + dependencies do not fit in the primary dex. The default is false,
   * which causes the build to fail in this case.
   */
  @AddToRuleKey private final boolean isPrimaryDexScenarioOverflowAllowed;

  /**
   * File that whitelists the class files that should be in the first secondary dexes.
   *
   * <p>Values in this file must match JAR entries (without the .class suffix), so they should
   * contain path separators. For example:
   *
   * <pre>
   * java/util/Map$Entry
   * </pre>
   */
  @AddToRuleKey private final Optional<SourcePath> secondaryDexHeadClassesFile;

  /**
   * File that whitelists the class files that should be in the last secondary dexes.
   *
   * <p>Values in this file must match JAR entries (without the .class suffix), so they should
   * contain path separators. For example:
   *
   * <pre>
   * java/util/Map$Entry
   * </pre>
   */
  @AddToRuleKey private final Optional<SourcePath> secondaryDexTailClassesFile;

  /**
   * @param primaryDexPatterns Set of substrings that, when matched, will cause individual input
   *     class or resource files to be placed into the primary jar (and thus the primary dex
   *     output). These classes are required for correctness.
   * @param primaryDexClassesFile Path to a file containing a list of classes that must be included
   *     in the primary dex. These classes are required for correctness.
   * @param primaryDexScenarioFile Path to a file containing a list of classes used in a scenario
   *     that should be included in the primary dex along with all dependency classes required for
   *     preverification. These dependencies will be calculated by buck. This list is used for
   *     performance, not correctness.
   * @param isPrimaryDexScenarioOverflowAllowed A boolean indicating whether to fail the build if
   *     any classes required by primaryDexScenarioFile cannot fit (false) or to allow the build to
   *     to proceed on a best-effort basis (true).
   * @param secondaryDexHeadClassesFile Path to a file containing a list of classes that are put in
   *     the first secondary dexes.
   * @param secondaryDexTailClassesFile Path to a file containing a list of classes that are put in
   *     the last secondary dexes.
   */
  public DexSplitMode(
      boolean shouldSplitDex,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      DexStore dexStore,
      long linearAllocHardLimit,
      Collection<String> primaryDexPatterns,
      Optional<SourcePath> primaryDexClassesFile,
      Optional<SourcePath> primaryDexScenarioFile,
      boolean isPrimaryDexScenarioOverflowAllowed,
      Optional<SourcePath> secondaryDexHeadClassesFile,
      Optional<SourcePath> secondaryDexTailClassesFile) {
    this.shouldSplitDex = shouldSplitDex;
    this.dexSplitStrategy = dexSplitStrategy;
    this.dexStore = dexStore;
    this.linearAllocHardLimit = linearAllocHardLimit;
    this.primaryDexPatterns = ImmutableSet.copyOf(primaryDexPatterns);
    this.primaryDexClassesFile = primaryDexClassesFile;
    this.primaryDexScenarioFile = primaryDexScenarioFile;
    this.isPrimaryDexScenarioOverflowAllowed = isPrimaryDexScenarioOverflowAllowed;
    this.secondaryDexHeadClassesFile = secondaryDexHeadClassesFile;
    this.secondaryDexTailClassesFile = secondaryDexTailClassesFile;
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

  public long getLinearAllocHardLimit() {
    return linearAllocHardLimit;
  }

  public ImmutableSet<String> getPrimaryDexPatterns() {
    return primaryDexPatterns;
  }

  public Optional<SourcePath> getPrimaryDexClassesFile() {
    return primaryDexClassesFile;
  }

  public Optional<SourcePath> getPrimaryDexScenarioFile() {
    return primaryDexScenarioFile;
  }

  public boolean isPrimaryDexScenarioOverflowAllowed() {
    return isPrimaryDexScenarioOverflowAllowed;
  }

  public Optional<SourcePath> getSecondaryDexHeadClassesFile() {
    return secondaryDexHeadClassesFile;
  }

  public Optional<SourcePath> getSecondaryDexTailClassesFile() {
    return secondaryDexTailClassesFile;
  }
}
