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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleBasedConstraintResolverTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testGettingConstraintsReturnCorrectObject() {
    BuildTarget constraintSettingTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//:setting");
    ConstraintSettingRule constraintSettingRule =
        new ConstraintSettingRule(constraintSettingTarget);
    BuildTarget constraintValueTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//:value");

    RuleBasedConstraintResolver ruleBasedConstraintResolver =
        new RuleBasedConstraintResolver(
            new ConfigurationRuleResolver() {
              @Override
              public <R extends ConfigurationRule> R getRule(
                  BuildTarget buildTarget, Class<R> ruleClass, DependencyStack dependencyStack) {
                if (buildTarget.equals(constraintSettingTarget)) {
                  return ruleClass.cast(new ConstraintSettingRule(buildTarget));
                } else {
                  return ruleClass.cast(
                      new ConstraintValueRule(buildTarget, constraintSettingRule));
                }
              }
            });

    ConstraintValue constraintValue =
        ruleBasedConstraintResolver.getConstraintValue(
            constraintValueTarget, DependencyStack.root());
    ConstraintSetting constraintSetting =
        ruleBasedConstraintResolver.getConstraintSetting(
            constraintSettingTarget, DependencyStack.root());

    assertEquals(constraintSetting, constraintValue.getConstraintSetting());
    assertEquals(constraintSettingTarget, constraintSetting.getBuildTarget());
    assertEquals(constraintValueTarget, constraintValue.getBuildTarget());
  }
}
