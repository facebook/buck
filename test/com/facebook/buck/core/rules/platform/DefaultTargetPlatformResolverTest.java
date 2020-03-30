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
import com.facebook.buck.core.model.ConfigurationForConfigurationTargets;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.impl.ConstraintBasedPlatform;
import com.facebook.buck.core.model.platform.impl.UnconfiguredPlatform;
import com.facebook.buck.core.rules.config.ConfigurationRule;
import com.facebook.buck.core.rules.config.ConfigurationRuleResolver;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultTargetPlatformResolverTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void returnCorrectPlatformForEmptyTargetConfiguration() {
    Platform emptyTargetConfigurationPlatform = UnconfiguredPlatform.INSTANCE;
    DefaultTargetPlatformResolver targetPlatformResolver =
        new DefaultTargetPlatformResolver(
            new RuleBasedTargetPlatformResolver(
                new RuleBasedPlatformResolver(
                    new ConfigurationRuleResolver() {
                      @Override
                      public <R extends ConfigurationRule> R getRule(
                          BuildTarget buildTarget,
                          Class<R> ruleClass,
                          DependencyStack dependencyStack) {
                        return null;
                      }
                    })));

    assertEquals(
        emptyTargetConfigurationPlatform,
        targetPlatformResolver.getTargetPlatform(
            UnconfiguredTargetConfiguration.INSTANCE, DependencyStack.root()));
  }

  @Test
  public void returnCorrectPlatformForConfigurationForConfigurationTargets() {
    Platform emptyTargetConfigurationPlatform = UnconfiguredPlatform.INSTANCE;
    DefaultTargetPlatformResolver targetPlatformResolver =
        new DefaultTargetPlatformResolver(
            new RuleBasedTargetPlatformResolver(
                new RuleBasedPlatformResolver(
                    new ConfigurationRuleResolver() {
                      @Override
                      public <R extends ConfigurationRule> R getRule(
                          BuildTarget buildTarget,
                          Class<R> ruleClass,
                          DependencyStack dependencyStack) {
                        return null;
                      }
                    })));

    assertEquals(
        emptyTargetConfigurationPlatform,
        targetPlatformResolver.getTargetPlatform(
            ConfigurationForConfigurationTargets.INSTANCE, DependencyStack.root()));
  }

  @Test
  public void returnCorrectPlatformForDefaultTargetConfiguration() {
    BuildTarget platformTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:platform");
    BuildTarget constraintSetting =
        ConfigurationBuildTargetFactoryForTests.newInstance("//constraint:setting");
    ConstraintSettingRule constraintSettingRule = new ConstraintSettingRule(constraintSetting);
    ConstraintValueRule constraintValue =
        new ConstraintValueRule(
            ConfigurationBuildTargetFactoryForTests.newInstance("//constraint:value"),
            constraintSettingRule);

    ConfigurationRuleResolver configurationRuleResolver =
        new ConfigurationRuleResolver() {
          @Override
          public <R extends ConfigurationRule> R getRule(
              BuildTarget buildTarget, Class<R> ruleClass, DependencyStack dependencyStack) {
            if (buildTarget.equals(platformTarget)) {
              return ruleClass.cast(
                  ImmutablePlatformRule.of(
                      platformTarget,
                      "platform",
                      ImmutableSet.of(constraintValue),
                      ImmutableSortedSet.of()));
            }
            if (buildTarget
                .getUnconfiguredBuildTarget()
                .equals(constraintValue.getBuildTarget().getUnconfiguredBuildTarget())) {
              return ruleClass.cast(constraintValue);
            }
            if (buildTarget.equals(constraintSetting)) {
              return ruleClass.cast(constraintSettingRule);
            }
            throw new IllegalArgumentException("Invalid build target: " + buildTarget);
          }
        };

    RuleBasedTargetPlatformResolver ruleBasedTargetPlatformResolver =
        new RuleBasedTargetPlatformResolver(
            new RuleBasedPlatformResolver(configurationRuleResolver));

    DefaultTargetPlatformResolver targetPlatformResolver =
        new DefaultTargetPlatformResolver(ruleBasedTargetPlatformResolver);

    ConstraintBasedPlatform platform =
        (ConstraintBasedPlatform)
            targetPlatformResolver.getTargetPlatform(
                RuleBasedTargetConfiguration.of(platformTarget), DependencyStack.root());

    assertEquals("//platform:platform", platform.toString());
    assertEquals(1, platform.getConstraintValues().size());
    assertEquals(
        constraintValue.getBuildTarget(),
        Iterables.getOnlyElement(platform.getConstraintValues()).getBuildTarget());
  }

  @Test
  public void requestingPlatformForWrongTypeThrowsException() {
    DefaultTargetPlatformResolver targetPlatformResolver =
        new DefaultTargetPlatformResolver(
            new RuleBasedTargetPlatformResolver(
                new RuleBasedPlatformResolver(
                    new ConfigurationRuleResolver() {
                      @Override
                      public <R extends ConfigurationRule> R getRule(
                          BuildTarget buildTarget,
                          Class<R> ruleClass,
                          DependencyStack dependencyStack) {
                        return null;
                      }
                    })));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Cannot determine target platform for configuration:");

    targetPlatformResolver.getTargetPlatform(
        new TargetConfiguration() {
          @Override
          public Optional<BuildTarget> getConfigurationTarget() {
            return Optional.empty();
          }

          @Override
          public String toString() {
            return "DTPRT";
          }
        },
        DependencyStack.root());
  }
}
