/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

public class CellTest {

  @Test
  public void shouldReturnItselfIfRequestedToGetACellWithAnAbsentOptionalName()
      throws IOException, InterruptedException {
    Cell cell = new TestCellBuilder().build();

    BuildTarget target = BuildTargetFactory.newInstance(cell.getFilesystem(), "//does/not:matter");
    Cell owner = cell.getCell(target);

    assertSame(cell, owner);
  }

  @Test(expected = HumanReadableException.class)
  public void shouldThrowAnExceptionIfTheNamedCellIsNotPresent()
      throws IOException, InterruptedException {
    Cell cell = new TestCellBuilder().build();

    BuildTarget target = BuildTargetFactory.newInstance(
        FakeProjectFilesystem.createJavaOnlyFilesystem(),
        "//does/not:matter");

    // Target's filesystem root is unknown to cell.
    cell.getCell(target);
  }

  @Test
  public void shouldResolveNamesOfCellsAgainstThoseGivenInTheBuckConfig()
      throws IOException, InterruptedException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root);
    Path cell2Root = root.resolve("repo2");
    Files.createDirectories(cell2Root);

    ProjectFilesystem filesystem1 = new ProjectFilesystem(cell1Root.toAbsolutePath());
    ProjectFilesystem filesystem2 = new ProjectFilesystem(cell2Root.toAbsolutePath());
    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem1)
        .setSections(
            "[repositories]",
            "example = " + filesystem2.getRootPath().toString())
        .build();

    Cell cell1 = new TestCellBuilder().setBuckConfig(config).setFilesystem(filesystem1).build();
    BuildTarget target = BuildTargetFactory.newInstance(filesystem2, "//does/not:matter");
    Cell other = cell1.getCell(target);

    assertEquals(cell2Root, other.getFilesystem().getRootPath());
  }

  @Test
  public void shouldResolveFallbackCell()
      throws IOException, InterruptedException {
    FileSystem vfs = Jimfs.newFileSystem(Configuration.unix());

    Path root = vfs.getPath("/opt/local/");
    Path cell1Root = root.resolve("repo1");
    Files.createDirectories(cell1Root);
    Path cell2Root = root.resolve("repo2");
    Files.createDirectories(cell2Root);

    ProjectFilesystem filesystem1 = new ProjectFilesystem(cell1Root.toAbsolutePath());
    ProjectFilesystem filesystem2 = new ProjectFilesystem(cell2Root.toAbsolutePath());
    BuckConfig config = FakeBuckConfig.builder()
        .setFilesystem(filesystem1)
        .setSections(
            "[repositories]",
            "example = " + filesystem2.getRootPath().toString())
        .build();

    Cell cell1 = new TestCellBuilder().setBuckConfig(config).setFilesystem(filesystem1).build();
    Path path = cell1.getCellRoots().apply(Optional.of("@example"));

    assertEquals(path, cell2Root);
  }
}
