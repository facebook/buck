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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.impl.CellPathResolverUtils;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.rules.pipeline.StateHolder;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.javacd.model.BasePipeliningCommand;
import com.facebook.buck.javacd.model.FilesystemParams;
import com.facebook.buck.javacd.model.PipelineState;
import com.facebook.buck.javacd.model.RelPathMapEntry;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.core.CalculateAbi;
import com.facebook.buck.jvm.core.DefaultJavaAbiInfo;
import com.facebook.buck.jvm.core.JavaAbiInfo;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.CalculateSourceAbi.SourceAbiBuildable;
import com.facebook.buck.jvm.java.stepsbuilder.AbiJarStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilderFactory;
import com.facebook.buck.jvm.java.stepsbuilder.creator.JavaCompileStepsBuilderFactoryCreator;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.BuildTargetValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.CompilerOutputPathsValueSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization.RelPathSerializer;
import com.facebook.buck.jvm.java.stepsbuilder.params.BaseJavaCDParams;
import com.facebook.buck.jvm.java.stepsbuilder.params.JavaCDParams;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PipelinedBuildable;
import com.facebook.buck.rules.modern.PipelinedModernBuildRule;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.rules.modern.impl.ModernBuildableSupport;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.AbstractMessage;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Source Abi calculation. Derives the abi from the source files (possibly with access to
 * dependencies).
 */
