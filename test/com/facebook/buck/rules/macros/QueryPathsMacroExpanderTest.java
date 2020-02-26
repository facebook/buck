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

package com.facebook.buck.rules.macros;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.reflect.TypeToken;
import java.nio.file.Paths;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class QueryPathsMacroExpanderTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private FakeProjectFilesystem filesystem;
  private CellNameResolver cellNameResolver;

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem(CanonicalCellName.rootCell(), AbsPath.of(tmp.getRoot()));
    cellNameResolver = TestCellBuilder.createCellRoots(filesystem).getCellNameResolver();
  }

  @Test
  public void sourcePathsToOutputsGivenByDefault() throws Exception {
    TargetNode<?> depNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//some:dep"), filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build();

    TargetNode<?> targetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//some:target"), filesystem)
            .addSrc(Paths.get("Target.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, targetNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph, filesystem);

    // Ensure that the root rule is in the graphBuilder
    BuildRule rule = graphBuilder.requireRule(targetNode.getBuildTarget());

    QueryPathsMacroExpander expander = new QueryPathsMacroExpander(targetGraph);
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.of(
            targetNode.getBuildTarget(),
            cellNameResolver,
            graphBuilder,
            ImmutableList.of(expander));

    String input = "$(query_paths 'deps(//some:target)')";

    String expanded =
        coerceAndStringify(filesystem, cellNameResolver, graphBuilder, converter, input, rule);

    // Expand the expected results
    String expected =
        Stream.of(depNode, targetNode)
            .map(TargetNode::getBuildTarget)
            .map(graphBuilder::requireRule)
            .map(BuildRule::getSourcePathToOutput)
            .map(graphBuilder.getSourcePathResolver()::getAbsolutePath)
            .map(Object::toString)
            .collect(Collectors.joining(" "));

    assertEquals(expected, expanded);
  }

  @Test
  public void shouldDeclareDeps() {
    TargetNode<?> dep =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//some:dep"), filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build();

    TargetNode<?> target =
        GenruleBuilder.newGenruleBuilder(
                BuildTargetFactory.newInstance("//some:target"), filesystem)
            .setOut("foo.txt")
            .setCmd(
                StringWithMacrosUtils.format(
                    "%s",
                    QueryPathsMacro.of(
                        Query.of(
                            dep.getBuildTarget().getFullyQualifiedName(),
                            dep.getBuildTarget().getTargetConfiguration(),
                            BaseName.ROOT))))
            .build();

    TargetGraph graph = TargetGraphFactory.newInstance(dep, target);

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(graph, filesystem);
    BuildRule depRule = graphBuilder.requireRule(dep.getBuildTarget());
    BuildRule rule = graphBuilder.requireRule(target.getBuildTarget());

    assertEquals(ImmutableSortedSet.of(depRule), rule.getBuildDeps());
  }

  private String coerceAndStringify(
      ProjectFilesystem filesystem,
      CellNameResolver cellNameResolver,
      ActionGraphBuilder graphBuilder,
      StringWithMacrosConverter converter,
      String input,
      BuildRule rule)
      throws CoerceFailedException {
    StringWithMacros stringWithMacros =
        new DefaultTypeCoercerFactory()
            .typeCoercerForType(TypeToken.of(StringWithMacros.class))
            .coerceBoth(
                cellNameResolver,
                filesystem,
                rule.getBuildTarget().getCellRelativeBasePath().getPath(),
                UnconfiguredTargetConfiguration.INSTANCE,
                UnconfiguredTargetConfiguration.INSTANCE,
                input);
    Arg arg = converter.convert(stringWithMacros);
    return Arg.stringify(arg, graphBuilder.getSourcePathResolver());
  }
}
