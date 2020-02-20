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

package com.facebook.buck.core.cell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemView;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class CellTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void shouldReturnItselfIfRequestedToGetACellWithAnAbsentOptionalName() {
    Cells cell = new TestCellBuilder().build();

    BuildTarget target = BuildTargetFactory.newInstance("//does/not:matter");
    Cell owner = cell.getCell(target.getCell());

    assertSame(cell.getRootCell(), owner);
  }

  @Test
  public void shouldThrowAnExceptionIfTheNamedCellIsNotPresent() {
    Cells cell = new TestCellBuilder().build();

    BuildTarget target = BuildTargetFactory.newInstance("unknown//does/not:matter");

    // Unregistered cell
    expectedException.expect(Exception.class);
    cell.getCell(target.getCell());
  }

  @Test
  public void shouldResolveNamesOfCellsAgainstThoseGivenInTheBuckConfig() throws IOException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root);
    Path cell2Root = root.resolve("repo2");
    Files.createDirectories(cell2Root);

    ProjectFilesystem filesystem1 =
        TestProjectFilesystems.createProjectFilesystem(cell1Root.toAbsolutePath());
    ProjectFilesystem filesystem2 =
        TestProjectFilesystems.createProjectFilesystem(cell2Root.toAbsolutePath());
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem1)
            .setSections("[repositories]", "example = " + filesystem2.getRootPath())
            .build();

    Cells cell1 = new TestCellBuilder().setBuckConfig(config).setFilesystem(filesystem1).build();
    BuildTarget target = BuildTargetFactory.newInstance("example//does/not:matter");
    Cell other = cell1.getCell(target.getCell());

    assertEquals(cell2Root, other.getFilesystem().getRootPath().getPath());
  }

  @Test
  public void shouldResolveFallbackCell() throws IOException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root);
    Path cell2Root = root.resolve("repo2");
    Files.createDirectories(cell2Root);

    ProjectFilesystem filesystem1 =
        TestProjectFilesystems.createProjectFilesystem(cell1Root.toAbsolutePath());
    ProjectFilesystem filesystem2 =
        TestProjectFilesystems.createProjectFilesystem(cell2Root.toAbsolutePath());
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem1)
            .setSections("[repositories]", "example = " + filesystem2.getRootPath())
            .build();

    Cells cell1 = new TestCellBuilder().setBuckConfig(config).setFilesystem(filesystem1).build();
    Path path =
        cell1.getRootCell().getCellPathResolver().getCellPathOrThrow(Optional.of("example"));

    assertEquals(path, cell2Root);
  }

  @Test
  public void shouldApplyCellConfigOverrides() throws IOException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root);
    Path cell2Root = root.resolve("repo2");
    Files.createDirectories(cell2Root);
    Path cell3Root = root.resolve("repo3");
    Files.createDirectories(cell3Root);

    ProjectFilesystem filesystem1 =
        TestProjectFilesystems.createProjectFilesystem(cell1Root.toAbsolutePath());
    BuckConfig config =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem1)
            .setSections("[repositories]", "second = " + cell2Root, "third = " + cell3Root)
            .build();

    Files.write(
        cell2Root.resolve(".buckconfig"),
        ImmutableList.of("[repositories]", "third = " + cell3Root),
        StandardCharsets.UTF_8);

    Cells cell1 =
        new TestCellBuilder()
            .setBuckConfig(config)
            .setFilesystem(filesystem1)
            .setCellConfigOverride(
                CellConfig.builder()
                    .put(CellName.of("second"), "test", "value", "cell2")
                    .put(CellName.ALL_CELLS_SPECIAL_NAME, "test", "common_value", "all")
                    .build())
            .build();
    BuildTarget target = BuildTargetFactory.newInstance("second//does/not:matter");

    Cell cell2 = cell1.getCell(target.getCell());
    assertThat(
        cell2.getBuckConfig().getValue("test", "value"), Matchers.equalTo(Optional.of("cell2")));

    BuildTarget target3 = BuildTargetFactory.newInstance("third//does/not:matter");
    Cell cell3 = cell1.getCell(target3.getCell());
    assertThat(
        cell3.getBuckConfig().getValue("test", "common_value"),
        Matchers.equalTo(Optional.of("all")));
  }

  @Test
  public void fileSystemViewForSourceFilesShouldListExistingFile() throws IOException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path cellRoot = root.resolve("repo");
    Files.createDirectories(cellRoot);
    Path someFile = cellRoot.resolve("somefile");
    Files.createFile(someFile);

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(cellRoot.toAbsolutePath());

    Cells cell = new TestCellBuilder().setFilesystem(filesystem).build();
    ProjectFilesystemView view = cell.getRootCell().getFilesystemViewForSourceFiles();
    ImmutableCollection<Path> list = view.getDirectoryContents(cellRoot);

    assertTrue(list.contains(cellRoot.relativize(someFile)));
  }

  @Test
  public void fileSystemViewForSourceFilesShouldIgnoreBuckOut() throws IOException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path cellRoot = root.resolve("repo");
    Files.createDirectories(cellRoot);

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(cellRoot.toAbsolutePath());

    Path buckOutRelative = filesystem.getBuckPaths().getBuckOut();
    Path buckOut = cellRoot.resolve(buckOutRelative);

    Files.createDirectories(buckOut);

    Cells cell = new TestCellBuilder().setFilesystem(filesystem).build();

    assertTrue(filesystem.isDirectory(buckOutRelative));

    ProjectFilesystemView view = cell.getRootCell().getFilesystemViewForSourceFiles();
    ImmutableCollection<Path> list = view.getDirectoryContents(cellRoot);

    assertTrue(list.isEmpty());
  }
}
