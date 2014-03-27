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

import java.nio.file.Path;
import java.util.Iterator;

/**
 * Aggregate information about a list of {@link AndroidResource}s.
 */
public class AndroidResourceDetails {
  /**
   * The {@code res} directories associated with the {@link AndroidResource}s.
   * <p>
   * An {@link Iterator} over this collection will reflect the order of the original list of
   * {@link AndroidResource}s that were specified.
   */
  public final ImmutableSet<Path> resDirectories;

  public final ImmutableSet<Path> whitelistedStringDirs;

  public final ImmutableSet<String> rDotJavaPackages;

  /**
   * @param androidResources A sequence of {@code android_resource} rules, ordered such that
   *     resources from rules that appear earlier in the list will take precedence over resources
   *     from rules that appear later in the list in the event of the conflict. This matches the
   *     order the directories should be passed to {@code aapt} to have the same precedence
   *     resolution.
   */
  @Beta
  public AndroidResourceDetails(ImmutableList<HasAndroidResourceDeps> androidResources) {
    ImmutableSet.Builder<Path> resDirectoryBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<String> rDotJavaPackageBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<Path> whitelistedStringDirsBuilder = ImmutableSet.builder();
    for (HasAndroidResourceDeps androidResource : androidResources) {
      Path resDirectory = androidResource.getRes();
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
