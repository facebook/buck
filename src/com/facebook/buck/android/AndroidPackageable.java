/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.model.HasBuildTarget;

/**
 * Something (usually a {@link com.facebook.buck.rules.BuildRule}) that can be included in
 * an Android package (android_binary or (hopefully soon) aar).
 */
public interface AndroidPackageable extends HasBuildTarget {
  /**
   * Get the set of packagables that need to be included in any package that includes this object.
   *
   * <p>For example, an android_library will need all of its Java deps (except provided_deps),
   * its resource deps, and its native library deps (even though it doesn't need the native
   * library as a build-time dependency).  An android_resource might need an android_library
   * that declares a custom view that it references, as well as other android_resource rules
   * that it references directly.
   *
   * TODO(natthu): Once build rules and buildables are merged, replace this method with another
   * interface that lets an {@link AndroidPackageable} override the default set which is all deps
   * of the type {@link AndroidPackageable}.
   *
   * @return All {@link AndroidPackageable}s that must be included along with this one.
   */
  Iterable<AndroidPackageable> getRequiredPackageables();

  /**
   * Add concrete resources to the given collector.
   *
   * <p>Implementations should call methods on the collector specify what concrete content
   * must be included in an Android package that includes this object.  For example, an
   * android_library will add Java classes, an ndk_library will add native libraries, and
   * android_resource will add resource directories.
   *
   * @param collector The {@link AndroidPackageableCollector} that will receive the content.
   */
  void addToCollector(AndroidPackageableCollector collector);
}
