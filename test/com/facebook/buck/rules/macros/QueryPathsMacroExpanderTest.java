/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class QueryPathsMacroExpanderTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private FakeProjectFilesystem filesystem;
  private CellPathResolver cellPathResolver;

  @Before
  public void setUp() {
    filesystem = new FakeProjectFilesystem(tmp.getRoot());
    cellPathResolver = TestCellBuilder.createCellRoots(filesystem);
  }

  @Test
  public void sourcePathsToOutputsGivenByDefault() throws Exception {
    TargetNode<?> depNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//some:dep"), filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build();

    TargetNode<?> targetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//some:target"),
                filesystem)
            .addSrc(Paths.get("Target.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, targetNode);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph, filesystem);

    // Ensure that the root rule is in the graphBuilder
    BuildRule rule = graphBuilder.requireRule(targetNode.getBuildTarget());

    QueryPathsMacroExpander expander = new QueryPathsMacroExpander(Optional.of(targetGraph));
    StringWithMacrosConverter converter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(targetNode.getBuildTarget())
            .setCellPathResolver(cellPathResolver)
            .addExpanders(expander)
            .build();

    String input = "$(query_paths 'deps(//some:target)')";

    String expanded =
        coerceAndStringify(filesystem, cellPathResolver, graphBuilder, converter, input, rule);

    // Expand the expected results
    DefaultSourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    String expected =
        Stream.of(depNode, targetNode)
            .map(TargetNode::getBuildTarget)
            .map(graphBuilder::requireRule)
            .map(BuildRule::getSourcePathToOutput)
            .map(pathResolver::getAbsolutePath)
            .map(Object::toString)
            .collect(Collectors.joining(" "));

    assertEquals(expected, expanded);
  }

  @Test
  public void shouldDeclareDeps() {
    TargetNode<?> dep =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//some:dep"), filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build();

    TargetNode<?> target =
        GenruleBuilder.newGenruleBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//some:target"),
                filesystem)
            .setOut("foo.txt")
            .setCmd(
                StringWithMacrosUtils.format(
                    "%s",
                    QueryPathsMacro.of(
                        Query.of(
                            dep.getBuildTarget().getFullyQualifiedName(),
                            dep.getBuildTarget().getTargetConfiguration()))))
            .build();

    TargetGraph graph = TargetGraphFactory.newInstance(dep, target);

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(graph, filesystem);
    BuildRule depRule = graphBuilder.requireRule(dep.getBuildTarget());
    BuildRule rule = graphBuilder.requireRule(target.getBuildTarget());

    assertEquals(ImmutableSortedSet.of(depRule), rule.getBuildDeps());
  }

  private String coerceAndStringify(
      ProjectFilesystem filesystem,
      CellPathResolver cellPathResolver,
      ActionGraphBuilder graphBuilder,
      StringWithMacrosConverter converter,
      String input,
      BuildRule rule)
      throws CoerceFailedException {
    StringWithMacros stringWithMacros =
        (StringWithMacros)
            new DefaultTypeCoercerFactory()
                .typeCoercerForType(StringWithMacros.class)
                .coerce(
                    cellPathResolver,
                    filesystem,
                    rule.getBuildTarget().getBasePath(),
                    EmptyTargetConfiguration.INSTANCE,
                    input);
    Arg arg = converter.convert(stringWithMacros, graphBuilder);
    return Arg.stringify(
        arg, DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder)));
  }
}
