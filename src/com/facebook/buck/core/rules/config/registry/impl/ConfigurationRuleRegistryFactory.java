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

package com.facebook.buck.core.rules.config.registry.impl;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.PlatformResolver;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.rules.config.impl.SameThreadConfigurationRuleResolver;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.platform.CachingPlatformResolver;
import com.facebook.buck.core.rules.platform.CombinedPlatformResolver;
import com.facebook.buck.core.rules.platform.DefaultTargetPlatformResolver;
import com.facebook.buck.core.rules.platform.RuleBasedConstraintResolver;
import com.facebook.buck.core.rules.platform.RuleBasedMultiPlatformResolver;
import com.facebook.buck.core.rules.platform.RuleBasedPlatformResolver;
import com.facebook.buck.core.rules.platform.RuleBasedTargetPlatformResolver;
import java.util.function.BiFunction;

/** Creates {@link ConfigurationRuleRegistry}. */
public class ConfigurationRuleRegistryFactory {
  private ConfigurationRuleRegistryFactory() {}

  public static ConfigurationRuleRegistry createRegistry(TargetGraph targetGraph) {
    return createRegistry((target, dependencyStack) -> targetGraph.get(target, dependencyStack));
  }

  public static ConfigurationRuleRegistry createRegistry(
      BiFunction<BuildTarget, DependencyStack, TargetNode<?>> targetNodeSupplier) {

    ConfigurationRuleResolver configurationRuleResolver =
        new SameThreadConfigurationRuleResolver(targetNodeSupplier);

    ConstraintResolver constraintResolver =
        new RuleBasedConstraintResolver(configurationRuleResolver);

    RuleBasedPlatformResolver ruleBasedPlatformResolver =
        new RuleBasedPlatformResolver(configurationRuleResolver);
    PlatformResolver platformResolver =
        new CachingPlatformResolver(
            new CombinedPlatformResolver(
                configurationRuleResolver,
                ruleBasedPlatformResolver,
                new RuleBasedMultiPlatformResolver(
                    configurationRuleResolver, ruleBasedPlatformResolver)));
    TargetPlatformResolver targetPlatformResolver =
        new DefaultTargetPlatformResolver(new RuleBasedTargetPlatformResolver(platformResolver));

    return ConfigurationRuleRegistry.of(
        configurationRuleResolver, constraintResolver, targetPlatformResolver);
  }
}
