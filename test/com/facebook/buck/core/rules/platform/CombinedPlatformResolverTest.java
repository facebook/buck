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

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.platform.FakeMultiPlatform;
import com.facebook.buck.core.model.platform.impl.ConstraintBasedPlatform;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CombinedPlatformResolverTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void requestingPlatformForWrongTypeThrowsException() {
    BuildTarget constraint =
        ConfigurationBuildTargetFactoryForTests.newInstance("//constraint:setting");
    ConfigurationRuleResolver configurationRuleResolver =
        new ConfigurationRuleResolver() {
          @Override
          public <R extends ConfigurationRule> R getRule(
              BuildTarget buildTarget, Class<R> ruleClass, DependencyStack dependencyStack) {
            return ruleClass.cast(new ConstraintSettingRule(constraint));
          }
        };
    RuleBasedPlatformResolver resolver =
        new RuleBasedPlatformResolver(configurationRuleResolver, new ThrowingConstraintResolver());
    RuleBasedMultiPlatformResolver multiPlatformResolver =
        new RuleBasedMultiPlatformResolver(configurationRuleResolver, resolver);
    CombinedPlatformResolver combinedPlatformResolver =
        new CombinedPlatformResolver(configurationRuleResolver, resolver, multiPlatformResolver);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "//constraint:setting is used as a target platform, but not declared using a platform rule");

    combinedPlatformResolver.getPlatform(constraint, DependencyStack.root());
  }

  @Test
  public void requestingPlatformForPlatformRuleCreatesPlatform() {
    BuildTarget multiPlatformTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:multi_platform");
    BuildTarget basePlatformTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:base_platform");
    BuildTarget nestedPlatform1Target =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:nested_platform_1");
    BuildTarget nestedPlatform2Target =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:nested_platform_2");
    BuildTarget baseConstraintValue =
        ConfigurationBuildTargetFactoryForTests.newInstance("//constraint:base_value");
    BuildTarget nestedConstraintValue1 =
        ConfigurationBuildTargetFactoryForTests.newInstance("//constraint:nested_value1");
    BuildTarget nestedConstraintValue2 =
        ConfigurationBuildTargetFactoryForTests.newInstance("//constraint:nested_value2");
    BuildTarget constraintSetting =
        ConfigurationBuildTargetFactoryForTests.newInstance("//constraint:setting");

    ConfigurationRuleResolver configurationRuleResolver =
        new ConfigurationRuleResolver() {
          @Override
          public <R extends ConfigurationRule> R getRule(
              BuildTarget buildTarget, Class<R> ruleClass, DependencyStack dependencyStack) {
            if (buildTarget.equals(multiPlatformTarget)) {
              return ruleClass.cast(
                  new FakeMultiPlatformRule(
                      multiPlatformTarget,
                      basePlatformTarget,
                      ImmutableList.of(nestedPlatform1Target, nestedPlatform2Target)));
            }
            if (buildTarget.equals(basePlatformTarget)) {
              return ruleClass.cast(
                  PlatformRule.of(
                      basePlatformTarget,
                      "base_platform",
                      ImmutableSortedSet.of(baseConstraintValue),
                      ImmutableSortedSet.of()));
            }
            if (buildTarget.equals(nestedPlatform1Target)) {
              return ruleClass.cast(
                  PlatformRule.of(
                      nestedPlatform1Target,
                      "nested_platform_1",
                      ImmutableSortedSet.of(nestedConstraintValue1),
                      ImmutableSortedSet.of()));
            }
            if (buildTarget.equals(nestedPlatform2Target)) {
              return ruleClass.cast(
                  PlatformRule.of(
                      nestedPlatform2Target,
                      "nested_platform_2",
                      ImmutableSortedSet.of(nestedConstraintValue2),
                      ImmutableSortedSet.of()));
            }
            if (buildTarget.equals(constraintSetting)) {
              return ruleClass.cast(new ConstraintSettingRule(constraintSetting));
            }
            if (buildTarget.equals(baseConstraintValue)
                || buildTarget.equals(nestedConstraintValue1)
                || buildTarget.equals(nestedConstraintValue2)) {
              return ruleClass.cast(new ConstraintValueRule(buildTarget, constraintSetting));
            }
            throw new IllegalArgumentException("Invalid build target: " + buildTarget);
          }
        };

    RuleBasedPlatformResolver resolver =
        new RuleBasedPlatformResolver(
            configurationRuleResolver, new RuleBasedConstraintResolver(configurationRuleResolver));
    RuleBasedMultiPlatformResolver multiPlatformResolver =
        new RuleBasedMultiPlatformResolver(configurationRuleResolver, resolver);
    CombinedPlatformResolver combinedPlatformResolver =
        new CombinedPlatformResolver(configurationRuleResolver, resolver, multiPlatformResolver);

    FakeMultiPlatform platform =
        (FakeMultiPlatform)
            combinedPlatformResolver.getPlatform(multiPlatformTarget, DependencyStack.root());
    assertEquals(multiPlatformTarget, platform.getBuildTarget());

    ConstraintBasedPlatform basePlatform =
        (ConstraintBasedPlatform)
            combinedPlatformResolver.getPlatform(basePlatformTarget, DependencyStack.root());
    assertEquals(basePlatformTarget, basePlatform.getBuildTarget());
  }
}
