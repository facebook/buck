/*
 * Copyright 2017-present Facebook, Inc.
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
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProvider;
import com.facebook.buck.core.model.actiongraph.computation.ActionGraphProviderBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.TestParserFactory;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class DistBuildFileHashesIntegrationTest {

  private static final String SYMLINK_FILE_NAME = "SymlinkSourceFile.java";

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void symlinkPathsRecordedInRootCell() throws Exception {
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "symlink", temporaryFolder);
    workspace.setUp();

    ProjectFilesystem rootFs =
        TestProjectFilesystems.createProjectFilesystem(
            temporaryFolder.getRoot().toAbsolutePath().resolve("root_cell"));

    Path absSymlinkFilePath = rootFs.resolve("../" + SYMLINK_FILE_NAME);
    Path symLinkPath = rootFs.resolve(SYMLINK_FILE_NAME);
    rootFs.createSymLink(symLinkPath, absSymlinkFilePath, false);

    BuckConfig rootCellConfig = FakeBuckConfig.builder().setFilesystem(rootFs).build();
    Cell rootCell =
        new TestCellBuilder().setBuckConfig(rootCellConfig).setFilesystem(rootFs).build();

    Parser parser = TestParserFactory.create(rootCellConfig);
    TargetGraph targetGraph =
        parser.buildTargetGraph(
            rootCell,
            /* enableProfiling */ false,
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
            ImmutableSet.of(BuildTargetFactory.newInstance(rootFs.getRootPath(), "//:libA")));

    DistBuildTargetGraphCodec targetGraphCodec =
        DistBuildStateTest.createDefaultCodec(rootCell, Optional.of(parser));
    BuildJobState dump =
        DistBuildState.dump(
            new DistBuildCellIndexer(rootCell),
            createDistBuildFileHashes(targetGraph, rootCell),
            targetGraphCodec,
            targetGraph,
            ImmutableSet.of(BuildTargetFactory.newInstance(rootFs.getRootPath(), "//:libA")));

    assertNotNull(dump);
    assertEquals(1, dump.getFileHashesSize());
    BuildJobStateFileHashes rootCellHashes = dump.getFileHashes().get(0);
    assertEquals(2, rootCellHashes.getEntriesSize());

    BuildJobStateFileHashEntry symLinkEntry =
        rootCellHashes.getEntries().stream().filter(x -> x.isSetRootSymLink()).findFirst().get();
    String expectedPath =
        temporaryFolder.getRoot().resolve(SYMLINK_FILE_NAME).toAbsolutePath().toString();
    assertEquals(
        MorePaths.pathWithUnixSeparators(expectedPath),
        symLinkEntry.getRootSymLinkTarget().getPath());
    assertEquals(SYMLINK_FILE_NAME, symLinkEntry.getRootSymLink().getPath());

    BuildJobStateFileHashEntry relPathEntry =
        rootCellHashes.getEntries().stream().filter(x -> !x.isPathIsAbsolute()).findFirst().get();
    assertEquals("A.java", relPathEntry.getPath().getPath());
  }

  @Test
  public void crossCellDoesNotCauseAbsolutePathSrcs() throws Exception {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "cross_cell", temporaryFolder);
    workspace.setUp();

    ProjectFilesystem rootFs =
        TestProjectFilesystems.createProjectFilesystem(
            temporaryFolder.getRoot().toAbsolutePath().resolve("root_cell"));
    ProjectFilesystem secondaryFs =
        TestProjectFilesystems.createProjectFilesystem(
            temporaryFolder.getRoot().toAbsolutePath().resolve("secondary_cell"));
    BuckConfig rootCellConfig =
        FakeBuckConfig.builder()
            .setFilesystem(rootFs)
            .setSections(
                "[repositories]",
                "cross_cell_secondary = " + secondaryFs.getRootPath().toAbsolutePath())
            .build();
    Cell rootCell =
        new TestCellBuilder().setBuckConfig(rootCellConfig).setFilesystem(rootFs).build();

    Parser parser = TestParserFactory.create(rootCellConfig);
    TargetGraph targetGraph =
        parser.buildTargetGraph(
            rootCell,
            /* enableProfiling */ false,
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
            ImmutableSet.of(BuildTargetFactory.newInstance(rootFs.getRootPath(), "//:libA")));

    DistBuildTargetGraphCodec targetGraphCodec =
        DistBuildStateTest.createDefaultCodec(rootCell, Optional.of(parser));
    BuildJobState dump =
        DistBuildState.dump(
            new DistBuildCellIndexer(rootCell),
            createDistBuildFileHashes(targetGraph, rootCell),
            targetGraphCodec,
            targetGraph,
            ImmutableSet.of(BuildTargetFactory.newInstance(rootFs.getRootPath(), "//:libA")));

    assertNotNull(dump);
    assertEquals(2, dump.getFileHashesSize());
    List<BuildJobStateFileHashes> sortedHashes =
        dump.getFileHashes()
            .stream()
            .sorted(Comparator.comparingInt(BuildJobStateFileHashes::getCellIndex))
            .collect(Collectors.toList());

    BuildJobStateFileHashes rootCellHashes = sortedHashes.get(0);
    assertEquals(1, rootCellHashes.getEntriesSize());
    assertEquals("A.java", rootCellHashes.getEntries().get(0).getPath().getPath());

    BuildJobStateFileHashes secondaryCellHashes = sortedHashes.get(1);
    assertEquals(1, secondaryCellHashes.getEntriesSize());
    assertEquals("B.java", secondaryCellHashes.getEntries().get(0).getPath().getPath());
  }

  private DistBuildFileHashes createDistBuildFileHashes(TargetGraph targetGraph, Cell rootCell)
      throws InterruptedException {
    ActionGraphProvider cache =
        new ActionGraphProviderBuilder()
            .withMaxEntries(rootCell.getBuckConfig().getMaxActionGraphCacheEntries())
            .withCellProvider(rootCell.getCellProvider())
            .withCheckActionGraphs()
            .build();
    ActionGraphAndBuilder actionGraphAndBuilder = cache.getActionGraph(targetGraph);
    BuildRuleResolver ruleResolver = actionGraphAndBuilder.getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver sourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);
    DistBuildCellIndexer cellIndexer = new DistBuildCellIndexer(rootCell);

    ImmutableList.Builder<ProjectFileHashCache> allCaches = ImmutableList.builder();
    allCaches.add(
        DefaultFileHashCache.createDefaultFileHashCache(
            rootCell.getFilesystem(), FileHashCacheMode.DEFAULT));
    for (Path cellPath : rootCell.getKnownRoots()) {
      Cell cell = rootCell.getCell(cellPath);
      allCaches.add(
          DefaultFileHashCache.createDefaultFileHashCache(
              cell.getFilesystem(), FileHashCacheMode.DEFAULT));
    }
    allCaches.addAll(
        DefaultFileHashCache.createOsRootDirectoriesCaches(
            new DefaultProjectFilesystemFactory(), FileHashCacheMode.DEFAULT));
    StackedFileHashCache stackedCache = new StackedFileHashCache(allCaches.build());

    return new DistBuildFileHashes(
        actionGraphAndBuilder.getActionGraph(),
        sourcePathResolver,
        ruleFinder,
        stackedCache,
        cellIndexer,
        MoreExecutors.newDirectExecutorService(),
        TestRuleKeyConfigurationFactory.create(),
        rootCell);
  }
}
