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

package com.facebook.buck.distributed;

import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.ConfigBuilder;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Executors;

public class DistributedBuildStateTest {
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Test
  public void canReconstructConfig() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Config config = new Config(ConfigBuilder.rawFromLines());
    BuckConfig buckConfig = new BuckConfig(
        config,
        filesystem,
        Architecture.detect(),
        Platform.detect(),
        ImmutableMap.<String, String>builder()
            .putAll(System.getenv())
            .put("envKey", "envValue")
            .build(),
        new DefaultCellPathResolver(filesystem.getRootPath(), config));
    Cell cell = new TestCellBuilder()
        .setFilesystem(filesystem)
        .setBuckConfig(buckConfig)
        .build();

    BuildJobState dump = DistributedBuildState.dump(
        buckConfig,
        emptyActionGraph(),
        defaultCodec(cell, Optional.<Parser>absent()),
        TargetGraph.EMPTY);
    DistributedBuildState distributedBuildState = new DistributedBuildState(dump);

    assertThat(
        distributedBuildState.createBuckConfig(filesystem),
        Matchers.equalTo(buckConfig));
  }

  @Test
  public void canReconstructGraph() throws Exception {
    ProjectWorkspace projectWorkspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "simple_java_target",
        temporaryFolder);
    projectWorkspace.setUp();

    Cell cell = projectWorkspace.asCell();
    ProjectFilesystem projectFilesystem = cell.getFilesystem();
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getBuckOut());
    BuckConfig buckConfig = cell.getBuckConfig();
    TypeCoercerFactory typeCoercerFactory =
        new DefaultTypeCoercerFactory(ObjectMappers.newDefaultInstance());
    ConstructorArgMarshaller constructorArgMarshaller =
        new ConstructorArgMarshaller(typeCoercerFactory);
    Parser parser = new Parser(
        new ParserConfig(buckConfig),
        typeCoercerFactory,
        constructorArgMarshaller);
    TargetGraph targetGraph = parser.buildTargetGraph(
        BuckEventBusFactory.newInstance(),
        cell,
        /* enableProfiling */ false,
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
        ImmutableSet.of(
            BuildTargetFactory.newInstance(projectFilesystem.getRootPath(), "//:lib")));


    DistributedBuildTargetGraphCodec targetGraphCodec = defaultCodec(cell, Optional.of(parser));
    BuildJobState dump = DistributedBuildState.dump(
        buckConfig,
        emptyActionGraph(),
        targetGraphCodec,
        targetGraph);
    DistributedBuildState distributedBuildState = new DistributedBuildState(dump);

    TargetGraph reconstructedGraph = distributedBuildState.createTargetGraph(targetGraphCodec);
    assertThat(reconstructedGraph.getNodes(), Matchers.hasSize(1));
    TargetNode<JavaLibraryDescription.Arg> reconstructedJavaLibrary =
        FluentIterable.from(reconstructedGraph.getNodes()).get(0)
        .castArg(JavaLibraryDescription.Arg.class).get();
    assertThat(
        reconstructedJavaLibrary.getConstructorArg().srcs.get(),
        Matchers.<SourcePath>contains(
            new PathSourcePath(
                reconstructedJavaLibrary.getRuleFactoryParams().getProjectFilesystem(),
                Paths.get("A.java"))));
  }

  @Test
  public void throwsOnPlatformMismatch() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Config config = new Config(ConfigBuilder.rawFromLines());
    BuckConfig buckConfig = new BuckConfig(
        config,
        filesystem,
        Architecture.MIPSEL,
        Platform.UNKNOWN,
        ImmutableMap.<String, String>builder()
            .putAll(System.getenv())
            .put("envKey", "envValue")
            .build(),
        new DefaultCellPathResolver(filesystem.getRootPath(), config));
    Cell cell = new TestCellBuilder()
        .setFilesystem(filesystem)
        .setBuckConfig(buckConfig)
        .build();

    BuildJobState dump = DistributedBuildState.dump(
        buckConfig,
        emptyActionGraph(),
        defaultCodec(cell, Optional.<Parser>absent()),
        TargetGraph.EMPTY);
    DistributedBuildState distributedBuildState = new DistributedBuildState(dump);

    expectedException.expect(IllegalStateException.class);
    distributedBuildState.createBuckConfig(filesystem);
  }

  private DistributedBuildFileHashes emptyActionGraph() {
    ActionGraph actionGraph = new ActionGraph(ImmutableList.<BuildRule>of());
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleResolver);
    ProjectFilesystem projectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    return new DistributedBuildFileHashes(
        actionGraph,
        sourcePathResolver,
        DefaultFileHashCache.createDefaultFileHashCache(projectFilesystem),
        MoreExecutors.newDirectExecutorService(),
        /* keySeed */ 0);
  }

  private DistributedBuildTargetGraphCodec defaultCodec(
      final Cell cell,
      final Optional<Parser> parser) {
    final ObjectMapper objectMapper = ObjectMappers.newDefaultInstance();
    final BuckEventBus eventBus = BuckEventBusFactory.newInstance();

    Function<? super TargetNode<?>, ? extends Map<String, Object>> nodeToRawNode;
    if (parser.isPresent()) {
     nodeToRawNode = new Function<TargetNode<?>, Map<String, Object>>() {
        @Override
        public Map<String, Object> apply(TargetNode<?> input) {
          try {
            return parser.get().getRawTargetNode(
                eventBus,
                cell.getCell(input.getBuildTarget()),
                /* enableProfiling */ false,
                MoreExecutors.listeningDecorator(MoreExecutors.newDirectExecutorService()),
                input);
          } catch (BuildFileParseException | InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
      };
    } else {
      nodeToRawNode = Functions.constant(ImmutableMap.<String, Object>of());
    }

    DistributedBuildTypeCoercerFactory typeCoercerFactory =
        new DistributedBuildTypeCoercerFactory(objectMapper);
    ParserTargetNodeFactory parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            eventBus,
            new ConstructorArgMarshaller(typeCoercerFactory),
            typeCoercerFactory);

    return new DistributedBuildTargetGraphCodec(
        cell.getFilesystem(),
        cell,
        objectMapper,
        parserTargetNodeFactory,
        nodeToRawNode);
  }
}
