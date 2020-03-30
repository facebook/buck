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

package com.facebook.buck.core.model.targetgraph.impl;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.graph.transformation.executor.DepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.executor.impl.DefaultDepsAwareExecutor;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.DefaultBuckEventBus;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserPythonInterpreterProvider;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.PerBuildState;
import com.facebook.buck.parser.PerBuildStateFactory;
import com.facebook.buck.parser.TestParserFactory;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.CloseableResource;
import com.facebook.buck.testutil.DummyFileHashCache;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.facebook.buck.util.timing.IncrementingFakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class TargetGraphHashingTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Rule
  public CloseableResource<DepsAwareExecutor<? super ComputeResult, ?>> executor =
      CloseableResource.of(() -> DefaultDepsAwareExecutor.of(4));

  private BuckEventBus eventBus;
  private RuleKeyConfiguration ruleKeyConfiguration;
  private ProjectFilesystem projectFilesystem;
  private Function<TargetNode<?>, ListenableFuture<?>> targetNodeRawAttributesProvider;

  @Before
  public void setUp() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "target_hashing", tmp);
    workspace.setUp();

    eventBus = new DefaultBuckEventBus(new IncrementingFakeClock(), new BuildId());
    ruleKeyConfiguration = TestRuleKeyConfigurationFactory.create();
    Cell cell = workspace.asCell();
    projectFilesystem = cell.getFilesystem();
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager());
    Parser parser =
        TestParserFactory.create(executor.get(), cell, knownRuleTypesProvider, eventBus);
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    PerBuildState parserState =
        new PerBuildStateFactory(
                typeCoercerFactory,
                new DefaultConstructorArgMarshaller(),
                knownRuleTypesProvider,
                new ParserPythonInterpreterProvider(cell.getBuckConfig(), new ExecutableFinder()),
                WatchmanFactory.NULL_WATCHMAN,
                eventBus,
                new ParsingUnconfiguredBuildTargetViewFactory(),
                UnconfiguredTargetConfiguration.INSTANCE)
            .create(
                ParsingContext.builder(cell, MoreExecutors.newDirectExecutorService()).build(),
                parser.getPermState());
    targetNodeRawAttributesProvider =
        node ->
            parser.getTargetNodeRawAttributesJob(parserState, cell, node, DependencyStack.root());
  }

  @Test
  public void emptyTargetGraphHasEmptyHashes() throws InterruptedException {
    TargetGraph targetGraph = TargetGraphFactory.newInstance();

    assertThat(
        new TargetGraphHashing(
                eventBus,
                targetGraph,
                new DummyFileHashCache(),
                ImmutableList.of(),
                MoreExecutors.newDirectExecutorService(),
                ruleKeyConfiguration,
                targetNodeRawAttributesProvider,
                Hashing.murmur3_128())
            .hashTargetGraph()
            .entrySet(),
        empty());
  }

  @Test
  public void hashChangesWhenSrcContentChanges() throws Exception {
    TargetNode<?> node =
        createJavaLibraryTargetNodeWithSrcs(
            BuildTargetFactory.newInstance("//foo:lib"),
            ImmutableSet.of(Paths.get("foo/FooLib.java")));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(node);

    FileHashLoader baseCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                projectFilesystem.resolve("foo/FooLib.java"), HashCode.fromString("abcdef")));

    FileHashLoader modifiedCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                projectFilesystem.resolve("foo/FooLib.java"), HashCode.fromString("abc1ef")));

    Map<BuildTarget, HashCode> baseResult =
        new TargetGraphHashing(
                eventBus,
                targetGraph,
                baseCache,
                ImmutableList.of(node),
                MoreExecutors.newDirectExecutorService(),
                ruleKeyConfiguration,
                targetNodeRawAttributesProvider,
                Hashing.murmur3_128())
            .hashTargetGraph();

    Map<BuildTarget, HashCode> modifiedResult =
        new TargetGraphHashing(
                eventBus,
                targetGraph,
                modifiedCache,
                ImmutableList.of(node),
                MoreExecutors.newDirectExecutorService(),
                ruleKeyConfiguration,
                targetNodeRawAttributesProvider,
                Hashing.murmur3_128())
            .hashTargetGraph();

    assertThat(baseResult, aMapWithSize(1));
    assertThat(baseResult, hasKey(node.getBuildTarget()));
    assertThat(modifiedResult, aMapWithSize(1));
    assertThat(modifiedResult, hasKey(node.getBuildTarget()));
    assertThat(
        modifiedResult.get(node.getBuildTarget()),
        not(equalTo(baseResult.get(node.getBuildTarget()))));
  }

  @Test
  public void twoNodeIndependentRootsTargetGraphHasExpectedHashes() throws InterruptedException {
    TargetNode<?> nodeA =
        createJavaLibraryTargetNodeWithSrcs(
            BuildTargetFactory.newInstance("//foo:lib"),
            ImmutableSet.of(Paths.get("foo/FooLib.java")));
    TargetNode<?> nodeB =
        createJavaLibraryTargetNodeWithSrcs(
            BuildTargetFactory.newInstance("//bar:lib"),
            ImmutableSet.of(Paths.get("bar/BarLib.java")));
    TargetGraph targetGraphA = TargetGraphFactory.newInstance(nodeA);
    TargetGraph targetGraphB = TargetGraphFactory.newInstance(nodeB);
    TargetGraph commonTargetGraph = TargetGraphFactory.newInstance(nodeA, nodeB);

    FileHashLoader fileHashLoader =
        new FakeFileHashCache(
            ImmutableMap.of(
                projectFilesystem.resolve("foo/FooLib.java"), HashCode.fromString("abcdef"),
                projectFilesystem.resolve("bar/BarLib.java"), HashCode.fromString("123456")));

    Map<BuildTarget, HashCode> resultsA =
        new TargetGraphHashing(
                eventBus,
                targetGraphA,
                fileHashLoader,
                ImmutableList.of(nodeA),
                MoreExecutors.newDirectExecutorService(),
                ruleKeyConfiguration,
                targetNodeRawAttributesProvider,
                Hashing.murmur3_128())
            .hashTargetGraph();

    Map<BuildTarget, HashCode> resultsB =
        new TargetGraphHashing(
                eventBus,
                targetGraphB,
                fileHashLoader,
                ImmutableList.of(nodeB),
                MoreExecutors.newDirectExecutorService(),
                ruleKeyConfiguration,
                targetNodeRawAttributesProvider,
                Hashing.murmur3_128())
            .hashTargetGraph();

    Map<BuildTarget, HashCode> commonResults =
        new TargetGraphHashing(
                eventBus,
                commonTargetGraph,
                fileHashLoader,
                ImmutableList.of(nodeA, nodeB),
                MoreExecutors.newDirectExecutorService(),
                ruleKeyConfiguration,
                targetNodeRawAttributesProvider,
                Hashing.murmur3_128())
            .hashTargetGraph();

    assertThat(resultsA, aMapWithSize(1));
    assertThat(resultsA, hasKey(nodeA.getBuildTarget()));
    assertThat(resultsB, aMapWithSize(1));
    assertThat(resultsB, hasKey(nodeB.getBuildTarget()));
    assertThat(commonResults, aMapWithSize(2));
    assertThat(commonResults, hasKey(nodeA.getBuildTarget()));
    assertThat(commonResults, hasKey(nodeB.getBuildTarget()));
    assertThat(
        resultsA.get(nodeA.getBuildTarget()), equalTo(commonResults.get(nodeA.getBuildTarget())));
    assertThat(
        resultsB.get(nodeB.getBuildTarget()), equalTo(commonResults.get(nodeB.getBuildTarget())));
  }

  private TargetGraph createGraphWithANodeAndADep(
      BuildTarget nodeTarget, BuildTarget depTarget, Path depSrc) {
    TargetNode<?> dep = createJavaLibraryTargetNodeWithSrcs(depTarget, ImmutableSet.of(depSrc));
    TargetNode<?> node =
        createJavaLibraryTargetNodeWithSrcs(
            nodeTarget, ImmutableSet.of(Paths.get("foo/FooLib.java")), dep);
    return TargetGraphFactory.newInstance(node, dep);
  }

  @Test
  public void hashChangesForDependentNodeWhenDepsChange() throws InterruptedException {
    BuildTarget nodeTarget = BuildTargetFactory.newInstance("//foo:lib");
    BuildTarget depTarget = BuildTargetFactory.newInstance("//dep:lib");

    TargetGraph targetGraphA =
        createGraphWithANodeAndADep(nodeTarget, depTarget, Paths.get("dep/DepLib1.java"));

    TargetGraph targetGraphB =
        createGraphWithANodeAndADep(nodeTarget, depTarget, Paths.get("dep/DepLib2.java"));

    FileHashLoader fileHashLoader =
        new FakeFileHashCache(
            ImmutableMap.of(
                projectFilesystem.resolve("foo/FooLib.java"), HashCode.fromString("abcdef"),
                projectFilesystem.resolve("dep/DepLib1.java"), HashCode.fromString("123456"),
                projectFilesystem.resolve("dep/DepLib2.java"), HashCode.fromString("123457")));

    Map<BuildTarget, HashCode> resultA =
        new TargetGraphHashing(
                eventBus,
                targetGraphA,
                fileHashLoader,
                ImmutableList.of(targetGraphA.get(nodeTarget)),
                MoreExecutors.newDirectExecutorService(),
                ruleKeyConfiguration,
                targetNodeRawAttributesProvider,
                Hashing.murmur3_128())
            .hashTargetGraph();

    Map<BuildTarget, HashCode> resultB =
        new TargetGraphHashing(
                eventBus,
                targetGraphB,
                fileHashLoader,
                ImmutableList.of(targetGraphB.get(nodeTarget)),
                MoreExecutors.newDirectExecutorService(),
                ruleKeyConfiguration,
                targetNodeRawAttributesProvider,
                Hashing.murmur3_128())
            .hashTargetGraph();

    assertThat(resultA, aMapWithSize(2));
    assertThat(resultA, hasKey(nodeTarget));
    assertThat(resultA, hasKey(depTarget));
    assertThat(resultB, aMapWithSize(2));
    assertThat(resultB, hasKey(nodeTarget));
    assertThat(resultB, hasKey(depTarget));
    assertThat(resultA.get(nodeTarget), not(equalTo(resultB.get(nodeTarget))));
    assertThat(resultA.get(depTarget), not(equalTo(resultB.get(depTarget))));
  }

  @Test
  public void hashingSourceThrowsError() throws Exception {
    TargetNode<?> node =
        createJavaLibraryTargetNodeWithSrcs(
            BuildTargetFactory.newInstance("//foo:lib"),
            ImmutableSet.of(Paths.get("foo/FooLib.java")));
    TargetGraph targetGraph = TargetGraphFactory.newInstance(node);

    FileHashLoader cache = new FakeFileHashCache(ImmutableMap.of());

    thrown.expectMessage("Error reading path foo/FooLib.java for rule //foo:lib");

    new TargetGraphHashing(
            eventBus,
            targetGraph,
            cache,
            ImmutableList.of(node),
            MoreExecutors.newDirectExecutorService(),
            ruleKeyConfiguration,
            targetNodeRawAttributesProvider,
            Hashing.murmur3_128())
        .hashTargetGraph();
  }

  private TargetNode<?> createJavaLibraryTargetNodeWithSrcs(
      BuildTarget buildTarget, ImmutableSet<Path> srcs, TargetNode<?>... deps) {
    JavaLibraryBuilder targetNodeBuilder =
        JavaLibraryBuilder.createBuilder(buildTarget, projectFilesystem);
    for (TargetNode<?> dep : deps) {
      targetNodeBuilder.addDep(dep.getBuildTarget());
    }
    for (Path src : srcs) {
      targetNodeBuilder.addSrc(src);
    }
    return targetNodeBuilder.build();
  }
}
