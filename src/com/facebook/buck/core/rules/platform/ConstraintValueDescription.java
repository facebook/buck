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

package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargets;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.rules.config.ConfigurationRuleArg;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.google.common.collect.ImmutableSet;

/**
 * A description for {@code constraint_value}.
 *
 * <p>Constraint value define a constraint that can be used to define platforms.
 *
 * <p>For example:
 *
 * <pre>
 *   constraint_value(
 *      name = "constraint_value",
 *      constraint_setting = ":constraint",
 *   )
 * </pre>
 */
public class ConstraintValueDescription
    implements ConfigurationRuleDescription<ConstraintValueArg, ConstraintValueRule> {

  @Override
  public Class<ConstraintValueArg> getConstructorArgType() {
    return ConstraintValueArg.class;
  }

  @Override
  public Class<ConstraintValueRule> getRuleClass() {
    return ConstraintValueRule.class;
  }

  @Override
  public ConstraintValueRule createConfigurationRule(
      ConfigurationRuleResolver configurationRuleResolver,
      BuildTarget buildTarget,
      DependencyStack dependencyStack,
      ConstraintValueArg arg) {
    ConstraintSettingRule constraintSettingRule =
        configurationRuleResolver.getRule(
            ConfigurationBuildTargets.convert(arg.getConstraintSetting()),
            ConstraintSettingRule.class,
            dependencyStack.child(arg.getConstraintSetting()));
    return new ConstraintValueRule(buildTarget, constraintSettingRule);
  }

  @Override
  public ImmutableSet<BuildTarget> getConfigurationDeps(ConstraintValueArg arg) {
    return ImmutableSet.of(ConfigurationBuildTargets.convert(arg.getConstraintSetting()));
  }

  @RuleArg
  interface AbstractConstraintValueArg extends ConfigurationRuleArg {
    @Hint(isConfigurable = false)
    UnconfiguredBuildTarget getConstraintSetting();
  }
}
