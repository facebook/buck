/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.artifact.BoundArtifact;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.impl.ActionExecutionStep;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.Objects;
import java.util.SortedSet;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * This represents the {@link RuleAnalysisResult} in the modern action framework as a legacy {@link
 * BuildRule} so that existing architecture can use them.
 *
 * <p>TODO(bobyf): figure out how to propagate provider info from here TODO(bobyf): make this a
 * {@link com.facebook.buck.rules.modern.ModernBuildRule}
 */
public class RuleAnalysisLegacyBuildRuleView extends AbstractBuildRule
    implements SupportsInputBasedRuleKey {
  // TODO(bobyf) figure out rulekeys and caching for new Actions

  private final String type;
  @AddToRuleKey private final Action action;
  private Supplier<SortedSet<BuildRule>> buildDepsSupplier;
  private BuildRuleResolver ruleResolver;

  /**
   * @param type the type of this {@link BuildRule}
   * @param buildTarget the {@link BuildTarget} of this analysis rule
   * @param action the action of the result for which we want to provide the {@link BuildRule} view
   * @param ruleResolver the current {@link BuildRuleResolver} to query dependent rules
   * @param projectFilesystem the filesystem
   */
  public RuleAnalysisLegacyBuildRuleView(
      String type,
      BuildTarget buildTarget,
      Action action,
      BuildRuleResolver ruleResolver,
      ProjectFilesystem projectFilesystem) {
    super(buildTarget, projectFilesystem);
    this.type = type;
    this.action = action;
    this.ruleResolver = ruleResolver;
    this.buildDepsSupplier = MoreSuppliers.memoize(this::getBuildDepsSupplier);
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDepsSupplier.get();
  }

  private SortedSet<BuildRule> getBuildDepsSupplier() {
    return ruleResolver.getAllRules(
        action.getInputs().stream()
            .map(Artifact::asBound)
            .map(BoundArtifact::asBuildArtifact)
            .filter(Objects::nonNull)
            .map(artifact -> artifact.getSourcePath().getTarget())
            .collect(ImmutableList.toImmutableList()));
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    // TODO(bobyf): refactor build engine and build rules to not require getBuildSteps but runs
    // actions
    for (Artifact artifact : action.getOutputs()) {
      buildableContext.recordArtifact(
          Objects.requireNonNull(artifact.asBound().asBuildArtifact())
              .getSourcePath()
              .getResolvedPath());
    }
    return ImmutableList.of(
        new ActionExecutionStep(
            action,
            context.getShouldDeleteTemporaries(),
            new ArtifactFilesystem(getProjectFilesystem())));
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return Iterables.getOnlyElement(action.getOutputs()).asBound().getSourcePath();
  }

  @Override
  public boolean isCacheable() {
    // TODO(bobyf): figure out rulekeys and caching for new Actions
    return false;
  }

  @Override
  public void updateBuildRuleResolver(BuildRuleResolver ruleResolver) {
    this.ruleResolver = ruleResolver;
    this.buildDepsSupplier = MoreSuppliers.memoize(this::getBuildDepsSupplier);
  }
}
