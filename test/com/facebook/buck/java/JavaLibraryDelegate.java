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

package com.facebook.buck.java;

import com.facebook.buck.model.AnnotationProcessingData;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleSuccess;
import com.facebook.buck.rules.BuildRuleSuccess.Type;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.InputRule;
import com.facebook.buck.rules.OutputKey;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Map.Entry;

/**
 * Implementation of {@link JavaLibraryRule} that delegates all of its methods to a
 * {@link DefaultJavaLibraryRule}. This makes it possible to override individual methods of a
 * {@link DefaultJavaLibraryRule}.
 */
public class JavaLibraryDelegate implements JavaLibraryRule {

  private final DefaultJavaLibraryRule delegate;

  public JavaLibraryDelegate(DefaultJavaLibraryRule delegate) {
    this.delegate = Preconditions.checkNotNull(delegate);
  }

  /**
   * Some methods of a {@link JavaLibraryRule} return collections that may contain self-references.
   * This method sanitizes such collections to replace any references to the delegate with a
   * reference to this object instead.
   */
  private ImmutableSetMultimap<JavaLibraryRule, String> remap(
      ImmutableSetMultimap<JavaLibraryRule, String> map) {
    if (map.containsKey(delegate)) {
      ImmutableSetMultimap.Builder<JavaLibraryRule, String> builder =
          ImmutableSetMultimap.builder();
      for (Entry<JavaLibraryRule, String> entry : map.entries()) {
        if (entry.getKey().equals(delegate)) {
          builder.put(this, entry.getValue());
        } else {
          builder.put(entry);
        }
      }
      return builder.build();
    } else {
      return map;
    }
  }

  /**
   * This does not override a method of {@link JavaLibraryRule}, but it provides an observer into
   * the underlying {@link DefaultJavaLibraryRule}.
   */
  public Optional<Sha1HashCode> getAbiKeyForDeps() {
    return delegate.getAbiKeyForDeps();
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getTransitiveClasspathEntries() {
    return remap(delegate.getTransitiveClasspathEntries());
  }

  @Override
  public BuildTarget getBuildTarget() {
    return delegate.getBuildTarget();
  }

  @Override
  public String getFullyQualifiedName() {
    return delegate.getFullyQualifiedName();
  }

  @Override
  public BuildRuleType getType() {
    return delegate.getType();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDeps() {
    return delegate.getDeps();
  }

  @Override
  public ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
    return delegate.getVisibilityPatterns();
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getDeclaredClasspathEntries() {
    return remap(delegate.getDeclaredClasspathEntries());
  }

  @Override
  public boolean isVisibleTo(BuildTarget target) {
    return delegate.isVisibleTo(target);
  }

  @Override
  public Iterable<InputRule> getInputs() {
    return delegate.getInputs();
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getOutputClasspathEntries() {
    return remap(delegate.getOutputClasspathEntries());
  }

  @Override
  public ListenableFuture<BuildRuleSuccess> build(BuildContext context) {
    return delegate.build(context);
  }

  @Override
  public Type getBuildResultType() {
    return delegate.getBuildResultType();
  }

  @Override
  public ImmutableSortedSet<String> getJavaSrcs() {
    return delegate.getJavaSrcs();
  }

  @Override
  public AnnotationProcessingData getAnnotationProcessingData() {
    return delegate.getAnnotationProcessingData();
  }

  @Override
  public Optional<Sha1HashCode> getAbiKey() {
    return delegate.getAbiKey();
  }

  @Override
  public boolean isAndroidRule() {
    return delegate.isAndroidRule();
  }

  @Override
  public boolean isLibrary() {
    return delegate.isLibrary();
  }

  @Override
  public boolean isPackagingRule() {
    return delegate.isPackagingRule();
  }

  @Override
  public boolean getExportDeps() {
    return delegate.getExportDeps();
  }

  @Override
  public String getPathToOutputFile() {
    return delegate.getPathToOutputFile();
  }

  @Override
  public OutputKey getOutputKey(ProjectFilesystem projectFilesystem) {
    return delegate.getOutputKey(projectFilesystem);
  }

  @Override
  public RuleKey getRuleKey() {
    return delegate.getRuleKey();
  }

  @Override
  public int compareTo(BuildRule o) {
    return delegate.compareTo(o);
  }

  @Override
  public String toString() {
    return delegate.toString();
  }

}
