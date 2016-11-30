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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.QueryMacroExpander;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Optional;

/**
 * Tests for the query macro. See {@link com.facebook.buck.shell.GenruleDescriptionIntegrationTest}
 * for some less contrived integration tests.
 */
public class QueryMacroExpanderTest {
  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private QueryMacroExpander expander;
  private ProjectFilesystem filesystem;
  private BuildRuleResolver ruleResolver;
  private CellPathResolver cellNames;
  private BuildRule rule;
  private BuildRule dep;
  private MacroHandler handler;

  @Before
  public void setUp() throws Exception {
    expander = new QueryMacroExpander(Optional.empty());
    handler = new MacroHandler(
        ImmutableMap.<String, MacroExpander>of("query", expander));
    filesystem = new FakeProjectFilesystem(tmp.getRoot());
    cellNames = TestCellBuilder.createCellRoots(filesystem);
    ruleResolver = new BuildRuleResolver(
        TargetGraph.EMPTY,
        new DefaultTargetNodeToBuildRuleTransformer());
    dep = ruleResolver.addToIndex(
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance(filesystem, "//exciting:dep"),
            filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build(ruleResolver, filesystem));

    rule = ruleResolver.addToIndex(
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance(filesystem, "//exciting:target"),
            filesystem)
            .addSrc(Paths.get("Other.java"))
            .addDep(dep.getBuildTarget())
            .build(ruleResolver, filesystem));

  }

  @Test
  public void classpathFunction() throws Exception {
    assertExpandsTo(
        "$(query 'classpath(//exciting:target)')",
        rule,
        "//exciting:dep //exciting:target");
  }

  @Test
  public void literals() throws Exception {
    assertExpandsTo(
        "$(query 'set(//exciting:target //exciting:dep)')",
        rule,
        "//exciting:dep //exciting:target");
  }

  @Test
  public void extractBuildTimeDeps() throws Exception {
    assertEquals(
        ImmutableList.of(dep),
        expander.extractBuildTimeDeps(
            dep.getBuildTarget(),
            cellNames,
            ruleResolver,
            ImmutableList.of("'set(//exciting:dep)'")));
    assertEquals(
        ImmutableList.of(dep, rule),
        expander.extractBuildTimeDeps(
            dep.getBuildTarget(),
            cellNames,
            ruleResolver,
            ImmutableList.of("'classpath(//exciting:target)'")));
  }


  private void assertExpandsTo(
      String input,
      BuildRule rule,
      String expected) throws MacroException {

    String results = handler.expand(
        rule.getBuildTarget(),
        cellNames,
        ruleResolver,
        input);

    assertEquals(expected, results);
  }
}
