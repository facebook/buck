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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.FilesystemBackedBuildFileTree;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.watchman.WatchmanFactory;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.BuildFileManifestFactory;
import com.facebook.buck.parser.api.ForwardingProjectBuildFileParserDecorator;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ParsePipelineTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException expectedException = ExpectedException.none();

  BuckEventBus eventBus;

  @Before
  public void setUp() {
    eventBus = BuckEventBusForTests.newInstance();
  }

  @Test
  public void testIgnoredDirsErr() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "ignored_dirs_err", tmp);
    workspace.setUp();

    // enforce creation of targetNode's
    ProcessResult processResult = workspace.runBuckBuild("//libraries/path-to-ignore:ignored-lib");
    processResult.assertFailure();
    assertThat(
        processResult.getStderr(),
        containsString(" cannot be built because it is defined in an ignored directory."));
  }

  private <T> void waitForAll(Iterable<T> items, Predicate<T> predicate)
      throws InterruptedException {
    boolean allThere = false;
    for (int i = 0; i < 50; ++i) {
      allThere |= Streams.stream(items).allMatch(predicate);
      if (allThere) {
        break;
      }
      Thread.sleep(100);
    }
    assertThat(allThere, is(true));
  }

  @Test
  public void speculativeDepsTraversal() throws Exception {
    Fixture fixture = createMultiThreadedFixture("pipeline_test");
    Cell cell = fixture.getCell();
    TargetNode<?> libTargetNode =
        fixture
            .getTargetNodeParsePipeline()
            .getNode(
                cell, BuildTargetFactory.newInstance(cell.getFilesystem().getRootPath(), "//:lib"));

    waitForAll(
        libTargetNode.getBuildDeps(),
        dep ->
            fixture.getTargetNodeParsePipelineCache().lookupComputedNode(cell, dep, eventBus)
                != null);
    fixture.close();
  }

  @Test
  public void speculativeDepsTraversalWhenGettingAllNodes() throws Exception {
    Fixture fixture = createMultiThreadedFixture("pipeline_test");
    Cell cell = fixture.getCell();
    ImmutableList<TargetNode<?>> libTargetNodes =
        fixture
            .getTargetNodeParsePipeline()
            .getAllNodes(cell, fixture.getCell().getFilesystem().resolve("BUCK"));
    FluentIterable<BuildTarget> allDeps =
        FluentIterable.from(libTargetNodes).transformAndConcat(input -> input.getBuildDeps());
    waitForAll(
        allDeps,
        dep ->
            fixture.getTargetNodeParsePipelineCache().lookupComputedNode(cell, dep, eventBus)
                != null);
    fixture.close();
  }

  @Test
  public void missingTarget() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      expectedException.expect(NoSuchBuildTargetException.class);
      expectedException.expectMessage(
          "The rule //:notthere could not be found.\nPlease check the spelling and whether it exists in");
      fixture
          .getTargetNodeParsePipeline()
          .getNode(
              cell,
              BuildTargetFactory.newInstance(cell.getFilesystem().getRootPath(), "//:notthere"));
    }
  }

  @Test
  public void missingBuildFile() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      expectedException.expect(BuildFileParseException.class);
      expectedException.expectMessage(
          stringContainsInOrder("Buck wasn't able to parse", "No such file or directory"));
      fixture
          .getTargetNodeParsePipeline()
          .getAllNodes(cell, cell.getFilesystem().resolve("no/such/file/BUCK"));
    }
  }

  @Test
  public void missingBuildFileRaw() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      expectedException.expect(BuildFileParseException.class);
      expectedException.expectMessage(
          stringContainsInOrder("Buck wasn't able to parse", "No such file or directory"));
      fixture
          .getBuildFileRawNodeParsePipeline()
          .getAllNodes(cell, cell.getFilesystem().resolve("no/such/file/BUCK"));
    }
  }

  @Test
  public void badDependency() throws Exception {
    try (Fixture fixture = createMultiThreadedFixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      fixture
          .getTargetNodeParsePipeline()
          .getNode(
              cell, BuildTargetFactory.newInstance(cell.getFilesystem().getRootPath(), "//:base"));
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
              cell,
              rootBuildFilePath,
              BuildFileManifestFactory.create(
                  ImmutableMap.of("bar", ImmutableMap.of("name", "bar"))),
              eventBus);
      expectedException.expect(IllegalStateException.class);
      expectedException.expectMessage("malformed raw data");
      fixture.getTargetNodeParsePipeline().getAllNodes(cell, rootBuildFilePath);
    }
  }

  @Test
  public void exceptionOnSwappedRawNodesInGetAllTargetNodes() throws Exception {
    try (Fixture fixture = createSynchronousExecutionFixture("pipeline_test")) {
      Cell cell = fixture.getCell();
      Path rootBuildFilePath = cell.getFilesystem().resolve("BUCK");
      Path aBuildFilePath = cell.getFilesystem().resolve("a/BUCK");
      fixture.getTargetNodeParsePipeline().getAllNodes(cell, rootBuildFilePath);
      Optional<BuildFileManifest> rootRawNodes =
          fixture
              .getRawNodeParsePipelineCache()
              .lookupComputedNode(cell, rootBuildFilePath, eventBus);
      fixture
          .getRawNodeParsePipelineCache()
          .putComputedNodeIfNotPresent(cell, aBuildFilePath, rootRawNodes.get(), eventBus);
      expectedException.expect(IllegalStateException.class);
      expectedException.expectMessage(
          "Raw data claims to come from [], but we tried rooting it at [a].");
      fixture.getTargetNodeParsePipeline().getAllNodes(cell, aBuildFilePath);
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
      fixture.getTargetNodeParsePipeline().getAllNodes(cell, rootBuildFilePath);
      Optional<BuildFileManifest> rootRawNodes =
          fixture
              .getRawNodeParsePipelineCache()
              .lookupComputedNode(cell, rootBuildFilePath, eventBus);
      fixture
          .getRawNodeParsePipelineCache()
          .putComputedNodeIfNotPresent(cell, aBuildFilePath, rootRawNodes.get(), eventBus);
      expectedException.expect(IllegalStateException.class);
      expectedException.expectMessage(
          "Raw data claims to come from [], but we tried rooting it at [a].");
      fixture
          .getTargetNodeParsePipeline()
          .getNode(
              cell, BuildTargetFactory.newInstance(cell.getFilesystem().getRootPath(), "//a:lib"));
    }
  }

  @Test
  public void recoversAfterSyntaxError() throws Exception {
    try (Fixture fixture = createSynchronousExecutionFixture("syntax_error")) {
      Cell cell = fixture.getCell();
      try {
        fixture
            .getTargetNodeParsePipeline()
            .getNode(
                cell,
                BuildTargetFactory.newInstance(
                    cell.getFilesystem().getRootPath(), "//error:error"));
        Assert.fail("Expected BuildFileParseException");
      } catch (BuildFileParseException e) {
        assertThat(e.getMessage(), containsString("crash!"));
      }

      fixture
          .getTargetNodeParsePipeline()
          .getNode(
              cell,
              BuildTargetFactory.newInstance(
                  cell.getFilesystem().getRootPath(), "//correct:correct"));
    }
  }

  private static class TypedParsePipelineCache<K, V> implements PipelineNodeCache.Cache<K, V> {
    private final Map<K, V> nodeMap = new HashMap<>();

    @Override
    public synchronized Optional<V> lookupComputedNode(Cell cell, K key, BuckEventBus eventBus) {
      return Optional.ofNullable(nodeMap.get(key));
    }

    @Override
    public synchronized V putComputedNodeIfNotPresent(
        Cell cell, K key, V value, BuckEventBus eventBus) {
      if (!nodeMap.containsKey(key)) {
        nodeMap.put(key, value);
      }
      return nodeMap.get(key);
    }
  }

  private Fixture createMultiThreadedFixture(String scenario) throws Exception {
    return new Fixture(
        scenario,
        MoreExecutors.listeningDecorator(
            MostExecutors.newMultiThreadExecutor("ParsePipelineTest", 4)),
        SpeculativeParsing.ENABLED);
  }

  // Use this method to make sure the Pipeline doesn't execute stuff on another thread, useful
  // if you're poking at the cache state directly.
  private Fixture createSynchronousExecutionFixture(String scenario) throws Exception {
    return new Fixture(
        scenario, MoreExecutors.newDirectExecutorService(), SpeculativeParsing.DISABLED);
  }

  private class Fixture implements AutoCloseable {

    private final ProjectWorkspace workspace;
    private final BuckEventBus eventBus;
    private final TestConsole console;
    private final TargetNodeParsePipeline targetNodeParsePipeline;
    private final BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline;
    private final ProjectBuildFileParserPool projectBuildFileParserPool;
    private final Cell cell;
    private final TypedParsePipelineCache<BuildTarget, TargetNode<?>> targetNodeParsePipelineCache;
    private final TypedParsePipelineCache<Path, BuildFileManifest> rawNodeParsePipelineCache;
    private final ListeningExecutorService executorService;
    private final Set<CloseRecordingProjectBuildFileParserDecorator> projectBuildFileParsers;

    private ThrowingCloseableMemoizedSupplier<ManifestService, IOException> getManifestSupplier() {
      return ThrowingCloseableMemoizedSupplier.of(() -> null, ManifestService::close);
    }

    public Fixture(
        String scenario,
        ListeningExecutorService executorService,
        SpeculativeParsing speculativeParsing)
        throws Exception {
      this.workspace = TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
      this.eventBus = BuckEventBusForTests.newInstance();
      this.console = new TestConsole();
      this.executorService = executorService;
      this.projectBuildFileParsers = new HashSet<>();
      this.workspace.setUp();

      this.cell = this.workspace.asCell();
      this.targetNodeParsePipelineCache = new TypedParsePipelineCache<>();
      this.rawNodeParsePipelineCache = new TypedParsePipelineCache<>();
      TypeCoercerFactory coercerFactory = new DefaultTypeCoercerFactory();
      ConstructorArgMarshaller constructorArgMarshaller =
          new DefaultConstructorArgMarshaller(coercerFactory);

      projectBuildFileParserPool =
          new ProjectBuildFileParserPool(
              4, // max parsers
              (buckEventBus, input, watchman) -> {
                CloseRecordingProjectBuildFileParserDecorator buildFileParser =
                    new CloseRecordingProjectBuildFileParserDecorator(
                        new DefaultProjectBuildFileParserFactory(
                                coercerFactory,
                                console,
                                new ParserPythonInterpreterProvider(
                                    input.getBuckConfig(), new ExecutableFinder()),
                                TestKnownRuleTypesProvider.create(
                                    BuckPluginManagerFactory.createPluginManager()),
                                getManifestSupplier(),
                                new FakeFileHashCache(ImmutableMap.of()))
                            .createBuildFileParser(eventBus, input, watchman));
                synchronized (projectBuildFileParsers) {
                  projectBuildFileParsers.add(buildFileParser);
                }
                return buildFileParser;
              },
              false);
      TargetNodeListener<TargetNode<?>> nodeListener = (buildFile, node) -> {};
      LoadingCache<Cell, BuildFileTree> buildFileTrees =
          CacheBuilder.newBuilder()
              .build(
                  new CacheLoader<Cell, BuildFileTree>() {
                    @Override
                    public BuildFileTree load(Cell cell) {
                      return new FilesystemBackedBuildFileTree(
                          cell.getFilesystem(), cell.getBuildFileName());
                    }
                  });
      buildFileRawNodeParsePipeline =
          new BuildFileRawNodeParsePipeline(
              new PipelineNodeCache<>(rawNodeParsePipelineCache),
              projectBuildFileParserPool,
              executorService,
              eventBus,
              WatchmanFactory.NULL_WATCHMAN);

      BuildTargetRawNodeParsePipeline buildTargetRawNodeParsePipeline =
          new BuildTargetRawNodeParsePipeline(executorService, buildFileRawNodeParsePipeline);

      KnownRuleTypesProvider knownRuleTypesProvider =
          TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager());
      this.targetNodeParsePipeline =
          new TargetNodeParsePipeline(
              this.targetNodeParsePipelineCache,
              DefaultParserTargetNodeFactory.createForParser(
                  knownRuleTypesProvider,
                  constructorArgMarshaller,
                  buildFileTrees,
                  nodeListener,
                  new TargetNodeFactory(coercerFactory),
                  TestRuleKeyConfigurationFactory.create()),
              this.executorService,
              this.eventBus,
              speculativeParsing == SpeculativeParsing.ENABLED,
              buildFileRawNodeParsePipeline,
              buildTargetRawNodeParsePipeline);
    }

    public TargetNodeParsePipeline getTargetNodeParsePipeline() {
      return targetNodeParsePipeline;
    }

    public BuildFileRawNodeParsePipeline getBuildFileRawNodeParsePipeline() {
      return buildFileRawNodeParsePipeline;
    }

    public Cell getCell() {
      return cell;
    }

    public TypedParsePipelineCache<BuildTarget, TargetNode<?>> getTargetNodeParsePipelineCache() {
      return targetNodeParsePipelineCache;
    }

    public TypedParsePipelineCache<Path, BuildFileManifest> getRawNodeParsePipelineCache() {
      return rawNodeParsePipelineCache;
    }

    private void waitForParsersToClose() throws InterruptedException {
      Iterable<CloseRecordingProjectBuildFileParserDecorator> parserSnapshot;
      synchronized (projectBuildFileParsers) {
        parserSnapshot = ImmutableSet.copyOf(projectBuildFileParsers);
      }
      waitForAll(parserSnapshot, CloseRecordingProjectBuildFileParserDecorator::isClosed);
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
        for (CloseRecordingProjectBuildFileParserDecorator parser : projectBuildFileParsers) {
          assertThat(parser.isClosed(), is(true));
        }
      }
    }
  }

  /**
   * Decorates {@link com.facebook.buck.parser.api.ProjectBuildFileParser} to track whether it was
   * closed.
   */
  private static class CloseRecordingProjectBuildFileParserDecorator
      extends ForwardingProjectBuildFileParserDecorator {
    private final AtomicBoolean isClosed;

    private CloseRecordingProjectBuildFileParserDecorator(ProjectBuildFileParser delegate) {
      super(delegate);
      this.isClosed = new AtomicBoolean(false);
    }

    @Override
    public void close() throws BuildFileParseException, InterruptedException, IOException {
      try {
        delegate.close();
      } finally {
        isClosed.set(true);
      }
    }

    public boolean isClosed() {
      return isClosed.get();
    }
  }
}
