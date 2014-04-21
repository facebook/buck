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

import com.facebook.buck.java.JavaLibrary;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

/**
 * This is something that an {@link AndroidBinary} must create to find the transitive closure
 * of Android resources that it depends on (in order).
 */
abstract class AndroidResourceDepsFinder {

  private final AndroidTransitiveDependencyGraph transitiveDependencyGraph;
  private final ImmutableSet<JavaLibrary> buildRulesToExcludeFromDex;
  private final AndroidTransitiveDependencies androidTransitiveDependencies;

  /*
   * Currently, allAndroidResources are expensive to compute, so we calculate them lazily.
   */

  private final Supplier<ImmutableList<HasAndroidResourceDeps>> allAndroidResourcesSupplier;
  private final Supplier<ImmutableList<HasAndroidResourceDeps>> androidResourceDepsSupplier;

  public AndroidResourceDepsFinder(AndroidTransitiveDependencyGraph transitiveDependencyGraph,
      ImmutableSet<JavaLibrary> buildRulesToExcludeFromDex) {
    this.transitiveDependencyGraph = Preconditions.checkNotNull(transitiveDependencyGraph);
    this.buildRulesToExcludeFromDex = Preconditions.checkNotNull(buildRulesToExcludeFromDex);
    this.androidTransitiveDependencies = transitiveDependencyGraph.findDependencies();

    this.allAndroidResourcesSupplier = Suppliers.memoize(
        new Supplier<ImmutableList<HasAndroidResourceDeps>>() {
          @Override
          public ImmutableList<HasAndroidResourceDeps> get() {
            return findMyAndroidResourceDeps();
          }
        });

    // This defined as a Supplier since getAndroidResources() is expected to be called multiple
    // times.
    this.androidResourceDepsSupplier = Suppliers.memoize(
        new Supplier<ImmutableList<HasAndroidResourceDeps>>() {
          @Override
          public ImmutableList<HasAndroidResourceDeps> get() {
            return FluentIterable.from(allAndroidResourcesSupplier.get())
                .filter(new Predicate<HasAndroidResourceDeps>() {
                          @Override
                          public boolean apply(HasAndroidResourceDeps input) {
                            return input.getRes() != null;
                          }
                        })
                .toList();
          }
        });
  }

  public AndroidTransitiveDependencies getAndroidTransitiveDependencies() {
    return androidTransitiveDependencies;
  }

  /**
   * @return List of android resources that should be considered while generating the uber
   *     {@code R.java} file, essentially excluding any resource rules that only contain assets.
   */
  public ImmutableList<HasAndroidResourceDeps> getAndroidResources() {
    return androidResourceDepsSupplier.get();
  }

  /**
   * @return List of android resources that only contain assets, and as a result only need to be
   *     passed to the {@code aapt} step which packages assets into the APK.
   */
  public ImmutableList<HasAndroidResourceDeps> getAssetOnlyAndroidResources() {
    return FluentIterable.from(allAndroidResourcesSupplier.get())
        .filter(new Predicate<HasAndroidResourceDeps>() {
                  @Override
                  public boolean apply(HasAndroidResourceDeps input) {
                    return input.getRes() == null && input.getAssets() != null;
                  }
                })
        .toList();
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
