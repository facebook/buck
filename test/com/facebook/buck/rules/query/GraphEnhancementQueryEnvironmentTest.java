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

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPatternParser;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

/** Tests for the query environment available during graph enhancement */
public class GraphEnhancementQueryEnvironmentTest {

  private CellPathResolver cellRoots;
  private static final TypeCoercerFactory TYPE_COERCER_FACTORY = new DefaultTypeCoercerFactory();
  private static final Path ROOT = Paths.get("/fake/cell/root");

  @Before
  public void setUp() {
    cellRoots = DefaultCellPathResolver.of(ROOT, ImmutableMap.of());
  }

  @Test
  public void getTargetsMatchingPatternThrowsInformativeException() {
    BuildTarget target = BuildTargetFactory.newInstance(ROOT, "//foo/bar:bar");
    GraphEnhancementQueryEnvironment envWithoutDeps =
        new GraphEnhancementQueryEnvironment(
            Optional.of(new TestActionGraphBuilder()),
            Optional.of(TargetGraph.EMPTY),
            TYPE_COERCER_FACTORY,
            cellRoots,
            BuildTargetPatternParser.forBaseName(target.getBaseName()),
            ImmutableSet.of());
    try {
      envWithoutDeps.getTargetsMatchingPattern("::");
      fail("Expected a QueryException to be thrown!");
    } catch (Exception e) {
      assertThat("Exception should contain a cause!", e.getCause(), Matchers.notNullValue());
    }
  }

  @Test
  public void getTargetsMatchingPatternWithoutDeps() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance(ROOT, "//foo/bar:bar");
    GraphEnhancementQueryEnvironment envWithoutDeps =
        new GraphEnhancementQueryEnvironment(
            Optional.of(new TestActionGraphBuilder()),
            Optional.of(TargetGraph.EMPTY),
            TYPE_COERCER_FACTORY,
            cellRoots,
            BuildTargetPatternParser.forBaseName(target.getBaseName()),
            ImmutableSet.of());

