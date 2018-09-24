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

import static com.facebook.buck.distributed.DistBuildConfig.SERVER_BUCKCONFIG_OVERRIDE;
import static com.facebook.buck.distributed.DistBuildConfig.STAMPEDE_SECTION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.actiongraph.ActionGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.core.module.TestBuckModuleManagerFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.core.rules.knowntypes.TestKnownRuleTypesProvider;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.JavaLibraryDescriptionArg;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.parser.TestParserFactory;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.PathTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.rules.visibility.VisibilityPatternFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.ConfigBuilder;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.pf4j.PluginManager;

public class DistBuildStateTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private ProcessExecutor processExecutor;
  private ExecutableFinder executableFinder;
  private BuckModuleManager moduleManager;
  private PluginManager pluginManager;

  private void setUp() {
    processExecutor = new DefaultProcessExecutor(new TestConsole());
    executableFinder = new ExecutableFinder();
    pluginManager = BuckPluginManagerFactory.createPluginManager();
    moduleManager = TestBuckModuleManagerFactory.create(pluginManager);
  }

  @Test
  public void canReconstructConfig() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = createJavaOnlyFilesystem("/saving");

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(System.getenv())
                    .put("envKey", "envValue")
                    .build())
            .setFilesystem(filesystem)
            .build();
    Cell rootCellWhenSaving =
        new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(buckConfig).build();
    setUp();

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
            FakeBuckConfig.builder().build(),
            dump,
            rootCellWhenLoading,
            ImmutableMap.of(),
            processExecutor,
            executableFinder,
            moduleManager,
            pluginManager,
            new DefaultProjectFilesystemFactory());
    ImmutableMap<Integer, Cell> cells = distributedBuildState.getCells();
    assertThat(cells, Matchers.aMapWithSize(1));
    assertThat(cells.get(0).getBuckConfig(), Matchers.equalTo(buckConfig));
  }

  @Test
  public void reconstructedServerConfigIncludesSpecifiedOverride()
      throws IOException, InterruptedException {
    ProjectFilesystem filesystem = createJavaOnlyFilesystem("/saving");
    filesystem.writeLinesToPath(
        ImmutableList.of(
            String.format("[%s]", STAMPEDE_SECTION),
            String.format("%s=%s", SERVER_BUCKCONFIG_OVERRIDE, "dummy_override_value")),
        filesystem.getRootPath().resolve("server-cfg"));

    Config config =
        ConfigBuilder.createFromText(
            String.format("[%s]", STAMPEDE_SECTION),
            String.format("%s=%s", SERVER_BUCKCONFIG_OVERRIDE, "server-cfg"));
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(System.getenv())
                    .put("envKey", "envValue")
                    .build())
            .setFilesystem(filesystem)
            .setSections(config.getRawConfig())
            .build();
    Cell rootCellWhenSaving =
        new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(buckConfig).build();
    setUp();

    Config serverConfig =
        ConfigBuilder.createFromText(
            filesystem.readFileIfItExists(filesystem.getPath("server-cfg")).get());
    BuckConfig serverBuckConfig =
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(System.getenv())
                    .put("envKey", "envValue")
                    .build())
            .setFilesystem(filesystem)
            .setSections(serverConfig.getRawConfig())
            .build();

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
            FakeBuckConfig.builder().build(),
            dump,
            rootCellWhenLoading,
            ImmutableMap.of(),
            processExecutor,
            executableFinder,
            moduleManager,
            pluginManager,
            new DefaultProjectFilesystemFactory());
    ImmutableMap<Integer, Cell> cells = distributedBuildState.getCells();

    assertThat(cells, Matchers.aMapWithSize(1));
    assertThat(cells.get(0).getBuckConfig(), Matchers.equalTo(serverBuckConfig));
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
    setUp();
    Parser parser = TestParserFactory.create(buckConfig);
    TargetGraph targetGraph =
        parser.buildTargetGraph(
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
            FakeBuckConfig.builder().build(),
            dump,
            rootCellWhenLoading,
            ImmutableMap.of(),
            processExecutor,
            executableFinder,
            moduleManager,
            pluginManager,
            new DefaultProjectFilesystemFactory());

    ProjectFilesystem reconstructedCellFilesystem =
        distributedBuildState.getCells().get(0).getFilesystem();
    TargetGraph reconstructedGraph =
        distributedBuildState.createTargetGraph(targetGraphCodec).getTargetGraph();
    assertEquals(
        reconstructedGraph
            .getNodes()
            .stream()
            .map(
                targetNode ->
                    TargetNodes.castArg(targetNode, JavaLibraryDescriptionArg.class).get())
            .sorted()
            .map(targetNode -> targetNode.getConstructorArg().getSrcs())
            .collect(Collectors.toList()),
        Lists.newArrayList("A.java", "B.java", "C.java")
            .stream()
            .map(f -> reconstructedCellFilesystem.getPath(f))
            .map(p -> PathSourcePath.of(reconstructedCellFilesystem, p))
            .map(ImmutableSortedSet::of)
            .collect(Collectors.toList()));
  }

  @Test
  public void worksCrossCell() throws IOException, InterruptedException {
    ProjectFilesystem parentFs = createJavaOnlyFilesystem("/saving");
    Path cell1Root = parentFs.resolve("cell1");
    Path cell2Root = parentFs.resolve("cell2");
    parentFs.mkdirs(cell1Root);
    parentFs.mkdirs(cell2Root);
    ProjectFilesystem cell1Filesystem = TestProjectFilesystems.createProjectFilesystem(cell1Root);
    ProjectFilesystem cell2Filesystem = TestProjectFilesystems.createProjectFilesystem(cell2Root);

    Config config =
        new Config(
            ConfigBuilder.rawFromLines(
                "[cache]", "repository=somerepo", "[repositories]", "cell2 = " + cell2Root));
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(System.getenv())
                    .put("envKey", "envValue")
                    .build())
            .setFilesystem(cell1Filesystem)
            .setSections(config.getRawConfig())
            .setSections(config.getRawConfig())
            .build();
    Cell rootCellWhenSaving =
        new TestCellBuilder().setFilesystem(cell1Filesystem).setBuckConfig(buckConfig).build();
    setUp();

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
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.<String, String>builder()
                    .putAll(System.getenv())
                    .put("envKey", "envValue")
                    .build())
            .setFilesystem(cell1Filesystem)
            .setSections(localConfig.getRawConfig())
            .build();
    DistBuildState distributedBuildState =
        DistBuildState.load(
            localBuckConfig,
            dump,
            rootCellWhenLoading,
            ImmutableMap.of(),
            processExecutor,
            executableFinder,
            moduleManager,
            pluginManager,
            new DefaultProjectFilesystemFactory());
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
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
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
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    projectFilesystem, FileHashCacheMode.DEFAULT))),
        new DistBuildCellIndexer(rootCell),
        MoreExecutors.newDirectExecutorService(),
        TestRuleKeyConfigurationFactory.create(),
        rootCell);
  }

  public static DistBuildTargetGraphCodec createDefaultCodec(Cell cell, Optional<Parser> parser) {
    Function<? super TargetNode<?>, ? extends Map<String, Object>> nodeToRawNode;
    if (parser.isPresent()) {
      nodeToRawNode =
          input -> {
            try {
              return parser
                  .get()
                  .getTargetNodeRawAttributes(
                      cell.getCell(input.getBuildTarget()),
                      /* enableProfiling */ false,
                      MoreExecutors.listeningDecorator(MoreExecutors.newDirectExecutorService()),
                      input);
            } catch (BuildFileParseException e) {
              throw new RuntimeException(e);
            }
          };
    } else {
      nodeToRawNode = ignored -> ImmutableMap.of();
    }

    TypeCoercerFactory typeCoercerFactory =
        new DefaultTypeCoercerFactory(PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY);
    KnownRuleTypesProvider knownRuleTypesProvider =
        TestKnownRuleTypesProvider.create(BuckPluginManagerFactory.createPluginManager());
    ParserTargetNodeFactory<Map<String, Object>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            knownRuleTypesProvider,
            new ConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(typeCoercerFactory),
            new VisibilityPatternFactory(),
            TestRuleKeyConfigurationFactory.create());

    return new DistBuildTargetGraphCodec(
        MoreExecutors.newDirectExecutorService(),
        parserTargetNodeFactory,
        nodeToRawNode,
        ImmutableSet.of());
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
            .addSrc(DefaultBuildTargetSourcePath.of(target))
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
