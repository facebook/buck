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
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildDeps;
import com.facebook.buck.core.rules.common.RecordArtifactVerifier;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.rules.pipeline.SupportsPipelining;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.DefaultJavaAbiInfo;
import com.facebook.buck.jvm.core.JavaAbiInfo;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.SortedSet;
import java.util.function.Predicate;
import javax.annotation.Nullable;

public class CalculateSourceAbi extends AbstractBuildRule
    implements CalculateAbi,
        InitializableFromDisk<Object>,
        SupportsDependencyFileRuleKey,
        SupportsInputBasedRuleKey,
        SupportsPipelining<JavacPipelineState> {
  @AddToRuleKey private final JarBuildStepsFactory jarBuildStepsFactory;

  // This will be added to the rule key by virtue of being returned from getBuildDeps.
  private final BuildDeps buildDeps;
  private final BuildOutputInitializer<Object> buildOutputInitializer;
  private final SourcePathRuleFinder ruleFinder;
  private final JavaAbiInfo javaAbiInfo;

  private final SourcePath sourcePathToOutput;

  public CalculateSourceAbi(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildDeps buildDeps,
      JarBuildStepsFactory jarBuildStepsFactory,
      SourcePathRuleFinder ruleFinder) {
    super(buildTarget, projectFilesystem);
    this.buildDeps = buildDeps;
    this.jarBuildStepsFactory = jarBuildStepsFactory;
    this.ruleFinder = ruleFinder;
    this.buildOutputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);
    this.sourcePathToOutput =
        Preconditions.checkNotNull(
            jarBuildStepsFactory.getSourcePathToOutput(getBuildTarget(), getProjectFilesystem()));
    this.javaAbiInfo = new DefaultJavaAbiInfo(getSourcePathToOutput());
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return jarBuildStepsFactory.getBuildStepsForAbiJar(
        context, getProjectFilesystem(), getArtifactVerifier(buildableContext), getBuildTarget());
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return Preconditions.checkNotNull(sourcePathToOutput);
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
  public ImmutableList<? extends Step> getPipelinedBuildSteps(
      BuildContext context, BuildableContext buildableContext, JavacPipelineState state) {
    return jarBuildStepsFactory.getPipelinedBuildStepsForAbiJar(
        getBuildTarget(),
        context,
        getProjectFilesystem(),
        getArtifactVerifier(buildableContext),
        state);
  }

  private RecordArtifactVerifier getArtifactVerifier(BuildableContext buildableContext) {
    CompilerOutputPaths outputPaths =
        CompilerOutputPaths.of(getBuildTarget(), getProjectFilesystem());
    return new RecordArtifactVerifier(
        ImmutableList.of(outputPaths.getOutputJarDirPath(), outputPaths.getAnnotationPath()),
        buildableContext);
  }

  @Override
  public RulePipelineStateFactory<JavacPipelineState> getPipelineStateFactory() {
    return jarBuildStepsFactory;
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return jarBuildStepsFactory.useDependencyFileRuleKeys();
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver) {
    return jarBuildStepsFactory.getCoveredByDepFilePredicate(pathResolver, ruleFinder);
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
    return jarBuildStepsFactory.getExistenceOfInterestPredicate(pathResolver);
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) {
    return jarBuildStepsFactory.getInputsAfterBuildingLocally(
        context, getProjectFilesystem(), ruleFinder, cellPathResolver, getBuildTarget());
  }
}
