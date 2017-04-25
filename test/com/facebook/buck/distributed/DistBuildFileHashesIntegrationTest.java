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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.listener.BroadcastEventListener;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraphAndResolver;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
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

  private static final int KEY_SEED = 0;

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void symlinkPathsRecordedInRootCell() throws Exception {
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "symlink", temporaryFolder);
    workspace.setUp();

    ProjectFilesystem rootFs =
        new ProjectFilesystem(temporaryFolder.getRoot().toAbsolutePath().resolve("root_cell"));

    Path absSymlinkFilePath = rootFs.resolve("../" + SYMLINK_FILE_NAME);
    Path symLinkPath = rootFs.resolve(SYMLINK_FILE_NAME);
    rootFs.createSymLink(symLinkPath, absSymlinkFilePath, false);

    BuckConfig rootCellConfig = FakeBuckConfig.builder().setFilesystem(rootFs).build();
    Cell rootCell =
        new TestCellBuilder().setBuckConfig(rootCellConfig).setFilesystem(rootFs).build();

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ConstructorArgMarshaller constructorArgMarshaller =
        new ConstructorArgMarshaller(typeCoercerFactory);
    Parser parser =
        new Parser(
            new BroadcastEventListener(),
            rootCellConfig.getView(ParserConfig.class),
            typeCoercerFactory,
            constructorArgMarshaller);
    TargetGraph targetGraph =
        parser.buildTargetGraph(
            BuckEventBusFactory.newInstance(),
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
        new ProjectFilesystem(temporaryFolder.getRoot().toAbsolutePath().resolve("root_cell"));
    ProjectFilesystem secondaryFs =
        new ProjectFilesystem(temporaryFolder.getRoot().toAbsolutePath().resolve("secondary_cell"));
    BuckConfig rootCellConfig =
        FakeBuckConfig.builder()
            .setFilesystem(rootFs)
            .setSections(
                "[repositories]",
                "cross_cell_secondary = " + secondaryFs.getRootPath().toAbsolutePath())
            .build();
    Cell rootCell =
        new TestCellBuilder().setBuckConfig(rootCellConfig).setFilesystem(rootFs).build();

    TypeCoercerFactory typeCoercerFactory = new DefaultTypeCoercerFactory();
    ConstructorArgMarshaller constructorArgMarshaller =
        new ConstructorArgMarshaller(typeCoercerFactory);
    Parser parser =
        new Parser(
            new BroadcastEventListener(),
            rootCellConfig.getView(ParserConfig.class),
            typeCoercerFactory,
            constructorArgMarshaller);
    TargetGraph targetGraph =
        parser.buildTargetGraph(
            BuckEventBusFactory.newInstance(),
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
      throws InterruptedException, IOException {
    ActionGraphCache cache = new ActionGraphCache(new BroadcastEventListener());
    ActionGraphAndResolver actionGraphAndResolver =
        cache.getActionGraph(BuckEventBusFactory.newInstance(), true, false, targetGraph, KEY_SEED);
    BuildRuleResolver ruleResolver = actionGraphAndResolver.getResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    DistBuildCellIndexer cellIndexer = new DistBuildCellIndexer(rootCell);

    ImmutableList.Builder<ProjectFileHashCache> allCaches = ImmutableList.builder();
    allCaches.add(DefaultFileHashCache.createDefaultFileHashCache(rootCell.getFilesystem()));
    for (Path cellPath : rootCell.getKnownRoots()) {
      Cell cell = rootCell.getCell(cellPath);
      allCaches.add(DefaultFileHashCache.createDefaultFileHashCache(cell.getFilesystem()));
    }
    allCaches.addAll(DefaultFileHashCache.createOsRootDirectoriesCaches());
    StackedFileHashCache stackedCache = new StackedFileHashCache(allCaches.build());

    return new DistBuildFileHashes(
        actionGraphAndResolver.getActionGraph(),
        sourcePathResolver,
        ruleFinder,
        stackedCache,
        cellIndexer,
        MoreExecutors.newDirectExecutorService(),
        /* keySeed */ KEY_SEED,
        rootCell);
  }
}
