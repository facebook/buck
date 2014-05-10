/*
 * Copyright 2014-present Facebook, Inc.
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

/**
 * Takes in an {@link TargetNode} from the target graph and builds a {@link DescribedRule}.
 */
public class TargetNodeToBuildRuleTransformer<T extends ConstructorArg> {

  private final TargetNode<T> targetNode;

  public TargetNodeToBuildRuleTransformer(TargetNode<T> targetNode) {
    this.targetNode = Preconditions.checkNotNull(targetNode);
  }

  public DescribedRule transform(BuildRuleResolver ruleResolver) throws NoSuchBuildTargetException {
    ImmutableSortedSet.Builder<BuildRule> declaredRules =
        expandRules(ruleResolver, targetNode.getDeclaredDeps());
    ImmutableSortedSet.Builder<BuildRule> extraRules =
        expandRules(ruleResolver, targetNode.getExtraDeps());

    BuildRuleFactoryParams ruleFactoryParams = targetNode.getRuleFactoryParams();
    Description<T> description = targetNode.getDescription();
    ConstructorArgMarshaller inspector =
        new ConstructorArgMarshaller(Paths.get(targetNode.getBuildTarget().getBasePath()));
    T arg = description.createUnpopulatedConstructorArg();
    inspector.populate(
        ruleResolver,
        ruleFactoryParams.getProjectFilesystem(),
        ruleFactoryParams,
        arg);

    // The params used for the Buildable only contain the declared parameters. However, the deps of
    // the rule include not only those, but also any that were picked up through the deps declared
    // via a SourcePath.
    BuildRuleParams params = new BuildRuleParams(
        targetNode.getBuildTarget(),
        declaredRules.build(),
        getVisibilityPatterns(targetNode),
        ruleFactoryParams.getProjectFilesystem(),
        ruleFactoryParams.getRuleKeyBuilderFactory());
    Buildable buildable = description.createBuildable(params, arg);

    // Check to see if the buildable would like a chance to monkey around with the deps
    ImmutableSortedSet<BuildRule> finalDependencySet;
    if (buildable instanceof DependencyEnhancer) {
      finalDependencySet = ((DependencyEnhancer) buildable).getEnhancedDeps(
          ruleResolver,
          declaredRules.build(),
          extraRules.build());
    } else {
      finalDependencySet = extraRules.addAll(declaredRules.build()).build();
    }

    // These are the params used by the rule, but not the buildable. Confusion will be lessened once
    // we move to a dependency graph that's not the action graph.
    params = new BuildRuleParams(
        params.getBuildTarget(),
        finalDependencySet,
        params.getVisibilityPatterns(),
        params.getProjectFilesystem(),
        params.getRuleKeyBuilderFactory());
    DescribedRule describedRule = new DescribedRule(
        description.getBuildRuleType(),
        buildable,
        params);

    // Note that describedRule has not been added to the BuildRuleResolver yet.
    if (description instanceof FlavorableDescription) {
      FlavorableDescription<T> flavorable = (FlavorableDescription<T>) description;
      flavorable.registerFlavors(
          arg,
          describedRule,
          ruleFactoryParams.getProjectFilesystem(),
          ruleFactoryParams.getRuleKeyBuilderFactory(),
          ruleResolver);
    }

    return describedRule;
  }

  private static ImmutableSortedSet.Builder<BuildRule> expandRules(
      BuildRuleResolver ruleResolver,
      Iterable<BuildTarget> targets) {
    ImmutableSortedSet.Builder<BuildRule> rules = ImmutableSortedSet.naturalOrder();

    for (BuildTarget target : targets) {
      BuildRule rule = ruleResolver.get(target);
      Preconditions.checkNotNull(rule);
      rules.add(rule);
    }

    return rules;
  }

  private static ImmutableSet<BuildTargetPattern> getVisibilityPatterns(TargetNode<?> targetNode)
      throws NoSuchBuildTargetException {
    ImmutableSet.Builder<BuildTargetPattern> builder = ImmutableSet.builder();
    BuildRuleFactoryParams params = targetNode.getRuleFactoryParams();
    for (String visibility : params.getOptionalListAttribute("visibility")) {
      builder.add(params.buildTargetPatternParser.parse(
              visibility,
              ParseContext.forVisibilityArgument()));
    }
    return builder.build();
  }
}
