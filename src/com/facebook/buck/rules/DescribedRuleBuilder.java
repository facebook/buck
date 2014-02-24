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
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Set;

public class DescribedRuleBuilder<T extends ConstructorArg>
    implements BuildRuleBuilder<DescribedRule> {

  private final Description<T> description;
  private final BuildTarget target;
  private final BuildRuleFactoryParams ruleFactoryParams;
  private final ImmutableSortedSet<BuildTarget> deps;
  private final ImmutableSet<BuildTargetPattern> visibilities;

  public DescribedRuleBuilder(Description<T> description, final BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException {
    this.description = Preconditions.checkNotNull(description);
    this.ruleFactoryParams = Preconditions.checkNotNull(params);
    this.target = params.target;

    final ImmutableSortedSet.Builder<BuildTarget> allDeps = ImmutableSortedSet.naturalOrder();
    for (String rawDep : params.getOptionalListAttribute("deps")) {
      allDeps.add(params.resolveBuildTarget(rawDep));
    }

    // Scan the input to find possible BuildTargets, necessary for loading dependent rules.
    TypeCoercerFactory typeCoercerFactory = new TypeCoercerFactory();
    T arg = description.createUnpopulatedConstructorArg();
    for (Field field : arg.getClass().getFields()) {
      ParamInfo info = new ParamInfo(typeCoercerFactory, Paths.get(target.getBasePath()), field);
      if (info.hasElementTypes(BuildRule.class, SourcePath.class)) {
        detectBuildTargetsForParameter(allDeps, info, params);
      }
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

    ConstructorArgMarshaller inspector =
        new ConstructorArgMarshaller(Paths.get(target.getBasePath()));
    T arg = description.createUnpopulatedConstructorArg();
    inspector.populate(ruleResolver, ruleFactoryParams, arg);

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

  /**
   * Converts {@code value} from a constructor arg to a {@link BuildTarget} iff the value might be a
   * {@link SourcePath} or a {@link BuildTarget}. {@code value} will be deemed to represent a
   * SourcePath if it is:
   * <ul>
   *   <li>Actually a SourcePath.
   *   <li>An {@link Optional} of SourcePath.
   *   <li>A {@link Collection} of SourcePaths.
   *   <li>An Optional of a Collection of SourcePaths.
   * </ul>
   * @param builder The current builder to use.
   */
  private void detectBuildTargetsForParameter(
      final ImmutableSortedSet.Builder<BuildTarget> builder,
      ParamInfo info,
      final BuildRuleFactoryParams params) throws NoSuchBuildTargetException {
    // We'll make no test for optionality here. Let's assume it's done elsewhere.

    try {
      info.traverse(new ParamInfo.Traversal() {
        @Override
        public void traverse(Object object) {
          if (object instanceof String) {
            try {
              addTargetIfPresent(builder, params, (String) object);
            } catch (NoSuchBuildTargetException e) {
              throw new RuntimeException(e);
            }
          }
        }
      }, params.getNullableRawAttribute(info.getName()));
    } catch (RuntimeException e) {
      if (e.getCause() instanceof NoSuchBuildTargetException) {
        throw (NoSuchBuildTargetException) e.getCause();
      }
    }
  }

  private void addTargetIfPresent(
      ImmutableSortedSet.Builder<BuildTarget> builder,
      BuildRuleFactoryParams params, String param) throws NoSuchBuildTargetException {
    if (isPossiblyATarget(param)) {
      builder.add(params.resolveBuildTarget(param));
    }
  }

  private boolean isPossiblyATarget(String param) {
    return param.charAt(0) == ':' || param.startsWith("//");
  }
}
