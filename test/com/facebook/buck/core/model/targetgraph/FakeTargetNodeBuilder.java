/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.model.targetgraph;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder.FakeDescription;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.rules.FakeBuildRule;
import com.google.common.collect.ImmutableCollection;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class FakeTargetNodeBuilder
    extends AbstractNodeBuilder<
        FakeTargetNodeArg.Builder, FakeTargetNodeArg, FakeDescription, BuildRule> {

  private FakeTargetNodeBuilder(FakeDescription description, BuildTarget target) {
    super(description, target);
  }

  public FakeTargetNodeBuilder setLabel(String label) {
    getArgForPopulating().addLabels(label);
    return this;
  }

  public FakeTargetNodeBuilder setDeps(BuildTarget... deps) {
    getArgForPopulating().setDeps(Arrays.asList(deps));
    return this;
  }

  public FakeTargetNodeBuilder setDeps(TargetNode<?>... deps) {
    return setDeps(Stream.of(deps).map(x -> x.getBuildTarget()).toArray(BuildTarget[]::new));
  }

  public FakeTargetNodeBuilder setExtraDeps(TargetNode<?>... deps) {
    description.setExtraDeps(
        Stream.of(deps).map(x -> x.getBuildTarget()).collect(Collectors.toSet()));
    return this;
  }

  public FakeTargetNodeBuilder setTargetGraphOnlyDeps(TargetNode<?>... deps) {
    description.setTargetGraphOnlyDeps(
        Stream.of(deps).map(x -> x.getBuildTarget()).collect(Collectors.toSet()));
    return this;
  }

  public FakeTargetNodeBuilder setProducesCacheableSubgraph(boolean value) {
    description.setProducesCacheableSubgraph(value);
    return this;
  }

  public static FakeTargetNodeBuilder newBuilder(FakeDescription description, BuildTarget target) {
    return new FakeTargetNodeBuilder(description, target);
  }

  public static FakeTargetNodeBuilder newBuilder(BuildTarget target) {
    return new FakeTargetNodeBuilder(new FakeDescription(), target);
  }

  public static FakeTargetNodeBuilder newBuilder(BuildRule rule) {
    return new FakeTargetNodeBuilder(new FakeDescription(rule), rule.getBuildTarget());
  }

  public static TargetNode<FakeTargetNodeArg> build(BuildRule rule) {
    return newBuilder(rule).build();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractFakeTargetNodeArg extends CommonDescriptionArg, HasDeclaredDeps {}

  public static class FakeDescription
      implements DescriptionWithTargetGraph<FakeTargetNodeArg>,
          ImplicitDepsInferringDescription<FakeTargetNodeArg> {
    private final BuildRule rule;
    private Set<BuildTarget> extraDeps;
    private Set<BuildTarget> targetGraphOnlyDeps;
    private boolean producesCacheableSubgraph = true;

    public FakeDescription() {
      this(null);
    }

    public FakeDescription(BuildRule rule) {
      this.rule = rule;
    }

    private void setExtraDeps(Set<BuildTarget> extraDeps) {
      this.extraDeps = extraDeps;
    }

    private void setTargetGraphOnlyDeps(Set<BuildTarget> targetGraphOnlyDeps) {
      this.targetGraphOnlyDeps = targetGraphOnlyDeps;
    }

    private void setProducesCacheableSubgraph(boolean value) {
      producesCacheableSubgraph = value;
    }

    @Override
    public Class<FakeTargetNodeArg> getConstructorArgType() {
      return FakeTargetNodeArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        FakeTargetNodeArg args) {
      if (rule != null) return rule;
      return new FakeBuildRule(buildTarget, context.getProjectFilesystem(), params);
    }

    @Override
    public void findDepsForTargetFromConstructorArgs(
        BuildTarget buildTarget,
        CellPathResolver cellRoots,
        FakeTargetNodeArg constructorArg,
        ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
        ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
      if (extraDeps != null) {
        extraDepsBuilder.addAll(extraDeps);
      }
      if (targetGraphOnlyDeps != null) {
        targetGraphOnlyDepsBuilder.addAll(targetGraphOnlyDeps);
      }
    }

    @Override
    public boolean producesCacheableSubgraph() {
      return producesCacheableSubgraph;
    }
  }
}
