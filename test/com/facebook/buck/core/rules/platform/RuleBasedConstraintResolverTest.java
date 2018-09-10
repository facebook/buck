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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.platform.ConstraintSetting;
import com.facebook.buck.core.model.platform.ConstraintValue;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;
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
        BuildTargetFactory.newInstance("//dummy:target"));
  }

  @Test
  public void testGettingConstraintValueThrowsWithWrongRuleType() {
    RuleBasedConstraintResolver ruleBasedConstraintResolver =
        new RuleBasedConstraintResolver(DummyConfigurationRule::of);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("//dummy:target is used as constraint_value, but has wrong type");

    ruleBasedConstraintResolver.getConstraintValue(
        BuildTargetFactory.newInstance("//dummy:target"));
  }

  @Test
  public void testGettingConstraintValueThrowsWithWrongConstraintSettingRuleType() {
    BuildTarget constraintSettingTarget = BuildTargetFactory.newInstance("//:setting");
    BuildTarget constraintValueTarget = BuildTargetFactory.newInstance("//:value");

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
    BuildTarget constraintSettingTarget = BuildTargetFactory.newInstance("//:setting");
    BuildTarget constraintValueTarget = BuildTargetFactory.newInstance("//:value");

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

  @BuckStyleImmutable
  @Value.Immutable(builder = false, copy = false)
  abstract static class AbstractDummyConfigurationRule implements ConfigurationRule {
    @Override
    @Value.Parameter
    public abstract BuildTarget getBuildTarget();
  }
}
