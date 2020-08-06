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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.parser.api.PackageMetadata;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PerBuildStateCacheTest {

  private PerBuildStateCache.PackageCache packageCache;
  private BuckEventBus eventBus;
  private ProjectFilesystem filesystem;
  private Cells cells;
  private Cell childCell;

  @Rule public TemporaryPaths tempDir = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    eventBus = BuckEventBusForTests.newInstance();
    packageCache = new PerBuildStateCache(1).getPackageCache();
    filesystem = TestProjectFilesystems.createProjectFilesystem(tempDir.getRoot());
    tempDir.newFolder("xplat");
    tempDir.newFile("xplat/.buckconfig");
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(ImmutableMap.of("repositories", ImmutableMap.of("xplat", "xplat")))
            .build();
    cells = new TestCellBuilder().setFilesystem(filesystem).setBuckConfig(config).build();
    childCell = cells.getRootCell().getCell(filesystem.resolve("xplat").toAbsolutePath());
  }

  Package createPackage(Cell cell, AbsPath packageFile) {
    return createPackage(cell, packageFile, PackageMetadata.EMPTY_SINGLETON);
  }

  Package createPackage(
      Cell cell,
      AbsPath packageFile,
      ImmutableList<String> visibility,
      ImmutableList<String> within_view) {
    return createPackage(cell, packageFile, PackageMetadata.of(visibility, within_view));
  }

  Package createPackage(Cell cell, AbsPath packageFile, PackageMetadata packageMetadata) {
    return PackageFactory.create(cell, packageFile.getPath(), packageMetadata, Optional.empty());
  }

  @Test
  public void putPackageIfNotPresent() {
    AbsPath packageFile = AbsPath.of(filesystem.resolve("Foo"));

    Package pkg = createPackage(cells.getRootCell(), packageFile);

    Package cachedPackage =
        packageCache.putComputedNodeIfNotPresent(
            cells.getRootCell(), packageFile, pkg, false, eventBus);

    Assert.assertSame(cachedPackage, pkg);
  }

  @Test
  public void lookupPackage() {
    AbsPath packageFile = AbsPath.of(filesystem.resolve("Foo"));

    Optional<Package> lookupPackage =
        packageCache.lookupComputedNode(cells.getRootCell(), packageFile, eventBus);

    Assert.assertFalse(lookupPackage.isPresent());

    Package pkg = createPackage(cells.getRootCell(), packageFile);
    packageCache.putComputedNodeIfNotPresent(
        cells.getRootCell(), packageFile, pkg, false, eventBus);

    lookupPackage =
        lookupPackage = packageCache.lookupComputedNode(cells.getRootCell(), packageFile, eventBus);
    Assert.assertSame(lookupPackage.get(), pkg);
  }

  @Test
  public void packageInRootCellIsNotInChildCell() {
    AbsPath packageFile = AbsPath.of(filesystem.resolve("Foo"));

    // Make sure to create two different packages
    Package pkg1 =
        createPackage(
            cells.getRootCell(), packageFile, ImmutableList.of("//bar/..."), ImmutableList.of());
    Package pkg2 =
        createPackage(childCell, packageFile, ImmutableList.of("//bar/..."), ImmutableList.of());

    packageCache.putComputedNodeIfNotPresent(
        cells.getRootCell(), packageFile, pkg1, false, eventBus);
    packageCache.putComputedNodeIfNotPresent(childCell, packageFile, pkg2, false, eventBus);

    Optional<Package> lookupPackage =
        packageCache.lookupComputedNode(cells.getRootCell(), packageFile, eventBus);
    Assert.assertSame(lookupPackage.get(), pkg1);
    Assert.assertNotSame(lookupPackage.get(), pkg2);

    lookupPackage = packageCache.lookupComputedNode(childCell, packageFile, eventBus);
    Assert.assertSame(lookupPackage.get(), pkg2);
    Assert.assertNotSame(lookupPackage.get(), pkg1);
  }
}
