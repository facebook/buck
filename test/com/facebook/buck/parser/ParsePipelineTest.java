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

package com.facebook.buck.parser;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.FilesystemBackedBuildFileTree;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ParsePipelineTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void testIgnoredDirsErr() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "ignored_dirs_err", tmp);
    workspace.setUp();

    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        " cannot be built because it is defined in an ignored directory.");
    // enforce creation of targetNode's
    workspace.runBuckBuild("//libraries/path-to-ignore:ignored-lib");
  }

  private <T> void waitForAll(Iterable<T> items, Predicate<T> predicate)
      throws InterruptedException {
    boolean allThere = false;
    for (int i = 0; i < 50; ++i) {
      allThere |= FluentIterable.from(items).allMatch(predicate);
      if (allThere) {
        break;
      }
      Thread.sleep(100);
    }
    assertThat(allThere, is(true));
  }

  @Test
  public void speculativeDepsTraversal() throws Exception {
    final Fixture fixture = createMultiThreadedFixture("pipeline_test");
    final Cell cell = fixture.getCell();
    TargetNode<?, ?> libTargetNode =
        fixture
            .getTargetNodeParsePipeline()
            .getNode(
                cell,
                BuildTargetFactory.newInstance(cell.getFilesystem().getRootPath(), "//:lib"),
                new AtomicLong());

    waitForAll(
        libTargetNode.getBuildDeps(),
        dep -> fixture.getTargetNodeParsePipelineCache().lookupComputedNode(cell, dep) != null);
    fixture.close();
  }

  @Test
  public void speculativeDepsTraversalWhenGettingAllNodes() throws Exception {
    final Fixture fixture = createMultiThreadedFixture("pipeline_test");
    final Cell cell = fixture.getCell();
    ImmutableSet<TargetNode<?, ?>> libTargetNodes =
        fixture
            .getTargetNodeParsePipeline()
            .getAllNodes(cell, fixture.getCell().getFilesystem().resolve("BUCK"), new AtomicLong());
    FluentIterable<BuildTarget> allDeps =
        FluentIterable.from(libTargetNodes)
            .transformAndConcat(
                new Function<TargetNode<?, ?>, Iterable<BuildTarget>>() {
                  @Override
                  public Iterable<BuildTarget> apply(TargetNode<?, ?> input) {
                    return input.getBuildDeps();
                  }
                });
    waitForAll(
        allDeps,
        dep -> fixture.getTargetNodeParsePipelineCache().lookupComputedNode(cell, dep) != null);
    fixture.close();
  }

  @Test
  public void missingTarget() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      expectedException.expect(NoSuchBuildTargetException.class);
      expectedException.expectMessage("No rule found when resolving target //:notthere");
      fixture
          .getTargetNodeParsePipeline()
          .getNode(
              cell,
              BuildTargetFactory.newInstance(cell.getFilesystem().getRootPath(), "//:notthere"),
              new AtomicLong());
    }
  }

  @Test
  public void missingBuildFile() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      expectedException.expect(BuildFileParseException.class);
      expectedException.expectMessage(
          stringContainsInOrder("Parse error for build file", "No such file or directory"));
      fixture
          .getTargetNodeParsePipeline()
          .getAllNodes(cell, cell.getFilesystem().resolve("no/such/file/BUCK"), new AtomicLong());
    }
  }

  @Test
  public void missingBuildFileRaw() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      expectedException.expect(BuildFileParseException.class);
      expectedException.expectMessage(
          stringContainsInOrder("Parse error for build file", "No such file or directory"));
      fixture
          .getRawNodeParsePipeline()
          .getAllNodes(cell, cell.getFilesystem().resolve("no/such/file/BUCK"), new AtomicLong());
    }
  }

  @Test
  public void badDependency() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      fixture
          .getTargetNodeParsePipeline()
          .getNode(
              cell,
              BuildTargetFactory.newInstance(cell.getFilesystem().getRootPath(), "//:base"),
              new AtomicLong());
    }
  }

  @Test
  public void exceptionOnMalformedRawNode() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("pipeline_test")) {
      Cell cell = fixture.getCell();
      Path rootBuildFilePath = cell.getFilesystem().resolve("BUCK");
      fixture
          .getRawNodeParsePipelineCache()
          .putComputedNodeIfNotPresent(
              cell, rootBuildFilePath, ImmutableSet.of(ImmutableMap.of("name", (Object) "bar")));
      expectedException.expect(IllegalStateException.class);
      expectedException.expectMessage("malformed raw data");
      fixture.getTargetNodeParsePipeline().getAllNodes(cell, rootBuildFilePath, new AtomicLong());
    }
  }

  @Test
  public void exceptionOnSwappedRawNodesInGetAllTargetNodes() throws Exception {
    try (Fixture fixture = createSynchronousExecutionFixture("pipeline_test")) {
      Cell cell = fixture.getCell();
      Path rootBuildFilePath = cell.getFilesystem().resolve("BUCK");
      Path aBuildFilePath = cell.getFilesystem().resolve("a/BUCK");
      fixture.getTargetNodeParsePipeline().getAllNodes(cell, rootBuildFilePath, new AtomicLong());
      Optional<ImmutableSet<Map<String, Object>>> rootRawNodes =
          fixture.getRawNodeParsePipelineCache().lookupComputedNode(cell, rootBuildFilePath);
      fixture
          .getRawNodeParsePipelineCache()
          .putComputedNodeIfNotPresent(cell, aBuildFilePath, rootRawNodes.get());
      expectedException.expect(IllegalStateException.class);
      expectedException.expectMessage(
          "Raw data claims to come from [], but we tried rooting it at [a].");
      fixture.getTargetNodeParsePipeline().getAllNodes(cell, aBuildFilePath, new AtomicLong());
    }
  }

  @Test
  public void exceptionOnSwappedRawNodesInGetTargetNode() throws Exception {
    // The difference between this test and exceptionOnSwappedRawNodesInGetAllTargetNodes is that
    // the two methods follow different code paths to determine what the BuildTarget for the result
    // should be and we want to test both of them.
    try (Fixture fixture = createSynchronousExecutionFixture("pipeline_test")) {
      Cell cell = fixture.getCell();
      Path rootBuildFilePath = cell.getFilesystem().resolve("BUCK");
      Path aBuildFilePath = cell.getFilesystem().resolve("a/BUCK");
      fixture.getTargetNodeParsePipeline().getAllNodes(cell, rootBuildFilePath, new AtomicLong());
      Optional<ImmutableSet<Map<String, Object>>> rootRawNodes =
          fixture.getRawNodeParsePipelineCache().lookupComputedNode(cell, rootBuildFilePath);
      fixture
          .getRawNodeParsePipelineCache()
          .putComputedNodeIfNotPresent(cell, aBuildFilePath, rootRawNodes.get());
      expectedException.expect(IllegalStateException.class);
      expectedException.expectMessage(
          "Raw data claims to come from [], but we tried rooting it at [a].");
      fixture
          .getTargetNodeParsePipeline()
          .getNode(
              cell,
              BuildTargetFactory.newInstance(cell.getFilesystem().getRootPath(), "//a:lib"),
              new AtomicLong());
    }
  }

  @Test
  public void recoversAfterSyntaxError() throws Exception {
    try (Fixture fixture = createSynchronousExecutionFixture("syntax_error")) {
      final Cell cell = fixture.getCell();
      try {
        fixture
            .getTargetNodeParsePipeline()
            .getNode(
                cell,
                BuildTargetFactory.newInstance(cell.getFilesystem().getRootPath(), "//error:error"),
                new AtomicLong());
        Assert.fail("Expected BuildFileParseException");
      } catch (BuildFileParseException e) {
        assertThat(e.getMessage(), containsString("crash!"));
      }

      fixture
          .getTargetNodeParsePipeline()
          .getNode(
              cell,
              BuildTargetFactory.newInstance(
                  cell.getFilesystem().getRootPath(), "//correct:correct"),
              new AtomicLong());
    }
  }

  private static class TypedParsePipelineCache<K, V> implements PipelineNodeCache.Cache<K, V> {
    private final Map<K, V> nodeMap = new HashMap<>();

    @Override
    public synchronized Optional<V> lookupComputedNode(Cell cell, K key) {
      return Optional.ofNullable(nodeMap.get(key));
    }

    @Override
    public synchronized V putComputedNodeIfNotPresent(Cell cell, K key, V value) {
      if (!nodeMap.containsKey(key)) {
        nodeMap.put(key, value);
      }
      return nodeMap.get(key);
    }
  }

  private static class RawNodeParsePipelineCache
      extends TypedParsePipelineCache<Path, ImmutableSet<Map<String, Object>>> {

    @Override
    public synchronized ImmutableSet<Map<String, Object>> putComputedNodeIfNotPresent(
        Cell cell, Path buildFile, ImmutableSet<Map<String, Object>> rawNodes) {
      // Strip meta entries.
      rawNodes =
          ImmutableSet.copyOf(Iterables.filter(rawNodes, input -> input.containsKey("name")));
      return super.putComputedNodeIfNotPresent(cell, buildFile, rawNodes);
    }
  }

  private Fixture createMultiThreadedFixture(String scenario) throws Exception {
    return new Fixture(
        scenario,
        com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
            MostExecutors.newMultiThreadExecutor("ParsePipelineTest", 4)),
        SpeculativeParsing.of(true));
  }

  // Use this method to make sure the Pipeline doesn't execute stuff on another thread, useful
  // if you're poking at the cache state directly.
  private Fixture createSynchronousExecutionFixture(String scenario) throws Exception {
    return new Fixture(
        scenario,
        com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService(),
        SpeculativeParsing.of(false));
  }

  private class Fixture implements AutoCloseable {

    private final ProjectWorkspace workspace;
    private final BuckEventBus eventBus;
    private final TestConsole console;
    private final TargetNodeParsePipeline targetNodeParsePipeline;
    private final RawNodeParsePipeline rawNodeParsePipeline;
    private final ProjectBuildFileParserPool projectBuildFileParserPool;
    private final Cell cell;
    private final TypedParsePipelineCache<BuildTarget, TargetNode<?, ?>>
        targetNodeParsePipelineCache;
    private final RawNodeParsePipelineCache rawNodeParsePipelineCache;
    private final ListeningExecutorService executorService;
    private final Set<ProjectBuildFileParser> projectBuildFileParsers;

    public Fixture(
        String scenario,
        ListeningExecutorService executorService,
        SpeculativeParsing speculativeParsing)
        throws Exception {
      this.workspace = TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
      this.eventBus = BuckEventBusFactory.newInstance();
      this.console = new TestConsole();
      this.executorService = executorService;
      this.projectBuildFileParsers = new HashSet<>();
      this.workspace.setUp();

      this.cell = this.workspace.asCell();
      this.targetNodeParsePipelineCache = new TypedParsePipelineCache<>();
      this.rawNodeParsePipelineCache = new RawNodeParsePipelineCache();
      final TypeCoercerFactory coercerFactory = new DefaultTypeCoercerFactory();
      final ConstructorArgMarshaller constructorArgMarshaller =
          new ConstructorArgMarshaller(coercerFactory);

      projectBuildFileParserPool =
          new ProjectBuildFileParserPool(
              4, // max parsers
              input -> {
                ProjectBuildFileParser buildFileParser =
                    input.createBuildFileParser(coercerFactory, console, eventBus);
                synchronized (projectBuildFileParsers) {
                  projectBuildFileParsers.add(buildFileParser);
                }
                return buildFileParser;
              });
      final TargetNodeListener<TargetNode<?, ?>> nodeListener = (buildFile, node) -> {};
      LoadingCache<Cell, BuildFileTree> buildFileTrees =
          CacheBuilder.newBuilder()
              .build(
                  new CacheLoader<Cell, BuildFileTree>() {
                    @Override
                    public BuildFileTree load(Cell cell) throws Exception {
                      return new FilesystemBackedBuildFileTree(
                          cell.getFilesystem(), cell.getBuildFileName());
                    }
                  });
      this.rawNodeParsePipeline =
          new RawNodeParsePipeline(
              this.rawNodeParsePipelineCache, this.projectBuildFileParserPool, executorService);
      this.targetNodeParsePipeline =
          new TargetNodeParsePipeline(
              this.targetNodeParsePipelineCache,
              DefaultParserTargetNodeFactory.createForParser(
                  constructorArgMarshaller,
                  buildFileTrees,
                  nodeListener,
                  new TargetNodeFactory(coercerFactory)),
              this.executorService,
              this.eventBus,
              speculativeParsing.value(),
              this.rawNodeParsePipeline);
    }

    public TargetNodeParsePipeline getTargetNodeParsePipeline() {
      return targetNodeParsePipeline;
    }

    public RawNodeParsePipeline getRawNodeParsePipeline() {
      return rawNodeParsePipeline;
    }

    public Cell getCell() {
      return cell;
    }

    public TypedParsePipelineCache<BuildTarget, TargetNode<?, ?>>
        getTargetNodeParsePipelineCache() {
      return targetNodeParsePipelineCache;
    }

    public RawNodeParsePipelineCache getRawNodeParsePipelineCache() {
      return rawNodeParsePipelineCache;
    }

    private void waitForParsersToClose() throws InterruptedException {
      Iterable<ProjectBuildFileParser> parserSnapshot;
      synchronized (projectBuildFileParsers) {
        parserSnapshot = ImmutableSet.copyOf(projectBuildFileParsers);
      }
      waitForAll(parserSnapshot, ProjectBuildFileParser::isClosed);
    }

    @Override
    public void close() throws Exception {
      targetNodeParsePipeline.close();
      projectBuildFileParserPool.close();
      // We wait for the parsers to shut down gracefully, they do this on a separate threadpool.
      waitForParsersToClose();
      executorService.shutdown();
      assertThat(executorService.awaitTermination(5, TimeUnit.SECONDS), is(true));
      synchronized (projectBuildFileParsers) {
        for (ProjectBuildFileParser parser : projectBuildFileParsers) {
          assertThat(parser.isClosed(), is(true));
        }
      }
    }
  }
}