public class CalculateSourceAbi
    extends PipelinedModernBuildRule<JavacPipelineState, SourceAbiBuildable>
    implements CalculateAbi, InitializableFromDisk<Object>, SupportsDependencyFileRuleKey {

  private final BuildOutputInitializer<Object> buildOutputInitializer;
  private final SourcePathRuleFinder ruleFinder;
  private final JavaAbiInfo javaAbiInfo;
  private final SourcePath sourcePathToOutput;

  public CalculateSourceAbi(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      JarBuildStepsFactory<?> jarBuildStepsFactory,
      SourcePathRuleFinder ruleFinder,
      Tool javaRuntimeLauncher,
      Supplier<SourcePath> javacdBinaryPathSourcePathSupplier,
      BaseJavaCDParams javaCDParams) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new SourceAbiBuildable(
            buildTarget,
            projectFilesystem,
            jarBuildStepsFactory,
            javaRuntimeLauncher,
            javacdBinaryPathSourcePathSupplier,
            javaCDParams));
    this.ruleFinder = ruleFinder;
    this.buildOutputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);
    this.sourcePathToOutput =
        Objects.requireNonNull(
            jarBuildStepsFactory.getSourcePathToOutput(
                getBuildTarget(), getProjectFilesystem().getBuckPaths()));
    this.javaAbiInfo = DefaultJavaAbiInfo.of(getSourcePathToOutput());
  }

  /** Buildable implementation. */
  public static class SourceAbiBuildable implements PipelinedBuildable<JavacPipelineState> {

    @AddToRuleKey(stringify = true)
    @CustomFieldBehavior(DefaultFieldSerialization.class)
    private final BuildTarget buildTarget;

    @AddToRuleKey private final JarBuildStepsFactory<?> jarBuildStepsFactory;

    @AddToRuleKey private final PublicOutputPath rootOutputPath;
    @AddToRuleKey private final PublicOutputPath annotationsOutputPath;

    @AddToRuleKey private final BaseJavaCDParams javaCDParams;

    @AddToRuleKey private final Tool javaRuntimeLauncher;

    @ExcludeFromRuleKey(
        reason = "path to javacd binary is not a part of a rule key",
        serialization = DefaultFieldSerialization.class,
        inputs = DefaultFieldInputs.class)
    private final Supplier<SourcePath> javacdBinaryPathSourcePathSupplier;

    public SourceAbiBuildable(
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        JarBuildStepsFactory<?> jarBuildStepsFactory,
        Tool javaRuntimeLauncher,
        Supplier<SourcePath> javacdBinaryPathSourcePathSupplier,
        BaseJavaCDParams javaCDParams) {
      this.buildTarget = buildTarget;
      this.jarBuildStepsFactory = jarBuildStepsFactory;
      this.javaCDParams = javaCDParams;
      this.javaRuntimeLauncher = javaRuntimeLauncher;
      CompilerOutputPaths outputPaths =
          CompilerOutputPaths.of(buildTarget, filesystem.getBuckPaths());
      this.rootOutputPath = new PublicOutputPath(outputPaths.getOutputJarDirPath());
      this.annotationsOutputPath = new PublicOutputPath(outputPaths.getAnnotationPath());
      this.javacdBinaryPathSourcePathSupplier = javacdBinaryPathSourcePathSupplier;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();
      AbiJarStepsBuilder stepsBuilder =
          getJavaCompileStepsBuilderFactory(filesystem, sourcePathResolver).getAbiJarBuilder();
      jarBuildStepsFactory.addBuildStepsForAbiJar(
          buildContext,
          filesystem,
          ModernBuildableSupport.getDerivedArtifactVerifier(buildTarget, filesystem, this),
          buildTarget,
          stepsBuilder);
      return stepsBuilder.build();
    }

    @Override
    public BasePipeliningCommand getPipelinedCommand(
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

      ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings =
          CellPathResolverUtils.getCellToPathMappings(
              filesystem.getRootPath(), buildContext.getCellPathResolver());

      FilesystemParams filesystemParams = FilesystemParamsUtils.of(filesystem);

      return buildMessage(
          buildTargetValue,
          filesystemParams,
          compilerOutputPathsValue,
          resourcesMap,
          cellToPathMappings);
    }

    private BasePipeliningCommand buildMessage(
        BuildTargetValue buildTargetValue,
        FilesystemParams filesystemParams,
        CompilerOutputPathsValue compilerOutputPathsValue,
        ImmutableMap<RelPath, RelPath> resourcesMap,
        ImmutableMap<CanonicalCellName, RelPath> cellToPathMappings) {
      BasePipeliningCommand.Builder builder = BasePipeliningCommand.newBuilder();
      builder
          .setBuildTargetValue(BuildTargetValueSerializer.serialize(buildTargetValue))
          .setFilesystemParams(filesystemParams)
          .setOutputPathsValue(
              CompilerOutputPathsValueSerializer.serialize(compilerOutputPathsValue));

      resourcesMap.forEach(
          (key, value) ->
              builder.addResourcesMap(
                  RelPathMapEntry.newBuilder()
                      .setKey(RelPathSerializer.serialize(key))
                      .setValue(RelPathSerializer.serialize(value))
                      .build()));
      cellToPathMappings.forEach(
          (key, value) ->
              builder.putCellToPathMappings(key.getName(), RelPathSerializer.serialize(value)));

      return builder.build();
    }

    @Override
    public ImmutableList<Step> getPipelinedBuildSteps(
        StateHolder<JavacPipelineState> stateHolder, AbstractMessage command) {
      Preconditions.checkState(command instanceof BasePipeliningCommand);
      BasePipeliningCommand basePipeliningCommand = (BasePipeliningCommand) command;

      FilesystemParams filesystemParams = basePipeliningCommand.getFilesystemParams();
      CompilerOutputPathsValue compilerOutputPathsValue =
          CompilerOutputPathsValueSerializer.deserialize(
              basePipeliningCommand.getOutputPathsValue());
      BuildTargetValue buildTargetValue =
          BuildTargetValueSerializer.deserialize(basePipeliningCommand.getBuildTargetValue());

      ImmutableList.Builder<IsolatedStep> stepsBuilder = ImmutableList.builder();

      ((BaseJavacToJarStepFactory) jarBuildStepsFactory.getConfiguredCompiler())
          .createPipelinedCompileToJarStep(
              filesystemParams,
              RelPathSerializer.toCellToPathMapping(
                  basePipeliningCommand.getCellToPathMappingsMap()),
              buildTargetValue,
              stateHolder.getState(),
              compilerOutputPathsValue,
              stepsBuilder,
              path -> {},
              RelPathSerializer.toResourceMap(basePipeliningCommand.getResourcesMapList()));

      return ImmutableList.copyOf(stepsBuilder.build()); // upcast to list of Steps
    }

    private JavaCompileStepsBuilderFactory getJavaCompileStepsBuilderFactory(
        ProjectFilesystem filesystem, SourcePathResolverAdapter sourcePathResolver) {
      return JavaCompileStepsBuilderFactoryCreator.createFactory(
          jarBuildStepsFactory.getConfiguredCompiler(),
          createJavaCDParams(filesystem, sourcePathResolver));
    }

    private JavaCDParams createJavaCDParams(
        ProjectFilesystem filesystem, SourcePathResolverAdapter sourcePathResolver) {
      return JavaCDParams.of(
          javaCDParams,
          javaRuntimeLauncher.getCommandPrefix(sourcePathResolver),
          () ->
              sourcePathResolver.getRelativePath(
                  filesystem, javacdBinaryPathSourcePathSupplier.get()));
    }

    public boolean supportsCompilationDaemon() {
      return javaCDParams.pipeliningSupported();
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return Objects.requireNonNull(sourcePathToOutput);
  }

  @Override
  public JavaAbiInfo getAbiInfo() {
    return javaAbiInfo;
  }

  @Override
  public String getType() {
    return JavaAbis.isSourceOnlyAbiTarget(getBuildTarget())
        ? "calculate_source_only_abi"
        : "calculate_source_abi";
  }

  @Override
  public void invalidateInitializeFromDiskState() {
    javaAbiInfo.invalidate();
  }

  @Override
  public Object initializeFromDisk(SourcePathResolverAdapter pathResolver) throws IOException {
    // Warm up the jar contents. We just wrote the thing, so it should be in the filesystem cache
    javaAbiInfo.load(pathResolver);
    return new Object();
  }

  @Override
  public BuildOutputInitializer<Object> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public boolean useRulePipelining() {
    return !JavaAbis.isSourceOnlyAbiTarget(getBuildTarget());
  }

  @Nullable
  @Override
  public SupportsPipelining<JavacPipelineState> getPreviousRuleInPipeline() {
    return null;
  }

  @Override
  public RulePipelineStateFactory<JavacPipelineState, PipelineState> getPipelineStateFactory() {
    return getBuildable().jarBuildStepsFactory;
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return getBuildable().jarBuildStepsFactory.useDependencyFileRuleKeys();
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate(
      SourcePathResolverAdapter pathResolver) {
    return getBuildable().jarBuildStepsFactory.getCoveredByDepFilePredicate(ruleFinder);
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(
      SourcePathResolverAdapter pathResolver) {
    return getBuildable().jarBuildStepsFactory.getExistenceOfInterestPredicate();
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) {
    return getBuildable()
        .jarBuildStepsFactory
        .getInputsAfterBuildingLocally(
            context, getProjectFilesystem(), ruleFinder, cellPathResolver, getBuildTarget());
  }

  @Override
  public boolean supportsCompilationDaemon() {
    return getBuildable().supportsCompilationDaemon();
  }
}
