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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTargetFactoryForTests;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuiltTargetVerifierTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  private Cells cell;

  @Before
  public void setUp() {
    cell = new TestCellBuilder().build();
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
        cell.getRootCell(),
        RuleType.of("build_rule", RuleType.Kind.BUILD),
        Paths.get("a/b/BUCK"),
        UnconfiguredBuildTargetFactoryForTests.newInstance("//a/b:c"),
        new SomeDescription(),
        RawTargetNode.copyOf(
            ForwardRelPath.EMPTY,
            "java_library",
            ImmutableList.of(),
            ImmutableList.of(),
            TwoArraysImmutableHashMap.copyOf(ImmutableMap.of("attribute", "value"))));
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
        cell.getRootCell(),
        RuleType.of("build_rule", RuleType.Kind.BUILD),
        cell.getRootCell().getRoot().resolve("a/b/BUCK"),
        UnconfiguredBuildTargetFactoryForTests.newInstance("//a/b:c"),
        new SomeDescription(),
        RawTargetNode.copyOf(
            ForwardRelPath.of("z/y/z"),
            "java_library",
            ImmutableList.of(),
            ImmutableList.of(),
            TwoArraysImmutableHashMap.copyOf(ImmutableMap.of("name", "target_name"))));
  }

  @Test
  public void testVerificationThrowsWhenUnflavoredBuildTargetsAreDifferent() {
    BuiltTargetVerifier builtTargetVerifier = new BuiltTargetVerifier();

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage(
        "Inconsistent internal state, target from data: //a/b:target_name, "
            + "expected: //a/b:c, raw data: name->target_name");

    builtTargetVerifier.verifyBuildTarget(
        cell.getRootCell(),
        RuleType.of("build_rule", RuleType.Kind.BUILD),
        cell.getRootCell().getRoot().resolve("a/b/BUCK"),
        UnconfiguredBuildTargetFactoryForTests.newInstance("//a/b:c"),
        new SomeDescription(),
        RawTargetNode.copyOf(
            ForwardRelPath.of("a/b"),
            "java_library",
            ImmutableList.of(),
            ImmutableList.of(),
            TwoArraysImmutableHashMap.copyOf(ImmutableMap.of("name", "target_name"))));
  }

  private static class SomeDescription implements DescriptionWithTargetGraph<BuildRuleArg> {

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
