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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.ArchiveMemberSourcePath;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.RulePipelineStateFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class JarBuildStepsFactory
    implements AddsToRuleKey, RulePipelineStateFactory<JavacPipelineState> {
  private static final Path METADATA_DIR = Paths.get("META-INF");

  private final ProjectFilesystem projectFilesystem;
  private final SourcePathRuleFinder ruleFinder;
  private final BuildTarget libraryTarget;

  @AddToRuleKey private final ConfiguredCompiler configuredCompiler;
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
  private final boolean trackJavacPhaseEvents;
  private final ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths;
  @AddToRuleKey private final RemoveClassesPatternsMatcher classesToRemoveFromJar;

  @AddToRuleKey private final AbiGenerationMode abiGenerationMode;
  @AddToRuleKey private final AbiGenerationMode abiCompatibilityMode;
  @Nullable private final Supplier<SourceOnlyAbiRuleInfo> ruleInfoSupplier;

  private final Map<BuildTarget, SourcePath> sourcePathsToOutput = new HashMap<>();

  public JarBuildStepsFactory(
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      BuildTarget libraryTarget,
      ConfiguredCompiler configuredCompiler,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      Optional<Path> resourcesRoot,
      Optional<SourcePath> manifestFile,
      ImmutableList<String> postprocessClassesCommands,
      ZipArchiveDependencySupplier abiClasspath,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths,
      RemoveClassesPatternsMatcher classesToRemoveFromJar,
      AbiGenerationMode abiGenerationMode,
      AbiGenerationMode abiCompatibilityMode,
      @Nullable Supplier<SourceOnlyAbiRuleInfo> ruleInfoSupplier) {
    this.projectFilesystem = projectFilesystem;
    this.ruleFinder = ruleFinder;
    this.libraryTarget = libraryTarget;
    this.configuredCompiler = configuredCompiler;
    this.srcs = srcs;
    this.resources = resources;
    this.resourcesRoot = resourcesRoot;
    this.postprocessClassesCommands = postprocessClassesCommands;
    this.manifestFile = manifestFile;
    this.abiClasspath = abiClasspath;
    this.trackClassUsage = trackClassUsage;
    this.trackJavacPhaseEvents = trackJavacPhaseEvents;
    this.compileTimeClasspathSourcePaths = compileTimeClasspathSourcePaths;
    this.classesToRemoveFromJar = classesToRemoveFromJar;
    this.abiGenerationMode = abiGenerationMode;
    this.abiCompatibilityMode = abiCompatibilityMode;
    this.ruleInfoSupplier = ruleInfoSupplier;
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
    return sourcePathsToOutput.computeIfAbsent(
        buildTarget,
        x ->
            getOutputJarPath(buildTarget)
                .map(path -> ExplicitBuildTargetSourcePath.of(buildTarget, path))
                .orElse(null));
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
    return abiClasspath.getArchiveMembers(pathResolver).collect(ImmutableSet.toImmutableSet())
        ::contains;
  }

  public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
    // Annotation processors might enumerate all files under a certain path and then generate
    // code based on that list (without actually reading the files), making the list of files
    // itself a used dependency that must be part of the dependency-based key. We don't
    // currently have the instrumentation to detect such enumeration perfectly, but annotation
    // processors are most commonly looking for files under META-INF, so as a stopgap we add
    // the listing of META-INF to the rule key.
    return (SourcePath path) ->
        (path instanceof ArchiveMemberSourcePath)
            && pathResolver
                .getRelativeArchiveMemberPath(path)
                .getMemberPath()
                .startsWith(METADATA_DIR);
  }

  public boolean useRulePipelining() {
    return configuredCompiler instanceof JavacToJarStepFactory
        && abiGenerationMode.isSourceAbi()
        && abiGenerationMode.usesDependencies();
  }

  public ImmutableList<Step> getBuildStepsForAbiJar(
      BuildContext context, BuildableContext buildableContext, BuildTarget buildTarget) {
    Preconditions.checkState(producesJar());
    Preconditions.checkArgument(
        buildTarget.equals(HasJavaAbi.getSourceAbiJar(libraryTarget))
            || buildTarget.equals(HasJavaAbi.getSourceOnlyAbiJar(libraryTarget)));
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    CompilerParameters compilerParameters = getCompilerParameters(context, buildTarget);

    ResourcesParameters resourcesParameters = getResourcesParameters();

    CompileToJarStepFactory compileToJarStepFactory = (CompileToJarStepFactory) configuredCompiler;
    compileToJarStepFactory.createCompileToJarStep(
        context,
        buildTarget,
        compilerParameters,
        resourcesParameters,
        ImmutableList.of(),
        getAbiJarParameters(buildTarget, context, compilerParameters).orElse(null),
        getLibraryJarParameters(context, compilerParameters).orElse(null),
        steps,
        buildableContext);

    return steps.build();
  }

  public ImmutableList<Step> getPipelinedBuildStepsForAbiJar(
      BuildTarget buildTarget,
      BuildContext context,
      BuildableContext buildableContext,
      JavacPipelineState state) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ((JavacToJarStepFactory) configuredCompiler)
        .createPipelinedCompileToJarStep(
            context,
            buildTarget,
            state,
            getResourcesParameters(),
            postprocessClassesCommands,
            steps,
            buildableContext);
    return steps.build();
  }

  public ImmutableList<Step> getBuildStepsForLibraryJar(
      BuildContext context, BuildableContext buildableContext, BuildTarget buildTarget) {
    Preconditions.checkArgument(buildTarget.equals(libraryTarget));
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    CompilerParameters compilerParameters = getCompilerParameters(context, buildTarget);
    ResourcesParameters resourcesParameters = getResourcesParameters();

    CompileToJarStepFactory compileToJarStepFactory = (CompileToJarStepFactory) configuredCompiler;
    compileToJarStepFactory.createCompileToJarStep(
        context,
        buildTarget,
        compilerParameters,
        resourcesParameters,
        postprocessClassesCommands,
        null,
        getLibraryJarParameters(context, compilerParameters).orElse(null),
        steps,
        buildableContext);

    JavaLibraryRules.addAccumulateClassNamesStep(
        buildTarget,
        projectFilesystem,
        getSourcePathToOutput(buildTarget),
        buildableContext,
        context,
        steps);

    return steps.build();
  }

  public ImmutableList<Step> getPipelinedBuildStepsForLibraryJar(
      BuildContext context, BuildableContext buildableContext, JavacPipelineState state) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ((JavacToJarStepFactory) configuredCompiler)
        .createPipelinedCompileToJarStep(
            context,
            libraryTarget,
            state,
            getResourcesParameters(),
            postprocessClassesCommands,
            steps,
            buildableContext);

    JavaLibraryRules.addAccumulateClassNamesStep(
        libraryTarget,
        projectFilesystem,
        getSourcePathToOutput(libraryTarget),
        buildableContext,
        context,
        steps);

    return steps.build();
  }

  protected CompilerParameters getCompilerParameters(
      BuildContext context, BuildTarget buildTarget) {
    return CompilerParameters.builder()
        .setClasspathEntriesSourcePaths(
            compileTimeClasspathSourcePaths, context.getSourcePathResolver())
        .setSourceFileSourcePaths(srcs, projectFilesystem, context.getSourcePathResolver())
        .setScratchPaths(buildTarget, projectFilesystem)
        .setShouldTrackClassUsage(trackClassUsage)
        .setShouldTrackJavacPhaseEvents(trackJavacPhaseEvents)
        .setAbiGenerationMode(abiGenerationMode)
        .setAbiCompatibilityMode(abiCompatibilityMode)
        .setSourceOnlyAbiRuleInfo(ruleInfoSupplier != null ? ruleInfoSupplier.get() : null)
        .build();
  }

  protected ResourcesParameters getResourcesParameters() {
    return ResourcesParameters.builder()
        .setResources(this.resources)
        .setResourcesRoot(this.resourcesRoot)
        .build();
  }

  protected Optional<JarParameters> getLibraryJarParameters(
      BuildContext context, CompilerParameters compilerParameters) {
    return getJarParameters(context, libraryTarget, compilerParameters);
  }

  protected Optional<JarParameters> getAbiJarParameters(
      BuildTarget target, BuildContext context, CompilerParameters compilerParameters) {
    if (HasJavaAbi.isLibraryTarget(target)) {
      return Optional.empty();
    }
    Preconditions.checkState(
        HasJavaAbi.isSourceAbiTarget(target) || HasJavaAbi.isSourceOnlyAbiTarget(target));
    return getJarParameters(context, target, compilerParameters);
  }

  private Optional<JarParameters> getJarParameters(
      BuildContext context, BuildTarget buildTarget, CompilerParameters compilerParameters) {
    return getOutputJarPath(buildTarget)
        .map(
            output ->
                JarParameters.builder()
                    .setEntriesToJar(ImmutableSortedSet.of(compilerParameters.getOutputDirectory()))
                    .setManifestFile(
                        manifestFile.map(context.getSourcePathResolver()::getAbsolutePath))
                    .setJarPath(output)
                    .setRemoveEntryPredicate(classesToRemoveFromJar)
                    .build());
  }

  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver, BuildTarget buildTarget) {
    Preconditions.checkState(useDependencyFileRuleKeys());
    return DefaultClassUsageFileReader.loadFromFile(
        projectFilesystem,
        cellPathResolver,
        projectFilesystem.getPathForRelativePath(getDepFileRelativePath(buildTarget)),
        getDepOutputPathToAbiSourcePath(context.getSourcePathResolver()));
  }

  private Optional<Path> getOutputJarPath(BuildTarget buildTarget) {
    if (!producesJar()) {
      return Optional.empty();
    }

    if (HasJavaAbi.isSourceAbiTarget(buildTarget)
        || HasJavaAbi.isSourceOnlyAbiTarget(buildTarget)) {
      return Optional.of(CompilerParameters.getAbiJarPath(buildTarget, projectFilesystem));
    } else if (HasJavaAbi.isLibraryTarget(buildTarget)) {
      return Optional.of(DefaultJavaLibrary.getOutputJarPath(buildTarget, projectFilesystem));
    } else {
      throw new IllegalArgumentException();
    }
  }

  private Path getDepFileRelativePath(BuildTarget buildTarget) {
    return CompilerParameters.getOutputJarDirPath(buildTarget, projectFilesystem)
        .resolve("used-classes.json");
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
          pathToSourcePathMapBuilder.put(path, DefaultBuildTargetSourcePath.of(buildTarget));
        }
      } else if (rule instanceof CalculateAbi) {
        pathToSourcePathMapBuilder.put(path, sourcePath);
      }
    }
    return pathToSourcePathMapBuilder.build();
  }

  @Override
  public JavacPipelineState newInstance(BuildContext context, BuildTarget firstRule) {
    JavacToJarStepFactory javacToJarStepFactory = (JavacToJarStepFactory) configuredCompiler;
    CompilerParameters compilerParameters = getCompilerParameters(context, firstRule);
    return javacToJarStepFactory.createPipelineState(
        firstRule,
        compilerParameters,
        getAbiJarParameters(firstRule, context, compilerParameters).orElse(null),
        getLibraryJarParameters(context, compilerParameters).orElse(null));
  }
}
