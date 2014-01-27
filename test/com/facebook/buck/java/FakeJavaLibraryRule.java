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

package com.facebook.buck.java;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.Sha1HashCode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;

import java.nio.file.Path;

public class FakeJavaLibraryRule extends FakeBuildRule implements JavaLibraryRule {

  private final static BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private ImmutableSortedSet<Path> srcs = ImmutableSortedSet.of();

  public FakeJavaLibraryRule(
      BuildTarget target,
      ImmutableSortedSet<BuildRule> deps) {
    this(BuildRuleType.JAVA_LIBRARY,
        target,
        deps,
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
  }

  public FakeJavaLibraryRule(
      BuildRuleType type,
      BuildTarget target,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSet<BuildTargetPattern> visibilityPatterns) {
    super(type, target, deps, visibilityPatterns);
  }

  public FakeJavaLibraryRule(BuildTarget target) {
    super(BuildRuleType.JAVA_LIBRARY, target);
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getDeclaredClasspathEntries() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getOutputClasspathEntries() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getTransitiveClasspathEntries() {
    return ImmutableSetMultimap.of((JavaLibraryRule) this, getPathToOutputFile().toString());
  }

  @Override
  public Path getPathToOutputFile() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s.jar");
  }

  @Override
  public ImmutableSortedSet<Path> getJavaSrcs() {
    return srcs;
  }

  public FakeJavaLibraryRule setJavaSrcs(ImmutableSortedSet<Path> srcs) {
    Preconditions.checkNotNull(srcs);
    this.srcs = ImmutableSortedSet.copyOf(srcs);
    return this;
  }

  @Override
  public AnnotationProcessingData getAnnotationProcessingData() {
    return AnnotationProcessingData.EMPTY;
  }

  @Override
  public Sha1HashCode getAbiKey() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    throw new UnsupportedOperationException();
  }
}
