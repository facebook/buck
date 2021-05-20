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
import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.ForwardRelPath;
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
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.facebook.buck.util.concurrent.AutoCloseableReadLocked;
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
  private DaemonicParserStateLocks locks;

  private void populateDummyRawNode(DaemonicCellState state, BuildTarget target) {
    state.putBuildFileManifestIfNotPresentForTest(
        target.getCellRelativeBasePath().getPath().resolve("BUCK"),
        BuildFileManifestFactory.create(
            ImmutableMap.of(
                target.getShortName(),
                RawTargetNode.copyOf(
                    ForwardRelPath.of(target.getCellRelativeBasePath().getPath().toString()),
                    "java_library",
                    ImmutableList.of(),
                    ImmutableList.of(),
                    TwoArraysImmutableHashMap.copyOf(
                        ImmutableMap.of("name", target.getShortName()))))),
        ImmutableSet.of());
  }

  @Before
  public void setUp() throws IOException {
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    Files.createDirectories(filesystem.resolve("../xplat").getPath());
    Files.createFile(filesystem.resolve("../xplat/.buckconfig").getPath());
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(ImmutableMap.of("repositories", ImmutableMap.of("xplat", "../xplat")))
            .build();
    cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    childCell = cells.getCell(CanonicalCellName.of(Optional.of("xplat")));
    locks = new DaemonicParserStateLocks();
    state = new DaemonicCellState(cells.getRootCell(), locks);
    childState = new DaemonicCellState(childCell, locks);
  }

  private UnconfiguredTargetNode rawTargetNode(String name) {
    return ImmutableUnconfiguredTargetNode.of(
        UnconfiguredBuildTargetParser.parse("//" + name + ":" + name).getUnflavoredBuildTarget(),
        RuleType.of("j_l", RuleType.Kind.BUILD),
        ImmutableMap.of(),
        ImmutableSet.of(),
        ImmutableSet.of(),
        Optional.empty(),
        Optional.empty(),
        ImmutableList.of());
  }

  private ForwardRelPath dummyPackageRelPath() {
    return ForwardRelPath.of("path/to/PACKAGE");
  }

  private AbsPath dummyPackageFile() {
    return filesystem.resolve(dummyPackageRelPath());
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

    try (AutoCloseableReadLocked readLock = locks.cachesLock.lockRead()) {

      cache.putComputedNodeIfNotPresent(target.getUnconfiguredBuildTarget(), n1, readLock);
      assertEquals(
          "Cached node was not found",
          Optional.of(n1),
          cache.lookupComputedNode(target.getUnconfiguredBuildTarget(), locks.validationToken()));

      assertEquals(
          n1, cache.putComputedNodeIfNotPresent(target.getUnconfiguredBuildTarget(), n2, readLock));
      assertEquals(
          "Previously cached node should not be updated",
          Optional.of(n1),
          cache.lookupComputedNode(target.getUnconfiguredBuildTarget(), locks.validationToken()));
    }
  }

  @Test
  public void testCellNameDoesNotAffectInvalidation() throws BuildTargetException {
    Cache<UnconfiguredBuildTarget, UnconfiguredTargetNode> cache =
        childState.getCache(DaemonicCellState.RAW_TARGET_NODE_CACHE_TYPE);

    ForwardRelPath targetPathRel = ForwardRelPath.of("path/to/BUCK");
    AbsPath targetPath = childCell.getRoot().resolve(targetPathRel);
    BuildTarget target = BuildTargetFactory.newInstance("xplat//path/to:target");

    // Make sure the cache has a raw node for this target.
    populateDummyRawNode(childState, target);

    UnconfiguredTargetNode n1 = rawTargetNode("n1");

    try (AutoCloseableReadLocked readLock = locks.cachesLock.lockRead()) {
      cache.putComputedNodeIfNotPresent(target.getUnconfiguredBuildTarget(), n1, readLock);
    }
    assertEquals(
        Optional.of(n1),
        cache.lookupComputedNode(target.getUnconfiguredBuildTarget(), locks.validationToken()));

    childState.putBuildFileManifestIfNotPresentForTest(
        targetPathRel,
        BuildFileManifestFactory.create(
            ImmutableMap.of(
                "target",
                // Forms the target "//path/to:target"
                RawTargetNode.copyOf(
                    ForwardRelPath.of("path/to"),
                    "java_library",
                    ImmutableList.of(),
                    ImmutableList.of(),
                    TwoArraysImmutableHashMap.copyOf(ImmutableMap.of("name", "target"))))),
        ImmutableSet.of());
    assertEquals(
        "Still only one invalidated node", 1, childState.invalidatePathForTest(targetPath, true));
    assertEquals(
        "Cell-named target should still be invalidated",
        Optional.empty(),
        cache.lookupComputedNode(target.getUnconfiguredBuildTarget(), locks.validationToken()));
  }

  @Test
  public void putPackageIfNotPresent() {
    ForwardRelPath packageFile = dummyPackageRelPath();
    PackageFileManifest manifest = PackageFileManifest.EMPTY_SINGLETON;

    PackageFileManifest cachedManifest =
        state.putPackageFileManifestIfNotPresentForTest(packageFile, manifest, ImmutableSet.of());

    assertSame(cachedManifest, manifest);

    PackageFileManifest secondaryManifest =
        PackageFileManifest.of(
            PackageMetadata.EMPTY_SINGLETON,
            ImmutableSortedSet.of(),
            ImmutableMap.of(),
            ImmutableList.of());

    cachedManifest =
        state.putPackageFileManifestIfNotPresentForTest(packageFile, manifest, ImmutableSet.of());

    assertNotSame(secondaryManifest, cachedManifest);
  }

  @Test
  public void lookupPackage() {
    ForwardRelPath packageFile = dummyPackageRelPath();

    Optional<PackageFileManifest> lookupManifest =
        state.lookupPackageFileManifest(packageFile, locks.validationToken());

    assertFalse(lookupManifest.isPresent());

    PackageFileManifest manifest = PackageFileManifest.EMPTY_SINGLETON;
    state.putPackageFileManifestIfNotPresentForTest(packageFile, manifest, ImmutableSet.of());
    lookupManifest = state.lookupPackageFileManifest(packageFile, locks.validationToken());
    assertSame(lookupManifest.get(), manifest);
  }

  @Test
  public void invalidatePackageFilePath() {
    ForwardRelPath packageFile = dummyPackageRelPath();
    PackageFileManifest manifest = PackageFileManifest.EMPTY_SINGLETON;

    state.putPackageFileManifestIfNotPresentForTest(packageFile, manifest, ImmutableSet.of());

    Optional<PackageFileManifest> lookupManifest =
        state.lookupPackageFileManifest(packageFile, locks.validationToken());
    assertTrue(lookupManifest.isPresent());

    state.invalidatePathForTest(filesystem.resolve("path/to/random.bzl"), true);

    lookupManifest = state.lookupPackageFileManifest(packageFile, locks.validationToken());
    assertTrue(lookupManifest.isPresent());

    state.invalidatePathForTest(dummyPackageFile(), true);

    lookupManifest = state.lookupPackageFileManifest(packageFile, locks.validationToken());
    assertFalse(lookupManifest.isPresent());
  }

  @Test
  public void dependentInvalidatesPackageFileManifest() {
    ForwardRelPath packageFile = dummyPackageRelPath();
    PackageFileManifest manifest = PackageFileManifest.EMPTY_SINGLETON;

    AbsPath dependentFile = filesystem.resolve("path/to/pkg_dependent.bzl");

    state.putPackageFileManifestIfNotPresentForTest(
        packageFile, manifest, ImmutableSet.of(dependentFile));

    Optional<PackageFileManifest> lookupManifest =
        state.lookupPackageFileManifest(packageFile, locks.validationToken());
    assertTrue(lookupManifest.isPresent());

    state.invalidatePathForTest(dependentFile, true);

    lookupManifest = state.lookupPackageFileManifest(packageFile, locks.validationToken());
    assertFalse(lookupManifest.isPresent());
  }
}
