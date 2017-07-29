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

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;
import javax.annotation.Nullable;

public class JarBuildStepsFactory implements AddsToRuleKey {
  private final ProjectFilesystem projectFilesystem;
  private final SourcePathRuleFinder ruleFinder;

  @AddToRuleKey private final CompileToJarStepFactory compileStepFactory;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> resources;

  @AddToRuleKey(stringify = true)
  private final Optional<Path> resourcesRoot;

  @AddToRuleKey private final Optional<SourcePath> manifestFile;
  @AddToRuleKey private final ImmutableList<String> postprocessClassesCommands;

  @SuppressWarnings("PMD.UnusedPrivateField")
  @AddToRuleKey
  private final ZipArchiveDependencySupplier abiClasspath;

  private final boolean trackClassUsage;
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
      ImmutableList<String> postprocessClassesCommands,
      ZipArchiveDependencySupplier abiClasspath,
      boolean trackClassUsage,
      ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths,
      RemoveClassesPatternsMatcher classesToRemoveFromJar) {
    this.projectFilesystem = projectFilesystem;
    this.ruleFinder = ruleFinder;
    this.compileStepFactory = compileStepFactory;
    this.srcs = srcs;
    this.resources = resources;
    this.resourcesRoot = resourcesRoot;
    this.postprocessClassesCommands = postprocessClassesCommands;
    this.manifestFile = manifestFile;
    this.abiClasspath = abiClasspath;
    this.trackClassUsage = trackClassUsage;
    this.compileTimeClasspathSourcePaths = compileTimeClasspathSourcePaths;
    this.classesToRemoveFromJar = classesToRemoveFromJar;
  }

  public boolean producesJar() {
    return !srcs.isEmpty() || !resources.isEmpty() || manifestFile.isPresent();
  }

  public ImmutableSortedSet<SourcePath> getSources() {
    return srcs;
  }

  public ImmutableSortedSet<SourcePath> getResources() {
    return resources;
  }

  @Nullable
  public SourcePath getSourcePathToOutput(BuildTarget buildTarget) {
    return getOutputJarPath(buildTarget)
        .map(path -> new ExplicitBuildTargetSourcePath(buildTarget, path))
        .orElse(null);
  }

  @VisibleForTesting
  public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    return compileTimeClasspathSourcePaths;
  }

  public boolean useDependencyFileRuleKeys() {
    return !srcs.isEmpty() && trackClassUsage;
  }

  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver) {
    // a hash set is intentionally used to achieve constant time look-up
    return abiClasspath.getArchiveMembers(pathResolver).collect(MoreCollectors.toImmutableSet())
        ::contains;
  }

  public ImmutableList<Step> getBuildStepsForAbiJar(
      BuildContext context, BuildableContext buildableContext, BuildTarget buildTarget) {
    Preconditions.checkState(producesJar());
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Optional<Path> abiJarPath = getOutputJarPath(buildTarget);
    ((JavacToJarStepFactory) compileStepFactory).setCompileAbi(abiJarPath.get());

    addCompileToJarSteps(
        buildTarget,
        context,
        buildableContext,
        abiJarPath,
        ImmutableList.of(),
        Optional.empty(),
        steps);

    return steps.build();
  }

  public ImmutableList<Step> getBuildStepsForLibraryJar(
      BuildContext context, BuildableContext buildableContext, BuildTarget buildTarget) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    addCompileToJarSteps(
        buildTarget,
        context,
        buildableContext,
        getOutputJarPath(buildTarget),
        postprocessClassesCommands,
        trackClassUsage ? Optional.of(getDepFileRelativePath(buildTarget)) : Optional.empty(),
        steps);

    JavaLibraryRules.addAccumulateClassNamesStep(
        buildTarget,
        projectFilesystem,
        getSourcePathToOutput(buildTarget),
        buildableContext,
        context,
        steps);

    return steps.build();
  }

  private void addCompileToJarSteps(
      BuildTarget target,
      BuildContext context,
      BuildableContext buildableContext,
      Optional<Path> outputJar,
      ImmutableList<String> postprocessClassesCommands,
      Optional<Path> depFileRelativePath,
      ImmutableList.Builder<Step> steps) {
    // Always create the output directory, even if there are no .java files to compile because there
    // might be resources that need to be copied there.
    Path outputDirectory = DefaultJavaLibrary.getClassesDir(target, projectFilesystem);

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), projectFilesystem, outputDirectory)));

    // We don't want to add provided to the declared or transitive deps, since they're only used at
    // compile time.
    ImmutableSortedSet<Path> compileTimeClasspathPaths =
        this.compileTimeClasspathSourcePaths
            .stream()
            .map(context.getSourcePathResolver()::getAbsolutePath)
            .collect(MoreCollectors.toImmutableSortedSet());

    // If there are resources, then link them to the appropriate place in the classes directory.
    JavaPackageFinder finder = context.getJavaPackageFinder();
    if (this.resourcesRoot.isPresent()) {
      finder = new ResourcesRootPackageFinder(this.resourcesRoot.get(), finder);
    }

    steps.add(
        new CopyResourcesStep(
            projectFilesystem,
            context,
            this.ruleFinder,
            target,
            this.resources,
            outputDirectory,
            finder));

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                projectFilesystem,
                DefaultJavaLibrary.getOutputJarDirPath(target, projectFilesystem))));

    // Only run javac if there are .java files to compile or we need to shovel the manifest file
    // into the built jar.
    if (!this.srcs.isEmpty()) {
      if (depFileRelativePath.isPresent()) {
        Preconditions.checkNotNull(depFileRelativePath);
        buildableContext.recordArtifact(depFileRelativePath.get());
      }

      // This adds the javac command, along with any supporting commands.
      Path pathToSrcsList = BuildTargets.getGenPath(projectFilesystem, target, "__%s__srcs");
      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), projectFilesystem, pathToSrcsList.getParent())));

      Path scratchDir =
          BuildTargets.getGenPath(projectFilesystem, target, "lib__%s____working_directory");

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), projectFilesystem, scratchDir)));
      Optional<Path> workingDirectory = Optional.of(scratchDir);

      Optional<Path> generatedCodeDirectory =
          JavaLibraryRules.getAnnotationPath(projectFilesystem, target);

      ImmutableSortedSet<Path> javaSrcs =
          this.srcs
              .stream()
              .map(context.getSourcePathResolver()::getRelativePath)
              .collect(MoreCollectors.toImmutableSortedSet());

      this.compileStepFactory.createCompileToJarStep(
          context,
          javaSrcs,
          target,
          context.getSourcePathResolver(),
          this.ruleFinder,
          projectFilesystem,
          compileTimeClasspathPaths,
          outputDirectory,
          generatedCodeDirectory,
          workingDirectory,
          depFileRelativePath,
          pathToSrcsList,
          postprocessClassesCommands,
          ImmutableSortedSet.of(outputDirectory),
          /* mainClass */ Optional.empty(),
          this.manifestFile.map(context.getSourcePathResolver()::getAbsolutePath),
          outputJar.get(),
          /* output params */
          steps,
          buildableContext,
          this.classesToRemoveFromJar);
    }

    if (outputJar.isPresent()) {
      Path output = outputJar.get();

      // No source files, only resources
      if (this.srcs.isEmpty()) {
        this.compileStepFactory.createJarStep(
            projectFilesystem,
            outputDirectory,
            Optional.empty(),
            this.manifestFile.map(context.getSourcePathResolver()::getAbsolutePath),
            this.classesToRemoveFromJar,
            output,
            steps);
      }
      buildableContext.recordArtifact(output);
    }
  }

  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver, BuildTarget buildTarget) {
    Preconditions.checkState(useDependencyFileRuleKeys());
    return DefaultClassUsageFileReader.loadFromFile(
        projectFilesystem,
        cellPathResolver,
        projectFilesystem.getPathForRelativePath(
            Preconditions.checkNotNull(getDepFileRelativePath(buildTarget))),
        getDepOutputPathToAbiSourcePath(context.getSourcePathResolver()));
  }

  private Optional<Path> getOutputJarPath(BuildTarget buildTarget) {
    if (!producesJar()) {
      return Optional.empty();
    }

    if (HasJavaAbi.isSourceAbiTarget(buildTarget)) {
      return Optional.of(
          BuildTargets.getGenPath(projectFilesystem, buildTarget, "lib__%s__output")
              .resolve(String.format("%s-abi.jar", buildTarget.getShortName())));
    } else if (HasJavaAbi.isLibraryTarget(buildTarget)) {
      return Optional.of(DefaultJavaLibrary.getOutputJarPath(buildTarget, projectFilesystem));
    } else {
      throw new IllegalArgumentException();
    }
  }

  @Nullable
  private Path getDepFileRelativePath(BuildTarget buildTarget) {
    return trackClassUsage
        ? DefaultJavaLibrary.getOutputJarDirPath(buildTarget, projectFilesystem)
            .resolve("used-classes.json")
        : null;
  }

  private ImmutableMap<Path, SourcePath> getDepOutputPathToAbiSourcePath(
      SourcePathResolver pathResolver) {
    ImmutableMap.Builder<Path, SourcePath> pathToSourcePathMapBuilder = ImmutableMap.builder();
    for (SourcePath sourcePath : compileTimeClasspathSourcePaths) {
      BuildRule rule = ruleFinder.getRule(sourcePath).get();
      Path path = pathResolver.getAbsolutePath(sourcePath);
      if (rule instanceof HasJavaAbi) {
        if (((HasJavaAbi) rule).getAbiJar().isPresent()) {
          BuildTarget buildTarget = ((HasJavaAbi) rule).getAbiJar().get();
          pathToSourcePathMapBuilder.put(path, new DefaultBuildTargetSourcePath(buildTarget));
        }
      } else if (rule instanceof CalculateAbi) {
        pathToSourcePathMapBuilder.put(path, sourcePath);
      }
    }
    return pathToSourcePathMapBuilder.build();
  }
}
