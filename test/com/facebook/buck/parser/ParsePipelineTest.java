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
import com.facebook.buck.util.concurrent.MoreExecutors;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ParsePipelineTest {
  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void speculativeDepsTraversal() throws Exception {
    Fixture fixture = new Fixture("pipeline_test");
    Cell cell = fixture.getCell();
    TargetNode<?> libTargetNode = fixture.getParsePipeline().getTargetNode(
        cell,
        BuildTargetFactory.newInstance(cell.getFilesystem(), "//:lib"));
    fixture.close();
    for (BuildTarget dep : libTargetNode.getDeps()) {
      assertThat(fixture.getCache().lookupTargetNode(cell, dep), Matchers.notNullValue());
    }
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
    public synchronized ImmutableList<Map<String, Object>> putRawNodesIfNotPresent(
        Cell cell, Path buildFile, ImmutableList<Map<String, Object>> rawNodes) {
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
    private Cell cell;
    private ParsePipelineCache cache;
    private ListeningExecutorService executorService;

    public Fixture(String scenario) throws Exception {
      this.workspace = TestDataHelper.createProjectWorkspaceForScenario(
          this,
          scenario,
          tmp);
      this.eventBus = BuckEventBusFactory.newInstance();
      this.console = new TestConsole();
      this.workspace.setUp();

      this.cell = this.workspace.asCell();
      this.cache = new ParsePipelineCache();
      final TypeCoercerFactory coercerFactory = new DefaultTypeCoercerFactory();
      final ConstructorArgMarshaller constructorArgMarshaller =
          new ConstructorArgMarshaller(coercerFactory);

      ParserLeaseVendor<ProjectBuildFileParser> vendorForTest = new ParserLeaseVendor<>(
          4, // max parsers
          new Function<Cell, ProjectBuildFileParser>() {
            @Override
            public ProjectBuildFileParser apply(Cell input) {
              return input.createBuildFileParser(constructorArgMarshaller, console, eventBus);
            }
          }
      );
      final TargetNodeListener nodeListener = new TargetNodeListener() {
        @Override
        public void onCreate(Path buildFile, TargetNode<?> node) throws IOException {
        }
      };
      this.executorService = com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
          MoreExecutors.newSingleThreadExecutor("test"));
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
          vendorForTest,
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

    @Override
    public void close() throws Exception {
      com.google.common.util.concurrent.MoreExecutors.shutdownAndAwaitTermination(
          executorService,
          5,
          TimeUnit.SECONDS);
      parsePipeline.close();
    }
  }

}
