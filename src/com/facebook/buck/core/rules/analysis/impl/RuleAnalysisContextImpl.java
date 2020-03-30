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

package com.facebook.buck.core.rules.analysis.impl;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.converter.SourceArtifactConverter;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.DefaultActionRegistry;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisData;
import com.facebook.buck.core.rules.analysis.action.ActionAnalysisDataRegistry;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Implementation of {@link com.facebook.buck.core.rules.analysis.RuleAnalysisContext}. This context
 * is created per rule analysis.
 */
public class RuleAnalysisContextImpl implements RuleAnalysisContext, ActionAnalysisDataRegistry {

  private final BuildTarget buildTarget;
  private final ImmutableMap<BuildTarget, ProviderInfoCollection> depProviders;
  private final Map<ActionAnalysisData.ID, ActionAnalysisData> actionAnalysisDataRegistry =
      new HashMap<>();
  private final ActionRegistry actionRegistry;
  private final BuckEventBus eventBus;

  public RuleAnalysisContextImpl(
      BuildTarget buildTarget,
      ImmutableMap<BuildTarget, ProviderInfoCollection> depProviders,
      ProjectFilesystem filesystem,
      BuckEventBus eventBus) {
    this.buildTarget = buildTarget;
    this.depProviders = depProviders;
    this.eventBus = eventBus;
    this.actionRegistry = new DefaultActionRegistry(buildTarget, this, filesystem);
  }

  @Override
  public Map<BuildTarget, ProviderInfoCollection> resolveDeps(Iterable<BuildTarget> deps) {
    if (deps instanceof Set) {
      Set<BuildTarget> buildTargets = (Set<BuildTarget>) deps;
      Map<BuildTarget, ProviderInfoCollection> result =
          Maps.filterKeys(depProviders, buildTargets::contains);

      if (result.size() != buildTargets.size()) {
        throw new IllegalStateException(
            String.format(
                "Deps didn't contain the following requested build targets: %s",
                Sets.difference(result.keySet(), buildTargets)));
      }

      return result;
    }
    return Maps.toMap(deps, dep -> resolveDep(dep));
  }

  @Override
  public ProviderInfoCollection resolveDep(BuildTarget dep) {
    return Objects.requireNonNull(
        depProviders.get(dep),
        String.format("Deps didn't contain the following build target %s", dep));
  }

  @Override
  public ImmutableSortedSet<Artifact> resolveSrcs(Iterable<SourcePath> srcs) {
    return SourceArtifactConverter.getArtifactsFromSrcs(srcs, depProviders);
  }

  @Override
  public Artifact resolveSrc(SourcePath src) {
    return SourceArtifactConverter.getArtifactsFromSrc(src, depProviders);
  }

  @Override
  public ActionRegistry actionRegistry() {
    return actionRegistry;
  }

  @Override
  public BuckEventBus getEventBus() {
    return eventBus;
  }

  @Override
  public void registerAction(ActionAnalysisData actionAnalysisData) {
    Preconditions.checkState(actionAnalysisData.getKey().getBuildTarget().equals(buildTarget));

    ActionAnalysisData prev =
        actionAnalysisDataRegistry.putIfAbsent(
            actionAnalysisData.getKey().getID(), actionAnalysisData);
    Verify.verify(
        prev == null,
        "Action of key %s was already registered with %s",
        actionAnalysisData.getKey(),
        prev);
  }

  /**
   * Verifies that the {@link ActionRegistry} has been finalized where all {@link
   * com.facebook.buck.core.artifact.Artifact}s are bound, and then returns all the {@link
   * ActionAnalysisData} registered.
   */
  public Map<ActionAnalysisData.ID, ActionAnalysisData> getRegisteredActionData() {
    actionRegistry.verifyAllArtifactsBound();
    return actionAnalysisDataRegistry;
  }
}
