/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.parser.ParseContext;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;

import javax.annotation.Nullable;

public class DescribedRuleBuilder<T> implements BuildRuleBuilder<DescribedRule> {

  private final Description<T> description;
  private final BuildTarget target;
  private final BuildRuleFactoryParams ruleFactoryParams;
  private final ImmutableSortedSet<BuildTarget> deps;
  private final ImmutableSet<BuildTargetPattern> visibilities;

  public DescribedRuleBuilder(Description<T> description, BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException {
    this.description = Preconditions.checkNotNull(description);
    this.ruleFactoryParams = Preconditions.checkNotNull(params);
    this.target = params.target;

    ImmutableSortedSet.Builder<BuildTarget> allDeps = ImmutableSortedSet.naturalOrder();
    for (String rawDep : params.getOptionalListAttribute("deps")) {
      allDeps.add(params.resolveBuildTarget(rawDep));
    }
    this.deps = allDeps.build();

    ImmutableSet.Builder<BuildTargetPattern> allVisibilities = ImmutableSet.builder();
    for (String rawVis : params.getOptionalListAttribute("visibility")) {
      allVisibilities.add(params.buildTargetPatternParser.parse(
          rawVis, ParseContext.forVisibilityArgument()));
    }
    this.visibilities = allVisibilities.build();
  }

  @Override
  public BuildTarget getBuildTarget() {
    return target;
  }

  @Override
  public Set<BuildTarget> getDeps() {
    return deps;
  }

  @Override
  public ImmutableSet<BuildTargetPattern> getVisibilityPatterns() {
    return visibilities;
  }

  @Override
  public DescribedRule build(BuildRuleResolver ruleResolver) {
    ImmutableSortedSet.Builder<BuildRule> rules = ImmutableSortedSet.naturalOrder();
    for (BuildTarget dep : deps) {
      rules.add(ruleResolver.get(dep));
    }

    BuildRuleParams params = new BuildRuleParams(
        target,
        rules.build(),
        getVisibilityPatterns(),
        ruleFactoryParams.getPathRelativizer(),
        ruleFactoryParams.getRuleKeyBuilderFactory());

    ConstructorArgMarshaller inspector = new ConstructorArgMarshaller(Paths.get(target.getBasePath()));
    T arg = description.createUnpopulatedConstructorArg();
    inspector.populate(ruleResolver, ruleFactoryParams, arg);

    // Check the populated args for SourcePaths and add as deps.
    ImmutableSortedSet<BuildRule> depsFromSourcePaths = findSourcePathDeps(ruleResolver, arg);
    if (depsFromSourcePaths != null) {
      ImmutableSortedSet<BuildRule> completeDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
          .addAll(params.getDeps())
          .addAll(depsFromSourcePaths)
          .build();

      params = new BuildRuleParams(
          params.getBuildTarget(),
          completeDeps,
          params.getVisibilityPatterns(),
          params.getPathRelativizer(),
          params.getRuleKeyBuilderFactory());
    }

    Buildable buildable = description.createBuildable(params, arg);

    // Check for graph enhancement.
    ImmutableSortedSet<BuildRule> enhancedDeps = buildable.getEnhancedDeps(ruleResolver);
    if (enhancedDeps != null) {
      params = new BuildRuleParams(
          params.getBuildTarget(),
          enhancedDeps,
          params.getVisibilityPatterns(),
          params.getPathRelativizer(),
          params.getRuleKeyBuilderFactory());
    }

    return new DescribedRule(description.getBuildRuleType(), buildable, params);
  }

  @Nullable
  private ImmutableSortedSet<BuildRule> findSourcePathDeps(BuildRuleResolver resolver, T arg) {
    ImmutableSortedSet.Builder<BuildRule> builder = null;

    for (Field field : arg.getClass().getFields()) {
      try {
        Object value = field.get(arg);
        builder = addBuildRule(resolver, builder, value);
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }

    return builder == null ? null : builder.build();
  }

  /**
   * Converts {@code value} from a constructor arg to a {@link BuildRule} iff the value might be a
   * {@link SourcePath}. {@code value} will be deemed to represent a SourcePath if it is:
   * <ul>
   *   <li>Actually a SourcePath.
   *   <li>An {@link Optional} of SourcePath.
   *   <li>A {@link Collection} of SourcePaths.
   *   <li>An Optional of a Collection of SourcePaths.
   * </ul>
   * @param resolver For resolving build targets to rules.
   * @param builder The current builder to use.
   * @param value The value of a field in a {@link Description#createUnpopulatedConstructorArg()}.
   * @return A builder if any SourcePaths were converted to a BuildRule.
   */
  private ImmutableSortedSet.Builder<BuildRule> addBuildRule(
      BuildRuleResolver resolver,
      @Nullable ImmutableSortedSet.Builder<BuildRule> builder,
      Object value) {

    if (value instanceof Optional) {
      value = ((Optional) value).orNull();
    }

    if (value instanceof BuildTargetSourcePath) {
      value = ((BuildTargetSourcePath) value).getTarget();
    } else if (value instanceof Collection) {
      for (Object item : ((Collection<?>) value)) {
        builder = addBuildRule(resolver, builder, item);
      }
    }

    if (value instanceof BuildTarget) {
      BuildRule rule = resolver.get((BuildTarget) value);
      if (builder == null) {
        builder = ImmutableSortedSet.naturalOrder();
      }
      builder.add(rule);
    }

    return builder;
  }
}
