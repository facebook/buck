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

import com.facebook.buck.model.AnnotationProcessingData;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.Sha1HashCode;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;

import javax.annotation.Nullable;

public class FakeJavaLibraryRule extends FakeBuildRule implements JavaLibraryRule {

  public FakeJavaLibraryRule(
      BuildRuleType type,
      BuildTarget target,
      ImmutableSortedSet<BuildRule> deps,
      ImmutableSet<BuildTargetPattern> visibilityPatterns) {
    super(type, target, deps, visibilityPatterns);
  }

  @Override
  public ImmutableSetMultimap<BuildRule, String> getDeclaredClasspathEntries() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSet<String> getOutputClasspathEntries() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSetMultimap<BuildRule, String> getTransitiveClasspathEntries() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSortedSet<String> getJavaSrcs() {
    return ImmutableSortedSet.of();
  }

  @Override
  public AnnotationProcessingData getAnnotationProcessingData() {
    return AnnotationProcessingData.EMPTY;
  }

  @Override
  public boolean isLibrary() {
    return true;
  }

  @Override
  @Nullable
  public Optional<Sha1HashCode> getAbiKey() {
    return Optional.absent();
  }
}
