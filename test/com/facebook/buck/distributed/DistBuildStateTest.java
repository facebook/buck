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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.config.Config;
import com.facebook.buck.config.ConfigBuilder;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultCellPathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.KnownBuildRuleTypesFactory;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeFactory;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.PathTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DistBuildStateTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());
  private KnownBuildRuleTypesFactory knownBuildRuleTypesFactory =
      new KnownBuildRuleTypesFactory(processExecutor, new FakeAndroidDirectoryResolver());

  @Test
  public void canReconstructConfig() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = createJavaOnlyFilesystem("/saving");

    Config config = new Config(ConfigBuilder.rawFromLines());
    BuckConfig buckConfig =
        new BuckConfig(
            config,
            filesystem,
            Architecture.detect(),
            Platform.detect(),
            ImmutableMap.<String, String>builder()
                .putAll(System.getenv())
                .put("envKey", "envValue")
                .build(),
            new DefaultCellPathResolver(filesystem.getRootPath(), config));
    Cell rootCellWhenSaving =
        new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(buckConfig).build();

    BuildJobState dump =
        DistBuildState.dump(
            new DistBuildCellIndexer(rootCellWhenSaving),
            emptyActionGraph(),
            createDefaultCodec(rootCellWhenSaving, Optional.empty()),
            createTargetGraph(filesystem),
            ImmutableSet.of(BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:dummy")));

    Cell rootCellWhenLoading =
        new TestCellBuilder().setFilesystem(createJavaOnlyFilesystem("/loading")).build();
    DistBuildState distributedBuildState =
        DistBuildState.load(
            Optional.empty(), dump, rootCellWhenLoading, knownBuildRuleTypesFactory);
    ImmutableMap<Integer, Cell> cells = distributedBuildState.getCells();
    assertThat(cells, Matchers.aMapWithSize(1));
    assertThat(cells.get(0).getBuckConfig(), Matchers.equalTo(buckConfig));
  }

  @Test
  public void canReconstructGraphAndTopLevelBuildTargets() throws Exception {
    ProjectWorkspace projectWorkspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "simple_java_target", temporaryFolder);
    projectWorkspace.setUp();

    Cell cell = projectWorkspace.asCell();
    ProjectFilesystem projectFilesystem = cell.getFilesystem();
    projectFilesystem.mkdirs(projectFilesystem.getBuckPaths().getBuckOut());
    BuckConfig buckConfig = cell.getBuckConfig();
    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ConstructorArgMarshaller constructorArgMarshaller =
        new ConstructorArgMarshaller(typeCoercerFactory);
    Parser parser =
        new Parser(
            new BroadcastEventListener(),
            buckConfig.getView(ParserConfig.class),
            typeCoercerFactory,
            constructorArgMarshaller);
    TargetGraph targetGraph =
        parser.buildTargetGraph(
            BuckEventBusFactory.newInstance(),
            cell,
            /* enableProfiling */ false,
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
            ImmutableSet.of(
                BuildTargetFactory.newInstance(projectFilesystem.getRootPath(), "//:lib1"),
                BuildTargetFactory.newInstance(projectFilesystem.getRootPath(), "//:lib2"),
                BuildTargetFactory.newInstance(projectFilesystem.getRootPath(), "//:lib3")));

    DistBuildTargetGraphCodec targetGraphCodec = createDefaultCodec(cell, Optional.of(parser));
    BuildJobState dump =
        DistBuildState.dump(
            new DistBuildCellIndexer(cell),
            emptyActionGraph(),
            targetGraphCodec,
            targetGraph,
            ImmutableSet.of(
                BuildTargetFactory.newInstance(projectFilesystem.getRootPath(), "//:lib1"),
                BuildTargetFactory.newInstance(projectFilesystem.getRootPath(), "//:lib2")));

    Cell rootCellWhenLoading =
        new TestCellBuilder().setFilesystem(createJavaOnlyFilesystem("/loading")).build();
    DistBuildState distributedBuildState =
        DistBuildState.load(
            Optional.empty(), dump, rootCellWhenLoading, knownBuildRuleTypesFactory);

    ProjectFilesystem reconstructedCellFilesystem =
        distributedBuildState.getCells().get(0).getFilesystem();
    TargetGraph reconstructedGraph =
        distributedBuildState.createTargetGraph(targetGraphCodec).getTargetGraph();
    assertEquals(
        reconstructedGraph
            .getNodes()
            .stream()
            .map(targetNode -> targetNode.castArg(JavaLibraryDescriptionArg.class).get())
            .sorted()
            .map(targetNode -> targetNode.getConstructorArg().getSrcs())
            .collect(Collectors.toList()),
        Lists.newArrayList("A.java", "B.java", "C.java")
            .stream()
            .map(f -> reconstructedCellFilesystem.getPath(f))
            .map(p -> new PathSourcePath(reconstructedCellFilesystem, p))
            .map(ImmutableSortedSet::of)
            .collect(Collectors.toList()));
  }

  @Test
  public void throwsOnPlatformMismatch() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = createJavaOnlyFilesystem("/opt/buck");
    Config config = new Config(ConfigBuilder.rawFromLines());
    BuckConfig buckConfig =
        new BuckConfig(
            config,
            filesystem,
            Architecture.MIPSEL,
            Platform.UNKNOWN,
            ImmutableMap.<String, String>builder()
                .putAll(System.getenv())
                .put("envKey", "envValue")
                .build(),
            new DefaultCellPathResolver(filesystem.getRootPath(), config));
    Cell cell = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(buckConfig).build();

    BuildJobState dump =
        DistBuildState.dump(
            new DistBuildCellIndexer(cell),
            emptyActionGraph(),
            createDefaultCodec(cell, Optional.empty()),
            createTargetGraph(filesystem),
            ImmutableSet.of(BuildTargetFactory.newInstance(filesystem.getRootPath(), "//:dummy")));

    expectedException.expect(IllegalStateException.class);
    DistBuildState.load(Optional.empty(), dump, cell, knownBuildRuleTypesFactory);
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

    Config config =
        new Config(
            ConfigBuilder.rawFromLines(
                "[cache]",
                "repository=somerepo",
                "[repositories]",
                "cell2 = " + cell2Root.toString()));
    BuckConfig buckConfig =
        new BuckConfig(
            config,
            cell1Filesystem,
            Architecture.detect(),
            Platform.detect(),
            ImmutableMap.<String, String>builder()
                .putAll(System.getenv())
                .put("envKey", "envValue")
                .build(),
            new DefaultCellPathResolver(cell1Root, config));
    Cell rootCellWhenSaving =
        new TestCellBuilder().setFilesystem(cell1Filesystem).setBuckConfig(buckConfig).build();

    BuildJobState dump =
        DistBuildState.dump(
            new DistBuildCellIndexer(rootCellWhenSaving),
            emptyActionGraph(),
            createDefaultCodec(rootCellWhenSaving, Optional.empty()),
            createCrossCellTargetGraph(cell1Filesystem, cell2Filesystem),
            ImmutableSet.of(
                BuildTargetFactory.newInstance(cell1Filesystem.getRootPath(), "//:dummy")));

    Cell rootCellWhenLoading =
        new TestCellBuilder().setFilesystem(createJavaOnlyFilesystem("/loading")).build();

    Config localConfig =
        new Config(ConfigBuilder.rawFromLines("[cache]", "slb_server_pool=http://someserver:8080"));
    BuckConfig localBuckConfig =
        new BuckConfig(
            localConfig,
            cell1Filesystem,
            Architecture.detect(),
            Platform.detect(),
            ImmutableMap.<String, String>builder()
                .putAll(System.getenv())
                .put("envKey", "envValue")
                .build(),
            new DefaultCellPathResolver(cell1Root, localConfig));
    DistBuildState distributedBuildState =
        DistBuildState.load(
            Optional.of(localBuckConfig), dump, rootCellWhenLoading, knownBuildRuleTypesFactory);
    ImmutableMap<Integer, Cell> cells = distributedBuildState.getCells();
    assertThat(cells, Matchers.aMapWithSize(2));

    BuckConfig rootCellBuckConfig = cells.get(0).getBuckConfig();

    Optional<ImmutableMap<String, String>> cacheSection = rootCellBuckConfig.getSection("cache");
    assertTrue(cacheSection.isPresent());
    assertTrue(cacheSection.get().containsKey("repository"));
    assertThat(cacheSection.get().get("repository"), Matchers.equalTo("somerepo"));
    assertThat(
        cacheSection.get().get("slb_server_pool"), Matchers.equalTo("http://someserver:8080"));
  }

  private DistBuildFileHashes emptyActionGraph() throws IOException, InterruptedException {
    ActionGraph actionGraph = new ActionGraph(ImmutableList.of());
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    ProjectFilesystem projectFilesystem = createJavaOnlyFilesystem("/opt/buck");
    Cell rootCell =
        new TestCellBuilder()
            .setFilesystem(projectFilesystem)
            .setBuckConfig(FakeBuckConfig.builder().build())
            .build();
    return new DistBuildFileHashes(
        actionGraph,
        sourcePathResolver,
        ruleFinder,
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(projectFilesystem))),
        new DistBuildCellIndexer(rootCell),
        MoreExecutors.newDirectExecutorService(),
        /* keySeed */ 0,
        rootCell);
  }

  public static DistBuildTargetGraphCodec createDefaultCodec(
      final Cell cell, final Optional<Parser> parser) {
    BuckEventBus eventBus = BuckEventBusFactory.newInstance();

    Function<? super TargetNode<?, ?>, ? extends Map<String, Object>> nodeToRawNode;
    if (parser.isPresent()) {
      nodeToRawNode =
          (Function<TargetNode<?, ?>, Map<String, Object>>)
              input -> {
                try {
                  return parser
                      .get()
                      .getRawTargetNode(
                          eventBus,
                          cell.getCell(input.getBuildTarget()),
                          /* enableProfiling */ false,
                          MoreExecutors.listeningDecorator(
                              MoreExecutors.newDirectExecutorService()),
                          input);
                } catch (BuildFileParseException e) {
                  throw new RuntimeException(e);
                }
              };
    } else {
      nodeToRawNode = Functions.constant(ImmutableMap.<String, Object>of());
    }

    TypeCoercerFactory typeCoercerFactory =
        new DefaultTypeCoercerFactory(PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY);
    ParserTargetNodeFactory<TargetNode<?, ?>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory));

    return new DistBuildTargetGraphCodec(parserTargetNodeFactory, nodeToRawNode, ImmutableSet.of());
  }

  private static TargetGraph createTargetGraph(ProjectFilesystem filesystem) {
    return TargetGraphFactory.newInstance(
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:foo"), filesystem)
            .build());
  }

  private static TargetGraph createCrossCellTargetGraph(
      ProjectFilesystem cellOneFilesystem, ProjectFilesystem cellTwoFilesystem) {
    Preconditions.checkArgument(!cellOneFilesystem.equals(cellTwoFilesystem));
    BuildTarget target = BuildTargetFactory.newInstance(cellTwoFilesystem.getRootPath(), "//:foo");
    return TargetGraphFactory.newInstance(
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance(cellOneFilesystem.getRootPath(), "//:foo"),
                cellOneFilesystem)
            .addSrc(new DefaultBuildTargetSourcePath(target))
            .build(),
        JavaLibraryBuilder.createBuilder(target, cellTwoFilesystem).build());
  }

  public static ProjectFilesystem createJavaOnlyFilesystem(String rootPath)
      throws InterruptedException, IOException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem(rootPath);
    filesystem.mkdirs(filesystem.getBuckPaths().getBuckOut());
    return filesystem;
  }
}
