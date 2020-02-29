/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableUnconfiguredTargetNode;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.DaemonicCellState.Cache;
import com.facebook.buck.parser.api.BuildFileManifestFactory;
import com.facebook.buck.parser.api.PackageFileManifest;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class DaemonicCellStateTest {

  private ProjectFilesystem filesystem;
  private Cells cells;
  private Cell childCell;
  private DaemonicCellState state;
  private DaemonicCellState childState;

  private void populateDummyRawNode(DaemonicCellState state, BuildTarget target) {
    Cell targetCell;
    if (target.getCell().equals(cells.getRootCell().getCanonicalName())) {
      targetCell = cells.getRootCell();
    } else if (target.getCell().equals(childCell.getCanonicalName())) {
      targetCell = childCell;
    } else {
      throw new AssertionError();
    }
    state.putBuildFileManifestIfNotPresent(
        targetCell
            .getRoot()
            .resolve(
                target
                    .getCellRelativeBasePath()
                    .getPath()
                    .toPath(filesystem.getFileSystem())
                    .resolve("BUCK")),
        BuildFileManifestFactory.create(
            ImmutableMap.of(
                target.getShortName(),
                ImmutableMap.of(
                    "name", target.getShortName(),
                    "buck.base_path", target.getCellRelativeBasePath().getPath().toString()))),
        ImmutableSet.of(),
        ImmutableMap.of());
  }

  @Before
  public void setUp() throws IOException {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Files.createDirectories(filesystem.resolve("../xplat"));
    Files.createFile(filesystem.resolve("../xplat/.buckconfig"));
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(ImmutableMap.of("repositories", ImmutableMap.of("xplat", "../xplat")))
            .build();
    cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    childCell = cells.getRootCell().getCell(filesystem.resolve("../xplat").toAbsolutePath());
    state = new DaemonicCellState(cells.getRootCell(), 1);
    childState = new DaemonicCellState(childCell, 1);
  }

  private UnconfiguredTargetNode rawTargetNode(String name) {
    return ImmutableUnconfiguredTargetNode.of(
        UnconfiguredBuildTargetParser.parse("//" + name + ":" + name),
        RuleType.of("j_l", RuleType.Kind.BUILD),
        ImmutableMap.of(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        Optional.empty(),
        ImmutableList.of());
  }

  private AbsPath dummyPackageFile() {
    return AbsPath.of(filesystem.resolve("path/to/PACKAGE"));
  }

  @Test
  public void testPutComputedNodeIfNotPresent() throws BuildTargetException {
    Cache<UnconfiguredBuildTarget, UnconfiguredTargetNode> cache =
        state.getCache(DaemonicCellState.RAW_TARGET_NODE_CACHE_TYPE);
    BuildTarget target = BuildTargetFactory.newInstance("//path/to:target");

    // Make sure the cache has a raw node for this target.
    populateDummyRawNode(state, target);

    UnconfiguredTargetNode n1 = rawTargetNode("n1");
    UnconfiguredTargetNode n2 = rawTargetNode("n2");

    cache.putComputedNodeIfNotPresent(target.getUnconfiguredBuildTarget(), n1);
    assertEquals(
        "Cached node was not found",
        Optional.of(n1),
        cache.lookupComputedNode(target.getUnconfiguredBuildTarget()));

    assertEquals(n1, cache.putComputedNodeIfNotPresent(target.getUnconfiguredBuildTarget(), n2));
    assertEquals(
        "Previously cached node should not be updated",
        Optional.of(n1),
        cache.lookupComputedNode(target.getUnconfiguredBuildTarget()));
  }

  @Test
  public void testCellNameDoesNotAffectInvalidation() throws BuildTargetException {
    Cache<UnconfiguredBuildTarget, UnconfiguredTargetNode> cache =
        childState.getCache(DaemonicCellState.RAW_TARGET_NODE_CACHE_TYPE);

    AbsPath targetPath = childCell.getRoot().resolve("path/to/BUCK");
    BuildTarget target = BuildTargetFactory.newInstance("xplat//path/to:target");

    // Make sure the cache has a raw node for this target.
    populateDummyRawNode(childState, target);

    UnconfiguredTargetNode n1 = rawTargetNode("n1");

    cache.putComputedNodeIfNotPresent(target.getUnconfiguredBuildTarget(), n1);
    assertEquals(Optional.of(n1), cache.lookupComputedNode(target.getUnconfiguredBuildTarget()));

    childState.putBuildFileManifestIfNotPresent(
        targetPath,
        BuildFileManifestFactory.create(
            ImmutableMap.of(
                "target",
                // Forms the target "//path/to:target"
                ImmutableMap.of(
                    "buck.base_path", "path/to",
                    "name", "target"))),
        ImmutableSet.of(),
        ImmutableMap.of());
    assertEquals("Still only one invalidated node", 1, childState.invalidatePath(targetPath));
    assertEquals(
        "Cell-named target should still be invalidated",
        Optional.empty(),
        cache.lookupComputedNode(target.getUnconfiguredBuildTarget()));
  }

  @Test
  public void putPackageIfNotPresent() {
    AbsPath packageFile = dummyPackageFile();
    PackageFileManifest manifest = PackageFileManifest.EMPTY_SINGLETON;

    PackageFileManifest cachedManifest =
        state.putPackageFileManifestIfNotPresent(
            packageFile, manifest, ImmutableSet.of(), manifest.getEnv().orElse(ImmutableMap.of()));

    assertSame(cachedManifest, manifest);

    PackageFileManifest secondaryManifest =
        PackageFileManifest.of(
            PackageMetadata.EMPTY_SINGLETON,
            ImmutableSortedSet.of(),
            ImmutableMap.of(),
            Optional.empty(),
            ImmutableList.of());

    cachedManifest =
        state.putPackageFileManifestIfNotPresent(
            packageFile, manifest, ImmutableSet.of(), manifest.getEnv().orElse(ImmutableMap.of()));

    assertNotSame(secondaryManifest, cachedManifest);
  }

  @Test
  public void lookupPackage() {
    AbsPath packageFile = dummyPackageFile();

    Optional<PackageFileManifest> lookupManifest = state.lookupPackageFileManifest(packageFile);

    assertFalse(lookupManifest.isPresent());

    PackageFileManifest manifest = PackageFileManifest.EMPTY_SINGLETON;
    state.putPackageFileManifestIfNotPresent(
        packageFile, manifest, ImmutableSet.of(), manifest.getEnv().orElse(ImmutableMap.of()));
    lookupManifest = state.lookupPackageFileManifest(packageFile);
    assertSame(lookupManifest.get(), manifest);
  }

  @Test
  public void invalidatePackageFilePath() {
    AbsPath packageFile = dummyPackageFile();
    PackageFileManifest manifest = PackageFileManifest.EMPTY_SINGLETON;

    state.putPackageFileManifestIfNotPresent(
        packageFile, manifest, ImmutableSet.of(), manifest.getEnv().orElse(ImmutableMap.of()));

    Optional<PackageFileManifest> lookupManifest = state.lookupPackageFileManifest(packageFile);
    assertTrue(lookupManifest.isPresent());

    state.invalidatePath(AbsPath.of(filesystem.resolve("path/to/random.bzl")));

    lookupManifest = state.lookupPackageFileManifest(packageFile);
    assertTrue(lookupManifest.isPresent());

    state.invalidatePath(packageFile);

    lookupManifest = state.lookupPackageFileManifest(packageFile);
    assertFalse(lookupManifest.isPresent());
  }

  @Test
  public void dependentInvalidatesPackageFileManifest() {
    AbsPath packageFile = dummyPackageFile();
    PackageFileManifest manifest = PackageFileManifest.EMPTY_SINGLETON;

    AbsPath dependentFile = AbsPath.of(filesystem.resolve("path/to/pkg_dependent.bzl"));

    state.putPackageFileManifestIfNotPresent(
        packageFile,
        manifest,
        ImmutableSet.of(dependentFile),
        manifest.getEnv().orElse(ImmutableMap.of()));

    Optional<PackageFileManifest> lookupManifest = state.lookupPackageFileManifest(packageFile);
    assertTrue(lookupManifest.isPresent());

    state.invalidatePath(dependentFile);

    lookupManifest = state.lookupPackageFileManifest(packageFile);
    assertFalse(lookupManifest.isPresent());
  }
}
