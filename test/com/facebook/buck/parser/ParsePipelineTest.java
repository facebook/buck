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

import static org.junit.Assert.assertThat;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.concurrent.MoreExecutors;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;


public class ParsePipelineTest {
  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void testIgnoredDirsErr() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "ignored_dirs_err",
        tmp);
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
    assertThat(allThere, Matchers.is(true));
  }

  @Test
  public void speculativeDepsTraversal() throws Exception {
    final Fixture fixture = new Fixture("pipeline_test");
    final Cell cell = fixture.getCell();
    TargetNode<?> libTargetNode = fixture.getParsePipeline().getTargetNode(
        cell,
        BuildTargetFactory.newInstance(cell.getFilesystem(), "//:lib"));

    waitForAll(
        libTargetNode.getDeps(),
        new Predicate<BuildTarget>() {
          @Override
          public boolean apply(BuildTarget dep) {
            return fixture.getCache().lookupTargetNode(cell, dep) != null;
          }
        });
    fixture.close();
  }

  @Test
  public void speculativeDepsTraversalWhenGettingAllNodes() throws Exception {
    final Fixture fixture = new Fixture("pipeline_test");
    final Cell cell = fixture.getCell();
    ImmutableSet<TargetNode<?>> libTargetNodes = fixture.getParsePipeline().getAllTargetNodes(
        cell,
        fixture.getCell().getFilesystem().resolve("BUCK"));
    FluentIterable<BuildTarget> allDeps = FluentIterable.from(libTargetNodes)
        .transformAndConcat(
            new Function<TargetNode<?>, Iterable<BuildTarget>>() {
              @Override
              public Iterable<BuildTarget> apply(TargetNode<?> input) {
                return input.getDeps();
              }
            });
    waitForAll(
        allDeps,
        new Predicate<BuildTarget>() {
          @Override
          public boolean apply(BuildTarget dep) {
            return fixture.getCache().lookupTargetNode(cell, dep) != null;
          }
        });
    fixture.close();
  }

  @Test
  public void missingTarget() throws Exception {
    try (Fixture fixture = new Fixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      expectedException.expect(HumanReadableException.class);
      expectedException.expectMessage("No rule found when resolving target //:notthere");
      fixture.getParsePipeline().getTargetNode(
          cell,
          BuildTargetFactory.newInstance(cell.getFilesystem(), "//:notthere"));
    }
  }

  @Test
  public void missingBuildFile() throws Exception {
    try (Fixture fixture = new Fixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      expectedException.expect(BuildFileParseException.class);
      expectedException.expectMessage(
          Matchers.stringContainsInOrder(
              "Parse error for build file",
              "No such file or directory"));
      fixture.getParsePipeline().getAllTargetNodes(
          cell,
          cell.getFilesystem().resolve("no/such/file/BUCK"));
    }
  }

  @Test
  public void missingBuildFileRaw() throws Exception {
    try (Fixture fixture = new Fixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      expectedException.expect(BuildFileParseException.class);
      expectedException.expectMessage(
          Matchers.stringContainsInOrder(
              "Parse error for build file",
              "No such file or directory"));
      fixture.getParsePipeline().getRawNodes(
          cell,
          cell.getFilesystem().resolve("no/such/file/BUCK"));
    }
  }

  @Test
  public void badDependency() throws Exception {
    try (Fixture fixture = new Fixture("parse_rule_with_bad_dependency")) {
      Cell cell = fixture.getCell();
      fixture.getParsePipeline().getTargetNode(
          cell,
          BuildTargetFactory.newInstance(cell.getFilesystem(), "//:base"));
    }
  }

  @Test
  public void exceptionOnMalformedRawNode() throws Exception {
    try (Fixture fixture = new Fixture("pipeline_test")) {
      Cell cell = fixture.getCell();
      Path rootBuildFilePath = cell.getFilesystem().resolve("BUCK");
      fixture.getCache().putRawNodesIfNotPresentAndStripMetaEntries(
          cell,
          rootBuildFilePath,
          ImmutableList.<Map<String, Object>>of(
              ImmutableMap.of("name", (Object) "bar")));
      expectedException.expect(IllegalStateException.class);
      expectedException.expectMessage("malformed raw data");
      fixture.getParsePipeline().getAllTargetNodes(
          cell,
          rootBuildFilePath);
    }
  }

  @Test
  public void exceptionOnSwappedRawNodesInGetAllTargetNodes() throws Exception {
    try (Fixture fixture = new Fixture("pipeline_test")) {
      Cell cell = fixture.getCell();
      Path rootBuildFilePath = cell.getFilesystem().resolve("BUCK");
      Path aBuildFilePath = cell.getFilesystem().resolve("a/BUCK");
      fixture.getParsePipeline().getAllTargetNodes(
          cell,
          rootBuildFilePath);
      Optional<ImmutableList<Map<String, Object>>> rootRawNodes = fixture.getCache().lookupRawNodes(
          cell,
          rootBuildFilePath);
      fixture.getCache().putRawNodesIfNotPresentAndStripMetaEntries(
          cell,
          aBuildFilePath,
          rootRawNodes.get());
      expectedException.expect(IllegalStateException.class);
      expectedException.expectMessage(
          "Raw data claims to come from [], but we tried rooting it at [a].");
      fixture.getParsePipeline().getAllTargetNodes(
          cell,
          aBuildFilePath);
    }
  }

  @Test
  public void exceptionOnSwappedRawNodesInGetTargetNode() throws Exception {
    // The difference between this test and exceptionOnSwappedRawNodesInGetAllTargetNodes is that
    // the two methods follow different code paths to determine what the BuildTarget for the result
    // should be and we want to test both of them.
    try (Fixture fixture = new Fixture("pipeline_test")) {
      Cell cell = fixture.getCell();
      Path rootBuildFilePath = cell.getFilesystem().resolve("BUCK");
      Path aBuildFilePath = cell.getFilesystem().resolve("a/BUCK");
      fixture.getParsePipeline().getAllTargetNodes(
          cell,
          rootBuildFilePath);
      Optional<ImmutableList<Map<String, Object>>> rootRawNodes = fixture.getCache().lookupRawNodes(
          cell,
          rootBuildFilePath);
      fixture.getCache().putRawNodesIfNotPresentAndStripMetaEntries(
          cell,
          aBuildFilePath,
          rootRawNodes.get());
      expectedException.expect(IllegalStateException.class);
      expectedException.expectMessage(
          "Raw data claims to come from [], but we tried rooting it at [a].");
      fixture.getParsePipeline().getTargetNode(
          cell,
          BuildTargetFactory.newInstance(cell.getFilesystem(), "//a:lib"));
    }
  }

  private static class ParsePipelineCache implements ParsePipeline.Cache {
    private final Map<BuildTarget, TargetNode<?>> targetNodeMap = new HashMap<>();
    private final Map<Path, ImmutableList<Map<String, Object>>> rawNodeMap = new HashMap<>();

    @Override
    public synchronized Optional<TargetNode<?>> lookupTargetNode(
        Cell cell, BuildTarget target) {
      return Optional.<TargetNode<?>>fromNullable(targetNodeMap.get(target));
    }

    @Override
    public synchronized TargetNode<?> putTargetNodeIfNotPresent(
        Cell cell, BuildTarget target, TargetNode<?> targetNode) {
      if (!targetNodeMap.containsKey(target)) {
        targetNodeMap.put(target, targetNode);
      }
      return targetNodeMap.get(target);
    }

    @Override
    public synchronized Optional<ImmutableList<Map<String, Object>>> lookupRawNodes(
        Cell cell, Path buildFile) {
      return Optional.fromNullable(rawNodeMap.get(buildFile));
    }

    @Override
    public synchronized ImmutableList<Map<String, Object>>
    putRawNodesIfNotPresentAndStripMetaEntries(
        Cell cell,
        Path buildFile,
        ImmutableList<Map<String, Object>> rawNodes) {
      // Strip meta entries.
      rawNodes = FluentIterable.from(rawNodes)
          .filter(
              new Predicate<Map<String, Object>>() {
                @Override
                public boolean apply(Map<String, Object> input) {
                  return input.containsKey("name");
                }
              })
          .toList();
      if (!rawNodeMap.containsKey(buildFile)) {
        rawNodeMap.put(buildFile, rawNodes);
      }
      return rawNodeMap.get(buildFile);
    }
  }

  private class Fixture implements AutoCloseable {

    private ProjectWorkspace workspace;
    private BuckEventBus eventBus;
    private TestConsole console;
    private ParsePipeline parsePipeline;
    private ProjectBuildFileParserPool projectBuildFileParserPool;
    private Cell cell;
    private ParsePipelineCache cache;
    private ListeningExecutorService executorService;
    private Set<ProjectBuildFileParser> projectBuildFileParsers;

    public Fixture(String scenario) throws Exception {
      this.workspace = TestDataHelper.createProjectWorkspaceForScenario(
          this,
          scenario,
          tmp);
      this.eventBus = BuckEventBusFactory.newInstance();
      this.console = new TestConsole();
      this.executorService = com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
          MoreExecutors.newMultiThreadExecutor("ParsePipelineTest", 4));
      this.projectBuildFileParsers = new HashSet<>();
      this.workspace.setUp();

      this.cell = this.workspace.asCell();
      this.cache = new ParsePipelineCache();
      final TypeCoercerFactory coercerFactory = new DefaultTypeCoercerFactory(
          ObjectMappers.newDefaultInstance());
      final ConstructorArgMarshaller constructorArgMarshaller =
          new ConstructorArgMarshaller(coercerFactory);

      projectBuildFileParserPool = new ProjectBuildFileParserPool(
          4, // max parsers
          new Function<Cell, ProjectBuildFileParser>() {
            @Override
            public ProjectBuildFileParser apply(Cell input) {
              ProjectBuildFileParser buildFileParser = input.createBuildFileParser(
                  constructorArgMarshaller,
                  console,
                  eventBus);
              synchronized (projectBuildFileParsers) {
                projectBuildFileParsers.add(buildFileParser);
              }
              return buildFileParser;
            }
          });
      final TargetNodeListener nodeListener = new TargetNodeListener() {
        @Override
        public void onCreate(Path buildFile, TargetNode<?> node) throws IOException {
        }
      };
      this.parsePipeline = new ParsePipeline(
          this.cache,
          new ParsePipeline.Delegate() {
            @Override
            public TargetNode<?> createTargetNode(
                Cell cell, Path buildFile, BuildTarget target, Map<String, Object> rawNode) {
              return DaemonicParserState.createTargetNode(
                  eventBus, cell, buildFile, target,
                  rawNode, constructorArgMarshaller, coercerFactory, nodeListener);
            }
          },
          this.executorService,
          this.eventBus,
          this.projectBuildFileParserPool,
          true);
    }

    public ParsePipeline getParsePipeline() {
      return parsePipeline;
    }

    public Cell getCell() {
      return cell;
    }

    public ParsePipelineCache getCache() {
      return cache;
    }

    private void waitForParsersToClose() throws InterruptedException {
      Iterable<ProjectBuildFileParser> parserSnapshot;
      synchronized (projectBuildFileParsers) {
        parserSnapshot = ImmutableSet.copyOf(projectBuildFileParsers);
      }
      waitForAll(
          parserSnapshot,
          new Predicate<ProjectBuildFileParser>() {
            @Override
            public boolean apply(ProjectBuildFileParser input) {
              return input.isClosed();
            }
          }
      );
    }

    @Override
    public void close() throws Exception {
      parsePipeline.close();
      projectBuildFileParserPool.close();
      // We wait for the parsers to shut down gracefully, they do this on a separate threadpool.
      waitForParsersToClose();
      executorService.shutdown();
      assertThat(
          executorService.awaitTermination(5, TimeUnit.SECONDS),
          Matchers.is(true));
      synchronized (projectBuildFileParsers) {
        for (ProjectBuildFileParser parser : projectBuildFileParsers) {
          assertThat(parser.isClosed(), Matchers.is(true));
        }
      }
    }
  }

}