    // No deps in == no deps out
    assertTrue(envWithoutDeps.getTargetsMatchingPattern("$declared_deps").isEmpty());
    // Check that returned path is resolved
    assertThat(
        envWithoutDeps.getTargetsMatchingPattern("//another/target:target"),
        Matchers.contains(
            QueryBuildTarget.of(BuildTargetFactory.newInstance(ROOT, "//another/target:target"))));
    // Check that the returned path is relative to the contextual path
    assertThat(
        envWithoutDeps.getTargetsMatchingPattern(":relative_name"),
        Matchers.contains(
            QueryBuildTarget.of(BuildTargetFactory.newInstance(ROOT, "//foo/bar:relative_name"))));
  }

  @Test
  public void getTargetsMatchingPatternWithDeps() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance(ROOT, "//foo/bar:bar");
    BuildTarget dep1 = BuildTargetFactory.newInstance(ROOT, "//deps:dep1");
    BuildTarget dep2 = BuildTargetFactory.newInstance(ROOT, "//deps:dep2");

    GraphEnhancementQueryEnvironment env =
        new GraphEnhancementQueryEnvironment(
            Optional.of(new TestActionGraphBuilder()),
            Optional.of(TargetGraph.EMPTY),
            TYPE_COERCER_FACTORY,
            cellRoots,
            BuildTargetPatternParser.forBaseName(target.getBaseName()),
            ImmutableSet.of(dep1, dep2));

    // Check that the macro resolves
    assertThat(
        env.getTargetsMatchingPattern("$declared_deps"),
        Matchers.hasItems(QueryBuildTarget.of(dep1), QueryBuildTarget.of(dep2)));
  }

  private GraphEnhancementQueryEnvironment buildQueryEnvironmentWithGraph() {
    // Set up target graph: lib -> sublib -> bottom
    TargetNode<JavaLibraryDescriptionArg> bottomNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:bottom")).build();
    TargetNode<JavaLibraryDescriptionArg> sublibNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:sublib"))
            .addDep(bottomNode.getBuildTarget())
            .build();
    TargetNode<JavaLibraryDescriptionArg> libNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addDep(sublibNode.getBuildTarget())
            .build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(bottomNode, libNode, sublibNode);
    ActionGraphBuilder realGraphBuilder = new TestActionGraphBuilder(targetGraph);

    FakeJavaLibrary bottomRule =
        realGraphBuilder.addToIndex(new FakeJavaLibrary(bottomNode.getBuildTarget()));
    bottomRule.setOutputFile("bottom.jar");
    FakeJavaLibrary sublibRule =
        realGraphBuilder.addToIndex(
            new FakeJavaLibrary(sublibNode.getBuildTarget(), ImmutableSortedSet.of(bottomRule)));
    sublibRule.setOutputFile("sublib.jar");
    FakeJavaLibrary libRule =
        realGraphBuilder.addToIndex(
            new FakeJavaLibrary(libNode.getBuildTarget(), ImmutableSortedSet.of(sublibRule)));
    libRule.setOutputFile("lib.jar");

    return new GraphEnhancementQueryEnvironment(
        Optional.of(realGraphBuilder),
        Optional.of(targetGraph),
        TYPE_COERCER_FACTORY,
        cellRoots,
        BuildTargetPatternParser.forBaseName(libNode.getBuildTarget().getBaseName()),
        ImmutableSet.of(sublibNode.getBuildTarget()));
  }

  private static QueryTarget getQueryTarget(String target) {
    return QueryBuildTarget.of(BuildTargetFactory.newInstance(target));
  }

  @Test
  public void getFwdDeps() {
    GraphEnhancementQueryEnvironment env = buildQueryEnvironmentWithGraph();
    // lib -> sublib
    assertThat(
        env.getFwdDeps(ImmutableSet.of(getQueryTarget("//:lib"))),
        Matchers.contains(getQueryTarget("//:sublib")));
    // sublib -> bottom
    assertThat(
        env.getFwdDeps(ImmutableSet.of(getQueryTarget("//:sublib"))),
        Matchers.contains(getQueryTarget("//:bottom")));
  }

  @Test
  public void forEachFwdDeps() {
    GraphEnhancementQueryEnvironment env = buildQueryEnvironmentWithGraph();
    // lib -> sublib
    ImmutableList.Builder<Object> libDeps = ImmutableList.builder();
    env.forEachFwdDep(ImmutableSet.of(getQueryTarget("//:lib")), libDeps::add);
    assertThat(libDeps.build(), Matchers.contains(getQueryTarget("//:sublib")));
    // sublib -> bottom
    ImmutableList.Builder<Object> subLibDeps = ImmutableList.builder();
    env.forEachFwdDep(ImmutableSet.of(getQueryTarget("//:sublib")), subLibDeps::add);
    assertThat(subLibDeps.build(), Matchers.contains(getQueryTarget("//:bottom")));
  }

  @Test
  public void getRDeps() {
    GraphEnhancementQueryEnvironment env = buildQueryEnvironmentWithGraph();
    // lib -> sublib
    assertThat(
        env.getReverseDeps(ImmutableSet.of(getQueryTarget("//:sublib"))),
        Matchers.contains(getQueryTarget("//:lib")));
    // sublib -> bottom
    assertThat(
        env.getReverseDeps(ImmutableSet.of(getQueryTarget("//:bottom"))),
        Matchers.contains(getQueryTarget("//:sublib")));
  }

  @Test
  public void getClasspath() {
    GraphEnhancementQueryEnvironment env = buildQueryEnvironmentWithGraph();
    ImmutableSet<QueryTarget> classpath =
        env.getFirstOrderClasspath(ImmutableSet.of(getQueryTarget("//:lib")))
            .collect(ImmutableSet.toImmutableSet());
    assertThat(classpath, Matchers.hasItems(getQueryTarget("//:sublib")));
  }
}
