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
package com.facebook.buck.core.rules.platform;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleBasedConstraintResolverTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testGettingConstraintSettingThrowsWithWrongRuleType() {
    RuleBasedConstraintResolver ruleBasedConstraintResolver =
        new RuleBasedConstraintResolver(DummyConfigurationRule::of);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("//dummy:target is used as constraint_setting, but has wrong type");

    ruleBasedConstraintResolver.getConstraintSetting(
        UnconfiguredBuildTargetFactoryForTests.newInstance("//dummy:target"));
  }

  @Test
  public void testGettingConstraintValueThrowsWithWrongRuleType() {
    RuleBasedConstraintResolver ruleBasedConstraintResolver =
        new RuleBasedConstraintResolver(DummyConfigurationRule::of);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("//dummy:target is used as constraint_value, but has wrong type");

    ruleBasedConstraintResolver.getConstraintValue(
        UnconfiguredBuildTargetFactoryForTests.newInstance("//dummy:target"));
  }

  @Test
  public void testGettingConstraintValueThrowsWithWrongConstraintSettingRuleType() {
    UnconfiguredBuildTargetView constraintSettingTarget =
        UnconfiguredBuildTargetFactoryForTests.newInstance("//:setting");
    UnconfiguredBuildTargetView constraintValueTarget =
        UnconfiguredBuildTargetFactoryForTests.newInstance("//:value");

    RuleBasedConstraintResolver ruleBasedConstraintResolver =
        new RuleBasedConstraintResolver(
            buildTarget -> {
              if (buildTarget.equals(constraintSettingTarget)) {
                return DummyConfigurationRule.of(buildTarget);
              } else {
                return new ConstraintValueRule(
                    buildTarget, buildTarget.getShortName(), constraintSettingTarget);
              }
            });

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("//:setting is used as constraint_setting, but has wrong type");

    ruleBasedConstraintResolver.getConstraintValue(constraintValueTarget);
  }

  @Test
  public void testGettingConstraintsReturnCorrectObject() {
    UnconfiguredBuildTargetView constraintSettingTarget =
        UnconfiguredBuildTargetFactoryForTests.newInstance("//:setting");
    UnconfiguredBuildTargetView constraintValueTarget =
        UnconfiguredBuildTargetFactoryForTests.newInstance("//:value");

    RuleBasedConstraintResolver ruleBasedConstraintResolver =
        new RuleBasedConstraintResolver(
            buildTarget -> {
              if (buildTarget.equals(constraintSettingTarget)) {
                return new ConstraintSettingRule(buildTarget, buildTarget.getShortName());
              } else {
                return new ConstraintValueRule(
                    buildTarget, buildTarget.getShortName(), constraintSettingTarget);
              }
            });

    ConstraintValue constraintValue =
        ruleBasedConstraintResolver.getConstraintValue(constraintValueTarget);
    ConstraintSetting constraintSetting =
        ruleBasedConstraintResolver.getConstraintSetting(constraintSettingTarget);

    assertEquals(constraintSetting, constraintValue.getConstraintSetting());
    assertEquals(constraintSettingTarget, constraintSetting.getBuildTarget());
    assertEquals(constraintValueTarget, constraintValue.getBuildTarget());
  }
}
