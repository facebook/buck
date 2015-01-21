/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.SingletonBuildTargetPattern;
import com.facebook.buck.model.SubdirectoryBuildTargetPattern;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

public class TargetNodeVisibilityTest {

  private static final BuildTarget orcaTarget =
      BuildTarget.builder("//src/com/facebook/orca", "orca").build();
  private static final BuildTarget publicTarget =
      BuildTarget.builder("//src/com/facebook/for", "everyone").build();
  private static final BuildTarget nonPublicTarget1 =
      BuildTarget.builder("//src/com/facebook/something1", "nonPublic").build();
  private static final BuildTarget nonPublicTarget2 =
      BuildTarget.builder("//src/com/facebook/something2", "nonPublic").build();

  private static final ImmutableSet<BuildTargetPattern> noVisibilityPatterns = ImmutableSet.of();

  @Test
  public void testVisibilityPublic()
      throws NoSuchBuildTargetException, TargetNode.InvalidSourcePathInputException {
    TargetNode<?> publicTargetNode = createTargetNode(
        publicTarget,
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
    TargetNode<?> orcaRule = createTargetNode(
        orcaTarget,
        noVisibilityPatterns);
    assertTrue(publicTargetNode.isVisibleTo(orcaTarget));
    assertFalse(orcaRule.isVisibleTo(publicTarget));
  }

  @Test
  public void testVisibilityNonPublic()
      throws NoSuchBuildTargetException, TargetNode.InvalidSourcePathInputException {
    TargetNode<?> nonPublicTargetNode1 = createTargetNode(
        nonPublicTarget1,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    TargetNode<?> nonPublicTargetNode2 = createTargetNode(
        nonPublicTarget2,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    TargetNode<?> orcaRule = createTargetNode(
        orcaTarget,        noVisibilityPatterns);

    assertTrue(shouldBeVisibleMessage(nonPublicTargetNode1, orcaTarget),
        nonPublicTargetNode1.isVisibleTo(orcaTarget));
    assertTrue(shouldBeVisibleMessage(nonPublicTargetNode2, orcaTarget),
        nonPublicTargetNode2.isVisibleTo(orcaTarget));
    assertFalse(orcaRule.isVisibleTo(nonPublicTarget1));
    assertFalse(orcaRule.isVisibleTo(nonPublicTarget2));

    TargetNode<?> publicTargetNode = createTargetNode(
        publicTarget,
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
    assertTrue(publicTargetNode.isVisibleTo(nonPublicTarget1));
    assertFalse(nonPublicTargetNode1.isVisibleTo(publicTarget));
  }

  @Test
  public void testVisibilityNonPublicFailure()
      throws NoSuchBuildTargetException, TargetNode.InvalidSourcePathInputException {
    TargetNode<?> nonPublicTargetNode1 = createTargetNode(
        nonPublicTarget1,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    try {
      nonPublicTargetNode1.checkVisibility(publicTarget);
      fail("checkVisibility() should throw an exception");
    } catch (RuntimeException e) {
      assertEquals(
          String.format("%s depends on %s, which is not visible",
              publicTarget,
              nonPublicTargetNode1.getBuildTarget()),
          e.getMessage());
    }
  }

  @Test
  public void testVisibilityMix()
      throws NoSuchBuildTargetException, TargetNode.InvalidSourcePathInputException {
    TargetNode<?> nonPublicTargetNode1 = createTargetNode(
        nonPublicTarget1,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    TargetNode<?> nonPublicTargetNode2 = createTargetNode(
        nonPublicTarget2,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    TargetNode<?> publicTargetNode = createTargetNode(
        publicTarget,
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
    TargetNode<?> orcaRule = createTargetNode(
        orcaTarget,
        noVisibilityPatterns);

    assertTrue(shouldBeVisibleMessage(nonPublicTargetNode1, orcaTarget),
        nonPublicTargetNode1.isVisibleTo(orcaTarget));
    assertTrue(shouldBeVisibleMessage(nonPublicTargetNode2, orcaTarget),
        nonPublicTargetNode2.isVisibleTo(orcaTarget));
    assertTrue(publicTargetNode.isVisibleTo(orcaTarget));
    assertFalse(orcaRule.isVisibleTo(nonPublicTarget1));
    assertFalse(orcaRule.isVisibleTo(nonPublicTarget2));
    assertFalse(orcaRule.isVisibleTo(publicTarget));
  }

  @Test
  public void testVisibilityMixFailure()
      throws NoSuchBuildTargetException, TargetNode.InvalidSourcePathInputException {
    TargetNode<?> nonPublicTargetNode1 = createTargetNode(
        nonPublicTarget1,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    TargetNode<?> nonPublicTargetNode2 = createTargetNode(
        nonPublicTarget2,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern("//some/other:target")));
    TargetNode<?> publicTargetNode = createTargetNode(
        publicTarget,
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));

    publicTargetNode.checkVisibility(orcaTarget);
    nonPublicTargetNode1.checkVisibility(orcaTarget);

    try {
      nonPublicTargetNode2.checkVisibility(orcaTarget);
      fail("checkVisibility() should throw an exception");
    } catch (RuntimeException e) {
      assertEquals(
          String.format(
              "%s depends on %s, which is not visible",
              orcaTarget,
              nonPublicTargetNode2.getBuildTarget()),
          e.getMessage());
    }
  }

  @Test
  public void testVisibilityForDirectory()
      throws NoSuchBuildTargetException, TargetNode.InvalidSourcePathInputException {
    BuildTarget libTarget = BuildTarget.builder("//lib", "lib").build();
    BuildTarget targetInSpecifiedDirectory =
        BuildTarget.builder("//src/com/facebook", "test").build();
    BuildTarget targetUnderSpecifiedDirectory =
        BuildTarget.builder("//src/com/facebook/buck", "test").build();
    BuildTarget targetInOtherDirectory = BuildTarget.builder("//src/com/instagram", "test").build();
    BuildTarget targetInParentDirectory = BuildTarget.builder("//", "test").build();

    // Build rule that visible to targets in or under directory src/come/facebook
    TargetNode<?> directoryTargetNode = createTargetNode(
        libTarget,
        ImmutableSet.<BuildTargetPattern>of(
            new SubdirectoryBuildTargetPattern("src/com/facebook/")));
    assertTrue(directoryTargetNode.isVisibleTo(targetInSpecifiedDirectory));
    assertTrue(directoryTargetNode.isVisibleTo(targetUnderSpecifiedDirectory));
    assertFalse(directoryTargetNode.isVisibleTo(targetInOtherDirectory));
    assertFalse(directoryTargetNode.isVisibleTo(targetInParentDirectory));

    // Build rule that visible to all targets, equals to PUBLIC.
    TargetNode<?> pubicTargetNode = createTargetNode(
        libTarget,
        ImmutableSet.<BuildTargetPattern>of(new SubdirectoryBuildTargetPattern("")));
    assertTrue(pubicTargetNode.isVisibleTo(targetInSpecifiedDirectory));
    assertTrue(pubicTargetNode.isVisibleTo(targetUnderSpecifiedDirectory));
    assertTrue(pubicTargetNode.isVisibleTo(targetInOtherDirectory));
    assertTrue(pubicTargetNode.isVisibleTo(targetInParentDirectory));
  }

  private String shouldBeVisibleMessage(TargetNode<?> rule, BuildTarget target) {
    return String.format(
        "%1$s should be visible to %2$s because the visibility list of %1$s contains %2$s",
        rule.getBuildTarget(),
        target);
  }

  public static class FakeDescription implements Description<FakeDescription.FakeArg> {

    @Override
    public BuildRuleType getBuildRuleType() {
      return ImmutableBuildRuleType.of("fake_rule");
    }

    @Override
    public FakeArg createUnpopulatedConstructorArg() {
      return new FakeArg();
    }

    @Override
    public <A extends FakeArg> BuildRule createBuildRule(
        BuildRuleParams params,
        BuildRuleResolver resolver,
        A args) {
      return new FakeBuildRule(params, new SourcePathResolver(resolver));
    }

    public static class FakeArg {

    }
  }

  private static TargetNode<?> createTargetNode(
      BuildTarget buildTarget,
      ImmutableSet<BuildTargetPattern> visibilityPatterns)
      throws NoSuchBuildTargetException, TargetNode.InvalidSourcePathInputException {
    Description<FakeDescription.FakeArg> description = new FakeDescription();
    FakeDescription.FakeArg arg = description.createUnpopulatedConstructorArg();
    BuildRuleFactoryParams params =
        NonCheckingBuildRuleFactoryParams.createNonCheckingBuildRuleFactoryParams(
            new BuildTargetParser(),
            buildTarget);
    return new TargetNode<>(
        description,
        arg,
        params,
        ImmutableSet.<BuildTarget>of(),
        visibilityPatterns);
  }
}
