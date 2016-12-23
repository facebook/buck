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

import static com.facebook.buck.rules.TestCellBuilder.createCellRoots;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;

import org.junit.Test;


public class TargetNodeVisibilityTest {

  private static final ProjectFilesystem filesystem = new FakeProjectFilesystem();

  private static final BuildTarget orcaTarget =
      BuildTarget.builder(
          filesystem.getRootPath(),
          "//src/com/facebook/orca",
          "orca").build();
  private static final BuildTarget publicTarget =
      BuildTarget.builder(
          filesystem.getRootPath(),
          "//src/com/facebook/for",
          "everyone").build();
  private static final BuildTarget nonPublicTarget1 =
      BuildTarget.builder(
          filesystem.getRootPath(),
          "//src/com/facebook/something1",
          "nonPublic").build();
  private static final BuildTarget nonPublicTarget2 =
      BuildTarget.builder(
          filesystem.getRootPath(),
          "//src/com/facebook/something2",
          "nonPublic").build();
  private static final TargetGraph GRAPH = new TargetGraph(
      new MutableDirectedGraph<TargetNode<?, ?>>(),
      ImmutableMap.of(),
      ImmutableSet.of());

  private static final String VISIBLETO_PUBLIC = "PUBLIC";
  private static final String VISIBLETO_ORCA = orcaTarget.getFullyQualifiedName();
  private static final String VISIBLETO_SOME_OTHER = "//some/other:target";

  @Test
  public void testVisibilityPublic()
      throws NoSuchBuildTargetException {
    TargetNode<?, ?> publicTargetNode = createTargetNode(publicTarget, VISIBLETO_PUBLIC);
    TargetNode<?, ?> orcaRule = createTargetNode(orcaTarget);

    assertTrue(publicTargetNode.isVisibleTo(GRAPH, orcaRule));
    assertFalse(orcaRule.isVisibleTo(GRAPH, publicTargetNode));
  }

  @Test
  public void testVisibilityNonPublic()
      throws NoSuchBuildTargetException {
    TargetNode<?, ?> nonPublicTargetNode1 = createTargetNode(nonPublicTarget1, VISIBLETO_ORCA);
    TargetNode<?, ?> nonPublicTargetNode2 = createTargetNode(nonPublicTarget2, VISIBLETO_ORCA);
    TargetNode<?, ?> orcaRule = createTargetNode(orcaTarget);
    TargetNode<?, ?> publicTargetNode = createTargetNode(publicTarget, VISIBLETO_PUBLIC);

    assertTrue(shouldBeVisibleMessage(nonPublicTargetNode1, orcaTarget),
        nonPublicTargetNode1.isVisibleTo(GRAPH, orcaRule));
    assertTrue(shouldBeVisibleMessage(nonPublicTargetNode2, orcaTarget),
        nonPublicTargetNode2.isVisibleTo(GRAPH, orcaRule));
    assertFalse(orcaRule.isVisibleTo(GRAPH, nonPublicTargetNode1));
    assertFalse(orcaRule.isVisibleTo(GRAPH, nonPublicTargetNode2));

    assertTrue(publicTargetNode.isVisibleTo(GRAPH, nonPublicTargetNode1));
    assertFalse(nonPublicTargetNode1.isVisibleTo(GRAPH, publicTargetNode));
  }

