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

package com.facebook.buck.python;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.python.toolchain.PythonPlatform;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

public abstract class PythonBinary extends AbstractBuildRule
    implements BinaryBuildRule, HasRuntimeDeps {

  private final Supplier<? extends SortedSet<BuildRule>> originalDeclaredDeps;
  private final PythonPlatform pythonPlatform;
  private final String mainModule;
  @AddToRuleKey private final PythonPackageComponents components;
  private final ImmutableSet<String> preloadLibraries;
  @AddToRuleKey private final String pexExtension;
  @AddToRuleKey private final boolean legacyOutputPath;

  public PythonBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Supplier<? extends SortedSet<BuildRule>> originalDeclaredDeps,
      PythonPlatform pythonPlatform,
      String mainModule,
      PythonPackageComponents components,
      ImmutableSet<String> preloadLibraries,
      String pexExtension,
      boolean legacyOutputPath) {
    super(buildTarget, projectFilesystem);
    this.originalDeclaredDeps = originalDeclaredDeps;
    this.pythonPlatform = pythonPlatform;
    this.mainModule = mainModule;
    this.components = components;
    this.preloadLibraries = preloadLibraries;
    this.pexExtension = pexExtension;
    this.legacyOutputPath = legacyOutputPath;
  }

  static Path getBinPath(
      BuildTarget target,
      ProjectFilesystem filesystem,
      String extension,
      boolean legacyOutputPath) {
    if (!legacyOutputPath) {
      target = target.withFlavors();
    }
    return BuildTargets.getGenPath(filesystem, target, "%s" + extension);
  }

  final Path getBinPath() {
    return getBinPath(getBuildTarget(), getProjectFilesystem(), pexExtension, legacyOutputPath);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getBinPath());
  }

  @VisibleForTesting
  protected final PythonPlatform getPythonPlatform() {
    return pythonPlatform;
  }

  @VisibleForTesting
  protected final String getMainModule() {
    return mainModule;
  }

  @VisibleForTesting
  protected final PythonPackageComponents getComponents() {
    return components;
  }

  @VisibleForTesting
  protected final ImmutableSet<String> getPreloadLibraries() {
    return preloadLibraries;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return originalDeclaredDeps.get().stream().map(BuildRule::getBuildTarget);
  }
}
