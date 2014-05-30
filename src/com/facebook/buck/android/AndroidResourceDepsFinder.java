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

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;

/**
 * This is something that an {@link AndroidBinary} must create to find the transitive closure
 * of Android resources that it depends on (in order).
 */
abstract class AndroidResourceDepsFinder {

  private final AndroidTransitiveDependencyGraph transitiveDependencyGraph;

  /*
   * Currently, allAndroidResources are expensive to compute, so we calculate them lazily.
   */

  private final Supplier<ImmutableList<HasAndroidResourceDeps>> androidResourceDepsSupplier;

  public AndroidResourceDepsFinder(AndroidTransitiveDependencyGraph transitiveDependencyGraph) {
    this.transitiveDependencyGraph = Preconditions.checkNotNull(transitiveDependencyGraph);

    this.androidResourceDepsSupplier = Suppliers.memoize(
        new Supplier<ImmutableList<HasAndroidResourceDeps>>() {
          @Override
          public ImmutableList<HasAndroidResourceDeps> get() {
            return findMyAndroidResourceDeps();
          }
        });
  }

  /**
   * @return List of android resources that should be considered while generating the uber
   *     {@code R.java} file, essentially excluding any resource rules that only contain assets.
   */
  public ImmutableList<HasAndroidResourceDeps> getAndroidResources() {
    return androidResourceDepsSupplier.get();
  }

  public AndroidResourceDetails getAndroidResourceDetails() {
    return transitiveDependencyGraph.findAndroidResourceDetails(getAndroidResources());
  }

  /**
   * Finds the transitive closure of android resource dependencies.
   * @return a list of {@link HasAndroidResourceDeps}s that should be passed, in order, to
   *     {@code aapt} when generating the {@code R.java} files for this APK.
   */
  protected abstract ImmutableList<HasAndroidResourceDeps> findMyAndroidResourceDeps();
}
