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
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.rules.pipeline.StateHolder;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.javacd.model.BasePipeliningCommand;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.javacd.model.LibraryJarBaseCommand;
import com.facebook.buck.javacd.model.LibraryPipeliningCommand;
import com.facebook.buck.javacd.model.PipelineState;
import com.facebook.buck.javacd.model.RelPathMapEntry;
import com.facebook.buck.javacd.model.UnusedDependenciesParams;
import com.facebook.buck.javacd.model.UnusedDependenciesParams.DependencyAndExportedDepsPath;
import com.facebook.buck.javacd.model.UnusedDependenciesParams.UnusedDependenciesAction;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilderFactory;
import com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryRules;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.creator.JavaCompileStepsBuilderFactoryCreator;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.BuildTargetValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.CompilerOutputPathsValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.RelPathSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.params.BaseJavaCDParams;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParams;
import com.facebook.buck.jvm.java.version.JavaVersion;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PipelinedBuildable;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.rules.modern.impl.ModernBuildableSupport;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.step.isolatedsteps.java.MakeMissingOutputsStep;
import com.facebook.buck.step.isolatedsteps.java.UnusedDependenciesFinder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.protobuf.AbstractMessage;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
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

  @AddToRuleKey private final BaseJavaCDParams javaCDParams;

  DefaultJavaLibraryBuildable(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      JarBuildStepsFactory<?> jarBuildStepsFactory,
      UnusedDependenciesAction unusedDependenciesAction,
      Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory,
      @Nullable CalculateSourceAbi sourceAbi,
      BaseJavaCDParams javaCDParams) {
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
    this.javaCDParams = javaCDParams;

    CompilerOutputPaths outputPaths =
        CompilerOutputPaths.of(buildTarget, filesystem.getBuckPaths());

    RelPath pathToClassHashes = getPathToClassHashes(filesystem);
    this.pathToClassHashesOutputPath = new PublicOutputPath(pathToClassHashes);
    this.rootOutputPath = new PublicOutputPath(outputPaths.getOutputJarDirPath());
    this.annotationsOutputPath = new PublicOutputPath(outputPaths.getAnnotationPath());
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

    JavaCompileStepsBuilderFactory javaCompileStepsBuilderFactory =
        getJavaCompileStepsBuilderFactory(filesystem);
    LibraryStepsBuilder stepsBuilder = javaCompileStepsBuilderFactory.getLibraryBuilder();

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

  private JavaCompileStepsBuilderFactory getJavaCompileStepsBuilderFactory(
      ProjectFilesystem projectFilesystem) {
    return JavaCompileStepsBuilderFactoryCreator.createFactory(
        jarBuildStepsFactory.getConfiguredCompiler(), createJavaCDParams(projectFilesystem));
  }

  @Override
  public LibraryPipeliningCommand getPipelinedCommand(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    BuckPaths buckPaths = filesystem.getBuckPaths();
    BuildTargetValue buildTargetValue = BuildTargetValue.withExtraParams(buildTarget, buckPaths);

    CompilerOutputPathsValue compilerOutputPathsValue =
        CompilerOutputPathsValue.of(buckPaths, buildTarget);
    RelPath classesDir =
        compilerOutputPathsValue.getByType(buildTargetValue.getType()).getClassesDir();

    ImmutableMap<RelPath, RelPath> resourcesMap =
        CopyResourcesStep.getResourcesMap(
            buildContext,
            filesystem,
            classesDir.getPath(),
            jarBuildStepsFactory.getResourcesParameters(),
            buildTarget);

    CellPathResolver cellPathResolver = buildContext.getCellPathResolver();
    SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();
    AbsPath rootPath = filesystem.getRootPath();

    ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings =
        CellPathResolverUtils.getCellToPathMappings(rootPath, cellPathResolver);

    FilesystemParams filesystemParams = FilesystemParamsUtils.of(filesystem);

    UnusedDependenciesParams unusedDependenciesParams = null;
    if (unusedDependenciesFinderFactory.isPresent()) {
      UnusedDependenciesFinderFactory factory = unusedDependenciesFinderFactory.get();
      unusedDependenciesParams =
          createUnusedDependenciesParams(
              factory.convert(factory.deps, sourcePathResolver, rootPath),
              factory.convert(factory.providedDeps, sourcePathResolver, rootPath),
              jarBuildStepsFactory.getConfiguredCompiler().getDepFilePaths(filesystem, buildTarget),
              factory);
    }

    RelPath rootOutput = outputPathResolver.resolvePath(rootOutputPath);
    RelPath pathToClassHashes = outputPathResolver.resolvePath(pathToClassHashesOutputPath);
    RelPath annotationsPath = outputPathResolver.resolvePath(annotationsOutputPath);
    Optional<RelPath> pathToClasses =
        jarBuildStepsFactory.getPathToClasses(buildContext, buildTarget, buckPaths);

    return buildMessage(
        buildTargetValue,
        filesystemParams,
        compilerOutputPathsValue,
        resourcesMap,
        cellToPathMappings,
        unusedDependenciesParams,
        rootOutput,
        pathToClassHashes,
        annotationsPath,
        pathToClasses);
  }

  private LibraryPipeliningCommand buildMessage(
      BuildTargetValue buildTargetValue,
      FilesystemParams filesystemParams,
      CompilerOutputPathsValue compilerOutputPathsValue,
      ImmutableMap<RelPath, RelPath> resourcesMap,
      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings,
      @Nullable UnusedDependenciesParams unusedDependenciesParams,
      RelPath rootOutput,
      RelPath pathToClassHashes,
      RelPath annotationsPath,
      Optional<RelPath> pathToClasses) {
    LibraryPipeliningCommand.Builder builder = LibraryPipeliningCommand.newBuilder();

    BasePipeliningCommand.Builder basePipeliningCommandBuilder =
        builder.getBasePipeliningCommandBuilder();

    basePipeliningCommandBuilder
        .setBuildTargetValue(BuildTargetValueSerializer.serialize(buildTargetValue))
        .setFilesystemParams(filesystemParams)
        .setOutputPathsValue(
            CompilerOutputPathsValueSerializer.serialize(compilerOutputPathsValue));

    resourcesMap.forEach(
        (key, value) ->
            basePipeliningCommandBuilder.addResourcesMap(
                RelPathMapEntry.newBuilder()
                    .setKey(RelPathSerializer.serialize(key))
                    .setValue(RelPathSerializer.serialize(value))
                    .build()));
    cellToPathMappings.forEach(
        (key, value) ->
            basePipeliningCommandBuilder.putCellToPathMappings(
                key.getName(), RelPathSerializer.serialize(value)));

    LibraryJarBaseCommand.Builder libraryJarBaseCommandBuilder =
        builder.getLibraryJarBaseCommandBuilder();
    libraryJarBaseCommandBuilder
        .setRootOutput(RelPathSerializer.serialize(rootOutput))
        .setPathToClassHashes(RelPathSerializer.serialize(pathToClassHashes))
        .setAnnotationsPath(RelPathSerializer.serialize(annotationsPath));

    pathToClasses
        .map(RelPathSerializer::serialize)
        .ifPresent(libraryJarBaseCommandBuilder::setPathToClasses);

    if (unusedDependenciesParams != null) {
      libraryJarBaseCommandBuilder.setUnusedDependenciesParams(unusedDependenciesParams);
    }

    return builder.build();
  }

  @Override
  public ImmutableList<Step> getPipelinedBuildSteps(
      StateHolder<JavacPipelineState> stateHolder,
      AbstractMessage command,
      ProjectFilesystem filesystem) {
    Preconditions.checkState(command instanceof LibraryPipeliningCommand);
    LibraryPipeliningCommand libraryPipeliningCommand = (LibraryPipeliningCommand) command;

    BasePipeliningCommand basePipeliningCommand =
        libraryPipeliningCommand.getBasePipeliningCommand();

    FilesystemParams filesystemParams = basePipeliningCommand.getFilesystemParams();
    CompilerOutputPathsValue compilerOutputPathsValue =
        CompilerOutputPathsValueSerializer.deserialize(basePipeliningCommand.getOutputPathsValue());
    BuildTargetValue buildTargetValue =
        BuildTargetValueSerializer.deserialize(basePipeliningCommand.getBuildTargetValue());
    ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings =
        RelPathSerializer.toCellToPathMapping(basePipeliningCommand.getCellToPathMappingsMap());

    ImmutableList.Builder<IsolatedStep> stepsBuilder = ImmutableList.builder();

    ((BaseJavacToJarStepFactory) jarBuildStepsFactory.getConfiguredCompiler())
        .createPipelinedCompileToJarStep(
            filesystemParams,
            cellToPathMappings,
            buildTargetValue,
            stateHolder.getState(),
            stateHolder.isFirstStage(),
            compilerOutputPathsValue,
            stepsBuilder,
            ModernBuildableSupport.getDerivedArtifactVerifier(buildTarget, filesystem, this),
            RelPathSerializer.toResourceMap(basePipeliningCommand.getResourcesMapList()));

    LibraryJarBaseCommand libraryJarBaseCommand =
        libraryPipeliningCommand.getLibraryJarBaseCommand();
    JavaLibraryRules.addAccumulateClassNamesStep(
        FilesystemParamsUtils.getIgnoredPaths(filesystemParams),
        stepsBuilder,
        libraryJarBaseCommand.hasPathToClasses()
            ? Optional.of(RelPathSerializer.deserialize(libraryJarBaseCommand.getPathToClasses()))
            : Optional.empty(),
        RelPathSerializer.deserialize(libraryJarBaseCommand.getPathToClassHashes()));

    if (libraryJarBaseCommand.hasUnusedDependenciesParams()) {
      UnusedDependenciesParams unusedDependenciesParams =
          libraryJarBaseCommand.getUnusedDependenciesParams();

      String buildozerPath = unusedDependenciesParams.getBuildozerPath();
      stepsBuilder.add(
          UnusedDependenciesFinder.of(
              buildTargetValue.getFullyQualifiedName(),
              unusedDependenciesParams.getDepsList(),
              unusedDependenciesParams.getProvidedDepsList(),
              unusedDependenciesParams.getExportedDepsList(),
              unusedDependenciesParams.getUnusedDependenciesAction(),
              buildozerPath.isEmpty() ? Optional.empty() : Optional.of(buildozerPath),
              unusedDependenciesParams.getOnlyPrintCommands(),
              cellToPathMappings,
              unusedDependenciesParams.getDepFileList().stream()
                  .map(path -> RelPath.get(path.getPath()))
                  .collect(ImmutableList.toImmutableList()),
              unusedDependenciesParams.getDoUltralightChecking()));
    }

    RelPath rootOutput = RelPathSerializer.deserialize(libraryJarBaseCommand.getRootOutput());
    RelPath pathToClassHashes =
        RelPathSerializer.deserialize(libraryJarBaseCommand.getPathToClassHashes());
    RelPath annotationsPath =
        RelPathSerializer.deserialize(libraryJarBaseCommand.getAnnotationsPath());
    stepsBuilder.add(new MakeMissingOutputsStep(rootOutput, pathToClassHashes, annotationsPath));

    return ImmutableList.copyOf(stepsBuilder.build()); // upcast to list of Steps
  }

  private JavaCDParams createJavaCDParams(ProjectFilesystem filesystem) {
    return JavaCDParams.of(javaCDParams, filesystem);
  }

  private void maybeAddUnusedDependencyStepAndAddMakeMissingOutputStep(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      LibraryStepsBuilder stepsBuilder) {

    if (unusedDependenciesFinderFactory.isPresent()) {
      UnusedDependenciesFinderFactory factory = unusedDependenciesFinderFactory.get();

      AbsPath rootPath = filesystem.getRootPath();
      CellPathResolver cellPathResolver = buildContext.getCellPathResolver();
      SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();

      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings =
          CellPathResolverUtils.getCellToPathMappings(rootPath, cellPathResolver);

      UnusedDependenciesParams unusedDependenciesParams =
          createUnusedDependenciesParams(
              factory.convert(factory.deps, sourcePathResolver, rootPath),
              factory.convert(factory.providedDeps, sourcePathResolver, rootPath),
              jarBuildStepsFactory.getConfiguredCompiler().getDepFilePaths(filesystem, buildTarget),
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
      ImmutableList<RelPath> depFiles,
      UnusedDependenciesFinderFactory factory) {
    UnusedDependenciesParams.Builder builder = UnusedDependenciesParams.newBuilder();

    for (DependencyAndExportedDepsPath dep : deps) {
      builder.addDeps(dep);
    }

    for (DependencyAndExportedDepsPath providedDep : providedDeps) {
      builder.addProvidedDeps(providedDep);
    }

    for (RelPath depFile : depFiles) {
      builder.addDepFile(toModelRelPath(depFile));
    }
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
      LibraryStepsBuilder stepsBuilder) {
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

  public RulePipelineStateFactory<JavacPipelineState, PipelineState> getPipelineStateFactory() {
    return jarBuildStepsFactory;
  }

  public boolean hasAnnotationProcessing() {
    return jarBuildStepsFactory.hasAnnotationProcessing();
  }

  public boolean supportsCompilationDaemon() {
    return javaCDParams.hasJavaCDEnabled();
  }
}
