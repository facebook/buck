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
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
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
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

public class DistributedBuildStateTest {
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void canReconstructConfig() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = createJavaOnlyFilesystem("/saving");

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
    Cell rootCellWhenSaving = new TestCellBuilder()
        .setFilesystem(filesystem)
        .setBuckConfig(buckConfig)
        .build();

    BuildJobState dump = DistBuildState.dump(
        new DistBuildCellIndexer(rootCellWhenSaving),
        emptyActionGraph(),
        createDefaultCodec(rootCellWhenSaving, Optional.absent()),
        createTargetGraph(filesystem));


    Cell rootCellWhenLoading = new TestCellBuilder()
        .setFilesystem(createJavaOnlyFilesystem("/loading"))
        .build();
    DistBuildState distributedBuildState =
        DistBuildState.load(dump, rootCellWhenLoading);
    ImmutableMap<Integer, Cell> cells = distributedBuildState.getCells();
    assertThat(cells, Matchers.aMapWithSize(1));
    assertThat(
        cells.get(0).getBuckConfig(),
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
        new BroadcastEventListener(),
        buckConfig.getView(ParserConfig.class),
        typeCoercerFactory,
        constructorArgMarshaller);
    TargetGraph targetGraph = parser.buildTargetGraph(
        BuckEventBusFactory.newInstance(),
        cell,
        /* enableProfiling */ false,
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
        ImmutableSet.of(
            BuildTargetFactory.newInstance(projectFilesystem.getRootPath(), "//:lib")));

    DistBuildTargetGraphCodec targetGraphCodec =
        createDefaultCodec(cell, Optional.of(parser));
    BuildJobState dump = DistBuildState.dump(
        new DistBuildCellIndexer(cell),
        emptyActionGraph(),
        targetGraphCodec,
        targetGraph);

