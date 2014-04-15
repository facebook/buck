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

import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Metadata about an AndroidBinaryRule's transitive Java dependencies as it relates to dexing.
 * Specifically, when creating the classes.dex for an APK, the AndroidBinaryRule needs to know
 * all of the .class files that need to be included in the DEX, as well as an optional set of
 * .class files to exclude from the DEX.
 */
public class AndroidDexTransitiveDependencies {
  public final ImmutableSet<Path> classpathEntriesToDex;
  public final ImmutableSet<Path> noDxClasspathEntries;
  public final ImmutableSet<Path> pathsToThirdPartyJars;
  public final Supplier<Map<String, HashCode>> classNamesToHashesSupplier;

  public AndroidDexTransitiveDependencies(
      Set<Path> pathsToDex,
      Set<Path> pathsToThirdPartyJars,
      ImmutableSet<Path> noDxClasspathEntries,
      Supplier<Map<String, HashCode>> classNamesToHashesSupplier) {
    this.classpathEntriesToDex = ImmutableSet.copyOf(pathsToDex);
    this.pathsToThirdPartyJars = ImmutableSet.copyOf(pathsToThirdPartyJars);
    this.noDxClasspathEntries = ImmutableSet.copyOf(noDxClasspathEntries);
    this.classNamesToHashesSupplier = Preconditions.checkNotNull(classNamesToHashesSupplier);
  }
}
