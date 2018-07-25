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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.type.RuleType;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuiltTargetVerifierTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private Cell cell;

  @Before
  public void setUp() throws Exception {
    cell = new TestCellBuilder().build();
  }

  @Test
  public void testVerificationThrowsWhenUnknownFlavorsArePresent() {
    BuiltTargetVerifier builtTargetVerifier = new BuiltTargetVerifier();

    thrown.expect(UnexpectedFlavorException.class);
    thrown.expectMessage(
        "The following flavor(s) are not supported on target //a/b:c#d:\nd\n\n"
            + "Available flavors are:\n\n\n\n"
            + "- Please check the spelling of the flavor(s).\n"
            + "- If the spelling is correct, please check that the related SDK has been installed.");

    builtTargetVerifier.verifyBuildTarget(
        cell,
        RuleType.of("build_rule"),
        Paths.get("a/b/BUCK"),
        BuildTargetFactory.newInstance("//a/b:c#d"),
        new FlavoredDescription(
            new FlavorDomain<>("flavors", ImmutableMap.of(InternalFlavor.of("a"), "b"))),
        ImmutableMap.of());
  }

  @Test
  public void testVerificationThrowsWhenDescriptionNotFlavored() {
    BuiltTargetVerifier builtTargetVerifier = new BuiltTargetVerifier();

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "The following flavor(s) are not supported on target //a/b:c:\nd.\n\n"
            + "Please try to remove them when referencing this target.");

    builtTargetVerifier.verifyBuildTarget(
        cell,
        RuleType.of("build_rule"),
        Paths.get("a/b/BUCK"),
        BuildTargetFactory.newInstance("//a/b:c#d"),
        new NonFlavoredDescription(),
        ImmutableMap.of());
  }

  @Test
  public void testVerificationThrowsWhenDataIsMalformed() {
    BuiltTargetVerifier builtTargetVerifier = new BuiltTargetVerifier();

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage(
        String.format(
            "Attempting to parse build target from malformed raw data in %s: attribute->value.",
            MorePaths.pathWithPlatformSeparators("a/b/BUCK")));

    builtTargetVerifier.verifyBuildTarget(
        cell,
        RuleType.of("build_rule"),
        Paths.get("a/b/BUCK"),
        BuildTargetFactory.newInstance("//a/b:c"),
        new NonFlavoredDescription(),
        ImmutableMap.of("attribute", "value"));
  }

  @Test
  public void testVerificationThrowsWhenPathsAreDifferent() {
    BuiltTargetVerifier builtTargetVerifier = new BuiltTargetVerifier();

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage(
        String.format(
            "Raw data claims to come from [z/y/z], but we tried rooting it at [%s].",
            MorePaths.pathWithPlatformSeparators("a/b")));

    builtTargetVerifier.verifyBuildTarget(
        cell,
        RuleType.of("build_rule"),
        cell.getRoot().resolve("a/b/BUCK"),
        BuildTargetFactory.newInstance("//a/b:c"),
        new NonFlavoredDescription(),
        ImmutableMap.of("name", "target_name", "buck.base_path", "z/y/z"));
  }

  @Test
  public void testVerificationThrowsWhenUnflavoredBuildTargetsAreDifferent() {
    BuiltTargetVerifier builtTargetVerifier = new BuiltTargetVerifier();

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage(
        "Inconsistent internal state, target from data: //a/b:target_name, "
            + "expected: //a/b:c, raw data: name->target_name,buck.base_path->a/b");

    builtTargetVerifier.verifyBuildTarget(
        cell,
        RuleType.of("build_rule"),
        cell.getRoot().resolve("a/b/BUCK"),
        BuildTargetFactory.newInstance("//a/b:c"),
        new NonFlavoredDescription(),
        ImmutableMap.of("name", "target_name", "buck.base_path", "a/b"));
  }

  @Test
  public void testVerificationDoesNotFailWithValidFlavoredTargets() {
    BuiltTargetVerifier builtTargetVerifier = new BuiltTargetVerifier();

    builtTargetVerifier.verifyBuildTarget(
        cell,
        RuleType.of("build_rule"),
        cell.getRoot().resolve("a/b/BUCK"),
        BuildTargetFactory.newInstance("//a/b:c#d"),
        new FlavoredDescription(
            new FlavorDomain<>("flavors", ImmutableMap.of(InternalFlavor.of("d"), "b"))),
        ImmutableMap.of("name", "c", "buck.base_path", "a/b"));
  }

  @Test
  public void testVerificationDoesNotFailWithValidUnflavoredTargets() {
    BuiltTargetVerifier builtTargetVerifier = new BuiltTargetVerifier();

    builtTargetVerifier.verifyBuildTarget(
        cell,
        RuleType.of("build_rule"),
        cell.getRoot().resolve("a/b/BUCK"),
        BuildTargetFactory.newInstance("//a/b:c"),
        new NonFlavoredDescription(),
        ImmutableMap.of("name", "c", "buck.base_path", "a/b"));
  }

  private static class FlavoredDescription implements DescriptionWithTargetGraph<Object>, Flavored {

    private final ImmutableSet<FlavorDomain<?>> flavorDomains;

    private FlavoredDescription(FlavorDomain<?>... flavorDomains) {
      this.flavorDomains = ImmutableSet.copyOf(flavorDomains);
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        Object args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Class<Object> getConstructorArgType() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
      return Optional.of(flavorDomains);
    }
  }

  private static class NonFlavoredDescription implements DescriptionWithTargetGraph<Object> {

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        Object args) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Class<Object> getConstructorArgType() {
      throw new UnsupportedOperationException();
    }
  }
}
