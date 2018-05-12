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

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.model.macros.MacroMatchResult;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.HashMapWithStats;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class QueryPathsMacroExpanderTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private Map<MacroMatchResult, Object> cache;
  private FakeProjectFilesystem filesystem;
  private CellPathResolver cellNames;

  @Before
  public void setUp() {
    cache = new HashMapWithStats<>();
    filesystem = new FakeProjectFilesystem(tmp.getRoot());
    cellNames = TestCellBuilder.createCellRoots(filesystem);
  }

  @Test
  public void sourcePathsToOutputsGivenByDefault() throws MacroException {
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//some:dep"), filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build();

    TargetNode<?, ?> targetNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//some:target"),
                filesystem)
            .addSrc(Paths.get("Target.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, targetNode);
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph, filesystem);

    // Ensure that the root rule is in the resolver
    resolver.requireRule(targetNode.getBuildTarget());

    // Run the query
    QueryPathsMacroExpander expander = new QueryPathsMacroExpander(Optional.of(targetGraph));
    MacroHandler handler = new MacroHandler(ImmutableMap.of("query", expander));
    String expanded =
        handler.expand(
            targetNode.getBuildTarget(),
            cellNames,
            resolver,
            "$(query 'deps(//some:target)')",
            cache);

    // Expand the expected results
    DefaultSourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    String expected =
        Stream.of(depNode, targetNode)
            .map(TargetNode::getBuildTarget)
            .map(resolver::requireRule)
            .map(BuildRule::getSourcePathToOutput)
            .map(pathResolver::getAbsolutePath)
            .map(Object::toString)
            .collect(Collectors.joining(" "));

    assertEquals(expected, expanded);
  }

  @Test
  public void canReturnInputsToRulesViaInputQueryFunction() throws MacroException {
    TargetNode<?, ?> node =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//some:dep"), filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(node);

    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph, filesystem);
    BuildRule rule = resolver.requireRule(node.getBuildTarget());

    ImmutableSet<Path> inputs = node.getInputs();
    System.out.println("inputs = " + inputs);

    QueryPathsMacroExpander expander = new QueryPathsMacroExpander(Optional.of(targetGraph));
    MacroHandler handler = new MacroHandler(ImmutableMap.of("query", expander));

    String query = "$(query 'inputs(//some:dep)')";
    String expanded = handler.expand(rule.getBuildTarget(), cellNames, resolver, query, cache);

    System.out.println("expanded = " + expanded);
  }

  @Test
  public void shouldDeclareDeps() {
    TargetNode<?, ?> dep =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//some:dep"), filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build();

    TargetNode<?, ?> target =
        GenruleBuilder.newGenruleBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//some:target"),
                filesystem)
            .setOut("foo.txt")
            .setCmd(
                StringWithMacrosUtils.format(
                    "%s",
                    QueryPathsMacro.of(Query.of(dep.getBuildTarget().getFullyQualifiedName()))))
            .build();

    TargetGraph graph = TargetGraphFactory.newInstance(dep, target);

    BuildRuleResolver resolver = new TestBuildRuleResolver(graph, filesystem);
    BuildRule depRule = resolver.requireRule(dep.getBuildTarget());
    BuildRule rule = resolver.requireRule(target.getBuildTarget());

    assertEquals(ImmutableSortedSet.of(depRule), rule.getBuildDeps());
  }
}
