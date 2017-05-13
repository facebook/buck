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

import static org.easymock.EasyMock.createMock;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.query.QueryBuildTarget;
import com.facebook.buck.query.QueryTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

/** Tests for the query environment available during graph enhancement */
public class GraphEnhancementQueryEnvironmentTest {

  private CellPathResolver cellRoots;
  private ListeningExecutorService executor;
  private static final Path ROOT = Paths.get("/fake/cell/root");

  @Before
  public void setUp() throws Exception {
    cellRoots = new DefaultCellPathResolver(ROOT, ImmutableMap.of());
    executor = MoreExecutors.newDirectExecutorService();
  }

  @Test
  public void getTargetsMatchingPatternWithoutDeps() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance(ROOT, "//foo/bar:bar");
    GraphEnhancementQueryEnvironment envWithoutDeps =
        new GraphEnhancementQueryEnvironment(
            Optional.of(createMock(BuildRuleResolver.class)),
            Optional.of(createMock(TargetGraph.class)),
            cellRoots,
            BuildTargetPatternParser.forBaseName(target.getBaseName()),
            ImmutableSet.of());

    // No deps in == no deps out
    assertTrue(envWithoutDeps.getTargetsMatchingPattern("$declared_deps", executor).isEmpty());
    // Check that returned path is resolved
    assertThat(
        envWithoutDeps.getTargetsMatchingPattern("//another/target:target", executor),
        Matchers.contains(
            QueryBuildTarget.of(BuildTargetFactory.newInstance(ROOT, "//another/target:target"))));
    // Check that the returned path is relative to the contextual path
    assertThat(
        envWithoutDeps.getTargetsMatchingPattern(":relative_name", executor),
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
            Optional.of(createMock(BuildRuleResolver.class)),
            Optional.of(createMock(TargetGraph.class)),
            cellRoots,
            BuildTargetPatternParser.forBaseName(target.getBaseName()),
            ImmutableSet.of(dep1, dep2));

    // Check that the macro resolves
    assertThat(
        env.getTargetsMatchingPattern("$declared_deps", executor),
        Matchers.hasItems(QueryBuildTarget.of(dep1), QueryBuildTarget.of(dep2)));
  }

  private GraphEnhancementQueryEnvironment buildQueryEnvironmentWithGraph() {
    // Set up target graph: lib -> sublib -> bottom
    TargetNode<JavaLibraryDescriptionArg, ?> bottomNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:bottom")).build();
    TargetNode<JavaLibraryDescriptionArg, ?> sublibNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:sublib"))
            .addDep(bottomNode.getBuildTarget())
            .build();
    TargetNode<JavaLibraryDescriptionArg, ?> libNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .addDep(sublibNode.getBuildTarget())
            .build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance(bottomNode, libNode, sublibNode);
    BuildRuleResolver realResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(realResolver));

    FakeJavaLibrary bottomRule =
        realResolver.addToIndex(new FakeJavaLibrary(bottomNode.getBuildTarget(), pathResolver));
    bottomRule.setOutputFile("bottom.jar");
    FakeJavaLibrary sublibRule =
        realResolver.addToIndex(
            new FakeJavaLibrary(
                sublibNode.getBuildTarget(), pathResolver, ImmutableSortedSet.of(bottomRule)));
    sublibRule.setOutputFile("sublib.jar");
    FakeJavaLibrary libRule =
        realResolver.addToIndex(
            new FakeJavaLibrary(
                libNode.getBuildTarget(), pathResolver, ImmutableSortedSet.of(sublibRule)));
    libRule.setOutputFile("lib.jar");

    return new GraphEnhancementQueryEnvironment(
        Optional.of(realResolver),
        Optional.of(targetGraph),
        cellRoots,
        BuildTargetPatternParser.forBaseName(libNode.getBuildTarget().getBaseName()),
        ImmutableSet.of(sublibNode.getBuildTarget()));
  }

  private static QueryTarget getQueryTarget(String target) {
    return QueryBuildTarget.of(BuildTargetFactory.newInstance(target));
  }

  @Test
  public void getFwdDeps() throws Exception {
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
  public void getClasspath() throws Exception {
    GraphEnhancementQueryEnvironment env = buildQueryEnvironmentWithGraph();
    ImmutableSet<QueryTarget> classpath =
        env.getFirstOrderClasspath(ImmutableSet.of(getQueryTarget("//:lib")))
            .collect(MoreCollectors.toImmutableSet());
    assertThat(classpath, Matchers.hasItems(getQueryTarget("//:sublib")));
  }
}
