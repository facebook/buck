/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import static com.facebook.buck.step.isolatedsteps.java.UnusedDependenciesFinder.isActionableUnusedDependenciesAction;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.impl.CellPathResolverUtils;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rulekey.IgnoredFieldInputs;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.javacd.model.UnusedDependenciesParams;
import com.facebook.buck.javacd.model.UnusedDependenciesParams.DependencyAndExportedDepsPath;
import com.facebook.buck.javacd.model.UnusedDependenciesParams.UnusedDependenciesAction;
import com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryRules;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryJarPipelineStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryJarStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryStepsBuilderBase;
import com.facebook.buck.jvm.java.stepsbuilder.creator.JavaCDParams;
import com.facebook.buck.jvm.java.stepsbuilder.creator.JavaCompileStepsBuilderFactoryCreator;
import com.facebook.buck.jvm.java.version.JavaVersion;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PipelinedBuildable;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.rules.modern.impl.ModernBuildableSupport;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Buildable for DefaultJavaLibrary. */
class DefaultJavaLibraryBuildable implements PipelinedBuildable<JavacPipelineState> {

  @AddToRuleKey private final int buckJavaVersion = JavaVersion.getMajorVersion();
  @AddToRuleKey private final JarBuildStepsFactory<?> jarBuildStepsFactory;
  @AddToRuleKey private final UnusedDependenciesAction unusedDependenciesAction;
  @AddToRuleKey private final Optional<NonHashableSourcePathContainer> sourceAbiOutput;

  @AddToRuleKey private final OutputPath rootOutputPath;
  @AddToRuleKey private final OutputPath pathToClassHashesOutputPath;
  @AddToRuleKey private final OutputPath annotationsOutputPath;

  @AddToRuleKey(stringify = true)
  @CustomFieldBehavior(DefaultFieldSerialization.class)
  private final BuildTarget buildTarget;

  @AddToRuleKey
  private final Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory;

  @AddToRuleKey private final boolean isJavaCDEnabled;
  @AddToRuleKey private final Tool javaRuntimeLauncher;

