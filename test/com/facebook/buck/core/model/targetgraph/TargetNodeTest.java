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

import static com.facebook.buck.core.cell.TestCellBuilder.createCellRoots;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.nameresolver.CellNameResolverProvider;
import com.facebook.buck.core.cell.nameresolver.SimpleCellNameResolverProvider;
import com.facebook.buck.core.cell.nameresolver.SingleRootCellNameResolverProvider;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.impl.ThrowingTargetConfigurationTransformer;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.knowntypes.KnownNativeRuleTypes;
import com.facebook.buck.core.select.impl.ThrowingSelectableConfigurationContext;
import com.facebook.buck.core.select.impl.ThrowingSelectorListResolver;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePathFactoryForTests;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;
import org.junit.Test;

public class TargetNodeTest {

  public static final BuildTarget TARGET_THREE =
      BuildTargetFactory.newInstance("//example/path:three");

  @Test
  public void testIgnoreNonBuildTargetOrPathOrSourcePathArgument()
      throws NoSuchBuildTargetException {

    TargetNode<ExampleDescriptionArg> targetNode =
        createTargetNode(TARGET_THREE, SingleRootCellNameResolverProvider.INSTANCE);

    assertTrue(targetNode.getExtraDeps().isEmpty());
    assertTrue(targetNode.getDeclaredDeps().isEmpty());
  }

  @Test
  public void testDepsAndPathsAreCollected() throws NoSuchBuildTargetException {
    ImmutableList<String> depsStrings =
        ImmutableList.of("//example/path:one", "//example/path:two");
    ImmutableSet<BuildTarget> depsTargets =
        depsStrings.stream()
            .map(BuildTargetFactory::newInstance)
            .collect(ImmutableSet.toImmutableSet());
    ImmutableMap<String, Object> rawNode =
        ImmutableMap.of(
            "name",
            TARGET_THREE.getShortName(),
            "deps",
            depsTargets.stream()
                .map(BuildTarget::getUnconfiguredBuildTarget)
                .collect(ImmutableList.toImmutableList()),
            "sourcePaths",
            ImmutableList.of(
                UnconfiguredSourcePathFactoryForTests.unconfiguredSourcePath("//example/path:two"),
                UnconfiguredSourcePathFactoryForTests.unconfiguredSourcePath("//example/path:four"),
                UnconfiguredSourcePathFactoryForTests.unconfiguredSourcePath(
                    "example/path/MyClass.java")),
            "appleSource",
            Optional.of("//example/path:five"),
            "source",
            Optional.of(Paths.get("example/path/AnotherClass.java")));

    TargetNode<ExampleDescriptionArg> targetNode =
        createTargetNode(
            SingleRootCellNameResolverProvider.INSTANCE,
            TARGET_THREE,
            depsTargets,
            rawNode,
            Sets.newHashSet(
                Paths.get("example/path/AnotherClass.java"),
                Paths.get("example/path/MyClass.java")));

    assertThat(
        targetNode.getInputs(),
        containsInAnyOrder(
            ForwardRelativePath.of("example/path/MyClass.java"),
            ForwardRelativePath.of("example/path/AnotherClass.java")));

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
  public void targetsWithTheSameRelativePathButNotTheSameCellMightNotBeAbleToSeeEachOther() {
    SimpleCellNameResolverProvider cellNames = new SimpleCellNameResolverProvider("aaa", "bbb");

    BuildTarget buildTargetOne = BuildTargetFactory.newInstance("aaa//foo:bar");
    TargetNode<ExampleDescriptionArg> targetNodeOne = createTargetNode(buildTargetOne, cellNames);

    BuildTarget buildTargetTwo = BuildTargetFactory.newInstance("bbb//foo:bar");
    TargetNode<ExampleDescriptionArg> targetNodeTwo = createTargetNode(buildTargetTwo, cellNames);

    boolean isVisible = targetNodeOne.isVisibleTo(targetNodeTwo);

    assertThat(isVisible, is(false));
  }

  @Test
  public void invalidArgumentsThrowAnException() {
    ImmutableMap<String, Object> rawNode =
        ImmutableMap.of(
            "name", TARGET_THREE.getShortName(), "cmd", Optional.of("$(query_outputs '123')"));

    try {
      createTargetNode(
          SingleRootCellNameResolverProvider.INSTANCE,
          TARGET_THREE,
          ImmutableSet.of(),
          rawNode,
          Sets.newHashSet());
    } catch (HumanReadableException e) {
      assertEquals(
          "Cannot traverse attribute cmd of //example/path:three: Error parsing query: 123",
          e.getHumanReadableErrorMessage());
    }
  }

  @Test
  public void configurationDepsAreCopiedToTargetNode() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExampleDescription description = new ExampleDescription();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    BuildTarget configurationBuildTarget = BuildTargetFactory.newInstance("//config:bar");
    TargetNode<?> targetNode =
        new TargetNodeFactory(
                new DefaultTypeCoercerFactory(), SingleRootCellNameResolverProvider.INSTANCE)
            .createFromObject(
                description,
                createPopulatedConstructorArg(buildTarget, ImmutableMap.of("name", "bar")),
                filesystem,
                buildTarget,
                DependencyStack.root(),
                ImmutableSet.of(),
                ImmutableSortedSet.of(configurationBuildTarget),
                ImmutableSet.of(),
                ImmutableSet.of());

    assertEquals(ImmutableSet.of(configurationBuildTarget), targetNode.getConfigurationDeps());
  }

