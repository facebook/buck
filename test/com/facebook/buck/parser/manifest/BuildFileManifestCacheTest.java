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

package com.facebook.buck.parser.manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.watchman.WatchmanEvent.Kind;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.BuildFileManifestFactory;
import com.facebook.buck.parser.api.RawTargetNode;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class BuildFileManifestCacheTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private BuildFileManifestCache cache;
  private BuildFileManifest manifestCell1Root;
  private BuildFileManifest manifestCell1Folder;
  private AbsPath rootCellPath;
  private AbsPath cell1Path;
  private AbsPath cell2Path;

  @Before
  public void setUp() throws IOException {
    rootCellPath = tmp.getRoot();
    cell1Path = tmp.newFolder("cell1");
    cell2Path = tmp.newFolder("cell2");

    Files.createFile(cell1Path.resolve("BUCK").getPath());
    Files.createFile(cell1Path.resolve("1.java").getPath());
    Files.createFile(cell1Path.resolve("2.java").getPath());

    // No build file between two packages
    AbsPath folder1 = cell1Path.resolve("folder1");
    Files.createDirectory(folder1.getPath());
    Files.createFile(folder1.resolve("1.java").getPath());
    Files.createFile(folder1.resolve("2.java").getPath());

    AbsPath cell1SubPackage = folder1.resolve("folder2");
    Files.createDirectory(cell1SubPackage.getPath());
    Files.createFile(cell1SubPackage.resolve("BUCK").getPath());
    Files.createFile(cell1SubPackage.resolve("1.java").getPath());
    Files.createFile(cell1SubPackage.resolve("2.java").getPath());

    AbsPath includesFolder = cell1Path.resolve("includes");
    Files.createDirectory(includesFolder.getPath());
    Files.createFile(includesFolder.resolve("BUCK").getPath());
    Files.createFile(includesFolder.resolve("include1.bzl").getPath());
    Files.createFile(includesFolder.resolve("include2.bzl").getPath());
    Files.createFile(includesFolder.resolve("noninclude.bzl").getPath());

    // setup second cell
    Files.createFile(cell2Path.resolve("1.java").getPath());
    Files.createFile(cell2Path.resolve("2.java").getPath());

    AbsPath cell2IncludesFolder = cell2Path.resolve("includes");
    Files.createDirectory(cell2IncludesFolder.getPath());
    Files.createFile(cell2IncludesFolder.resolve("BUCK").getPath());
    Files.createFile(cell2IncludesFolder.resolve("include1.bzl").getPath());
    Files.createFile(cell2IncludesFolder.resolve("include2.bzl").getPath());
    Files.createFile(cell2IncludesFolder.resolve("noninclude.bzl").getPath());

    cache =
        BuildFileManifestCache.of(
            rootCellPath.getPath(),
            cell1Path.getPath(),
            Paths.get("BUCK"),
            TestProjectFilesystems.createProjectFilesystem(cell1Path).asView());

    ImmutableMap<String, RawTargetNode> targets =
        ImmutableMap.of(
            "target1",
            RawTargetNode.copyOf(
                ForwardRelativePath.EMPTY,
                "java_library",
                ImmutableList.of(),
                ImmutableList.of(),
                TwoArraysImmutableHashMap.copyOf(ImmutableMap.of("key1", "val1"))),
            "target2",
            RawTargetNode.copyOf(
                ForwardRelativePath.EMPTY,
                "java_library",
                ImmutableList.of(),
                ImmutableList.of(),
                TwoArraysImmutableHashMap.copyOf(ImmutableMap.of("key2", 2))));
    ImmutableSortedSet<String> includes =
        ImmutableSortedSet.of(includesFolder.resolve("include1.bzl").toString());
    ImmutableMap<String, Object> configs = ImmutableMap.of("config1", "cval1");
    ImmutableList<GlobSpecWithResult> globManifest =
        ImmutableList.of(
            GlobSpecWithResult.of(
                GlobSpec.of(ImmutableList.of("*.java"), ImmutableList.of("*.cpp"), false),
                ImmutableSet.of("1.java", "2.java")));

    manifestCell1Root =
        BuildFileManifestFactory.create(
            targets, includes, configs, globManifest, ImmutableList.of());

    cache.put(ImmutableBuildPackagePathToBuildFileManifestKey.of(Paths.get("")), manifestCell1Root);

    includes =
        ImmutableSortedSet.of(
            includesFolder.resolve("include2.bzl").toString(),
            cell2IncludesFolder.resolve("include2.bzl").toString());
    manifestCell1Folder =
        BuildFileManifestFactory.create(
            targets, includes, configs, globManifest, ImmutableList.of());

    cache.put(
        ImmutableBuildPackagePathToBuildFileManifestKey.of(Paths.get("folder1/folder2")),
        manifestCell1Folder);
  }

  @Test
  public void canReadAndWrite() {
    Optional<BuildFileManifest> fetchedManifest =
        cache.get(ImmutableBuildPackagePathToBuildFileManifestKey.of(Paths.get("")));
    assertTrue(fetchedManifest.isPresent());
    assertEquals(manifestCell1Root, fetchedManifest.get());

    Optional<BuildFileManifest> fetchedEmpty =
        cache.get(ImmutableBuildPackagePathToBuildFileManifestKey.of(Paths.get("nonexisting")));
    assertFalse(fetchedEmpty.isPresent());
  }

  private void assertPackages(boolean rootPackageExists, boolean subPackageExists) {
    Optional<BuildFileManifest> fetchedManifest =
        cache.get(ImmutableBuildPackagePathToBuildFileManifestKey.of(Paths.get("")));
    assertEquals(rootPackageExists, fetchedManifest.isPresent());

    fetchedManifest =
        cache.get(ImmutableBuildPackagePathToBuildFileManifestKey.of(Paths.get("folder1/folder2")));
    assertEquals(subPackageExists, fetchedManifest.isPresent());
  }

  @Test
  public void whenRootBuildFileIsModifiedThenInvalidateOnlyRootPackage() {
    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.MODIFY, ForwardRelativePath.of("BUCK"));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(false, true);
  }

  @Test
  public void whenNonRootBuildFileIsModifiedThenInvalidateOnlyNonRootPackage() {
    WatchmanPathEvent event =
        WatchmanPathEvent.of(
            cell1Path, Kind.MODIFY, ForwardRelativePath.of("folder1/folder2/BUCK"));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(true, false);
  }

  @Test
  public void whenBuildFileIsModifiedInAnotherCellThenDoNotInvalidate() {
    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell2Path, Kind.MODIFY, ForwardRelativePath.of("BUCK"));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(true, true);
  }

  @Test
  public void whenRootBuildFileIsDeletedThenOnlyRootPackageInvalidated() throws Exception {
    Path buckFilePath = Paths.get("BUCK");
    Files.delete(cell1Path.resolve(buckFilePath).getPath());

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.DELETE, ForwardRelativePath.ofPath(buckFilePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(false, true);
  }

  @Test
  public void whenBuildFileIsDeletedThenContainingAndParentPackagesAreInvalidated()
      throws Exception {
    Path buckFilePath = Paths.get("folder1/folder2/BUCK");
    Files.delete(cell1Path.resolve(buckFilePath).getPath());

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.DELETE, ForwardRelativePath.ofPath(buckFilePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(false, false);
  }

  @Test
  public void whenRegularFileIsModifiedThenNoPackagesAreInvalidated() {
    Path modifiedFilePath = Paths.get("folder1/folder2/1.java");

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.MODIFY, ForwardRelativePath.ofPath(modifiedFilePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(true, true);
  }

  @Test
  public void whenRegularFileIsDeletedThenContainingPackageIsInvalidated() throws Exception {
    Path buckFilePath = Paths.get("folder1/folder2/1.java");
    Files.delete(cell1Path.resolve(buckFilePath).getPath());

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.DELETE, ForwardRelativePath.ofPath(buckFilePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(true, false);
  }

  @Test
  public void whenRegularFileIsCreatedThenContainingPackageIsInvalidated() throws Exception {
    Path newFilePath = Paths.get("folder1/folder2/3.java");
    Files.createFile(cell1Path.resolve(newFilePath).getPath());

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.CREATE, ForwardRelativePath.ofPath(newFilePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(true, false);
  }

  @Test
  public void whenRegularFileIsCreatedInNonPackageRootFolderThenContainingPackageIsInvalidated()
      throws Exception {
    Path newFilePath = Paths.get("folder1/3.java");
    Files.createFile(cell1Path.resolve(newFilePath).getPath());

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.CREATE, ForwardRelativePath.ofPath(newFilePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(false, true);
  }

  @Test
  public void whenRegularFileIsDeletedInNonPackageRootFolderThenContainingPackageIsInvalidated()
      throws Exception {
    Path filePath = Paths.get("folder1/1.java");
    Files.delete(cell1Path.resolve(filePath).getPath());

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.DELETE, ForwardRelativePath.ofPath(filePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(false, true);
  }

  @Test
  public void whenBzlFileIsModifiedThenReferencingPackageIsInvalidated() {
    Path filePath = Paths.get("includes/include2.bzl");

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.MODIFY, ForwardRelativePath.ofPath(filePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(true, false);
  }

  @Test
  public void whenBzlFileIsModifiedAndPackageIsRootThenReferencingPackageIsInvalidated() {
    Path filePath = Paths.get("includes/include1.bzl");

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.MODIFY, ForwardRelativePath.ofPath(filePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(false, true);
  }

  @Test
  public void whenBzlFileIsDeletedThenReferencingPackageIsInvalidated() throws Exception {
    Path filePath = Paths.get("includes/include2.bzl");
    Files.delete(cell1Path.resolve(filePath).getPath());

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.DELETE, ForwardRelativePath.ofPath(filePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(true, false);
  }

  @Test
  public void whenBzlFileIsCreatedThenNothingHappens() throws Exception {
    Path filePath = Paths.get("includes/include3.bzl");
    Files.createFile(cell1Path.resolve(filePath).getPath());

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.CREATE, ForwardRelativePath.ofPath(filePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(true, true);
  }

  @Test
  public void whenBzlFileIsNotReferencedAndModifiedThenNothingHappens() {
    Path filePath = Paths.get("includes/noninclude.bzl");

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.MODIFY, ForwardRelativePath.ofPath(filePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(true, true);
  }

  @Test
  public void whenBzlFileFromAnotherCellIsModifiedThenPackageIsInvalidated() {
    Path filePath = Paths.get("includes/include2.bzl");

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell2Path, Kind.MODIFY, ForwardRelativePath.ofPath(filePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(true, false);
  }

  @Test
  public void whenSomeFileFromFromUnloadedPackageIsCreatedNothingChanges() throws Exception {
    Path filePath = Paths.get("includes/somefile.java");
    Files.createFile(cell1Path.resolve(filePath).getPath());

    WatchmanPathEvent event =
        WatchmanPathEvent.of(cell1Path, Kind.CREATE, ForwardRelativePath.ofPath(filePath));
    cache.getInvalidator().onFileSystemChange(event);

    assertPackages(true, true);
  }
}
