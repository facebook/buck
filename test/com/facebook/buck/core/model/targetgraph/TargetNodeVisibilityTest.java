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

package com.facebook.buck.core.model.targetgraph;

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.visibility.VisibilityPatternParser;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import org.immutables.value.Value;
import org.junit.Test;

public class TargetNodeVisibilityTest {

  private static final ProjectFilesystem filesystem = new FakeProjectFilesystem();

  private static final BuildTarget orcaTarget =
      BuildTargetFactory.newInstance(filesystem.getRootPath(), "//src/com/facebook/orca", "orca");
  private static final BuildTarget publicTarget =
      BuildTargetFactory.newInstance(
          filesystem.getRootPath(), "//src/com/facebook/for", "everyone");
  private static final BuildTarget nonPublicTarget1 =
      BuildTargetFactory.newInstance(
          filesystem.getRootPath(), "//src/com/facebook/something1", "nonPublic");
  private static final BuildTarget nonPublicTarget2 =
      BuildTargetFactory.newInstance(
          filesystem.getRootPath(), "//src/com/facebook/something2", "nonPublic");

  private static final ImmutableList<String> DEFAULT = ImmutableList.of();
  private static final ImmutableList<String> PUBLIC = ImmutableList.of("PUBLIC");
  private static final ImmutableList<String> ORCA =
      ImmutableList.of(orcaTarget.getFullyQualifiedName());
  private static final ImmutableList<String> SOME_OTHER = ImmutableList.of("//some/other:target");

  @Test
  public void testVisibilityPublic() throws NoSuchBuildTargetException {
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, PUBLIC);
    TargetNode<?> orcaRule = createTargetNode(orcaTarget, DEFAULT);

