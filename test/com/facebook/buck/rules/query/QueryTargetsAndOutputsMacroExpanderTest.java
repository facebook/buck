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

package com.facebook.buck.rules.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.model.macros.MacroMatchResult;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.QueryTargetsAndOutputsMacroExpander;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.HashMapWithStats;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for the query macro. See {@link com.facebook.buck.shell.GenruleDescriptionIntegrationTest}
 * for some less contrived integration tests.
 */
public class QueryTargetsAndOutputsMacroExpanderTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private QueryTargetsAndOutputsMacroExpander expander;
  private ProjectFilesystem filesystem;
  private ActionGraphBuilder graphBuilder;
  private CellPathResolver cellNames;
  private BuildRule rule;
  private BuildRule dep;
  private MacroHandler handler;
  private HashMapWithStats<MacroMatchResult, Object> cache;

  @Before
  public void setUp() throws Exception {
    cache = new HashMapWithStats<>();
    expander = new QueryTargetsAndOutputsMacroExpander(Optional.empty());
    handler = new MacroHandler(ImmutableMap.of("query_targets_and_outputs", expander));
    filesystem = new FakeProjectFilesystem(tmp.getRoot());
    cellNames = TestCellBuilder.createCellRoots(filesystem);
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//exciting:dep"),
                filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build();

    TargetNode<?, ?> ruleNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//exciting:target"),
                filesystem)
            .addSrc(Paths.get("Other.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, ruleNode);
    graphBuilder = new TestActionGraphBuilder(targetGraph, filesystem);

    dep = graphBuilder.requireRule(depNode.getBuildTarget());
    rule = graphBuilder.requireRule(ruleNode.getBuildTarget());
  }

  @Test
  public void classpathFunction() throws Exception {
    assertExpandsTo(
        "$(query_targets_and_outputs 'classpath(//exciting:target)')",
        rule,
        String.format(
            "%s %s %s %s",
            "//exciting:dep",
            absolutify("exciting/lib__dep__output/dep.jar"),
            "//exciting:target",
            absolutify("exciting/lib__target__output/target.jar")));
  }

  @Test
  public void literals() throws Exception {
    assertExpandsTo(
        "$(query_targets_and_outputs 'set(//exciting:target //exciting:dep)')",
        rule,
        String.format(
            "%s %s %s %s",
            "//exciting:dep",
            absolutify("exciting/lib__dep__output/dep.jar"),
            "//exciting:target",
            absolutify("exciting/lib__target__output/target.jar")));
  }

  @Test
  public void addsSeparator() throws MacroException {
    assertExpandsTo(
        "$(query_targets_and_outputs \" test \" 'set(//exciting:target //exciting:dep)')",
        rule,
        String.format(
            "%s test %s test %s test %s",
            "//exciting:dep",
            absolutify("exciting/lib__dep__output/dep.jar"),
            "//exciting:target",
            absolutify("exciting/lib__target__output/target.jar")));
  }

  @Test
  public void canUseCacheOfPrecomputedWork() throws Exception {
    assertEquals(
        ImmutableList.of(dep, rule),
        handler.extractBuildTimeDeps(
            dep.getBuildTarget(),
            cellNames,
            graphBuilder,
            "$(query_targets_and_outputs 'classpath(//exciting:target)')",
            cache));
    // Cache should be populated at this point
    assertThat(cache.values(), Matchers.hasSize(1));
    assertEquals(1, cache.numPuts());

    int getsSoFar = cache.numGets();
    assertExpandsTo(
        "$(query_targets_and_outputs 'classpath(//exciting:target)')",
        rule,
        String.format(
            "%s %s %s %s",
            "//exciting:dep",
            absolutify("exciting/lib__dep__output/dep.jar"),
            "//exciting:target",
            absolutify("exciting/lib__target__output/target.jar")));
    // No new cache entry should have appeared
    assertThat(cache.values(), Matchers.hasSize(1));
    assertEquals(1, cache.numPuts());
    // And we should have been able to read the value
    assertEquals(getsSoFar + 1, cache.numGets());
  }

  private void assertExpandsTo(String input, BuildRule rule, String expected)
      throws MacroException {

    String results = handler.expand(rule.getBuildTarget(), cellNames, graphBuilder, input, cache);

    assertEquals(expected, results);
  }

  private String absolutify(String relativePath) {
    relativePath = relativePath.replace("/", File.separator);
    return filesystem.resolve(Paths.get("buck-out", "gen", relativePath)).toString();
  }
}
