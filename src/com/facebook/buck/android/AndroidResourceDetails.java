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

import com.google.common.annotations.Beta;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.Iterator;

/**
 * Aggregate information about a list of {@link AndroidResourceRule}s.
 */
public class AndroidResourceDetails {
  /**
   * The {@code res} directories associated with the {@link AndroidResourceRule}s.
   * <p>
   * An {@link Iterator} over this collection will reflect the order of the original list of
   * {@link AndroidResourceRule}s that were specified.
   */
  public final ImmutableSet<String> resDirectories;

  public final ImmutableSet<String> whitelistedStringDirs;

  public final ImmutableSet<String> rDotJavaPackages;

  @Beta
  public AndroidResourceDetails(ImmutableList<HasAndroidResourceDeps> androidResourceDeps) {
    ImmutableSet.Builder<String> resDirectoryBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<String> rDotJavaPackageBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<String> whitelistedStringDirsBuilder = ImmutableSet.builder();
    for (HasAndroidResourceDeps androidResource : androidResourceDeps) {
      String resDirectory = androidResource.getRes();
      if (resDirectory != null) {
        resDirectoryBuilder.add(resDirectory);
        rDotJavaPackageBuilder.add(androidResource.getRDotJavaPackage());
        if (androidResource.hasWhitelistedStrings()) {
          whitelistedStringDirsBuilder.add(resDirectory);
        }
      }
    }
    resDirectories = resDirectoryBuilder.build();
    rDotJavaPackages = rDotJavaPackageBuilder.build();
    whitelistedStringDirs = whitelistedStringDirsBuilder.build();
  }
}
