/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableDefaultTargetConfiguration;
import com.facebook.buck.core.model.platform.ConstraintBasedPlatform;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleBasedTargetPlatformResolverTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void requestingPlatformForWrongTypeThrowsException() {

    UnconfiguredBuildTargetView constraint =
        UnconfiguredBuildTargetFactoryForTests.newInstance("//constraint:setting");
    RuleBasedTargetPlatformResolver resolver =
        new RuleBasedTargetPlatformResolver(
            target -> new ConstraintSettingRule(constraint, "setting"),
            new ThrowingConstraintResolver());

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "//constraint:setting is used as a target platform, but not declared using `platform` rule");

    resolver.getTargetPlatform(ImmutableDefaultTargetConfiguration.of(constraint));
  }

  @Test
  public void requestingPlatformForPlatformRuleCreatesPlatform() {

    UnconfiguredBuildTargetView platformTarget =
        UnconfiguredBuildTargetFactoryForTests.newInstance("//platform:platform");
    UnconfiguredBuildTargetView constraintValue =
        UnconfiguredBuildTargetFactoryForTests.newInstance("//constraint:value");
    UnconfiguredBuildTargetView constraintSetting =
        UnconfiguredBuildTargetFactoryForTests.newInstance("//constraint:setting");

    ConfigurationRuleResolver configurationRuleResolver =
        buildTarget -> {
          if (buildTarget.equals(platformTarget)) {
            return PlatformRule.of(platformTarget, "platform", ImmutableList.of(constraintValue));
          }
          if (buildTarget.equals(constraintValue)) {
            return new ConstraintValueRule(constraintValue, "value", constraintSetting);
          }
          if (buildTarget.equals(constraintSetting)) {
            return new ConstraintSettingRule(constraintValue, "value");
          }
          throw new IllegalArgumentException("Invalid build target: " + buildTarget);
        };

    RuleBasedTargetPlatformResolver resolver =
        new RuleBasedTargetPlatformResolver(
            configurationRuleResolver, new RuleBasedConstraintResolver(configurationRuleResolver));

    ConstraintBasedPlatform platform =
        (ConstraintBasedPlatform)
            resolver.getTargetPlatform(ImmutableDefaultTargetConfiguration.of(platformTarget));

    assertEquals("//platform:platform", platform.toString());
    assertEquals(1, platform.getConstraintValues().size());
    assertEquals(
        constraintValue, Iterables.getOnlyElement(platform.getConstraintValues()).getBuildTarget());
  }
}
