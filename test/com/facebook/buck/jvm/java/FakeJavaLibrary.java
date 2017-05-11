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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;

import com.facebook.buck.android.AndroidPackageable;
import com.facebook.buck.android.AndroidPackageableCollector;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

public class FakeJavaLibrary extends FakeBuildRule implements JavaLibrary, AndroidPackageable {

  private static final BuildableProperties OUTPUT_TYPE = new BuildableProperties(LIBRARY);

  private ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();

  public FakeJavaLibrary(
      BuildTarget target,
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      ImmutableSortedSet<BuildRule> deps) {
    super(target, filesystem, resolver, deps.toArray(new BuildRule[deps.size()]));
  }

  public FakeJavaLibrary(
      BuildTarget target, SourcePathResolver resolver, ImmutableSortedSet<BuildRule> deps) {
    super(target, resolver, deps);
  }

  public FakeJavaLibrary(BuildTarget target, SourcePathResolver resolver) {
    super(target, resolver);
  }

  @Override
  public BuildableProperties getProperties() {
    return OUTPUT_TYPE;
  }

  @Override
  public ImmutableSet<SourcePath> getOutputClasspaths() {
    return ImmutableSet.of();
  }

  @Override
  public Set<BuildRule> getDepsForTransitiveClasspathEntries() {
    return getBuildDeps();
  }

  @Override
  public ImmutableSet<SourcePath> getTransitiveClasspaths() {
    return JavaLibraryClasspathProvider.getClasspathsFromLibraries(
        this.getTransitiveClasspathDeps());
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return JavaLibraryClasspathProvider.getTransitiveClasspathDeps(this);
  }

  @Override
  public ImmutableSet<SourcePath> getImmediateClasspaths() {
    return ImmutableSet.of(getSourcePathToOutput());
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(),
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.jar"));
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJarContents() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJavaSrcs() {
    return srcs;
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSources() {
    return srcs;
  }

  @Override
  public ImmutableSortedSet<SourcePath> getResources() {
    return ImmutableSortedSet.of();
  }

  public FakeJavaLibrary setJavaSrcs(ImmutableSortedSet<Path> srcs) {
    Preconditions.checkNotNull(srcs);
    this.srcs =
        FluentIterable.from(srcs)
            .transform(p -> (SourcePath) new PathSourcePath(new FakeProjectFilesystem(), p))
            .toSortedSet(Ordering.natural());
    return this;
  }

  @Override
  public Optional<Path> getGeneratedSourcePath() {
    return Optional.empty();
  }

  @Override
  public Optional<BuildTarget> getAbiJar() {
    return Optional.empty();
  }

  @Override
  public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables() {
    return AndroidPackageableCollector.getPackageableRules(getBuildDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addClasspathEntry(this, getSourcePathToOutput());
  }

  @Override
  public Optional<String> getMavenCoords() {
    return Optional.empty();
  }
}
