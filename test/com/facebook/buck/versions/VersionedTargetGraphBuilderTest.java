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

package com.facebook.buck.versions;

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class VersionedTargetGraphBuilderTest {

  private ForkJoinPool pool;
  private ForkJoinPool executorPool;
  private DepsAwareExecutor executor;

  @Before
  public void setUp() {
    pool = new ForkJoinPool(2);
    executorPool = new ForkJoinPool(2);
    executor = DefaultDepsAwareExecutor.from(executorPool);
  }

  @After
  public void tearDown() {
    executor.shutdownNow();
    executorPool.shutdownNow();
  }

  private static String getVersionedTarget(
      BuildTarget target, ImmutableSortedMap<BuildTarget, Version> versions) {
    return target
        .withAppendedFlavors(ParallelVersionedTargetGraphBuilder.getVersionedFlavor(versions))
        .toString();
  }

  private static String getVersionedTarget(String target, String dep, String version) {
    return getVersionedTarget(
        BuildTargetFactory.newInstance(target),
        ImmutableSortedMap.of(BuildTargetFactory.newInstance(dep), Version.of(version)));
  }

  private static void assertEquals(TargetNode<?> expected, TargetNode<?> actual) {
    assertThat(actual.getBuildTarget(), Matchers.equalTo(expected.getBuildTarget()));
    assertThat(
        String.format("%s: declared deps: ", expected.getBuildTarget()),
        actual.getDeclaredDeps(),
        Matchers.equalTo(expected.getDeclaredDeps()));
    assertThat(
        String.format("%s: extra deps: ", expected.getBuildTarget()),
        actual.getExtraDeps(),
        Matchers.equalTo(expected.getExtraDeps()));
    assertThat(
        String.format("%s: inputs: ", expected.getBuildTarget()),
        actual.getInputs(),
        Matchers.equalTo(expected.getInputs()));
    for (Field field : actual.getConstructorArg().getClass().getFields()) {
      if (!field.getName().equals("selectedVersions")) {
        try {
          assertThat(
              field.get(actual.getConstructorArg()),
              Matchers.equalTo(field.get(expected.getConstructorArg())));
        } catch (IllegalAccessException e) {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private static void assertEquals(TargetGraph expected, TargetGraph actual) {
    ImmutableMap<BuildTarget, TargetNode<?>> expectedNodes =
        Maps.uniqueIndex(expected.getNodes(), TargetNode::getBuildTarget);
    ImmutableMap<BuildTarget, TargetNode<?>> actualNodes =
        Maps.uniqueIndex(actual.getNodes(), TargetNode::getBuildTarget);
    assertThat(actualNodes.keySet(), Matchers.equalTo(expectedNodes.keySet()));
    for (Map.Entry<BuildTarget, TargetNode<?>> ent : expectedNodes.entrySet()) {
      assertEquals(ent.getValue(), actualNodes.get(ent.getKey()));
    }
  }

  @Test
  @Parameters(method = "builderFactory")
  public void singleRootNode(VersionedTargetGraphBuilderFactory factory) throws Exception {
    TargetNode<?> root = new VersionRootBuilder("//:root").build();
    TargetGraph graph = TargetGraphFactory.newInstanceExact(root);
    VersionedTargetGraphBuilder builder =
        factory.create(
            pool,
            executor,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(graph, ImmutableSet.of(root.getBuildTarget())),
            new DefaultTypeCoercerFactory());
    TargetGraph versionedGraph = builder.build();
    assertEquals(graph, versionedGraph);
  }

  @Test
  @Parameters(method = "builderFactory")
  public void rootWithDepOnRoot(VersionedTargetGraphBuilderFactory factory) throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstanceExact(
            new VersionRootBuilder("//:root2").build(),
            new VersionRootBuilder("//:root1").setDeps("//:root2").build());
    VersionedTargetGraphBuilder builder =
        factory.create(
            pool,
            executor,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph,
                ImmutableSet.of(
                    BuildTargetFactory.newInstance("//:root1"),
                    BuildTargetFactory.newInstance("//:root2"))),
            new DefaultTypeCoercerFactory());
    TargetGraph versionedGraph = builder.build();
    assertEquals(graph, versionedGraph);
  }

  @Test
  @Parameters(method = "builderFactory")
  public void versionedSubGraph(VersionedTargetGraphBuilderFactory factory) throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstanceExact(
            new VersionPropagatorBuilder("//:dep").build(),
            new VersionedAliasBuilder("//:versioned").setVersions("1.0", "//:dep").build(),
            new VersionRootBuilder("//:root").setDeps("//:versioned").build());
    VersionedTargetGraphBuilder builder =
        factory.create(
            pool,
            executor,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph, ImmutableSet.of(BuildTargetFactory.newInstance("//:root"))),
            new DefaultTypeCoercerFactory());
    TargetGraph versionedGraph = builder.build();
    TargetGraph expectedTargetGraph =
        TargetGraphFactory.newInstanceExact(
            new VersionPropagatorBuilder("//:dep").build(),
            new VersionRootBuilder("//:root").setDeps("//:dep").build());
    assertEquals(expectedTargetGraph, versionedGraph);
  }

  @Test
  @Parameters(method = "builderFactory")
  public void versionedSubGraphWithDepOnRoot(VersionedTargetGraphBuilderFactory factory)
      throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstanceExact(
            new VersionRootBuilder("//:dep_root").build(),
            new VersionPropagatorBuilder("//:dep").setDeps("//:dep_root").build(),
            new VersionedAliasBuilder("//:versioned").setVersions("1.0", "//:dep").build(),
            new VersionRootBuilder("//:root").setDeps("//:versioned").build());
    VersionedTargetGraphBuilder builder =
        factory.create(
            pool,
            executor,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph, ImmutableSet.of(BuildTargetFactory.newInstance("//:root"))),
            new DefaultTypeCoercerFactory());
    TargetGraph versionedGraph = builder.build();
    TargetGraph expectedTargetGraph =
        TargetGraphFactory.newInstanceExact(
            new VersionRootBuilder("//:dep_root").build(),
            new VersionPropagatorBuilder("//:dep").setDeps("//:dep_root").build(),
            new VersionRootBuilder("//:root").setDeps("//:dep").build());
    assertEquals(expectedTargetGraph, versionedGraph);
  }

  @Test
  @Parameters(method = "builderFactory")
  public void versionedSubGraphWithDepAnotherVersionedSubGraph(
      VersionedTargetGraphBuilderFactory factory) throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstanceExact(
            new VersionPropagatorBuilder("//:dep2").build(),
            new VersionedAliasBuilder("//:versioned2").setVersions("1.0", "//:dep2").build(),
            new VersionRootBuilder("//:root2").setDeps("//:versioned2").build(),
            new VersionPropagatorBuilder("//:dep1").setDeps("//:root2").build(),
            new VersionedAliasBuilder("//:versioned1").setVersions("1.0", "//:dep1").build(),
            new VersionRootBuilder("//:root1").setDeps("//:versioned1").build());
    VersionedTargetGraphBuilder builder =
        factory.create(
            pool,
            executor,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph, ImmutableSet.of(BuildTargetFactory.newInstance("//:root1"))),
            new DefaultTypeCoercerFactory());
    TargetGraph versionedGraph = builder.build();
    TargetGraph expectedTargetGraph =
        TargetGraphFactory.newInstanceExact(
            new VersionPropagatorBuilder("//:dep2").build(),
            new VersionRootBuilder("//:root2").setDeps("//:dep2").build(),
            new VersionPropagatorBuilder("//:dep1").setDeps("//:root2").build(),
            new VersionRootBuilder("//:root1").setDeps("//:dep1").build());
    assertEquals(expectedTargetGraph, versionedGraph);
  }

  @Test
  @Parameters(method = "builderFactory")
  public void versionedSubGraphWithVersionedFlavor(VersionedTargetGraphBuilderFactory factory)
      throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstanceExact(
            new VersionPropagatorBuilder("//:dep").build(),
            new VersionedAliasBuilder("//:versioned").setVersions("1.0", "//:dep").build(),
            new VersionPropagatorBuilder("//:a").setDeps("//:versioned").build(),
            new VersionRootBuilder("//:root").setDeps("//:a").build());
    VersionedTargetGraphBuilder builder =
        factory.create(
            pool,
            executor,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph, ImmutableSet.of(BuildTargetFactory.newInstance("//:root"))),
            new DefaultTypeCoercerFactory());
    TargetGraph versionedGraph = builder.build();
    TargetGraph expectedTargetGraph =
        TargetGraphFactory.newInstanceExact(
            new VersionPropagatorBuilder("//:dep").build(),
            new VersionPropagatorBuilder(getVersionedTarget("//:a", "//:versioned", "1.0"))
                .setDeps("//:dep")
                .build(),
            new VersionRootBuilder("//:root")
                .setDeps(getVersionedTarget("//:a", "//:versioned", "1.0"))
                .build());
    assertEquals(expectedTargetGraph, versionedGraph);
  }

  @Test
  @Parameters(method = "builderFactory")
  public void versionedSubGraphWithConstraints(VersionedTargetGraphBuilderFactory factory)
      throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstanceExact(
            new VersionPropagatorBuilder("//:v2").build(),
            new VersionPropagatorBuilder("//:v1").build(),
            new VersionedAliasBuilder("//:dep").setVersions("1.0", "//:v1", "2.0", "//:v2").build(),
            new VersionPropagatorBuilder("//:lib").setDeps("//:dep").build(),
            new VersionRootBuilder("//:a")
                .setDeps("//:lib")
                .setVersionedDeps("//:dep", ExactConstraint.of(Version.of("1.0")))
                .build(),
            new VersionRootBuilder("//:b")
                .setDeps("//:lib")
                .setVersionedDeps("//:dep", ExactConstraint.of(Version.of("2.0")))
                .build());
    BuildTarget a = BuildTargetFactory.newInstance("//:a");
    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    BuildTarget dep = BuildTargetFactory.newInstance("//:dep");
    VersionedTargetGraphBuilder builder =
        factory.create(
            pool,
            executor,
            new FixedVersionSelector(
                ImmutableMap.of(
                    a, ImmutableMap.of(dep, Version.of("1.0")),
                    b, ImmutableMap.of(dep, Version.of("2.0")))),
            TargetGraphAndBuildTargets.of(
                graph,
                ImmutableSet.of(
                    BuildTargetFactory.newInstance("//:a"),
                    BuildTargetFactory.newInstance("//:b"))),
            new DefaultTypeCoercerFactory());
    TargetGraph versionedGraph = builder.build();
    TargetGraph expectedTargetGraph =
        TargetGraphFactory.newInstanceExact(
            new VersionPropagatorBuilder("//:v1").build(),
            new VersionPropagatorBuilder(getVersionedTarget("//:lib", "//:dep", "1.0"))
                .setDeps("//:v1")
                .build(),
            new VersionRootBuilder("//:a")
                .setDeps(getVersionedTarget("//:lib", "//:dep", "1.0"))
                .setVersionedDeps("//:v1", ExactConstraint.of(Version.of("1.0")))
                .build(),
            new VersionPropagatorBuilder("//:v2").build(),
            new VersionPropagatorBuilder(getVersionedTarget("//:lib", "//:dep", "2.0"))
                .setDeps("//:v2")
                .build(),
            new VersionRootBuilder("//:b")
                .setDeps(getVersionedTarget("//:lib", "//:dep", "2.0"))
                .setVersionedDeps("//:v2", ExactConstraint.of(Version.of("2.0")))
                .build());
    assertEquals(expectedTargetGraph, versionedGraph);
  }

  @Test
  @Parameters(method = "builderFactory")
  public void explicitNonRootTreatedAsRoot(VersionedTargetGraphBuilderFactory factory)
      throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstanceExact(
            new VersionPropagatorBuilder("//:dep").build(),
            new VersionedAliasBuilder("//:versioned").setVersions("1.0", "//:dep").build(),
            new VersionPropagatorBuilder("//:root").setDeps("//:versioned").build());
    VersionedTargetGraphBuilder builder =
        factory.create(
            pool,
            executor,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph, ImmutableSet.of(BuildTargetFactory.newInstance("//:root"))),
            new DefaultTypeCoercerFactory());
    TargetGraph versionedGraph = builder.build();
    TargetGraph expectedTargetGraph =
        TargetGraphFactory.newInstanceExact(
            new VersionPropagatorBuilder("//:dep").build(),
            new VersionPropagatorBuilder("//:root").setDeps("//:dep").build());
    assertEquals(expectedTargetGraph, versionedGraph);
  }

  @Test
  @Parameters(method = "builderFactory")
  public void nodeWithTestParameterReferringToNonExistentTarget(
      VersionedTargetGraphBuilderFactory factory) throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstanceExact(
            new VersionPropagatorBuilder("//:root2")
                .setTests(ImmutableSortedSet.of(BuildTargetFactory.newInstance("//:test")))
                .build(),
            new VersionRootBuilder("//:root1").setDeps("//:root2").build());
    VersionedTargetGraphBuilder builder =
        factory.create(
            pool,
            executor,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph,
                ImmutableSet.of(
                    BuildTargetFactory.newInstance("//:root1"),
                    BuildTargetFactory.newInstance("//:root2"))),
            new DefaultTypeCoercerFactory());
    TargetGraph versionedGraph = builder.build();
    assertEquals(graph, versionedGraph);
  }

  @SuppressWarnings("unused")
  private Object[] builderFactory() {
    return new Object[] {
      new Object[] {
        (VersionedTargetGraphBuilderFactory)
            (pool,
                executor,
                versionSelector,
                unversionedTargetGraphAndBuildTargets,
                typeCoercerFactory) ->
                new ParallelVersionedTargetGraphBuilder(
                    pool,
                    versionSelector,
                    unversionedTargetGraphAndBuildTargets,
                    typeCoercerFactory,
                    20)
      },
      new Object[] {
        (VersionedTargetGraphBuilderFactory)
            (pool,
                executor,
                versionSelector,
                unversionedTargetGraphAndBuildTargets,
                typeCoercerFactory) ->
                new AsyncVersionedTargetGraphBuilder(
                    executor,
                    versionSelector,
                    unversionedTargetGraphAndBuildTargets,
                    typeCoercerFactory,
                    20)
      }
    };
  }

  private interface VersionedTargetGraphBuilderFactory {
    VersionedTargetGraphBuilder create(
        ForkJoinPool pool,
        DepsAwareExecutor executor,
        VersionSelector versionSelector,
        TargetGraphAndBuildTargets unversionedTargetGraphAndBuildTargets,
        TypeCoercerFactory typeCoercerFactory);
  }
}
