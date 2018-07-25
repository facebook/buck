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

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaAbiInfo;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.rules.FakeBuildRule;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

public class FakeJavaLibrary extends FakeBuildRule implements JavaLibrary, AndroidPackageable {

  private ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
  private Optional<String> mavenCoords = Optional.empty();

  public FakeJavaLibrary(
      BuildTarget target, ProjectFilesystem filesystem, ImmutableSortedSet<BuildRule> deps) {
    super(target, filesystem, deps.toArray(new BuildRule[deps.size()]));
  }

  public FakeJavaLibrary(BuildTarget target, ImmutableSortedSet<BuildRule> deps) {
    super(target, deps);
  }

  public FakeJavaLibrary(BuildTarget target) {
    super(target);
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
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(),
        BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.jar"));
  }

  @Override
  public JavaAbiInfo getAbiInfo() {
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

  public FakeJavaLibrary setJavaSrcs(ImmutableSortedSet<SourcePath> srcs) {
    Preconditions.checkNotNull(srcs);
    this.srcs = srcs;
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
  public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
    return AndroidPackageableCollector.getPackageableRules(getBuildDeps());
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addClasspathEntry(this, getSourcePathToOutput());
  }

  @Override
  public Optional<String> getMavenCoords() {
    return mavenCoords;
  }

  public FakeJavaLibrary setMavenCoords(String mavenCoords) {
    this.mavenCoords = Optional.of(mavenCoords);
    return this;
  }
}
