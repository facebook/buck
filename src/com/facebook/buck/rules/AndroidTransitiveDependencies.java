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
package com.facebook.buck.rules;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;

import java.util.Set;

public class AndroidTransitiveDependencies {
  // TODO(devjasta): these fields should not be immutable; we should expect that these values
  // will all be transformed by various commands (and we shouldn't need to keep checking back to
  // get the latest values of them to observe those transformations).  There's a much richer
  // rule-based design that should be implemented though, so in the absence of that it's probably
  // best to keep this code as narrowly defined as possible.
  public ImmutableSet<String> classpathEntriesToDex;
  public final ImmutableSet<String> noDxClasspathEntries;
  public final ImmutableSet<String> pathsToThirdPartyJars;
  public final ImmutableSet<String> assetsDirectories;
  public final ImmutableSet<String> nativeLibsDirectories;
  public final ImmutableSet<String> manifestFiles;
  public final ImmutableSet<String> resDirectories;
  public final ImmutableSet<String> rDotJavaPackages;
  public final ImmutableMultimap<BuildRuleType,BuildRule> uncachedBuildRules;
  public final ImmutableSet<String> proguardConfigs;

  public AndroidTransitiveDependencies(Set<String> pathsToDex,
                                       Set<String> pathsToThirdPartyJars,
                                       ImmutableSet<String> assetsDirectories,
                                       ImmutableSet<String> nativeLibsDirectories,
                                       ImmutableSet<String> manifestFiles,
                                       ImmutableSet<String> resDirectories,
                                       ImmutableSet<String> rDotJavaPackages,
                                       ImmutableMultimap<BuildRuleType,BuildRule> uncachedBuildRules,
                                       ImmutableSet<String> proguardConfigs,
                                       ImmutableSet<String> noDxClasspathEntries) {
    this.classpathEntriesToDex = ImmutableSet.copyOf(pathsToDex);
    this.pathsToThirdPartyJars = ImmutableSet.copyOf(pathsToThirdPartyJars);
    this.assetsDirectories = ImmutableSet.copyOf(assetsDirectories);
    this.nativeLibsDirectories = ImmutableSet.copyOf(nativeLibsDirectories);
    this.manifestFiles = ImmutableSet.copyOf(manifestFiles);
    this.resDirectories = ImmutableSet.copyOf(resDirectories);
    this.rDotJavaPackages = ImmutableSet.copyOf(rDotJavaPackages);
    this.uncachedBuildRules = ImmutableMultimap.copyOf(uncachedBuildRules);
    this.proguardConfigs = ImmutableSet.copyOf(proguardConfigs);
    this.noDxClasspathEntries = ImmutableSet.copyOf(noDxClasspathEntries);
  }

  public void applyClasspathTransformation(InputTransformation transformation) {
    classpathEntriesToDex = ImmutableSet.copyOf(
        Iterables.transform(classpathEntriesToDex, transformation));
  }

  public interface InputTransformation extends Function<String, String> {
  }
}