  @Test
  public void testVisibilityNonPublicFailure()
      throws NoSuchBuildTargetException {
    TargetNode<?, ?> nonPublicTargetNode1 = createTargetNode(nonPublicTarget1, VISIBLETO_ORCA);
    TargetNode<?, ?> publicTargetNode = createTargetNode(publicTarget, VISIBLETO_PUBLIC);

    try {
      nonPublicTargetNode1.isVisibleToOrThrow(GRAPH, publicTargetNode);
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
      throws NoSuchBuildTargetException {
    TargetNode<?, ?> nonPublicTargetNode1 = createTargetNode(nonPublicTarget1, VISIBLETO_ORCA);
    TargetNode<?, ?> nonPublicTargetNode2 = createTargetNode(nonPublicTarget2, VISIBLETO_ORCA);
    TargetNode<?, ?> publicTargetNode = createTargetNode(publicTarget, VISIBLETO_PUBLIC);
    TargetNode<?, ?> orcaRule = createTargetNode(orcaTarget);

    assertTrue(shouldBeVisibleMessage(nonPublicTargetNode1, orcaTarget),
        nonPublicTargetNode1.isVisibleTo(GRAPH, orcaRule));
    assertTrue(shouldBeVisibleMessage(nonPublicTargetNode2, orcaTarget),
        nonPublicTargetNode2.isVisibleTo(GRAPH, orcaRule));
    assertTrue(publicTargetNode.isVisibleTo(GRAPH, orcaRule));
    assertFalse(orcaRule.isVisibleTo(GRAPH, nonPublicTargetNode1));
    assertFalse(orcaRule.isVisibleTo(GRAPH, nonPublicTargetNode2));
    assertFalse(orcaRule.isVisibleTo(GRAPH, publicTargetNode));
  }

  @Test
  public void testVisibilityMixFailure()
      throws NoSuchBuildTargetException {
    TargetNode<?, ?> nonPublicTargetNode1 = createTargetNode(nonPublicTarget1, VISIBLETO_ORCA);
    TargetNode<?, ?> nonPublicTargetNode2 =
        createTargetNode(nonPublicTarget2, VISIBLETO_SOME_OTHER);
    TargetNode<?, ?> publicTargetNode = createTargetNode(publicTarget, VISIBLETO_PUBLIC);
    TargetNode<?, ?> orcaRule = createTargetNode(orcaTarget);

    publicTargetNode.isVisibleToOrThrow(GRAPH, orcaRule);
    nonPublicTargetNode1.isVisibleToOrThrow(GRAPH, orcaRule);

    try {
      nonPublicTargetNode2.isVisibleToOrThrow(GRAPH, orcaRule);
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
      throws NoSuchBuildTargetException {
    BuildTarget libTarget = BuildTarget.builder(filesystem.getRootPath(), "//lib", "lib").build();
    TargetNode<?, ?> targetInSpecifiedDirectory = createTargetNode(
        BuildTarget.builder(filesystem.getRootPath(), "//src/com/facebook", "test").build());
    TargetNode<?, ?> targetUnderSpecifiedDirectory = createTargetNode(
        BuildTarget.builder(filesystem.getRootPath(), "//src/com/facebook/buck", "test").build());
    TargetNode<?, ?> targetInOtherDirectory = createTargetNode(
        BuildTarget.builder(filesystem.getRootPath(), "//src/com/instagram", "test").build());
    TargetNode<?, ?> targetInParentDirectory = createTargetNode(
        BuildTarget.builder(filesystem.getRootPath(), "//", "test").build());

    // Build rule that visible to targets in or under directory src/com/facebook
    TargetNode<?, ?> directoryTargetNode = createTargetNode(libTarget, "//src/com/facebook/...");
    assertTrue(directoryTargetNode.isVisibleTo(GRAPH, targetInSpecifiedDirectory));
    assertTrue(directoryTargetNode.isVisibleTo(GRAPH, targetUnderSpecifiedDirectory));
    assertFalse(directoryTargetNode.isVisibleTo(GRAPH, targetInOtherDirectory));
    assertFalse(directoryTargetNode.isVisibleTo(GRAPH, targetInParentDirectory));

    // Build rule that's visible to all targets, equals to PUBLIC.
    TargetNode<?, ?> pubicTargetNode = createTargetNode(libTarget, "//...");
    assertTrue(pubicTargetNode.isVisibleTo(GRAPH, targetInSpecifiedDirectory));
    assertTrue(pubicTargetNode.isVisibleTo(GRAPH, targetUnderSpecifiedDirectory));
    assertTrue(pubicTargetNode.isVisibleTo(GRAPH, targetInOtherDirectory));
    assertTrue(pubicTargetNode.isVisibleTo(GRAPH, targetInParentDirectory));
  }

  private String shouldBeVisibleMessage(TargetNode<?, ?> rule, BuildTarget target) {
    return String.format(
        "%1$s should be visible to %2$s because the visibility list of %1$s contains %2$s",
        rule.getBuildTarget(),
        target);
  }

  public static class FakeRuleDescription implements Description<FakeRuleDescription.FakeArg> {

    @Override
    public FakeArg createUnpopulatedConstructorArg() {
      return new FakeArg();
    }

    @Override
    public <A extends FakeArg> BuildRule createBuildRule(
        TargetGraph targetGraph,
        BuildRuleParams params,
        BuildRuleResolver resolver,
        A args) {
      return new FakeBuildRule(params, new SourcePathResolver(new SourcePathRuleFinder(resolver)));
    }

    public static class FakeArg extends AbstractDescriptionArg {

    }
  }

  private static TargetNode<?, ?> createTargetNode(
      BuildTarget buildTarget,
      String... visibilities)
      throws NoSuchBuildTargetException {
    VisibilityPatternParser parser = new VisibilityPatternParser();
    ImmutableSet.Builder<VisibilityPattern> builder = ImmutableSet.builder();
    CellPathResolver cellNames = new FakeCellPathResolver(filesystem);
    for (String visibility : visibilities) {
      builder.add(parser.parse(cellNames, visibility));
    }
    Description<FakeRuleDescription.FakeArg> description = new FakeRuleDescription();
    FakeRuleDescription.FakeArg arg = description.createUnpopulatedConstructorArg();
    return new TargetNodeFactory(new DefaultTypeCoercerFactory(ObjectMappers.newDefaultInstance()))
        .create(
            Hashing.sha1().hashString(buildTarget.getFullyQualifiedName(), UTF_8),
            description,
            arg,
            filesystem,
            buildTarget,
            ImmutableSet.of(),
            builder.build(),
            createCellRoots(filesystem));
  }
}
