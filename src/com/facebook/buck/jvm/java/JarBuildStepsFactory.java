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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasCustomDepsLogic;
import com.facebook.buck.core.rules.common.RecordArtifactVerifier;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.rules.modern.annotations.DefaultFieldSerialization;
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
import com.facebook.buck.rules.modern.DefaultFieldInputs;
import com.facebook.buck.rules.modern.impl.ModernBuildableSupport;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class JarBuildStepsFactory
    implements AddsToRuleKey, RulePipelineStateFactory<JavacPipelineState> {
  private static final Path METADATA_DIR = Paths.get("META-INF");

  @CustomFieldBehavior(DefaultFieldSerialization.class)
  private final BuildTarget libraryTarget;

  @AddToRuleKey private final CompileToJarStepFactory configuredCompiler;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> resources;
  @AddToRuleKey private final ResourcesParameters resourcesParameters;

  @AddToRuleKey private final Optional<SourcePath> manifestFile;
  @AddToRuleKey private final ImmutableList<String> postprocessClassesCommands;

  @AddToRuleKey private final DependencyInfoHolder dependencyInfos;
  @AddToRuleKey private final ZipArchiveDependencySupplier abiClasspath;

  @AddToRuleKey private final boolean trackClassUsage;

  @CustomFieldBehavior(DefaultFieldSerialization.class)
  private final boolean trackJavacPhaseEvents;

  @AddToRuleKey private final boolean isRequiredForSourceOnlyAbi;
  @AddToRuleKey private final RemoveClassesPatternsMatcher classesToRemoveFromJar;

  @AddToRuleKey private final AbiGenerationMode abiGenerationMode;
  @AddToRuleKey private final AbiGenerationMode abiCompatibilityMode;

  /** Contains information about a Java classpath dependency. */
  public static class JavaDependencyInfo implements AddsToRuleKey {
    @AddToRuleKey public final SourcePath compileTimeJar;
    @AddToRuleKey public final SourcePath abiJar;
    @AddToRuleKey public final boolean isRequiredForSourceOnlyAbi;

    public JavaDependencyInfo(
        SourcePath compileTimeJar, SourcePath abiJar, boolean isRequiredForSourceOnlyAbi) {
      this.compileTimeJar = compileTimeJar;
      this.abiJar = abiJar;
      this.isRequiredForSourceOnlyAbi = isRequiredForSourceOnlyAbi;
    }
  }

  private static class DependencyInfoHolder implements AddsToRuleKey, HasCustomDepsLogic {
    // Adding this to the rulekey is slow for large projects and the abiClasspath already reflects
    // all
    // the inputs.
    // TODO(cjhopman): Improve rulekey calculation so we don't need such micro-optimizations.
    @CustomFieldBehavior({DefaultFieldSerialization.class, DefaultFieldInputs.class})
    private final ImmutableList<JavaDependencyInfo> infos;

    public DependencyInfoHolder(ImmutableList<JavaDependencyInfo> infos) {
      this.infos = infos;
    }

    @Override
    public Stream<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
      Stream.Builder<BuildRule> builder = Stream.builder();
      infos.forEach(
          info ->
              ruleFinder
                  .filterBuildRuleInputs(info.abiJar, info.compileTimeJar)
                  .forEach(builder::add));
      return builder.build();
    }

    public ZipArchiveDependencySupplier getAbiClasspath() {
      return new ZipArchiveDependencySupplier(
          this.infos
              .stream()
              .map(i -> i.abiJar)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())));
    }

    public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
      return infos
          .stream()
          .map(info -> info.compileTimeJar)
          .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    }
  }

  public JarBuildStepsFactory(
      BuildTarget libraryTarget,
      CompileToJarStepFactory configuredCompiler,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> resources,
      ResourcesParameters resourcesParameters,
      Optional<SourcePath> manifestFile,
      ImmutableList<String> postprocessClassesCommands,
      boolean trackClassUsage,
      boolean trackJavacPhaseEvents,
      RemoveClassesPatternsMatcher classesToRemoveFromJar,
      AbiGenerationMode abiGenerationMode,
      AbiGenerationMode abiCompatibilityMode,
      ImmutableList<JavaDependencyInfo> dependencyInfos,
      boolean isRequiredForSourceOnlyAbi) {
    this.libraryTarget = libraryTarget;
    this.configuredCompiler = configuredCompiler;
    this.srcs = srcs;
    this.resources = resources;
    this.resourcesParameters = resourcesParameters;
    this.postprocessClassesCommands = postprocessClassesCommands;
    this.manifestFile = manifestFile;
    this.trackClassUsage = trackClassUsage;
    this.trackJavacPhaseEvents = trackJavacPhaseEvents;
    this.classesToRemoveFromJar = classesToRemoveFromJar;
    this.abiGenerationMode = abiGenerationMode;
    this.abiCompatibilityMode = abiCompatibilityMode;
    this.dependencyInfos = new DependencyInfoHolder(dependencyInfos);
    this.abiClasspath = this.dependencyInfos.getAbiClasspath();
    this.isRequiredForSourceOnlyAbi = isRequiredForSourceOnlyAbi;
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

  public Optional<String> getResourcesRoot() {
    return resourcesParameters.getResourcesRoot();
  }

  @Nullable
  public SourcePath getSourcePathToOutput(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return getOutputJarPath(buildTarget, filesystem)
        .map(path -> ExplicitBuildTargetSourcePath.of(buildTarget, path))
        .orElse(null);
  }

  @Nullable
  public SourcePath getSourcePathToGeneratedAnnotationPath(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return getGeneratedAnnotationPath(buildTarget, filesystem)
        .map(path -> ExplicitBuildTargetSourcePath.of(buildTarget, path))
        .orElse(null);
  }

  @VisibleForTesting
  public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    return dependencyInfos.getCompileTimeClasspathSourcePaths();
  }

  public boolean useDependencyFileRuleKeys() {
    return !srcs.isEmpty() && trackClassUsage;
  }

  /** Returns a predicate indicating whether a SourcePath is covered by the depfile. */
  public Predicate<SourcePath> getCoveredByDepFilePredicate(
      SourcePathResolver pathResolver, SourcePathRuleFinder ruleFinder) {
    // a hash set is intentionally used to achieve constant time look-up
    // TODO(cjhopman): This could probably be changed to be a 2-level check of archivepath->inner,
    // withinarchivepath->boolean.
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
      RecordArtifactVerifier buildableContext,
      BuildTarget buildTarget) {
    Preconditions.checkState(producesJar());
    Preconditions.checkArgument(
        buildTarget.equals(JavaAbis.getSourceAbiJar(libraryTarget))
            || buildTarget.equals(JavaAbis.getSourceOnlyAbiJar(libraryTarget)));
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    CompilerParameters compilerParameters = getCompilerParameters(context, filesystem, buildTarget);

    ResourcesParameters resourcesParameters = getResourcesParameters();

    configuredCompiler.createCompileToJarStep(
        context,
        filesystem,
        buildTarget,
        compilerParameters,
        resourcesParameters,
        ImmutableList.of(),
        getAbiJarParameters(buildTarget, context, filesystem, compilerParameters).orElse(null),
        getLibraryJarParameters(context, filesystem, compilerParameters).orElse(null),
        steps,
        buildableContext);

    return steps.build();
  }

  public ImmutableList<Step> getPipelinedBuildStepsForAbiJar(
      BuildTarget buildTarget,
      BuildContext context,
      ProjectFilesystem filesystem,
      RecordArtifactVerifier buildableContext,
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
      RecordArtifactVerifier buildableContext,
      BuildTarget buildTarget,
      Path pathToClassHashes) {
    Preconditions.checkArgument(buildTarget.equals(libraryTarget));
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    CompilerParameters compilerParameters = getCompilerParameters(context, filesystem, buildTarget);
    ResourcesParameters resourcesParameters = getResourcesParameters();

    configuredCompiler.createCompileToJarStep(
        context,
        filesystem,
        buildTarget,
        compilerParameters,
        resourcesParameters,
        postprocessClassesCommands,
        null,
        getLibraryJarParameters(context, filesystem, compilerParameters).orElse(null),
        steps,
        buildableContext);

    JavaLibraryRules.addAccumulateClassNamesStep(
        ModernBuildableSupport.newCellRelativePathFactory(
            context.getBuildCellRootPath(), filesystem),
        filesystem,
        steps,
        Optional.ofNullable(getSourcePathToOutput(buildTarget, filesystem))
            .map(context.getSourcePathResolver()::getRelativePath),
        pathToClassHashes);

    return steps.build();
  }

  public ImmutableList<Step> getPipelinedBuildStepsForLibraryJar(
      BuildContext context,
      ProjectFilesystem filesystem,
      RecordArtifactVerifier buildableContext,
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
        Optional.ofNullable(getSourcePathToOutput(libraryTarget, filesystem))
            .map(context.getSourcePathResolver()::getRelativePath),
        pathToClassHashes);

    return steps.build();
  }

  protected CompilerParameters getCompilerParameters(
      BuildContext context, ProjectFilesystem filesystem, BuildTarget buildTarget) {
    return CompilerParameters.builder()
        .setClasspathEntriesSourcePaths(
            dependencyInfos.getCompileTimeClasspathSourcePaths(), context.getSourcePathResolver())
        .setSourceFileSourcePaths(srcs, filesystem, context.getSourcePathResolver())
        .setScratchPaths(buildTarget, filesystem)
        .setShouldTrackClassUsage(trackClassUsage)
        .setShouldTrackJavacPhaseEvents(trackJavacPhaseEvents)
        .setAbiGenerationMode(abiGenerationMode)
        .setAbiCompatibilityMode(abiCompatibilityMode)
        .setSourceOnlyAbiRuleInfoFactory(
            new DefaultSourceOnlyAbiRuleInfoFactory(
                dependencyInfos.infos, buildTarget, isRequiredForSourceOnlyAbi))
        .build();
  }

  protected ResourcesParameters getResourcesParameters() {
    return resourcesParameters;
  }

  protected Optional<JarParameters> getLibraryJarParameters(
      BuildContext context, ProjectFilesystem filesystem, CompilerParameters compilerParameters) {
    return getJarParameters(context, filesystem, libraryTarget, compilerParameters);
  }

  protected Optional<JarParameters> getAbiJarParameters(
      BuildTarget target,
      BuildContext context,
      ProjectFilesystem filesystem,
      CompilerParameters compilerParameters) {
    if (JavaAbis.isLibraryTarget(target)) {
      return Optional.empty();
    }
    Preconditions.checkState(
        JavaAbis.isSourceAbiTarget(target) || JavaAbis.isSourceOnlyAbiTarget(target));
    return getJarParameters(context, filesystem, target, compilerParameters);
  }

  private Optional<JarParameters> getJarParameters(
      BuildContext context,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      CompilerParameters compilerParameters) {
    return getOutputJarPath(buildTarget, filesystem)
        .map(
            output ->
                JarParameters.builder()
                    .setEntriesToJar(
                        ImmutableSortedSet.of(compilerParameters.getOutputPaths().getClassesDir()))
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

  private Optional<Path> getOutputJarPath(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    if (!producesJar()) {
      return Optional.empty();
    }

    if (JavaAbis.isSourceAbiTarget(buildTarget) || JavaAbis.isSourceOnlyAbiTarget(buildTarget)) {
      return Optional.of(CompilerOutputPaths.getAbiJarPath(buildTarget, filesystem));
    } else if (JavaAbis.isLibraryTarget(buildTarget)) {
      return Optional.of(AbstractCompilerOutputPaths.getOutputJarPath(buildTarget, filesystem));
    } else {
      throw new IllegalArgumentException();
    }
  }

  private Optional<Path> getGeneratedAnnotationPath(
      BuildTarget buildTarget, ProjectFilesystem filesystem) {
    if (!hasAnnotationProcessing()) {
      return Optional.empty();
    }

    return CompilerOutputPaths.getAnnotationPath(filesystem, buildTarget);
  }

  private Path getDepFileRelativePath(ProjectFilesystem filesystem, BuildTarget buildTarget) {
    return CompilerOutputPaths.getOutputJarDirPath(buildTarget, filesystem)
        .resolve("used-classes.json");
  }

  private ImmutableMap<Path, SourcePath> getDepOutputPathToAbiSourcePath(
      SourcePathResolver pathResolver, SourcePathRuleFinder ruleFinder) {
    ImmutableMap.Builder<Path, SourcePath> pathToSourcePathMapBuilder = ImmutableMap.builder();
    for (JavaDependencyInfo depInfo : dependencyInfos.infos) {
      SourcePath sourcePath = depInfo.compileTimeJar;
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
        getAbiJarParameters(firstRule, context, filesystem, compilerParameters).orElse(null),
        getLibraryJarParameters(context, filesystem, compilerParameters).orElse(null));
  }

  public boolean hasAnnotationProcessing() {
    return configuredCompiler.hasAnnotationProcessing();
  }
}