  @RuleArg
  interface AbstractExampleDescriptionArg extends BuildRuleArg, HasDeclaredDeps {
    @Value.NaturalOrder
    ImmutableSortedSet<SourcePath> getSourcePaths();

    Optional<SourceWithFlags> getAppleSource();

    Optional<Path> getSource();

    Optional<String> getString();

    @Hint(isDep = false)
    Optional<BuildTarget> getTarget();

    Optional<StringWithMacros> getCmd();
  }

  public static class ExampleDescription
      implements DescriptionWithTargetGraph<ExampleDescriptionArg> {

    @Override
    public Class<ExampleDescriptionArg> getConstructorArgType() {
      return ExampleDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContextWithTargetGraph context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        ExampleDescriptionArg args) {
      return new FakeBuildRule(buildTarget, context.getProjectFilesystem(), params);
    }
  }

  private static TargetNode<ExampleDescriptionArg> createTargetNode(
      BuildTarget buildTarget, CellNameResolverProvider cellNames)
      throws NoSuchBuildTargetException {
    ImmutableMap<String, Object> rawNode =
        ImmutableMap.of(
            "name",
            buildTarget.getShortName(),
            "deps",
            ImmutableList.of(),
            "string",
            Optional.of("//example/path:one"),
            "target",
            Optional.of(UnconfiguredBuildTargetParser.parse("//example/path:two")),
            "sourcePaths",
            ImmutableList.of());

    return createTargetNode(cellNames, buildTarget, ImmutableSet.of(), rawNode, Sets.newHashSet());
  }

  private static TargetNode<ExampleDescriptionArg> createTargetNode(
      CellNameResolverProvider cellNames,
      BuildTarget buildTarget,
      ImmutableSet<BuildTarget> declaredDeps,
      ImmutableMap<String, Object> rawNode,
      Set<Path> files)
      throws NoSuchBuildTargetException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(buildTarget.getCell(), files);

    ExampleDescription description = new ExampleDescription();

    return new TargetNodeFactory(new DefaultTypeCoercerFactory(), cellNames)
        .createFromObject(
            description,
            createPopulatedConstructorArg(buildTarget, rawNode),
            filesystem,
            buildTarget,
            DependencyStack.root(),
            declaredDeps,
            ImmutableSortedSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of());
  }

  private static ExampleDescriptionArg createPopulatedConstructorArg(
      BuildTarget buildTarget, Map<String, Object> instance) throws NoSuchBuildTargetException {
    DefaultTypeCoercerFactory coercerFactory = new DefaultTypeCoercerFactory();
    ConstructorArgMarshaller marshaller = new DefaultConstructorArgMarshaller();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    KnownNativeRuleTypes knownRuleTypes =
        KnownNativeRuleTypes.of(
            ImmutableList.of(new ExampleDescription()), ImmutableList.of(), ImmutableList.of());
    DataTransferObjectDescriptor<ExampleDescriptionArg> builder =
        knownRuleTypes
            .getDescriptorByNameChecked("example", ExampleDescriptionArg.class)
            .dataTransferObjectDescriptor(coercerFactory);
    try {
      return marshaller.populate(
          createCellRoots(projectFilesystem).getCellNameResolver(),
          projectFilesystem,
          new ThrowingSelectorListResolver(),
          new ThrowingTargetConfigurationTransformer(),
          new ThrowingSelectableConfigurationContext(),
          buildTarget,
          UnconfiguredTargetConfiguration.INSTANCE,
          DependencyStack.root(),
          builder,
          ImmutableSet.builder(),
          ImmutableSet.builder(),
          instance);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
