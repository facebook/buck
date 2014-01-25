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

package com.facebook.buck.android;

import com.facebook.buck.rules.BuildRule;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import javax.annotation.Nullable;

/**
 * This is something that an {@link AndroidBinaryRule} must create to find the transitive closure
 * of Android resources that it depends on (in order).
 */
abstract class AndroidResourceDepsFinder {

  private final AndroidTransitiveDependencyGraph transitiveDependencyGraph;
  private final ImmutableSet<BuildRule> buildRulesToExcludeFromDex;
  private final AndroidTransitiveDependencies androidTransitiveDependencies;

  /*
   * Currently, androidResources are expensive to compute, so we calculate them lazily.
   */

  @Nullable
  private volatile ImmutableList<HasAndroidResourceDeps> androidResources;

  public AndroidResourceDepsFinder(AndroidTransitiveDependencyGraph transitiveDependencyGraph,
      ImmutableSet<BuildRule> buildRulesToExcludeFromDex) {
    this.transitiveDependencyGraph = Preconditions.checkNotNull(transitiveDependencyGraph);
    this.buildRulesToExcludeFromDex = Preconditions.checkNotNull(buildRulesToExcludeFromDex);
    this.androidTransitiveDependencies = transitiveDependencyGraph.findDependencies();
  }

  public AndroidTransitiveDependencies getAndroidTransitiveDependencies() {
    return androidTransitiveDependencies;
  }

  public ImmutableList<HasAndroidResourceDeps> getAndroidResources() {
    if (androidResources == null) {
      synchronized (this) {
        if (androidResources == null) {
          androidResources = findMyAndroidResourceDeps();
        }
      }
    }
    return androidResources;
  }

  public AndroidResourceDetails getAndroidResourceDetails() {
    return transitiveDependencyGraph.findAndroidResourceDetails(getAndroidResources());
  }

  public AndroidDexTransitiveDependencies getAndroidDexTransitiveDependencies(
      UberRDotJava uberRDotJava) {
    return transitiveDependencyGraph.findDexDependencies(
        getAndroidResources(),
        buildRulesToExcludeFromDex,
        uberRDotJava);
  }

  /**
   * Finds the transitive closure of android resource dependencies.
   * @return a list of {@link HasAndroidResourceDeps}s that should be passed, in order, to
   *     {@code aapt} when generating the {@code R.java} files for this APK.
   */
  protected abstract ImmutableList<HasAndroidResourceDeps> findMyAndroidResourceDeps();
}
