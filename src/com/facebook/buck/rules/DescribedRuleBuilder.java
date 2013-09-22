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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Paths;
import java.util.Set;

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

    Buildable buildable = description.createBuildable(params, arg);

    return new DescribedRule(description.getBuildRuleType(), buildable, params);
  }
}