    assertTrue(publicTargetNode.isVisibleTo(orcaRule));
    assertFalse(orcaRule.isVisibleTo(publicTargetNode));
  }

  @Test
  public void testVisibilityNonPublic() throws NoSuchBuildTargetException {
    TargetNode<?> nonPublicTargetNode1 = createTargetNode(nonPublicTarget1, ORCA);
    TargetNode<?> nonPublicTargetNode2 = createTargetNode(nonPublicTarget2, ORCA);
    TargetNode<?> orcaRule = createTargetNode(orcaTarget, DEFAULT);
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, PUBLIC);

    assertTrue(
        shouldBeVisibleMessage(nonPublicTargetNode1, orcaTarget),
        nonPublicTargetNode1.isVisibleTo(orcaRule));
    assertTrue(
        shouldBeVisibleMessage(nonPublicTargetNode2, orcaTarget),
        nonPublicTargetNode2.isVisibleTo(orcaRule));
    assertFalse(orcaRule.isVisibleTo(nonPublicTargetNode1));
    assertFalse(orcaRule.isVisibleTo(nonPublicTargetNode2));

    assertTrue(publicTargetNode.isVisibleTo(nonPublicTargetNode1));
    assertFalse(nonPublicTargetNode1.isVisibleTo(publicTargetNode));
  }

  @Test
  public void testVisibilityNonPublicFailure() throws NoSuchBuildTargetException {
    TargetNode<?> nonPublicTargetNode1 = createTargetNode(nonPublicTarget1, ORCA);
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, PUBLIC);

    try {
      nonPublicTargetNode1.isVisibleToOrThrow(publicTargetNode);
      fail("checkVisibility() should throw an exception");
    } catch (RuntimeException e) {
      assertEquals(
          String.format(
              "%s depends on %s, which is not visible",
              publicTarget, nonPublicTargetNode1.getBuildTarget()),
          e.getMessage());
    }
  }

  @Test
  public void testVisibilityMix() throws NoSuchBuildTargetException {
    TargetNode<?> nonPublicTargetNode1 = createTargetNode(nonPublicTarget1, ORCA);
    TargetNode<?> nonPublicTargetNode2 = createTargetNode(nonPublicTarget2, ORCA);
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, PUBLIC);
    TargetNode<?> orcaRule = createTargetNode(orcaTarget, DEFAULT);

    assertTrue(
        shouldBeVisibleMessage(nonPublicTargetNode1, orcaTarget),
        nonPublicTargetNode1.isVisibleTo(orcaRule));
    assertTrue(
        shouldBeVisibleMessage(nonPublicTargetNode2, orcaTarget),
        nonPublicTargetNode2.isVisibleTo(orcaRule));
    assertTrue(publicTargetNode.isVisibleTo(orcaRule));
    assertFalse(orcaRule.isVisibleTo(nonPublicTargetNode1));
    assertFalse(orcaRule.isVisibleTo(nonPublicTargetNode2));
    assertFalse(orcaRule.isVisibleTo(publicTargetNode));
  }

  @Test
  public void testVisibilityMixFailure() throws NoSuchBuildTargetException {
    TargetNode<?> nonPublicTargetNode1 = createTargetNode(nonPublicTarget1, ORCA);
    TargetNode<?> nonPublicTargetNode2 = createTargetNode(nonPublicTarget2, SOME_OTHER);
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, PUBLIC);
    TargetNode<?> orcaRule = createTargetNode(orcaTarget, DEFAULT);

    publicTargetNode.isVisibleToOrThrow(orcaRule);
    nonPublicTargetNode1.isVisibleToOrThrow(orcaRule);

    try {
      nonPublicTargetNode2.isVisibleToOrThrow(orcaRule);
      fail("checkVisibility() should throw an exception");
    } catch (RuntimeException e) {
      assertEquals(
          String.format(
              "%s depends on %s, which is not visible",
              orcaTarget, nonPublicTargetNode2.getBuildTarget()),
          e.getMessage());
    }
  }

  @Test
  public void testVisibilityForDirectory() throws NoSuchBuildTargetException {
    BuildTarget libTarget =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//lib", "lib");
    TargetNode<?> targetInSpecifiedDirectory =
        createTargetNode(
            BuildTargetFactory.newInstance(filesystem.getRootPath(), "//src/com/facebook", "test"),
            DEFAULT);
    TargetNode<?> targetUnderSpecifiedDirectory =
        createTargetNode(
            BuildTargetFactory.newInstance(
                filesystem.getRootPath(), "//src/com/facebook/buck", "test"),
            DEFAULT);
    TargetNode<?> targetInOtherDirectory =
        createTargetNode(
            BuildTargetFactory.newInstance(filesystem.getRootPath(), "//src/com/instagram", "test"),
            DEFAULT);
    TargetNode<?> targetInParentDirectory =
        createTargetNode(
            BuildTargetFactory.newInstance(filesystem.getRootPath(), "//", "test"), DEFAULT);

    // Build rule that visible to targets in or under directory src/com/facebook
    TargetNode<?> directoryTargetNode =
        createTargetNode(libTarget, ImmutableList.of("//src/com/facebook/..."));
    assertTrue(directoryTargetNode.isVisibleTo(targetInSpecifiedDirectory));
    assertTrue(directoryTargetNode.isVisibleTo(targetUnderSpecifiedDirectory));
    assertFalse(directoryTargetNode.isVisibleTo(targetInOtherDirectory));
    assertFalse(directoryTargetNode.isVisibleTo(targetInParentDirectory));

    // Build rule that's visible to all targets, equals to PUBLIC.
    TargetNode<?> pubicTargetNode = createTargetNode(libTarget, ImmutableList.of("//..."));
    assertTrue(pubicTargetNode.isVisibleTo(targetInSpecifiedDirectory));
    assertTrue(pubicTargetNode.isVisibleTo(targetUnderSpecifiedDirectory));
    assertTrue(pubicTargetNode.isVisibleTo(targetInOtherDirectory));
    assertTrue(pubicTargetNode.isVisibleTo(targetInParentDirectory));
  }

  @Test
  public void testOnlyWithinViewIsVisible() throws NoSuchBuildTargetException {
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, PUBLIC, ORCA);
    TargetNode<?> publicOrcaRule = createTargetNode(orcaTarget, PUBLIC, SOME_OTHER);

    assertTrue(publicOrcaRule.isVisibleTo(publicTargetNode));
    assertFalse(publicTargetNode.isVisibleTo(publicOrcaRule));
  }

  private String shouldBeVisibleMessage(TargetNode<?> rule, BuildTarget target) {
    return String.format(
        "%1$s should be visible to %2$s because the visibility list of %1$s contains %2$s",
        rule.getBuildTarget(), target);
  }

  public static class FakeRuleDescription
      implements DescriptionWithTargetGraph<FakeRuleDescriptionArg> {

    @Override
    public Class<FakeRuleDescriptionArg> getConstructorArgType() {
      return FakeRuleDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        FakeRuleDescriptionArg args) {
      return new FakeBuildRule(buildTarget, context.getProjectFilesystem(), params);
    }

    @BuckStyleImmutable
    @Value.Immutable
    interface AbstractFakeRuleDescriptionArg extends CommonDescriptionArg {}
  }

  private static TargetNode<?> createTargetNode(
      BuildTarget buildTarget, ImmutableList<String> visibilities)
      throws NoSuchBuildTargetException {
    return createTargetNode(buildTarget, visibilities, DEFAULT);
  }

  private static TargetNode<?> createTargetNode(
      BuildTarget buildTarget, ImmutableList<String> visibilities, ImmutableList<String> withinView)
      throws NoSuchBuildTargetException {
    VisibilityPatternParser parser = new VisibilityPatternParser();
    CellPathResolver cellNames = TestCellPathResolver.get(filesystem);
    FakeRuleDescription description = new FakeRuleDescription();
    FakeRuleDescriptionArg arg =
        FakeRuleDescriptionArg.builder().setName(buildTarget.getShortName()).build();
    return new TargetNodeFactory(new DefaultTypeCoercerFactory())
        .createFromObject(
            Hashing.sha1().hashString(buildTarget.getFullyQualifiedName(), UTF_8),
            description,
            arg,
            filesystem,
            buildTarget,
            ImmutableSet.of(),
            visibilities
                .stream()
                .map(s -> parser.parse(cellNames, s))
                .collect(ImmutableSet.toImmutableSet()),
            withinView
                .stream()
                .map(s -> parser.parse(cellNames, s))
                .collect(ImmutableSet.toImmutableSet()),
            createCellRoots(filesystem));
  }
}
