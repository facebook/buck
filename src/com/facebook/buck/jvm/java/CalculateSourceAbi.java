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
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.rules.modern.annotations.DefaultFieldSerialization;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.DefaultJavaAbiInfo;
import com.facebook.buck.jvm.core.JavaAbiInfo;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.jvm.java.CalculateSourceAbi.SourceAbiBuildable;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PipelinedBuildable;
import com.facebook.buck.rules.modern.PipelinedModernBuildRule;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.rules.modern.impl.ModernBuildableSupport;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Predicate;
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
      JarBuildStepsFactory jarBuildStepsFactory,
      SourcePathRuleFinder ruleFinder) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new SourceAbiBuildable(buildTarget, projectFilesystem, jarBuildStepsFactory));
    this.ruleFinder = ruleFinder;
    this.buildOutputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);
    this.sourcePathToOutput =
        Objects.requireNonNull(
            jarBuildStepsFactory.getSourcePathToOutput(getBuildTarget(), getProjectFilesystem()));
    this.javaAbiInfo = new DefaultJavaAbiInfo(getSourcePathToOutput());
  }

  /** Buildable implementation. */
  public static class SourceAbiBuildable implements PipelinedBuildable<JavacPipelineState> {
    @AddToRuleKey(stringify = true)
    @CustomFieldBehavior(DefaultFieldSerialization.class)
    private final BuildTarget buildTarget;

    @AddToRuleKey private final JarBuildStepsFactory jarBuildStepsFactory;

    @AddToRuleKey private final PublicOutputPath rootOutputPath;
    @AddToRuleKey private final PublicOutputPath annotationsOutputPath;

    public SourceAbiBuildable(
        BuildTarget buildTarget,
        ProjectFilesystem filesystem,
        JarBuildStepsFactory jarBuildStepsFactory) {
      this.buildTarget = buildTarget;
      this.jarBuildStepsFactory = jarBuildStepsFactory;

      CompilerOutputPaths outputPaths = CompilerOutputPaths.of(buildTarget, filesystem);
      this.rootOutputPath = new PublicOutputPath(outputPaths.getOutputJarDirPath());
      this.annotationsOutputPath = new PublicOutputPath(outputPaths.getAnnotationPath());
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return jarBuildStepsFactory.getBuildStepsForAbiJar(
          buildContext,
          filesystem,
          ModernBuildableSupport.getDerivedArtifactVerifier(buildTarget, filesystem, this),
          buildTarget);
    }

    @Override
    public ImmutableList<Step> getPipelinedBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        JavacPipelineState state,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return jarBuildStepsFactory.getPipelinedBuildStepsForAbiJar(
          buildTarget,
          buildContext,
          filesystem,
          ModernBuildableSupport.getDerivedArtifactVerifier(buildTarget, filesystem, this),
          state);
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
  public void invalidateInitializeFromDiskState() {
    javaAbiInfo.invalidate();
  }

  @Override
  public Object initializeFromDisk(SourcePathResolver pathResolver) throws IOException {
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
  public RulePipelineStateFactory<JavacPipelineState> getPipelineStateFactory() {
    return getBuildable().jarBuildStepsFactory;
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return getBuildable().jarBuildStepsFactory.useDependencyFileRuleKeys();
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver) {
    return getBuildable()
        .jarBuildStepsFactory
        .getCoveredByDepFilePredicate(pathResolver, ruleFinder);
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
    return getBuildable().jarBuildStepsFactory.getExistenceOfInterestPredicate(pathResolver);
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) {
    return getBuildable()
        .jarBuildStepsFactory
        .getInputsAfterBuildingLocally(
            context, getProjectFilesystem(), ruleFinder, cellPathResolver, getBuildTarget());
  }
}
