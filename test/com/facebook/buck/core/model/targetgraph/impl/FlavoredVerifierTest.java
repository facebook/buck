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

package com.facebook.buck.core.model.targetgraph.impl;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class FlavoredVerifierTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testVerificationThrowsWhenUnknownFlavorsArePresent() {

    thrown.expect(UnexpectedFlavorException.class);
    thrown.expectMessage(
        "The following flavor(s) are not supported on target //a/b:c#d:\nd\n\n"
            + "Available flavors are:\n\n\n\n"
            + "- Please check the spelling of the flavor(s).\n"
            + "- If the spelling is correct, please check that the related SDK has been installed.");

    FlavoredVerifier.verify(
        UnconfiguredBuildTargetFactoryForTests.newInstance("//a/b:c#d"),
        RuleType.of("build_rule", RuleType.Kind.BUILD),
        new FlavoredDescription(
            new FlavorDomain<>("flavors", ImmutableMap.of(InternalFlavor.of("a"), "b"))));
  }

  @Test
  public void testVerificationThrowsWhenDescriptionNotFlavored() {

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "The following flavor(s) are not supported on target //a/b:c:\nd.\n\n"
            + "Please try to remove them when referencing this target.");

    FlavoredVerifier.verify(
        UnconfiguredBuildTargetFactoryForTests.newInstance("//a/b:c#d"),
        RuleType.of("build_rule", RuleType.Kind.BUILD),
        new NonFlavoredDescription());
  }

  @Test
  public void testVerificationDoesNotFailWithValidFlavoredTargets() {

    FlavoredVerifier.verify(
        UnconfiguredBuildTargetFactoryForTests.newInstance("//a/b:c#d"),
        RuleType.of("build_rule", RuleType.Kind.BUILD),
        new FlavoredDescription(
            new FlavorDomain<>("flavors", ImmutableMap.of(InternalFlavor.of("d"), "b"))));
  }

  @Test
  public void testVerificationDoesNotFailWithValidUnflavoredTargets() {

    FlavoredVerifier.verify(
        UnconfiguredBuildTargetFactoryForTests.newInstance("//a/b:c"),
        RuleType.of("build_rule", RuleType.Kind.BUILD),
        new NonFlavoredDescription());
  }

  private static class FlavoredDescription
      implements DescriptionWithTargetGraph<BuildRuleArg>, Flavored {

    private final ImmutableSet<FlavorDomain<?>> flavorDomains;

    private FlavoredDescription(FlavorDomain<?>... flavorDomains) {
      this.flavorDomains = ImmutableSet.copyOf(flavorDomains);
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        BuildRuleArg args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Class<BuildRuleArg> getConstructorArgType() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
        TargetConfiguration toolchainTargetConfiguration) {
      return Optional.of(flavorDomains);
    }
  }

  private static class NonFlavoredDescription implements DescriptionWithTargetGraph<BuildRuleArg> {

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        BuildRuleArg args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Class<BuildRuleArg> getConstructorArgType() {
      throw new UnsupportedOperationException();
    }
  }
}
