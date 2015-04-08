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

package com.facebook.buck.io;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ExecutableFinderTest {
  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testSearchPathsFileFoundReturnsPath() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    Path dir2 = tmp.newFolder("bar").toPath();
    Path dir3 = tmp.newFolder("baz").toPath();
    Path file = createExecutable("bar/blech");

    assertEquals(
        Optional.of(file),
        new ExecutableFinder()
            .getOptionalExecutable(
                Paths.get("blech"),
                ImmutableList.of(dir1, dir2, dir3),
                ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsNonExecutableFileIsIgnored() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    // Note this is not executable.
    tmp.newFile("foo/blech");
    Path dir2 = tmp.newFolder("bar").toPath();
    Path dir3 = tmp.newFolder("baz").toPath();
    Path file = createExecutable("bar/blech");

    assertEquals(
        Optional.of(file),
        new ExecutableFinder().getOptionalExecutable(
            Paths.get("blech"),
            ImmutableList.of(dir1, dir2, dir3),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsDirAndFileFoundReturnsFileNotDir() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    // We don't want to find this folder.
    tmp.newFolder("foo/foo");
    Path dir2 = tmp.newFolder("bar").toPath();
    Path file = createExecutable("bar/foo");

    assertEquals(
        Optional.of(file),
        new ExecutableFinder().getOptionalExecutable(
            Paths.get("foo"),
            ImmutableList.of(dir1, dir2),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsMultipleFileFoundReturnsFirstPath() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    Path dir2 = tmp.newFolder("bar").toPath();
    Path dir3 = tmp.newFolder("baz").toPath();
    Path file1 = createExecutable("bar/blech");
    createExecutable("baz/blech");

    assertEquals(
        Optional.of(file1),
        new ExecutableFinder().getOptionalExecutable(
            Paths.get("blech"),
            ImmutableList.of(dir1, dir2, dir3),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsSymlinkToExecutableOutsideSearchPathReturnsPath() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    Path dir2 = tmp.newFolder("bar").toPath();
    Path dir3 = tmp.newFolder("baz").toPath();
    tmp.newFolder("unsearched");
    Path binary = createExecutable("unsearched/binary");
    Path file1 = dir2.resolve("blech");
    Files.createSymbolicLink(file1, binary);

    assertEquals(
        Optional.of(file1),
        new ExecutableFinder().getOptionalExecutable(
            Paths.get("blech"),
            ImmutableList.of(dir1, dir2, dir3),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsFileNotFoundReturnsAbsent() throws IOException {
    Path dir1 = tmp.newFolder("foo").toPath();
    Path dir2 = tmp.newFolder("bar").toPath();
    Path dir3 = tmp.newFolder("baz").toPath();

    assertEquals(
        Optional.<Path>absent(),
        new ExecutableFinder().getOptionalExecutable(
            Paths.get("blech"),
            ImmutableList.of(dir1, dir2, dir3),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsEmptyReturnsAbsent() throws IOException {
    assertEquals(
        Optional.<Path>absent(),
        new ExecutableFinder().getOptionalExecutable(
            Paths.get("blech"),
            ImmutableList.<Path>of(),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsWithIsExecutableFunctionFailure() throws IOException {
    // Path to search
    Path baz = tmp.newFolder("baz").toPath();

    // Unexecutable "executable"
    Path bar = baz.resolve("bar");
    Files.write(bar, "".getBytes(UTF_8));
    assertTrue(bar.toFile().setExecutable(false));

    assertEquals(
        Optional.<Path>absent(),
        new ExecutableFinder().getOptionalExecutable(
            Paths.get("bar"),
            ImmutableList.of(baz),
            ImmutableList.<String>of()));
  }

  @Test
  public void testSearchPathsWithExtensions() throws IOException {
    Path dir = tmp.newFolder("foo").toPath();
    Path file = createExecutable("foo/bar.EXE");

    assertEquals(
        Optional.of(file),
        new ExecutableFinder().getOptionalExecutable(
            Paths.get("bar"),
            ImmutableList.of(dir),
            ImmutableList.of(".BAT", ".EXE")));
  }

  @Test
  public void testSearchPathsWithExtensionsNoMatch() throws IOException {
    Path dir = tmp.newFolder("foo").toPath();
    createExecutable("foo/bar.COM");

    assertEquals(
        Optional.absent(),
        new ExecutableFinder().getOptionalExecutable(
            Paths.get("bar"),
            ImmutableList.of(dir),
            ImmutableList.of(".BAT", ".EXE")));
  }

  private Path createExecutable(String executablePath) throws IOException {
    File file = tmp.newFile(executablePath);
    assertTrue(file.setExecutable(true));
    return file.toPath();
  }
}
