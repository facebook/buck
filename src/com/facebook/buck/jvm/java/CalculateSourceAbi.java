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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildDeps;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.RulePipelineStateFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SupportsPipelining;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
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
  @AddToRuleKey private final int seed = 1;
  // This will be added to the rule key by virtue of being returned from getBuildDeps.
  private final BuildDeps buildDeps;
  private final JarContentsSupplier outputJarContents;
  private final BuildOutputInitializer<Object> buildOutputInitializer;

  public CalculateSourceAbi(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildDeps buildDeps,
      SourcePathRuleFinder ruleFinder,
      JarBuildStepsFactory jarBuildStepsFactory) {
    super(buildTarget, projectFilesystem);
    this.buildDeps = buildDeps;
    this.jarBuildStepsFactory = jarBuildStepsFactory;
    this.outputJarContents =
        new JarContentsSupplier(
            DefaultSourcePathResolver.from(ruleFinder), getSourcePathToOutput());
    buildOutputInitializer = new BuildOutputInitializer<>(getBuildTarget(), this);
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDeps;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return jarBuildStepsFactory.getBuildStepsForAbiJar(context, buildableContext, getBuildTarget());
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return Preconditions.checkNotNull(jarBuildStepsFactory.getSourcePathToOutput(getBuildTarget()));
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJarContents() {
    return outputJarContents.get();
  }

  @Override
  public boolean jarContains(String path) {
    return outputJarContents.jarContains(path);
  }

  @Override
  public Object initializeFromDisk() throws IOException {
    // Warm up the jar contents. We just wrote the thing, so it should be in the filesystem cache
    outputJarContents.load();
    return new Object();
  }

  @Override
  public BuildOutputInitializer<Object> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  @Override
  public boolean useRulePipelining() {
    return !HasJavaAbi.isSourceOnlyAbiTarget(getBuildTarget());
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
        getBuildTarget(), context, buildableContext, state);
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
    return jarBuildStepsFactory.getCoveredByDepFilePredicate(pathResolver);
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
    return jarBuildStepsFactory.getExistenceOfInterestPredicate(pathResolver);
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) throws IOException {
    return jarBuildStepsFactory.getInputsAfterBuildingLocally(
        context, cellPathResolver, getBuildTarget());
  }
}
