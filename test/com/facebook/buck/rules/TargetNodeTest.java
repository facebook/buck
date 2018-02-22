/*
 * Copyright 2014-present Facebook, Inc.
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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.ParamInfoException;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.Hashing;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;
import org.junit.Test;

public class TargetNodeTest {

  public static final BuildTarget TARGET_THREE =
      BuildTargetFactory.newInstance("//example/path:three");

  @Test
  public void testIgnoreNonBuildTargetOrPathOrSourcePathArgument()
      throws NoSuchBuildTargetException {

    TargetNode<ExampleDescriptionArg, ExampleDescription> targetNode =
        createTargetNode(TARGET_THREE);

    assertTrue(targetNode.getExtraDeps().isEmpty());
    assertTrue(targetNode.getDeclaredDeps().isEmpty());
  }

  @Test
  public void testDepsAndPathsAreCollected() throws NoSuchBuildTargetException {
    ImmutableList<String> depsStrings =
        ImmutableList.of("//example/path:one", "//example/path:two");
    ImmutableSet<BuildTarget> depsTargets =
        depsStrings
            .stream()
            .map(BuildTargetFactory::newInstance)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableMap<String, Object> rawNode =
        ImmutableMap.of(
            "name",
            TARGET_THREE.getShortName(),
            "deps",
            depsStrings,
            "sourcePaths",
            ImmutableList.of("//example/path:two", "//example/path:four", "MyClass.java"),
            "appleSource",
            "//example/path:five",
            "source",
            "AnotherClass.java");

    TargetNode<ExampleDescriptionArg, ExampleDescription> targetNode =
        createTargetNode(TARGET_THREE, depsTargets, rawNode);

    assertThat(
        targetNode.getInputs(),
        containsInAnyOrder(
            Paths.get("example/path/MyClass.java"), Paths.get("example/path/AnotherClass.java")));

    assertThat(
        targetNode.getExtraDeps(),
        containsInAnyOrder(
            BuildTargetFactory.newInstance("//example/path:two"),
            BuildTargetFactory.newInstance("//example/path:four"),
            BuildTargetFactory.newInstance("//example/path:five")));

    assertThat(
        targetNode.getDeclaredDeps(),
        containsInAnyOrder(
            BuildTargetFactory.newInstance("//example/path:one"),
            BuildTargetFactory.newInstance("//example/path:two")));
  }

  @Test
  public void targetsWithTheSameRelativePathButNotTheSameCellMightNotBeAbleToSeeEachOther()
      throws Exception {

    ProjectFilesystem rootOne = FakeProjectFilesystem.createJavaOnlyFilesystem("/one");
    BuildTarget buildTargetOne = BuildTargetFactory.newInstance(rootOne.getRootPath(), "//foo:bar");
    TargetNode<ExampleDescriptionArg, ExampleDescription> targetNodeOne =
        createTargetNode(buildTargetOne);

    ProjectFilesystem rootTwo = FakeProjectFilesystem.createJavaOnlyFilesystem("/two");
    BuildTarget buildTargetTwo = BuildTargetFactory.newInstance(rootTwo.getRootPath(), "//foo:bar");
    TargetNode<ExampleDescriptionArg, ExampleDescription> targetNodeTwo =
        createTargetNode(buildTargetTwo);

    boolean isVisible = targetNodeOne.isVisibleTo(targetNodeTwo);

    assertThat(isVisible, is(false));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractExampleDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getSourcePaths();

    Optional<SourceWithFlags> getAppleSource();

    Optional<Path> getSource();

    Optional<String> getString();

    @Hint(isDep = false)
    Optional<BuildTarget> getTarget();
  }

  public static class ExampleDescription implements Description<ExampleDescriptionArg> {

    @Override
    public Class<ExampleDescriptionArg> getConstructorArgType() {
      return ExampleDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContext context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        ExampleDescriptionArg args) {
      return new FakeBuildRule(buildTarget, context.getProjectFilesystem(), params);
    }
  }

  private static TargetNode<ExampleDescriptionArg, ExampleDescription> createTargetNode(
      BuildTarget buildTarget) throws NoSuchBuildTargetException {
    ImmutableMap<String, Object> rawNode =
        ImmutableMap.of(
            "name",
            buildTarget.getShortName(),
            "deps",
            ImmutableList.of(),
            "string",
            "//example/path:one",
            "target",
            "//example/path:two",
            "sourcePaths",
            ImmutableSortedSet.of());

    return createTargetNode(buildTarget, ImmutableSet.of(), rawNode);
  }

  private static TargetNode<ExampleDescriptionArg, ExampleDescription> createTargetNode(
      BuildTarget buildTarget,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableMap<String, Object> rawNode)
      throws NoSuchBuildTargetException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    ExampleDescription description = new ExampleDescription();

    return new TargetNodeFactory(new DefaultTypeCoercerFactory())
        .create(
            Hashing.sha1().hashString(buildTarget.getFullyQualifiedName(), UTF_8),
            description,
            createPopulatedConstructorArg(buildTarget, rawNode),
            filesystem,
            buildTarget,
            declaredDeps,
            ImmutableSet.of(),
            ImmutableSet.of(),
            createCellRoots(filesystem));
  }

  private static ExampleDescriptionArg createPopulatedConstructorArg(
      BuildTarget buildTarget, Map<String, Object> instance) throws NoSuchBuildTargetException {
    ConstructorArgMarshaller marshaller =
        new ConstructorArgMarshaller(new DefaultTypeCoercerFactory());
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    try {
      return marshaller.populate(
          createCellRoots(projectFilesystem),
          projectFilesystem,
          buildTarget,
          ExampleDescriptionArg.class,
          ImmutableSet.builder(),
          instance);
    } catch (ParamInfoException e) {
      throw new RuntimeException(e);
    }
  }
}
