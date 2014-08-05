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
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Takes in an {@link TargetNode} from the target graph and builds a {@link DescribedRule}.
 */
public class TargetNodeToBuildRuleTransformer<T extends ConstructorArg> {

  private final TargetNode<T> targetNode;

  public TargetNodeToBuildRuleTransformer(TargetNode<T> targetNode) {
    this.targetNode = Preconditions.checkNotNull(targetNode);
  }

  public BuildRule transform(BuildRuleResolver ruleResolver) throws NoSuchBuildTargetException {
    BuildRuleFactoryParams ruleFactoryParams = targetNode.getRuleFactoryParams();
    Description<T> description = targetNode.getDescription();
    ConstructorArgMarshaller inspector =
        new ConstructorArgMarshaller(targetNode.getBuildTarget().getBasePath());
    T arg = description.createUnpopulatedConstructorArg();
    try {
      inspector.populate(
          ruleResolver,
          ruleFactoryParams.getProjectFilesystem(),
          ruleFactoryParams,
          arg);
    } catch (ConstructorArgMarshalException e) {
      throw new HumanReadableException("%s: %s", targetNode.getBuildTarget(), e.getMessage());
    }

    ImmutableSortedSet.Builder<BuildRule> declaredRules =
        expandRules(ruleResolver, targetNode.getDeclaredDeps());
    ImmutableSortedSet.Builder<BuildRule> extraRules =
        expandRules(ruleResolver, targetNode.getExtraDeps());

    // The params used for the Buildable only contain the declared parameters. However, the deps of
    // the rule include not only those, but also any that were picked up through the deps declared
    // via a SourcePath.
    BuildRuleParams params = new BuildRuleParams(
        targetNode.getBuildTarget(),
        declaredRules.build(),
        extraRules.build(),
        getVisibilityPatterns(targetNode),
        ruleFactoryParams.getProjectFilesystem(),
        ruleFactoryParams.getRuleKeyBuilderFactory(),
        description.getBuildRuleType());
    BuildRule buildRule = description.createBuildRule(params, ruleResolver, arg);

    // Note that describedRule has not been added to the BuildRuleResolver yet.
    if (description instanceof FlavorableDescription && !targetNode.getBuildTarget().isFlavored()) {
      FlavorableDescription<T> flavorable = (FlavorableDescription<T>) description;
      flavorable.registerFlavors(
          arg,
          buildRule,
          ruleFactoryParams.getProjectFilesystem(),
          ruleFactoryParams.getRuleKeyBuilderFactory(),
          ruleResolver);
    }

    return buildRule;
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