    Cell rootCellWhenLoading = new TestCellBuilder()
        .setFilesystem(createJavaOnlyFilesystem("/loading"))
        .build();
    DistBuildState distributedBuildState =
        DistBuildState.load(dump, rootCellWhenLoading);
    TargetGraph reconstructedGraph = distributedBuildState.createTargetGraph(targetGraphCodec);
    assertThat(reconstructedGraph.getNodes(), Matchers.hasSize(1));
    TargetNode<JavaLibraryDescription.Arg> reconstructedJavaLibrary =
        FluentIterable.from(reconstructedGraph.getNodes()).get(0)
        .castArg(JavaLibraryDescription.Arg.class).get();
    ProjectFilesystem reconstructedCellFilesystem =
        distributedBuildState.getCells().get(0).getFilesystem();
    assertThat(
        reconstructedJavaLibrary.getConstructorArg().srcs.get(),
        Matchers.contains(
            new PathSourcePath(
                reconstructedCellFilesystem,
                reconstructedCellFilesystem.getRootPath().getFileSystem().getPath("A.java"))));
  }

  @Test
  public void throwsOnPlatformMismatch() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = createJavaOnlyFilesystem("/opt/buck");
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

    BuildJobState dump = DistBuildState.dump(
        new DistBuildCellIndexer(cell),
        emptyActionGraph(),
        createDefaultCodec(cell, Optional.absent()),
        createTargetGraph(filesystem));

    expectedException.expect(IllegalStateException.class);
    DistBuildState.load(dump, cell);
  }

  @Test
  public void worksCrossCell() throws IOException, InterruptedException {
    ProjectFilesystem parentFs = createJavaOnlyFilesystem("/saving");
    Path cell1Root = parentFs.resolve("cell1");
    Path cell2Root = parentFs.resolve("cell2");
    parentFs.mkdirs(cell1Root);
    parentFs.mkdirs(cell2Root);
    ProjectFilesystem cell1Filesystem = new ProjectFilesystem(cell1Root);
    ProjectFilesystem cell2Filesystem = new ProjectFilesystem(cell2Root);

    Config config = new Config(ConfigBuilder.rawFromLines(
        "[repositories]",
        "cell2 = " + cell2Root.toString()));
    BuckConfig buckConfig = new BuckConfig(
        config,
        cell1Filesystem,
        Architecture.detect(),
        Platform.detect(),
        ImmutableMap.<String, String>builder()
            .putAll(System.getenv())
            .put("envKey", "envValue")
            .build(),
        new DefaultCellPathResolver(cell1Root, config));
    Cell rootCellWhenSaving = new TestCellBuilder()
        .setFilesystem(cell1Filesystem)
        .setBuckConfig(buckConfig)
        .build();

    BuildJobState dump = DistBuildState.dump(
        new DistBuildCellIndexer(rootCellWhenSaving),
        emptyActionGraph(),
        createDefaultCodec(rootCellWhenSaving, Optional.absent()),
        createCrossCellTargetGraph(cell1Filesystem, cell2Filesystem));

    Cell rootCellWhenLoading = new TestCellBuilder()
        .setFilesystem(createJavaOnlyFilesystem("/loading"))
        .build();
    DistBuildState distributedBuildState =
        DistBuildState.load(dump, rootCellWhenLoading);
    ImmutableMap<Integer, Cell> cells = distributedBuildState.getCells();
    assertThat(cells, Matchers.aMapWithSize(2));
  }

  private DistBuildFileHashes emptyActionGraph() throws IOException {
    ActionGraph actionGraph = new ActionGraph(ImmutableList.of());
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleResolver);
    ProjectFilesystem projectFilesystem = createJavaOnlyFilesystem("/opt/buck");
    return new DistBuildFileHashes(
        actionGraph,
        sourcePathResolver,
        DefaultFileHashCache.createDefaultFileHashCache(projectFilesystem),
        Functions.constant(0),
        MoreExecutors.newDirectExecutorService(),
        /* keySeed */ 0);
  }

  private static DistBuildTargetGraphCodec createDefaultCodec(
      final Cell cell,
      final Optional<Parser> parser) {
    ObjectMapper objectMapper = ObjectMappers.newDefaultInstance(); // NOPMD confused by lambda
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();

    Function<? super TargetNode<?>, ? extends Map<String, Object>> nodeToRawNode;
    if (parser.isPresent()) {
     nodeToRawNode = (Function<TargetNode<?>, Map<String, Object>>) input -> {
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
     };
    } else {
      nodeToRawNode = Functions.constant(ImmutableMap.<String, Object>of());
    }

    DistBuildTypeCoercerFactory typeCoercerFactory =
        new DistBuildTypeCoercerFactory(objectMapper);
    ParserTargetNodeFactory<TargetNode<?>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory));

    return new DistBuildTargetGraphCodec(
        objectMapper,
        parserTargetNodeFactory,
        nodeToRawNode);
  }

  private static TargetGraph createTargetGraph(ProjectFilesystem filesystem) {
    return TargetGraphFactory.newInstance(
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:foo"), filesystem)
            .build());
  }

  private static TargetGraph createCrossCellTargetGraph(
      ProjectFilesystem cellOneFilesystem,
      ProjectFilesystem cellTwoFilesystem) {
    Preconditions.checkArgument(!cellOneFilesystem.equals(cellTwoFilesystem));
    BuildTarget target = BuildTargetFactory.newInstance(cellTwoFilesystem, "//:foo");
    return TargetGraphFactory.newInstance(
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance(cellOneFilesystem, "//:foo"),
            cellOneFilesystem)
            .addSrc(new BuildTargetSourcePath(target))
            .build(),
        JavaLibraryBuilder.createBuilder(
            target,
            cellTwoFilesystem)
            .build()
        );
  }

  private static ProjectFilesystem createJavaOnlyFilesystem(String rootPath) throws IOException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem(rootPath);
    filesystem.mkdirs(filesystem.getBuckPaths().getBuckOut());
    return filesystem;
  }
}
