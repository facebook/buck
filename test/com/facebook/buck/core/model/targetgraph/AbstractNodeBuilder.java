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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.query.QueryCache;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.rules.visibility.VisibilityPatternParser;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Support class for writing builders for nodes of a {@link TargetGraph} and {@link ActionGraph}
 * ({@link TargetNode} and {@link BuildRule} respectively) mirroring the behavior seen when running
 * the actual parser as closely as possible.
 */
public abstract class AbstractNodeBuilder<
    TArgBuilder,
    TArg,
    TDescription extends DescriptionWithTargetGraph<TArg>,
    TBuildRule extends BuildRule> {
  protected static final TypeCoercerFactory TYPE_COERCER_FACTORY = new DefaultTypeCoercerFactory();
  private static final VisibilityPatternParser VISIBILITY_PATTERN_PARSER =
      new VisibilityPatternParser();

  protected final TDescription description;
  protected final ProjectFilesystem filesystem;
  protected final ToolchainProvider toolchainProvider;
  protected final BuildTarget target;
  protected final TArgBuilder argBuilder;
  protected final CellPathResolver cellRoots;
  @Nullable private final HashCode rawHashCode;
  private Optional<ImmutableMap<BuildTarget, Version>> selectedVersions = Optional.empty();

  protected AbstractNodeBuilder(TDescription description, BuildTarget target) {
    this(
        description,
        target,
        new FakeProjectFilesystem(),
        new ToolchainProviderBuilder().build(),
        null);
  }

  protected AbstractNodeBuilder(
      TDescription description, BuildTarget target, ProjectFilesystem projectFilesystem) {
    this(description, target, projectFilesystem, new ToolchainProviderBuilder().build(), null);
  }

  protected AbstractNodeBuilder(
      TDescription description,
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      HashCode hashCode) {
    this(description, target, projectFilesystem, new ToolchainProviderBuilder().build(), hashCode);
  }

  protected AbstractNodeBuilder(
      TDescription description,
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      ToolchainProvider toolchainProvider,
      HashCode hashCode) {
    this.description = description;
    this.filesystem = projectFilesystem;
    this.toolchainProvider = toolchainProvider;
    this.target = target;
    this.argBuilder = makeArgBuilder(description);
    this.rawHashCode = hashCode;

    this.cellRoots = TestCellPathResolver.get(projectFilesystem);
  }

  @SuppressWarnings("unchecked")
  private TArgBuilder makeArgBuilder(TDescription description) {
    Class<? extends TArg> constructorArgType = description.getConstructorArgType();
    TArgBuilder builder;
    try {
      builder = (TArgBuilder) constructorArgType.getMethod("builder").invoke(null);
      // Set a default value for name from the target. The real coercer stack implicitly sets name,
      // but we're not going through that stack so we emulate it instead.
      // If setName is explicitly called, its value with override this one.
      builder.getClass().getMethod("setName", String.class).invoke(builder, target.getShortName());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return builder;
  }

  public final TBuildRule build(ActionGraphBuilder graphBuilder) {
    return build(graphBuilder, filesystem, TargetGraph.EMPTY);
  }

  public final TBuildRule build(ActionGraphBuilder graphBuilder, TargetGraph targetGraph) {
    return build(graphBuilder, filesystem, targetGraph);
  }

  public final TBuildRule build(ActionGraphBuilder graphBuilder, ProjectFilesystem filesystem) {
    return build(graphBuilder, filesystem, TargetGraph.EMPTY);
  }

  public final TBuildRule build(
      ActionGraphBuilder graphBuilder, ProjectFilesystem filesystem, TargetGraph targetGraph) {

    // The BuildRule determines its deps by extracting them from the rule parameters.
    BuildRuleParams params = createBuildRuleParams(graphBuilder);

    TArg builtArg = getPopulatedArg();

    QueryCache cache = new QueryCache();
    builtArg =
        QueryUtils.withDepsQuery(builtArg, target, cache, graphBuilder, cellRoots, targetGraph);
    builtArg =
        QueryUtils.withProvidedDepsQuery(
            builtArg, target, cache, graphBuilder, cellRoots, targetGraph);

    @SuppressWarnings("unchecked")
    TBuildRule rule =
        (TBuildRule)
            description.createBuildRule(
                ImmutableBuildRuleCreationContextWithTargetGraph.of(
                    targetGraph, graphBuilder, filesystem, cellRoots, toolchainProvider),
                target,
                params,
                builtArg);
    graphBuilder.addToIndex(rule);
    return rule;
  }

  public TargetNode<TArg, TDescription> build(ProjectFilesystem filesystem) {
    try {
      HashCode hash =
          rawHashCode == null
              ? Hashing.sha1().hashString(target.getFullyQualifiedName(), UTF_8)
              : rawHashCode;
      TargetNodeFactory factory = new TargetNodeFactory(TYPE_COERCER_FACTORY);
      TArg populatedArg = getPopulatedArg();
      TargetNode<TArg, TDescription> node =
          factory
              .createFromObject(
                  // This hash will do in a pinch.
                  hash,
                  description,
                  populatedArg,
                  filesystem,
                  target,
                  getDepsFromArg(populatedArg),
                  ImmutableSet.of(
                      VISIBILITY_PATTERN_PARSER.parse(
                          null, VisibilityPatternParser.VISIBILITY_PUBLIC)),
                  ImmutableSet.of(),
                  cellRoots)
              .withSelectedVersions(selectedVersions);
      return node;
    } catch (NoSuchBuildTargetException e) {
      throw new RuntimeException(e);
    }
  }

  public TargetNode<TArg, TDescription> build() {
    return build(filesystem);
  }

  public BuildRuleParams createBuildRuleParams(BuildRuleResolver resolver) {
    TargetNode<?, ?> node = build();
    return TestBuildRuleParams.create()
        .withDeclaredDeps(resolver.getAllRules(node.getDeclaredDeps()))
        .withExtraDeps(resolver.getAllRules(node.getExtraDeps()));
  }

  @SuppressWarnings("unchecked")
  public ImmutableSortedSet<BuildTarget> findImplicitDeps() {
    ImplicitDepsInferringDescription<TArg> desc =
        (ImplicitDepsInferringDescription<TArg>) description;
    ImmutableSortedSet.Builder<BuildTarget> builder = ImmutableSortedSet.naturalOrder();
    desc.findDepsForTargetFromConstructorArgs(
        target, cellRoots, getPopulatedArg(), builder, ImmutableSortedSet.naturalOrder());
    return builder.build();
  }

  public BuildTarget getTarget() {
    return target;
  }

  public AbstractNodeBuilder<TArgBuilder, TArg, TDescription, TBuildRule> setSelectedVersions(
      ImmutableMap<BuildTarget, Version> selectedVersions) {
    this.selectedVersions = Optional.of(selectedVersions);
    return this;
  }

  public TArgBuilder getArgForPopulating() {
    return argBuilder;
  }

  @SuppressWarnings("unchecked")
  protected TArg getPopulatedArg() {
    try {
      return (TArg) argBuilder.getClass().getMethod("build").invoke(argBuilder);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected final ImmutableSortedSet<BuildTarget> getDepsFromArg(TArg arg) {
    if (!(arg instanceof HasDeclaredDeps)) {
      return ImmutableSortedSet.of();
    }
    return ((HasDeclaredDeps) arg).getDeps();
  }
}
