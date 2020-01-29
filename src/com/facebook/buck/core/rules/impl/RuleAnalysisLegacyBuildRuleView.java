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

package com.facebook.buck.core.rules.impl;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.artifact.BoundArtifact;
import com.facebook.buck.core.artifact.OutputArtifact;
import com.facebook.buck.core.artifact.converter.DefaultInfoArtifactsRetriever;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.analysis.RuleAnalysisResult;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.impl.ActionExecutionStep;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    implements SupportsInputBasedRuleKey, HasMultipleOutputs {

  private final String type;
  @AddToRuleKey private final Optional<Action> action;
  private Supplier<SortedSet<BuildRule>> buildDepsSupplier;
  private BuildRuleResolver ruleResolver;
  private final ProviderInfoCollection providerInfoCollection;
  private final Supplier<ImmutableSet<OutputLabel>> outputLabels;

  /**
   * @param type the type of this {@link BuildRule}
   * @param buildTarget the {@link BuildTarget} of this analysis rule
   * @param action the action of the result for which we want to provide the {@link BuildRule} view
   * @param ruleResolver the current {@link BuildRuleResolver} to query dependent rules
   * @param projectFilesystem the filesystem
   * @param providerInfoCollection the providers returned by this build target
   */
  public RuleAnalysisLegacyBuildRuleView(
      String type,
      BuildTarget buildTarget,
      Optional<Action> action, // TODO change this to iterable once we allow multiple outputs
      BuildRuleResolver ruleResolver,
      ProjectFilesystem projectFilesystem,
      ProviderInfoCollection providerInfoCollection) {
    super(buildTarget, projectFilesystem);
    this.type = type;
    this.action = action;
    this.ruleResolver = ruleResolver;
    this.providerInfoCollection = providerInfoCollection;
    this.buildDepsSupplier = MoreSuppliers.memoize(this::getBuildDepsSupplier);
    this.outputLabels = MoreSuppliers.memoize(this::getOutputLabelsSupplier);
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
    return action
        .map(
            a ->
                ruleResolver.getAllRules(
                    a.getInputs().stream()
                        .map(Artifact::asBound)
                        .map(BoundArtifact::asBuildArtifact)
                        .filter(Objects::nonNull)
                        .map(artifact -> artifact.getSourcePath().getTarget())
                        .collect(ImmutableList.toImmutableList())))
        .orElse(ImmutableSortedSet.of());
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    // TODO(bobyf): refactor build engine and build rules to not require getBuildSteps but runs
    // actions

    if (!action.isPresent()) {
      return ImmutableList.of();
    }

    for (OutputArtifact artifact : action.get().getOutputs()) {
      buildableContext.recordArtifact(
          Objects.requireNonNull(artifact.getArtifact().asBound().asBuildArtifact())
              .getSourcePath()
              .getResolvedPath());
    }
    return ImmutableList.of(
        new ActionExecutionStep(action.get(), new ArtifactFilesystem(getProjectFilesystem())));
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    ImmutableSortedSet<SourcePath> output = getSourcePathToOutput(OutputLabel.defaultLabel());
    if (output.isEmpty()) {
      return null;
    }
    return Iterables.getOnlyElement(output);
  }

  @Override
  public ImmutableSortedSet<SourcePath> getSourcePathToOutput(OutputLabel outputLabel) {
    return convertToSourcePaths(
        DefaultInfoArtifactsRetriever.getArtifacts(
            providerInfoCollection.getDefaultInfo(),
            BuildTargetWithOutputs.of(getBuildTarget(), outputLabel)));
  }

  @Override
  public ImmutableSet<OutputLabel> getOutputLabels() {
    return outputLabels.get();
  }

  private ImmutableSet<OutputLabel> getOutputLabelsSupplier() {
    DefaultInfo defaultInfo = providerInfoCollection.getDefaultInfo();
    ImmutableSet.Builder<OutputLabel> builder =
        ImmutableSet.builderWithExpectedSize(defaultInfo.namedOutputs().size() + 1);
    defaultInfo
        .namedOutputs()
        .keySet()
        .forEach(outputLabel -> builder.add(OutputLabel.of(outputLabel)));
    builder.add(OutputLabel.defaultLabel());
    return builder.build();
  }

  @Override
  public boolean isCacheable() {
    return true;
  }

  @Override
  public void updateBuildRuleResolver(BuildRuleResolver ruleResolver) {
    this.ruleResolver = ruleResolver;
    this.buildDepsSupplier = MoreSuppliers.memoize(this::getBuildDepsSupplier);
  }

  /** @return the {@link ProviderInfoCollection} returned from rule analysis */
  public ProviderInfoCollection getProviderInfos() {
    return providerInfoCollection;
  }

  private static ImmutableSortedSet<SourcePath> convertToSourcePaths(Set<Artifact> artifacts) {
    return artifacts.stream()
        .map(artifact -> artifact.asBound().getSourcePath())
        .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
  }
}
