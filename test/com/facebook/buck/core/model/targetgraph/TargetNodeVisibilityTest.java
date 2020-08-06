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

package com.facebook.buck.core.model.targetgraph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.cell.nameresolver.SingleRootCellNameResolverProvider;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.visibility.VisibilityDefiningPath;
import com.facebook.buck.rules.visibility.VisibilityError;
import com.facebook.buck.rules.visibility.parser.VisibilityPatternParser;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class TargetNodeVisibilityTest {

  private static final ProjectFilesystem filesystem = new FakeProjectFilesystem();

  private static final BuildTarget orcaTarget =
      BuildTargetFactory.newInstance("//src/com/facebook/orca", "orca");
  private static final Path orcaBuildFile = Paths.get("src/com/facebook/orca/BUCK");
  private static final BuildTarget publicTarget =
      BuildTargetFactory.newInstance("//src/com/facebook/for", "everyone");
  private static final Path publicBuildFile = Paths.get("src/com/facebook/for/BUCK");
  private static final BuildTarget nonPublicTarget1 =
      BuildTargetFactory.newInstance("//src/com/facebook/something1", "nonPublic");
  private static final BuildTarget nonPublicTarget2 =
      BuildTargetFactory.newInstance("//src/com/facebook/something2", "nonPublic");
  private static final BuildTarget nonPublicTarget3 =
      BuildTargetFactory.newInstance("//src/com/facebook/something1", "nonPublic3");
  private static final Path nonPublicBuildFile = Paths.get("src/com/facebook/BUCK");

  private static final ImmutableList<String> DEFAULT = ImmutableList.of();
  private static final ImmutableList<String> PUBLIC = ImmutableList.of("PUBLIC");
  private static final ImmutableList<String> ORCA =
      ImmutableList.of(orcaTarget.getFullyQualifiedName());
  private static final ImmutableList<String> SOME_OTHER = ImmutableList.of("//some/other:target");

  @Test
  public void testVisibilityPublic() throws NoSuchBuildTargetException {
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, publicBuildFile, PUBLIC);
    TargetNode<?> orcaRule = createTargetNode(orcaTarget, orcaBuildFile, DEFAULT);

    assertTrue(isVisible(publicTargetNode, orcaRule));
    assertFalse(isVisible(orcaRule, publicTargetNode));
  }

  @Test
  public void testVisibilityNonPublic() throws NoSuchBuildTargetException {
    TargetNode<?> nonPublicTargetNode1 =
        createTargetNode(nonPublicTarget1, nonPublicBuildFile, ORCA);
    TargetNode<?> nonPublicTargetNode2 =
        createTargetNode(nonPublicTarget2, nonPublicBuildFile, ORCA);
    TargetNode<?> orcaRule = createTargetNode(orcaTarget, orcaBuildFile, DEFAULT);
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, publicBuildFile, PUBLIC);

    assertTrue(
        shouldBeVisibleMessage(nonPublicTargetNode1, orcaTarget),
        isVisible(nonPublicTargetNode1, orcaRule));
    assertTrue(
        shouldBeVisibleMessage(nonPublicTargetNode2, orcaTarget),
        isVisible(nonPublicTargetNode2, orcaRule));
    assertFalse(isVisible(orcaRule, nonPublicTargetNode1));
    assertFalse(isVisible(orcaRule, nonPublicTargetNode2));

    assertTrue(isVisible(publicTargetNode, nonPublicTargetNode1));
    assertFalse(isVisible(nonPublicTargetNode1, publicTargetNode));
  }

  @Test
  public void testVisibilityNonPublicFailure() throws NoSuchBuildTargetException {
    TargetNode<?> nonPublicTargetNode1 =
        createTargetNode(nonPublicTarget1, nonPublicBuildFile, ORCA);
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, publicBuildFile, PUBLIC);

    Optional<VisibilityError> error = nonPublicTargetNode1.isVisibleTo(publicTargetNode);

    assertVisibilityError(
        error, VisibilityError.ErrorType.VISIBILITY, nonPublicTargetNode1, publicTargetNode);
  }

  @Test
  public void testVisibilityMix() throws NoSuchBuildTargetException {
    TargetNode<?> nonPublicTargetNode1 =
        createTargetNode(nonPublicTarget1, nonPublicBuildFile, ORCA);
    TargetNode<?> nonPublicTargetNode2 =
        createTargetNode(nonPublicTarget2, nonPublicBuildFile, ORCA);
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, publicBuildFile, PUBLIC);
    TargetNode<?> orcaRule = createTargetNode(orcaTarget, orcaBuildFile, DEFAULT);

    assertTrue(
        shouldBeVisibleMessage(nonPublicTargetNode1, orcaTarget),
        isVisible(nonPublicTargetNode1, orcaRule));
    assertTrue(
        shouldBeVisibleMessage(nonPublicTargetNode2, orcaTarget),
        isVisible(nonPublicTargetNode2, orcaRule));
    assertTrue(isVisible(publicTargetNode, orcaRule));
    assertFalse(isVisible(orcaRule, nonPublicTargetNode1));
    assertFalse(isVisible(orcaRule, nonPublicTargetNode2));
    assertFalse(isVisible(orcaRule, publicTargetNode));
  }

  @Test
  public void testVisibilityMixFailure() throws NoSuchBuildTargetException {
    TargetNode<?> nonPublicTargetNode1 =
        createTargetNode(nonPublicTarget1, nonPublicBuildFile, ORCA);
    TargetNode<?> nonPublicTargetNode2 =
        createTargetNode(nonPublicTarget2, nonPublicBuildFile, SOME_OTHER);
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, publicBuildFile, PUBLIC);
    TargetNode<?> orcaRule = createTargetNode(orcaTarget, orcaBuildFile, DEFAULT);

    Optional<VisibilityError> error = publicTargetNode.isVisibleTo(orcaRule);

    assertFalse(error.isPresent());

    error = nonPublicTargetNode1.isVisibleTo(orcaRule);

    assertFalse(error.isPresent());

    error = nonPublicTargetNode2.isVisibleTo(orcaRule);

    assertVisibilityError(
        error, VisibilityError.ErrorType.VISIBILITY, nonPublicTargetNode2, orcaRule);
  }

  @Test
  public void testVisibilityForDirectory() throws NoSuchBuildTargetException {
    BuildTarget libTarget = BuildTargetFactory.newInstance("//lib", "lib");
    TargetNode<?> targetInSpecifiedDirectory =
        createTargetNode(
            BuildTargetFactory.newInstance("//src/com/facebook", "test"),
            Paths.get("src/com/facebook/BUCK"),
            DEFAULT);
    TargetNode<?> targetUnderSpecifiedDirectory =
        createTargetNode(
            BuildTargetFactory.newInstance("//src/com/facebook/buck", "test"),
            Paths.get("src/com/facebook/buck/BUCK"),
            DEFAULT);
    TargetNode<?> targetInOtherDirectory =
        createTargetNode(
            BuildTargetFactory.newInstance("//src/com/instagram", "test"),
            Paths.get("src/com/instagram/BUCK"),
            DEFAULT);
    TargetNode<?> targetInParentDirectory =
        createTargetNode(BuildTargetFactory.newInstance("//", "test"), Paths.get("BUCK"), DEFAULT);

    // Build rule that visible to targets in or under directory src/com/facebook
    TargetNode<?> directoryTargetNode =
        createTargetNode(libTarget, Paths.get("BUCK"), ImmutableList.of("//src/com/facebook/..."));
    assertTrue(isVisible(directoryTargetNode, targetInSpecifiedDirectory));
    assertTrue(isVisible(directoryTargetNode, targetUnderSpecifiedDirectory));
    assertFalse(isVisible(directoryTargetNode, targetInOtherDirectory));
    assertFalse(isVisible(directoryTargetNode, targetInParentDirectory));

    // Build rule that's visible to all targets, equals to PUBLIC.
    TargetNode<?> pubicTargetNode =
        createTargetNode(libTarget, Paths.get("BUCK"), ImmutableList.of("//..."));
    assertTrue(isVisible(pubicTargetNode, targetInSpecifiedDirectory));
    assertTrue(isVisible(pubicTargetNode, targetUnderSpecifiedDirectory));
    assertTrue(isVisible(pubicTargetNode, targetInOtherDirectory));
    assertTrue(isVisible(pubicTargetNode, targetInParentDirectory));
  }

  @Test
  public void testOnlyWithinViewIsVisible() throws NoSuchBuildTargetException {
    TargetNode<?> publicTargetNode = createTargetNode(publicTarget, publicBuildFile, PUBLIC, ORCA);
    TargetNode<?> publicOrcaRule = createTargetNode(orcaTarget, orcaBuildFile, PUBLIC, SOME_OTHER);

    assertTrue(isVisible(publicOrcaRule, publicTargetNode));

    Optional<VisibilityError> error = publicTargetNode.isVisibleTo(publicOrcaRule);

    assertVisibilityError(
        error, VisibilityError.ErrorType.WITHIN_VIEW, publicTargetNode, publicOrcaRule);
  }

  @Test
  public void packageTargetsAreVisibleAndWithinViewToEachOther() throws NoSuchBuildTargetException {
    TargetNode<?> nonPublicTargetNode1 =
        createTargetNode(nonPublicTarget1, nonPublicBuildFile, SOME_OTHER, SOME_OTHER);
    TargetNode<?> nonPublicTargetNode2 =
        createTargetNode(nonPublicTarget3, nonPublicBuildFile, SOME_OTHER, SOME_OTHER);

    assertTrue(isVisible(nonPublicTargetNode1, nonPublicTargetNode2));
    assertTrue(isVisible(nonPublicTargetNode2, nonPublicTargetNode1));
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

    @RuleArg
    interface AbstractFakeRuleDescriptionArg extends BuildRuleArg {}
  }

  private static TargetNode<?> createTargetNode(
      BuildTarget buildTarget, Path buildFile, ImmutableList<String> visibilities)
      throws NoSuchBuildTargetException {
    return createTargetNode(buildTarget, buildFile, visibilities, DEFAULT);
  }

  private static TargetNode<?> createTargetNode(
      BuildTarget buildTarget,
      Path buildFile,
      ImmutableList<String> visibilities,
      ImmutableList<String> withinView)
      throws NoSuchBuildTargetException {
    RelPath relativeBuildFile = filesystem.relativize(filesystem.resolve(buildFile));
    CellPathResolver cellNames = TestCellPathResolver.get(filesystem);
    FakeRuleDescription description = new FakeRuleDescription();
    FakeRuleDescriptionArg arg =
        FakeRuleDescriptionArg.builder().setName(buildTarget.getShortName()).build();
    VisibilityDefiningPath definingPath =
        VisibilityDefiningPath.of(ForwardRelativePath.ofRelPath(relativeBuildFile), true);
    return new TargetNodeFactory(
            new DefaultTypeCoercerFactory(), SingleRootCellNameResolverProvider.INSTANCE)
        .createFromObject(
            description,
            arg,
            TwoArraysImmutableHashMap.of(),
            filesystem,
            buildTarget,
            DependencyStack.root(),
            ImmutableSet.of(),
            ImmutableSortedSet.of(),
            visibilities.stream()
                .map(s -> VisibilityPatternParser.parse(cellNames, definingPath, s))
                .collect(ImmutableSet.toImmutableSet()),
            withinView.stream()
                .map(s -> VisibilityPatternParser.parse(cellNames, definingPath, s))
                .collect(ImmutableSet.toImmutableSet()),
            RuleType.of("fake", RuleType.Kind.BUILD));
  }

  private void assertVisibilityError(
      Optional<VisibilityError> error,
      VisibilityError.ErrorType errorType,
      TargetNode<?> owner,
      TargetNode<?> viewer) {
    assertTrue(error.isPresent());
    error.ifPresent(
        visibilityError -> {
          assertSame(errorType, visibilityError.getErrorType());
          assertEquals(viewer.getBuildTarget(), visibilityError.getNode());
          assertEquals(owner.getBuildTarget(), visibilityError.getDep());
        });
  }

  private static boolean isVisible(TargetNode<?> owner, TargetNode<?> viewer) {
    Optional<VisibilityError> visibilityError = owner.isVisibleTo(viewer);
    return !visibilityError.isPresent();
  }
}
