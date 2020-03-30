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

package com.facebook.buck.core.cell.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.cell.CellName;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.util.CreateSymlinksForTests;
import com.facebook.buck.util.config.ConfigBuilder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class DefaultCellPathResolverTest {
  private static final String REPOSITORIES_SECTION =
      "[" + DefaultCellPathResolver.REPOSITORIES_SECTION + "]";

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void transitiveMappingForSimpleSetup() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    AbsPath root = AbsPath.of(vfs.getPath("/opt/local/"));
    AbsPath cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root.getPath());
    AbsPath cell2Root = root.resolve("repo2");
    Files.createDirectories(cell2Root.getPath());

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.create(
            cell1Root,
            ConfigBuilder.createFromText(REPOSITORIES_SECTION, " simple = " + cell2Root));

    assertThat(
        cellPathResolver.getPathMapping(),
        Matchers.equalTo(
            ImmutableMap.of(CellName.ROOT_CELL_NAME, cell1Root, CellName.of("simple"), cell2Root)));
  }

  @Test
  public void transtiveMappingForNonexistantCell() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    AbsPath root = AbsPath.of(vfs.getPath("/opt/local/"));
    AbsPath cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root.getPath());
    AbsPath cell2Root = root.resolve("repo2");

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.create(
            cell1Root,
            ConfigBuilder.createFromText(REPOSITORIES_SECTION, " simple = " + cell2Root));

    // Allow non-existant paths; Buck should allow paths whose .buckconfigs
    // cannot be loaded.
    assertThat(
        cellPathResolver.getPathMapping(),
        Matchers.equalTo(
            ImmutableMap.of(CellName.ROOT_CELL_NAME, cell1Root, CellName.of("simple"), cell2Root)));
  }

  @Test
  public void transitiveMappingForSymlinkCycle() throws Exception {
    Assume.assumeTrue(Platform.detect() != Platform.WINDOWS);

    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    AbsPath root = AbsPath.of(vfs.getPath("/opt/local/"));
    AbsPath cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root.getPath());

    AbsPath cell2Root = root.resolve("repo2");
    Files.createDirectories(cell2Root.getPath());

    AbsPath symlinkPath = cell2Root.resolve("symlink");
    CreateSymlinksForTests.createSymLink(symlinkPath.getPath(), cell2Root.getPath());

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.create(
            cell1Root, ConfigBuilder.createFromText(REPOSITORIES_SECTION, " two = ../repo2"));

    Files.write(
        cell2Root.resolve(".buckconfig").getPath(),
        ImmutableList.of(REPOSITORIES_SECTION, " three = symlink"),
        StandardCharsets.UTF_8);

    assertThat(
        cellPathResolver.getPathMapping(),
        Matchers.equalTo(
            ImmutableMap.of(CellName.ROOT_CELL_NAME, cell1Root, CellName.of("two"), cell2Root)));
  }

  @Test
  public void transitiveMappingForDiamond() throws Exception {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    AbsPath root = AbsPath.of(vfs.getPath("/opt/local/"));
    AbsPath cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root.getPath());
    AbsPath cellLeftRoot = root.resolve("left");
    Files.createDirectories(cellLeftRoot.getPath());
    AbsPath cellRightRoot = root.resolve("right");
    Files.createDirectories(cellRightRoot.getPath());
    AbsPath cellCenterRoot = root.resolve("center");
    Files.createDirectories(cellCenterRoot.getPath());

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.create(
            cell1Root,
            ConfigBuilder.createFromText(
                REPOSITORIES_SECTION,
                " left = " + cellLeftRoot,
                " right = " + cellRightRoot,
                " center = " + cellCenterRoot));

    Files.write(
        cellLeftRoot.resolve(".buckconfig").getPath(),
        ImmutableList.of(REPOSITORIES_SECTION, " center = " + cellCenterRoot),
        StandardCharsets.UTF_8);

    Files.write(
        cellRightRoot.resolve(".buckconfig").getPath(),
        ImmutableList.of(REPOSITORIES_SECTION, " center = " + cellCenterRoot),
        StandardCharsets.UTF_8);

    assertThat(
        cellPathResolver.getPathMapping(),
        Matchers.equalTo(
            ImmutableMap.<CellName, AbsPath>builder()
                .put(CellName.ROOT_CELL_NAME, cell1Root)
                .put(CellName.of("center"), cellCenterRoot)
                .put(CellName.of("left"), cellLeftRoot)
                .put(CellName.of("right"), cellRightRoot)
                .build()));
  }

  @Test
  public void canonicalCellNameForRootIsEmpty() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    DefaultCellPathResolver cellPathResolver =
        TestCellPathResolver.create(
            vfs.getPath("/foo/root"), ImmutableMap.of("root", vfs.getPath("/foo/root")));
    assertEquals(Optional.empty(), cellPathResolver.getCanonicalCellName(vfs.getPath("/foo/root")));
  }

  @Test
  public void canonicalCellNameForCellIsLexicographicallySmallest() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    DefaultCellPathResolver cellPathResolver =
        TestCellPathResolver.create(
            vfs.getPath("/foo/root"),
            ImmutableMap.of(
                "root", vfs.getPath("/foo/root"),
                "a", vfs.getPath("/foo/cell"),
                "b", vfs.getPath("/foo/cell")));

    assertEquals(Optional.of("a"), cellPathResolver.getCanonicalCellName(vfs.getPath("/foo/cell")));

    cellPathResolver =
        TestCellPathResolver.create(
            vfs.getPath("/foo/root"),
            ImmutableMap.of(
                "root", vfs.getPath("/foo/root"),
                "b", vfs.getPath("/foo/cell"),
                "a", vfs.getPath("/foo/cell")));

    assertEquals(
        "After flipping insertion order, still smallest.",
        Optional.of("a"),
        cellPathResolver.getCanonicalCellName(vfs.getPath("/foo/cell")));
  }

  @Test
  public void cellPathsAreCorrectlySorted() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/root").toAbsolutePath();
    Path a = vfs.getPath("/root/a");
    Path abcde = vfs.getPath("/root/a/b/c/d/e");
    Path afg = vfs.getPath("/root/a/f/g");
    Path i = vfs.getPath("/root/i");

    DefaultCellPathResolver cellPathResolver =
        TestCellPathResolver.create(
            root,
            // out of order
            ImmutableMap.of(
                "root", root,
                "abcde", abcde,
                "i", i,
                "afg", afg,
                "a", a));

    assertEquals(
        cellPathResolver.getCellPathsByRootCellExternalName(),
        ImmutableMap.of(
            "i", AbsPath.of(i),
            "afg", AbsPath.of(afg),
            "abcde", AbsPath.of(abcde),
            "a", AbsPath.of(a),
            "root", AbsPath.of(root)));

    assertEquals(
        cellPathResolver.getKnownRoots(),
        ImmutableSortedSet.orderedBy(AbsPath.comparator())
            .add(AbsPath.of(i), AbsPath.of(afg), AbsPath.of(abcde), AbsPath.of(a), AbsPath.of(root))
            .build());
  }

  @Test
  public void testGetKnownRootsReturnsAllRoots() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    DefaultCellPathResolver cellPathResolver =
        TestCellPathResolver.create(
            vfs.getPath("/foo/root"),
            ImmutableMap.of(
                "root", vfs.getPath("/foo/root"),
                "a", vfs.getPath("/foo/cell"),
                "b", vfs.getPath("/foo/cell")));

    assertEquals(
        cellPathResolver.getKnownRoots(),
        ImmutableSortedSet.orderedBy(AbsPath.comparator())
            .add(AbsPath.of(vfs.getPath("/foo/root")), AbsPath.of(vfs.getPath("/foo/cell")))
            .build());
  }

  @Test
  public void errorMessageIncludesASpellingSuggestionForUnknownCells() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    DefaultCellPathResolver cellPathResolver =
        TestCellPathResolver.create(
            vfs.getPath("/foo/root"),
            ImmutableMap.of(
                "root", vfs.getPath("/foo/root"),
                "apple", vfs.getPath("/foo/cell"),
                "maple", vfs.getPath("/foo/cell")));

    thrown.expectMessage("Unknown cell: mappl. Did you mean one of [apple, maple] instead?");
    cellPathResolver.getCellPathOrThrow(Optional.of("mappl"));
  }

  @Test
  public void errorMessageIncludesAllCellsWhenNoSpellingSuggestionsAreAvailable() {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());
    DefaultCellPathResolver cellPathResolver =
        TestCellPathResolver.create(
            vfs.getPath("/foo/root"),
            ImmutableMap.of(
                "root", vfs.getPath("/foo/root"),
                "apple", vfs.getPath("/foo/cell"),
                "maple", vfs.getPath("/foo/cell")));

    thrown.expectMessage(
        "Unknown cell: does_not_exist. Did you mean one of [apple, maple, root] instead?");
    cellPathResolver.getCellPathOrThrow(Optional.of("does_not_exist"));
  }
}
