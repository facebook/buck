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
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.QueryTargetsAndOutputsMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.testutil.HashMapWithStats;
import com.facebook.buck.testutil.TemporaryPaths;
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

  private ProjectFilesystem filesystem;
  private ActionGraphBuilder graphBuilder;
  private CellPathResolver cellNames;
  private SourcePathResolver pathResolver;
  private TypeCoercer<?> coercer;
  private BuildRule rule;
  private StringWithMacrosConverter converter;
  private HashMapWithStats<Macro, Object> cache;

  @Before
  public void setUp() {
    cache = new HashMapWithStats<>();
    filesystem = new FakeProjectFilesystem(tmp.getRoot());
    cellNames = TestCellBuilder.createCellRoots(filesystem);
    coercer = new DefaultTypeCoercerFactory().typeCoercerForType(StringWithMacros.class);

    TargetNode<?> depNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//exciting:dep"),
                filesystem)
            .addSrc(Paths.get("Dep.java"))
            .build();

    TargetNode<?> ruleNode =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(filesystem.getRootPath(), "//exciting:target"),
                filesystem)
            .addSrc(Paths.get("Other.java"))
            .addDep(depNode.getBuildTarget())
            .build();

    TargetGraph targetGraph = TargetGraphFactory.newInstance(depNode, ruleNode);
    graphBuilder = new TestActionGraphBuilder(targetGraph, filesystem);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    rule = graphBuilder.requireRule(ruleNode.getBuildTarget());

    converter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(ruleNode.getBuildTarget())
            .setCellPathResolver(cellNames)
            .addExpanders(new QueryTargetsAndOutputsMacroExpander(Optional.of(targetGraph)))
            .setPrecomputedWorkCache(cache)
            .build();
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
  public void addsSeparator() throws Exception {
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
    coerceAndStringify("$(query_targets_and_outputs 'classpath(//exciting:target)')", rule);
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

  private String coerceAndStringify(String input, BuildRule rule) throws CoerceFailedException {
    StringWithMacros stringWithMacros =
        (StringWithMacros)
            coercer.coerce(
                cellNames,
                filesystem,
                rule.getBuildTarget().getBasePath(),
                EmptyTargetConfiguration.INSTANCE,
                input);
    Arg arg = converter.convert(stringWithMacros, graphBuilder);
    return Arg.stringify(arg, pathResolver);
  }

  private void assertExpandsTo(String input, BuildRule rule, String expected)
      throws CoerceFailedException {
    String results = coerceAndStringify(input, rule);
    assertEquals(expected, results);
  }

  private String absolutify(String relativePath) {
    relativePath = relativePath.replace("/", File.separator);
    return filesystem.resolve(Paths.get("buck-out", "gen", relativePath)).toString();
  }
}
