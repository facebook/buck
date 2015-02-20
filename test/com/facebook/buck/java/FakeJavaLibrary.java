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

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;

import java.nio.file.Path;

public class FakeJavaLibrary extends FakeBuildRule implements JavaLibrary, AndroidPackageable {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();

  public FakeJavaLibrary(
      BuildTarget target,
      SourcePathResolver resolver,
      ImmutableSortedSet<BuildRule> deps) {
    this(JavaLibraryDescription.TYPE,
        target,
        resolver,
        deps
    );
  }

  public FakeJavaLibrary(
      BuildRuleType type,
      BuildTarget target,
      SourcePathResolver resolver,
      ImmutableSortedSet<BuildRule> deps) {
    super(type, target, resolver, deps);
  }

  public FakeJavaLibrary(BuildTarget target, SourcePathResolver resolver) {
    super(JavaLibraryDescription.TYPE, target, resolver);
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getDeclaredClasspathEntries() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getOutputClasspathEntries() {
    return ImmutableSetMultimap.of();
  }

  @Override
  public ImmutableSortedSet<BuildRule> getDepsForTransitiveClasspathEntries() {
    return getDeps();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    return ImmutableSetMultimap.of((JavaLibrary) this, getPathToOutputFile());
  }

  @Override
  public Path getPathToOutputFile() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s.jar");
  }

  @Override
  public ImmutableSortedSet<Path> getJavaSrcs() {
    return ImmutableSortedSet.copyOf(getResolver().getAllPaths(srcs));
  }

  public FakeJavaLibrary setJavaSrcs(ImmutableSortedSet<Path> srcs) {
    Preconditions.checkNotNull(srcs);
    this.srcs = FluentIterable.from(srcs)
        .transform(SourcePaths.toSourcePath(new FakeProjectFilesystem()))
        .toSortedSet(Ordering.natural());
    return this;
  }

  @Override
  public AnnotationProcessingParams getAnnotationProcessingParams() {
    return AnnotationProcessingParams.EMPTY;
  }

  @Override
  public Sha1HashCode getAbiKey() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(getDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addClasspathEntry(this, getPathToOutputFile());
  }

}
