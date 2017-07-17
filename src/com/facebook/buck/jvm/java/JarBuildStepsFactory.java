/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

public class JarBuildStepsFactory implements AddsToRuleKey {
  private final ProjectFilesystem projectFilesystem;
  private final SourcePathRuleFinder ruleFinder;

  @AddToRuleKey private final CompileToJarStepFactory compileStepFactory;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> resources;

  @AddToRuleKey(stringify = true)
  private final Optional<Path> resourcesRoot;

  @AddToRuleKey private final Optional<SourcePath> manifestFile;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ZipArchiveDependencySupplier abiClasspath;

  private final ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths;
  @AddToRuleKey private final RemoveClassesPatternsMatcher classesToRemoveFromJar;

  public JarBuildStepsFactory(
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CompileToJarStepFactory compileStepFactory,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      ZipArchiveDependencySupplier abiClasspath,
      ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths,
      RemoveClassesPatternsMatcher classesToRemoveFromJar) {
    this.projectFilesystem = projectFilesystem;
    this.ruleFinder = ruleFinder;
    this.compileStepFactory = compileStepFactory;
    this.srcs = srcs;
    this.resources = resources;
    this.resourcesRoot = resourcesRoot;
    this.manifestFile = manifestFile;
    this.abiClasspath = abiClasspath;
    this.compileTimeClasspathSourcePaths = compileTimeClasspathSourcePaths;
    this.classesToRemoveFromJar = classesToRemoveFromJar;
  }

  public ImmutableList<Step> getBuildStepsForAbiJar(
      BuildContext context, BuildableContext buildableContext, BuildTarget buildTarget) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path abiJarPath = getAbiJarPath(buildTarget);
    ((JavacToJarStepFactory) compileStepFactory).setCompileAbi(abiJarPath);

    JavaLibraryRules.addCompileToJarSteps(
        buildTarget,
        projectFilesystem,
        context,
        buildableContext,
        Optional.of(abiJarPath),
        ruleFinder,
        srcs,
        resources,
        ImmutableList.of(),
        compileTimeClasspathSourcePaths,
        false,
        null,
        compileStepFactory,
        resourcesRoot,
        manifestFile,
        classesToRemoveFromJar,
        steps);

    return steps.build();
  }

  public Path getAbiJarPath(BuildTarget buildTarget) {
    Preconditions.checkArgument(HasJavaAbi.isSourceAbiTarget(buildTarget));

    return BuildTargets.getGenPath(projectFilesystem, buildTarget, "lib__%s__output")
        .resolve(String.format("%s-abi.jar", buildTarget.getShortName()));
  }
}