  @ExcludeFromRuleKey(
      reason = "start javacd jvm options is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  private final ImmutableList<String> startJavacdCommandOptions;

  @ExcludeFromRuleKey(
      reason = "javacd worker tool pool size is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  private final int javacdWorkerToolPoolSize;

  @ExcludeFromRuleKey(
      reason = "javacd borrow from the pool is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  private final int javacdBorrowFromPoolTimeoutInSeconds;

  @ExcludeFromRuleKey(
      reason = "path to javacd binary is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = IgnoredFieldInputs.class)
  private final Supplier<SourcePath> javacdBinaryPathSourcePathSupplier;

  DefaultJavaLibraryBuildable(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      JarBuildStepsFactory<?> jarBuildStepsFactory,
      UnusedDependenciesAction unusedDependenciesAction,
      Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory,
      @Nullable CalculateSourceAbi sourceAbi,
      boolean isJavaCDEnabled,
      Tool javaRuntimeLauncher,
      Supplier<SourcePath> javacdBinaryPathSourcePathSupplier,
      ImmutableList<String> startJavacdCommandOptions,
      int javacdWorkerToolPoolSize,
      int javacdBorrowFromPoolTimeoutInSeconds) {
    this.jarBuildStepsFactory = jarBuildStepsFactory;
    this.unusedDependenciesAction = unusedDependenciesAction;
    this.buildTarget = buildTarget;
    this.unusedDependenciesFinderFactory = unusedDependenciesFinderFactory;
    this.sourceAbiOutput =
        Optional.ofNullable(sourceAbi)
            .map(
                rule ->
                    new NonHashableSourcePathContainer(
                        Objects.requireNonNull(rule.getSourcePathToOutput())));
    this.isJavaCDEnabled = isJavaCDEnabled;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.startJavacdCommandOptions = startJavacdCommandOptions;
    this.javacdWorkerToolPoolSize = javacdWorkerToolPoolSize;
    this.javacdBorrowFromPoolTimeoutInSeconds = javacdBorrowFromPoolTimeoutInSeconds;

    CompilerOutputPaths outputPaths =
        CompilerOutputPaths.of(buildTarget, filesystem.getBuckPaths());

    RelPath pathToClassHashes = getPathToClassHashes(filesystem);
    this.pathToClassHashesOutputPath = new PublicOutputPath(pathToClassHashes);

    this.rootOutputPath = new PublicOutputPath(outputPaths.getOutputJarDirPath());
    this.annotationsOutputPath = new PublicOutputPath(outputPaths.getAnnotationPath());
    this.javacdBinaryPathSourcePathSupplier = javacdBinaryPathSourcePathSupplier;
  }

  RelPath getPathToClassHashes(ProjectFilesystem filesystem) {
    return JavaLibraryRules.getPathToClassHashes(buildTarget, filesystem);
  }

  public ImmutableSortedSet<SourcePath> getSources() {
    return jarBuildStepsFactory.getSources();
  }

  public ImmutableSortedSet<SourcePath> getResources() {
    return jarBuildStepsFactory.getResources();
  }

  public Optional<String> getResourcesRoot() {
    return jarBuildStepsFactory.getResourcesRoot();
  }

  public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    return jarBuildStepsFactory.getCompileTimeClasspathSourcePaths();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {

    SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();
    LibraryJarStepsBuilder stepsBuilder =
        JavaCompileStepsBuilderFactoryCreator.createFactory(
                jarBuildStepsFactory.getConfiguredCompiler(),
                createJavaCDParams(sourcePathResolver))
            .getLibraryJarBuilder();

    jarBuildStepsFactory.addBuildStepsForLibraryJar(
        buildContext,
        filesystem,
        ModernBuildableSupport.getDerivedArtifactVerifier(buildTarget, filesystem, this),
        buildTarget,
        outputPathResolver.resolvePath(pathToClassHashesOutputPath),
        stepsBuilder);

    maybeAddUnusedDependencyStepAndAddMakeMissingOutputStep(
        buildContext, filesystem, outputPathResolver, stepsBuilder);

    return stepsBuilder.build();
  }

  @Override
  public ImmutableList<Step> getPipelinedBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      JavacPipelineState state,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {

    SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();
    LibraryJarPipelineStepsBuilder stepsBuilder =
        JavaCompileStepsBuilderFactoryCreator.createFactory(
                jarBuildStepsFactory.getConfiguredCompiler(),
                createJavaCDParams(sourcePathResolver))
            .getPipelineLibraryJarBuilder();

    jarBuildStepsFactory.addPipelinedBuildStepsForLibraryJar(
        buildContext,
        filesystem,
        ModernBuildableSupport.getDerivedArtifactVerifier(buildTarget, filesystem, this),
        state,
        outputPathResolver.resolvePath(pathToClassHashesOutputPath),
        stepsBuilder);

    maybeAddUnusedDependencyStepAndAddMakeMissingOutputStep(
        buildContext, filesystem, outputPathResolver, stepsBuilder);

    return stepsBuilder.build();
  }

  private JavaCDParams createJavaCDParams(SourcePathResolverAdapter sourcePathResolver) {
    return JavaCDParams.of(
        isJavaCDEnabled,
        javaRuntimeLauncher.getCommandPrefix(sourcePathResolver),
        () -> sourcePathResolver.getAbsolutePath(javacdBinaryPathSourcePathSupplier.get()),
        startJavacdCommandOptions,
        javacdWorkerToolPoolSize,
        javacdBorrowFromPoolTimeoutInSeconds);
  }

  private void maybeAddUnusedDependencyStepAndAddMakeMissingOutputStep(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      LibraryStepsBuilderBase stepsBuilder) {

    if (unusedDependenciesFinderFactory.isPresent()) {
      UnusedDependenciesFinderFactory factory = unusedDependenciesFinderFactory.get();

      AbsPath rootPath = filesystem.getRootPath();
      CellPathResolver cellPathResolver = buildContext.getCellPathResolver();
      SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();

      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings =
          CellPathResolverUtils.getCellToPathMappings(rootPath, cellPathResolver);

      BuckPaths buckPaths = filesystem.getBuckPaths();

      RelPath depFile =
          CompilerOutputPaths.getDepFilePath(
              CompilerOutputPaths.of(buildTarget, buckPaths).getOutputJarDirPath());
      UnusedDependenciesParams unusedDependenciesParams =
          createUnusedDependenciesParams(
              factory.convert(factory.deps, sourcePathResolver, rootPath),
              factory.convert(factory.providedDeps, sourcePathResolver, rootPath),
              depFile,
              factory);

      addUnusedDependencyStep(
          unusedDependenciesParams,
          cellToPathMappings,
          buildTarget.getFullyQualifiedName(),
          stepsBuilder);
    }

    RelPath rootOutput = outputPathResolver.resolvePath(rootOutputPath);
    RelPath pathToClassHashes = outputPathResolver.resolvePath(pathToClassHashesOutputPath);
    RelPath annotationsPath = outputPathResolver.resolvePath(annotationsOutputPath);
    stepsBuilder.addMakeMissingOutputsStep(rootOutput, pathToClassHashes, annotationsPath);
  }

  public UnusedDependenciesParams createUnusedDependenciesParams(
      ImmutableList<DependencyAndExportedDepsPath> deps,
      ImmutableList<DependencyAndExportedDepsPath> providedDeps,
      RelPath depFile,
      UnusedDependenciesFinderFactory factory) {
    UnusedDependenciesParams.Builder builder = UnusedDependenciesParams.newBuilder();

    for (DependencyAndExportedDepsPath dep : deps) {
      builder.addDeps(dep);
    }

    for (DependencyAndExportedDepsPath providedDep : providedDeps) {
      builder.addProvidedDeps(providedDep);
    }

    builder.setDepFile(toModelRelPath(depFile));
    builder.setUnusedDependenciesAction(unusedDependenciesAction);

    for (String exportedDep : factory.exportedDeps) {
      builder.addExportedDeps(exportedDep);
    }

    Optional<String> buildozerPath = factory.buildozerPath;
    buildozerPath.ifPresent(builder::setBuildozerPath);
    builder.setOnlyPrintCommands(factory.onlyPrintCommands);
    builder.setDoUltralightChecking(factory.doUltralightChecking);

    return builder.build();
  }

  public com.facebook.buck.javacd.model.RelPath toModelRelPath(RelPath relPath) {
    String path = relPath.toString();
    return com.facebook.buck.javacd.model.RelPath.newBuilder().setPath(path).build();
  }

  private void addUnusedDependencyStep(
      UnusedDependenciesParams unusedDependenciesParams,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      String buildTargetFullyQualifiedName,
      LibraryStepsBuilderBase stepsBuilder) {
    stepsBuilder.addUnusedDependencyStep(
        unusedDependenciesParams, cellToPathMappings, buildTargetFullyQualifiedName);
  }

  public boolean useDependencyFileRuleKeys() {
    return jarBuildStepsFactory.useDependencyFileRuleKeys() && !unusedDependenciesFinderEnabled();
  }

  private boolean unusedDependenciesFinderEnabled() {
    return unusedDependenciesFinderFactory.isPresent()
        && isActionableUnusedDependenciesAction(unusedDependenciesAction);
  }

  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathRuleFinder ruleFinder) {
    return jarBuildStepsFactory.getCoveredByDepFilePredicate(ruleFinder);
  }

  public Predicate<SourcePath> getExistenceOfInterestPredicate() {
    return jarBuildStepsFactory.getExistenceOfInterestPredicate();
  }

  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellPathResolver) {
    return jarBuildStepsFactory.getInputsAfterBuildingLocally(
        context, projectFilesystem, ruleFinder, cellPathResolver, buildTarget);
  }

  public boolean useRulePipelining() {
    return jarBuildStepsFactory.useRulePipelining();
  }

  public RulePipelineStateFactory<JavacPipelineState> getPipelineStateFactory() {
    return jarBuildStepsFactory;
  }

  public boolean hasAnnotationProcessing() {
    return jarBuildStepsFactory.hasAnnotationProcessing();
  }
}
