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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfoFactory;
import com.facebook.buck.rules.modern.impl.ModernBuildableSupport;
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

  // TODO(cjhopman): Remove this field.
  private final ProjectFilesystem projectFilesystemForOutputJarPaths;
  private final BuildTarget libraryTarget;

  @AddToRuleKey private final ConfiguredCompiler configuredCompiler;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> resources;
  @AddToRuleKey private final ResourcesParameters resourcesParameters;

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
  @Nullable private final Supplier<SourceOnlyAbiRuleInfoFactory> ruleInfoFactorySupplier;

  private final Map<BuildTarget, SourcePath> sourcePathsToOutput = new HashMap<>();

  public JarBuildStepsFactory(
      ProjectFilesystem projectFilesystem,
      BuildTarget libraryTarget,
      ConfiguredCompiler configuredCompiler,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      ResourcesParameters resourcesParameters,
      Optional<SourcePath> manifestFile,
      ImmutableList<String> postprocessClassesCommands,
      ZipArchiveDependencySupplier abiClasspath,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      ImmutableSortedSet<SourcePath> compileTimeClasspathSourcePaths,
      RemoveClassesPatternsMatcher classesToRemoveFromJar,
      AbiGenerationMode abiGenerationMode,
      AbiGenerationMode abiCompatibilityMode,
      @Nullable Supplier<SourceOnlyAbiRuleInfoFactory> ruleInfoFactorySupplier) {
    this.projectFilesystemForOutputJarPaths = projectFilesystem;
    this.libraryTarget = libraryTarget;
    this.configuredCompiler = configuredCompiler;
    this.srcs = srcs;
    this.resources = resources;
    this.resourcesParameters = resourcesParameters;
    this.postprocessClassesCommands = postprocessClassesCommands;
    this.manifestFile = manifestFile;
    this.abiClasspath = abiClasspath;
    this.trackClassUsage = trackClassUsage;
    this.trackJavacPhaseEvents = trackJavacPhaseEvents;
    this.compileTimeClasspathSourcePaths = compileTimeClasspathSourcePaths;
    this.classesToRemoveFromJar = classesToRemoveFromJar;
    this.abiGenerationMode = abiGenerationMode;
    this.abiCompatibilityMode = abiCompatibilityMode;
    this.ruleInfoFactorySupplier = ruleInfoFactorySupplier;
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

  /** Returns a predicate indicating whether a SourcePath is covered by the depfile. */
  public Predicate<SourcePath> getCoveredByDepFilePredicate(
      SourcePathResolver pathResolver, SourcePathRuleFinder ruleFinder) {
    // a hash set is intentionally used to achieve constant time look-up
    return abiClasspath
            .getArchiveMembers(pathResolver, ruleFinder)
            .collect(ImmutableSet.toImmutableSet())
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
      BuildContext context,
      ProjectFilesystem filesystem,
      BuildableContext buildableContext,
      BuildTarget buildTarget) {
    Preconditions.checkState(producesJar());
    Preconditions.checkArgument(
        buildTarget.equals(JavaAbis.getSourceAbiJar(libraryTarget))
            || buildTarget.equals(JavaAbis.getSourceOnlyAbiJar(libraryTarget)));
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    CompilerParameters compilerParameters = getCompilerParameters(context, filesystem, buildTarget);

    ResourcesParameters resourcesParameters = getResourcesParameters();

    CompileToJarStepFactory compileToJarStepFactory = (CompileToJarStepFactory) configuredCompiler;
    compileToJarStepFactory.createCompileToJarStep(
        context,
        filesystem,
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
      ProjectFilesystem filesystem,
      BuildableContext buildableContext,
      JavacPipelineState state) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ((JavacToJarStepFactory) configuredCompiler)
        .createPipelinedCompileToJarStep(
            context,
            filesystem,
            buildTarget,
            state,
            getResourcesParameters(),
            postprocessClassesCommands,
            steps,
            buildableContext);
    return steps.build();
  }

  public ImmutableList<Step> getBuildStepsForLibraryJar(
      BuildContext context,
      ProjectFilesystem filesystem,
      BuildableContext buildableContext,
      BuildTarget buildTarget,
      Path pathToClassHashes) {
    Preconditions.checkArgument(buildTarget.equals(libraryTarget));
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    CompilerParameters compilerParameters = getCompilerParameters(context, filesystem, buildTarget);
    ResourcesParameters resourcesParameters = getResourcesParameters();

    CompileToJarStepFactory compileToJarStepFactory = (CompileToJarStepFactory) configuredCompiler;
    compileToJarStepFactory.createCompileToJarStep(
        context,
        filesystem,
        buildTarget,
        compilerParameters,
        resourcesParameters,
        postprocessClassesCommands,
        null,
        getLibraryJarParameters(context, compilerParameters).orElse(null),
        steps,
        buildableContext);

    JavaLibraryRules.addAccumulateClassNamesStep(
        ModernBuildableSupport.newCellRelativePathFactory(
            context.getBuildCellRootPath(), filesystem),
        filesystem,
        steps,
        Optional.ofNullable(getSourcePathToOutput(buildTarget))
            .map(context.getSourcePathResolver()::getRelativePath),
        pathToClassHashes);

    return steps.build();
  }

  public ImmutableList<Step> getPipelinedBuildStepsForLibraryJar(
      BuildContext context,
      ProjectFilesystem filesystem,
      BuildableContext buildableContext,
      JavacPipelineState state,
      Path pathToClassHashes) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    ((JavacToJarStepFactory) configuredCompiler)
        .createPipelinedCompileToJarStep(
            context,
            filesystem,
            libraryTarget,
            state,
            getResourcesParameters(),
            postprocessClassesCommands,
            steps,
            buildableContext);

    JavaLibraryRules.addAccumulateClassNamesStep(
        ModernBuildableSupport.newCellRelativePathFactory(
            context.getBuildCellRootPath(), filesystem),
        filesystem,
        steps,
        Optional.ofNullable(getSourcePathToOutput(libraryTarget))
            .map(context.getSourcePathResolver()::getRelativePath),
        pathToClassHashes);

    return steps.build();
  }

  protected CompilerParameters getCompilerParameters(
      BuildContext context, ProjectFilesystem filesystem, BuildTarget buildTarget) {
    return CompilerParameters.builder()
        .setClasspathEntriesSourcePaths(
            compileTimeClasspathSourcePaths, context.getSourcePathResolver())
        .setSourceFileSourcePaths(srcs, filesystem, context.getSourcePathResolver())
        .setScratchPaths(buildTarget, filesystem)
        .setShouldTrackClassUsage(trackClassUsage)
        .setShouldTrackJavacPhaseEvents(trackJavacPhaseEvents)
        .setAbiGenerationMode(abiGenerationMode)
        .setAbiCompatibilityMode(abiCompatibilityMode)
        .setSourceOnlyAbiRuleInfoFactory(
            ruleInfoFactorySupplier != null ? ruleInfoFactorySupplier.get() : null)
        .build();
  }

  protected ResourcesParameters getResourcesParameters() {
    return resourcesParameters;
  }

  protected Optional<JarParameters> getLibraryJarParameters(
      BuildContext context, CompilerParameters compilerParameters) {
    return getJarParameters(context, libraryTarget, compilerParameters);
  }

  protected Optional<JarParameters> getAbiJarParameters(
      BuildTarget target, BuildContext context, CompilerParameters compilerParameters) {
    if (JavaAbis.isLibraryTarget(target)) {
      return Optional.empty();
    }
    Preconditions.checkState(
        JavaAbis.isSourceAbiTarget(target) || JavaAbis.isSourceOnlyAbiTarget(target));
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
      BuildContext context,
      ProjectFilesystem filesystem,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellPathResolver,
      BuildTarget buildTarget) {
    Preconditions.checkState(useDependencyFileRuleKeys());
    return DefaultClassUsageFileReader.loadFromFile(
        filesystem,
        cellPathResolver,
        filesystem.getPathForRelativePath(getDepFileRelativePath(filesystem, buildTarget)),
        getDepOutputPathToAbiSourcePath(context.getSourcePathResolver(), ruleFinder));
  }

  private Optional<Path> getOutputJarPath(BuildTarget buildTarget) {
    if (!producesJar()) {
      return Optional.empty();
    }

    if (JavaAbis.isSourceAbiTarget(buildTarget) || JavaAbis.isSourceOnlyAbiTarget(buildTarget)) {
      return Optional.of(
          CompilerParameters.getAbiJarPath(buildTarget, projectFilesystemForOutputJarPaths));
    } else if (JavaAbis.isLibraryTarget(buildTarget)) {
      return Optional.of(
          DefaultJavaLibrary.getOutputJarPath(buildTarget, projectFilesystemForOutputJarPaths));
    } else {
      throw new IllegalArgumentException();
    }
  }

  private Path getDepFileRelativePath(ProjectFilesystem filesystem, BuildTarget buildTarget) {
    return CompilerParameters.getOutputJarDirPath(buildTarget, filesystem)
        .resolve("used-classes.json");
  }

  private ImmutableMap<Path, SourcePath> getDepOutputPathToAbiSourcePath(
      SourcePathResolver pathResolver, SourcePathRuleFinder ruleFinder) {
    ImmutableMap.Builder<Path, SourcePath> pathToSourcePathMapBuilder = ImmutableMap.builder();
    for (SourcePath sourcePath : compileTimeClasspathSourcePaths) {
      BuildRule rule = ruleFinder.getRule(sourcePath).get();
      Path path = pathResolver.getAbsolutePath(sourcePath);
      if (rule instanceof HasJavaAbi && ((HasJavaAbi) rule).getAbiJar().isPresent()) {
        BuildTarget buildTarget = ((HasJavaAbi) rule).getAbiJar().get();
        pathToSourcePathMapBuilder.put(path, DefaultBuildTargetSourcePath.of(buildTarget));
      }
    }
    return pathToSourcePathMapBuilder.build();
  }

  @Override
  public JavacPipelineState newInstance(
      BuildContext context, ProjectFilesystem filesystem, BuildTarget firstRule) {
    JavacToJarStepFactory javacToJarStepFactory = (JavacToJarStepFactory) configuredCompiler;
    CompilerParameters compilerParameters = getCompilerParameters(context, filesystem, firstRule);
    return javacToJarStepFactory.createPipelineState(
        firstRule,
        compilerParameters,
        getAbiJarParameters(firstRule, context, compilerParameters).orElse(null),
        getLibraryJarParameters(context, compilerParameters).orElse(null));
  }
}
