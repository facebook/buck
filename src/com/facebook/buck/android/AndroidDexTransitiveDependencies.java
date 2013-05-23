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

import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.util.Set;

/**
 * Metadata about an AndroidBinaryRule's transitive Java dependencies as it relates to dexing.
 * Specifically, when creating the classes.dex for an APK, the AndroidBinaryRule needs to know
 * all of the .class files that need to be included in the DEX, as well as an optional set of
 * .class files to exclude from the DEX.
 */
public class AndroidDexTransitiveDependencies {
  // TODO(devjasta): these fields should not be immutable; we should expect that these values
  // will all be transformed by various commands (and we shouldn't need to keep checking back to
  // get the latest values of them to observe those transformations).  There's a much richer
  // rule-based design that should be implemented though, so in the absence of that it's probably
  // best to keep this code as narrowly defined as possible.
  public ImmutableSet<String> classpathEntriesToDex;
  public final ImmutableSet<String> noDxClasspathEntries;
  public final ImmutableSet<String> pathsToThirdPartyJars;

  public AndroidDexTransitiveDependencies(Set<String> pathsToDex,
                                          Set<String> pathsToThirdPartyJars,
                                          ImmutableSet<String> noDxClasspathEntries) {
    this.classpathEntriesToDex = ImmutableSet.copyOf(pathsToDex);
    this.pathsToThirdPartyJars = ImmutableSet.copyOf(pathsToThirdPartyJars);
    this.noDxClasspathEntries = ImmutableSet.copyOf(noDxClasspathEntries);
  }

  public void applyClasspathTransformation(InputTransformation transformation) {
    classpathEntriesToDex = ImmutableSet.copyOf(
        Iterables.transform(classpathEntriesToDex, transformation));
  }

  public interface InputTransformation extends Function<String, String> {
  }
}
