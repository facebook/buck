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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.platform.FakeMultiPlatform;
import com.facebook.buck.core.model.platform.NamedPlatform;
import com.facebook.buck.core.model.platform.impl.ConstraintBasedPlatform;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class RuleBasedMultiPlatformResolverTest {

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

    RuleBasedPlatformResolver resolver = new RuleBasedPlatformResolver(configurationRuleResolver);
    RuleBasedMultiPlatformResolver multiPlatformResolver =
        new RuleBasedMultiPlatformResolver(configurationRuleResolver, resolver);

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "//constraint:setting is used as a multiplatform, but not declared using an appropriate rule");

    multiPlatformResolver.getPlatform(constraint, DependencyStack.root());
  }

  private static ConstraintValueRule constraintValueRule(String target) {
    return new ConstraintValueRule(
        ConfigurationBuildTargetFactoryForTests.newInstance(target),
        new ConstraintSettingRule(
            ConfigurationBuildTargetFactoryForTests.newInstance(target + "-setting")));
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
    ConstraintValueRule baseConstraintValue = constraintValueRule("//constraint:base_value");
    ConstraintValueRule nestedConstraintValue1 = constraintValueRule("//constraint:nested_value1");
    ConstraintValueRule nestedConstraintValue2 = constraintValueRule("//constraint:nested_value2");
    BuildTarget constraintSetting =
        ConfigurationBuildTargetFactoryForTests.newInstance("//constraint:setting");
    ConstraintSettingRule constraintSettingRule = new ConstraintSettingRule(constraintSetting);

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
                  ImmutablePlatformRule.of(
                      basePlatformTarget,
                      "base_platform",
                      ImmutableSet.of(baseConstraintValue),
                      ImmutableSortedSet.of()));
            }
            if (buildTarget.equals(nestedPlatform1Target)) {
              return ruleClass.cast(
                  ImmutablePlatformRule.of(
                      nestedPlatform1Target,
                      "nested_platform_1",
                      ImmutableSet.of(nestedConstraintValue1),
                      ImmutableSortedSet.of()));
            }
            if (buildTarget.equals(nestedPlatform2Target)) {
              return ruleClass.cast(
                  ImmutablePlatformRule.of(
                      nestedPlatform2Target,
                      "nested_platform_2",
                      ImmutableSet.of(nestedConstraintValue2),
                      ImmutableSortedSet.of()));
            }
            if (buildTarget.equals(constraintSetting)) {
              return ruleClass.cast(constraintSettingRule);
            }
            if (buildTarget.equals(baseConstraintValue.getBuildTarget())) {
              return ruleClass.cast(baseConstraintValue);
            }
            if (buildTarget.equals(nestedConstraintValue1.getBuildTarget())) {
              return ruleClass.cast(nestedConstraintValue1);
            }
            if (buildTarget.equals(nestedConstraintValue2.getBuildTarget())) {
              return ruleClass.cast(nestedConstraintValue2);
            }
            throw new IllegalArgumentException("Invalid build target: " + buildTarget);
          }
        };

    RuleBasedPlatformResolver resolver = new RuleBasedPlatformResolver(configurationRuleResolver);
    RuleBasedMultiPlatformResolver multiPlatformResolver =
        new RuleBasedMultiPlatformResolver(configurationRuleResolver, resolver);

    FakeMultiPlatform platform =
        (FakeMultiPlatform)
            multiPlatformResolver.getPlatform(multiPlatformTarget, DependencyStack.root());

    assertEquals(multiPlatformTarget, platform.getBuildTarget());

    ConstraintBasedPlatform basedPlatform = (ConstraintBasedPlatform) platform.getBasePlatform();
    assertEquals(basePlatformTarget, basedPlatform.getBuildTarget());
    assertEquals(1, basedPlatform.getConstraintValues().size());
    assertEquals(
        baseConstraintValue.getBuildTarget(),
        Iterables.getOnlyElement(basedPlatform.getConstraintValues()).getBuildTarget());

    ImmutableList<NamedPlatform> nestedPlatforms = platform.getNestedPlatforms().asList();

    ConstraintBasedPlatform nestedPlatform1 = (ConstraintBasedPlatform) nestedPlatforms.get(0);
    assertEquals(nestedPlatform1Target, nestedPlatform1.getBuildTarget());
    assertEquals(1, nestedPlatform1.getConstraintValues().size());
    assertEquals(
        nestedConstraintValue1.getBuildTarget(),
        Iterables.getOnlyElement(nestedPlatform1.getConstraintValues()).getBuildTarget());

    ConstraintBasedPlatform nestedPlatform2 = (ConstraintBasedPlatform) nestedPlatforms.get(1);
    assertEquals(nestedPlatform2Target, nestedPlatform2.getBuildTarget());
    assertEquals(1, nestedPlatform2.getConstraintValues().size());
    assertEquals(
        nestedConstraintValue2.getBuildTarget(),
        Iterables.getOnlyElement(nestedPlatform2.getConstraintValues()).getBuildTarget());
  }
}
