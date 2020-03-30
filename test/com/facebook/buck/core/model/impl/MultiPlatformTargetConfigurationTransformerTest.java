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

package com.facebook.buck.core.model.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.ConfigurationBuildTargetFactoryForTests;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.platform.FakeMultiPlatform;
import com.facebook.buck.core.model.platform.impl.ConstraintBasedPlatform;
import com.facebook.buck.core.model.platform.impl.UnconfiguredPlatform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MultiPlatformTargetConfigurationTransformerTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void noTransformationForRegularPlatform() {
    BuildTarget platformBuildTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//:platform");
    MultiPlatformTargetConfigurationTransformer transformer =
        new MultiPlatformTargetConfigurationTransformer(
            (configuration, dependencyStack) ->
                new ConstraintBasedPlatform(platformBuildTarget, ImmutableSet.of()));

    assertFalse(
        transformer.needsTransformation(
            RuleBasedTargetConfiguration.of(platformBuildTarget), DependencyStack.root()));
  }

  @Test
  public void noTransformationForEmptyPlatform() {
    MultiPlatformTargetConfigurationTransformer transformer =
        new MultiPlatformTargetConfigurationTransformer(
            (configuration, dependencyStack) -> UnconfiguredPlatform.INSTANCE);

    assertFalse(
        transformer.needsTransformation(
            UnconfiguredTargetConfiguration.INSTANCE, DependencyStack.root()));
  }

  @Test
  public void transformationNeededForMultiPlatform() {
    BuildTarget multiPlatformBuildTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//:multi_platform");
    MultiPlatformTargetConfigurationTransformer transformer =
        new MultiPlatformTargetConfigurationTransformer(
            (configuration, dependencyStack) ->
                new FakeMultiPlatform(
                    multiPlatformBuildTarget,
                    new ConstraintBasedPlatform(
                        ConfigurationBuildTargetFactoryForTests.newInstance("//:platform"),
                        ImmutableSet.of()),
                    ImmutableList.of()));

    assertTrue(
        transformer.needsTransformation(
            RuleBasedTargetConfiguration.of(multiPlatformBuildTarget), DependencyStack.root()));
  }

  @Test
  public void transformFailsWithNonMultiPlatform() {
    BuildTarget platformBuildTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//:platform");
    MultiPlatformTargetConfigurationTransformer transformer =
        new MultiPlatformTargetConfigurationTransformer(
            (configuration, dependencyStack) ->
                new ConstraintBasedPlatform(platformBuildTarget, ImmutableSet.of()));

    thrown.expectMessage("Not multi platform: //:platform");

    transformer.transform(
        RuleBasedTargetConfiguration.of(platformBuildTarget), DependencyStack.root());
  }

  @Test
  public void transformSplitsMultiPlatform() {
    BuildTarget multiPlatformTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:multi_platform");
    BuildTarget basePlatformTarget =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:base_platform");
    BuildTarget nestedPlatform1Target =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:nested_platform_1");
    BuildTarget nestedPlatform2Target =
        ConfigurationBuildTargetFactoryForTests.newInstance("//platform:nested_platform_2");

    MultiPlatformTargetConfigurationTransformer transformer =
        new MultiPlatformTargetConfigurationTransformer(
            (configuration, dependencyStack) ->
                new FakeMultiPlatform(
                    multiPlatformTarget,
                    new ConstraintBasedPlatform(basePlatformTarget, ImmutableSet.of()),
                    ImmutableList.of(
                        new ConstraintBasedPlatform(nestedPlatform1Target, ImmutableSet.of()),
                        new ConstraintBasedPlatform(nestedPlatform2Target, ImmutableSet.of()))));

    ImmutableList<TargetConfiguration> configurations =
        transformer.transform(
            RuleBasedTargetConfiguration.of(multiPlatformTarget), DependencyStack.root());

    assertEquals(
        ImmutableList.of(
            "//platform:multi_platform",
            "//platform:nested_platform_1",
            "//platform:nested_platform_2"),
        configurations.stream()
            .map(RuleBasedTargetConfiguration.class::cast)
            .map(RuleBasedTargetConfiguration::getTargetPlatform)
            .map(BuildTarget::getFullyQualifiedName)
            .collect(ImmutableList.toImmutableList()));
  }
}
