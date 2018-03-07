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

import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

public class ExecutableFinderTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testSearchPathsFileFoundReturnsPath() throws IOException {
    Path dir1 = tmp.newFolder("foo");
    Path dir2 = tmp.newFolder("bar");
    Path dir3 = tmp.newFolder("baz");
    Path file = createExecutable("bar/blech");

    assertEquals(
        Optional.of(file),
        new ExecutableFinder()
            .getOptionalExecutable(
                Paths.get("blech"), ImmutableList.of(dir1, dir2, dir3), ImmutableList.of()));
  }

  @Test
  public void testSearchPathsNonExecutableFileIsIgnored() throws IOException {
    // file.canExecute() always true for Windows
    Assume.assumeFalse(Platform.detect() == Platform.WINDOWS);

    Path dir1 = tmp.newFolder("foo");
    // Note this is not executable.
    tmp.newFile("foo/blech");
    Path dir2 = tmp.newFolder("bar");
    Path dir3 = tmp.newFolder("baz");
    Path file = createExecutable("bar/blech");

    assertEquals(
        Optional.of(file),
        new ExecutableFinder()
            .getOptionalExecutable(
                Paths.get("blech"), ImmutableList.of(dir1, dir2, dir3), ImmutableList.of()));
  }

  @Test
  public void testSearchPathsDirAndFileFoundReturnsFileNotDir() throws IOException {
    Path dir1 = tmp.newFolder("foo");
    // We don't want to find this folder.
    tmp.newFolder("foo", "foo");
    Path dir2 = tmp.newFolder("bar");
    Path file = createExecutable("bar/foo");

    assertEquals(
        Optional.of(file),
        new ExecutableFinder()
            .getOptionalExecutable(
                Paths.get("foo"), ImmutableList.of(dir1, dir2), ImmutableList.of()));
  }

  @Test
  public void testSearchPathsMultipleFileFoundReturnsFirstPath() throws IOException {
    Path dir1 = tmp.newFolder("foo");
    Path dir2 = tmp.newFolder("bar");
    Path dir3 = tmp.newFolder("baz");
    Path file1 = createExecutable("bar/blech");
    createExecutable("baz/blech");

    assertEquals(
        Optional.of(file1),
        new ExecutableFinder()
            .getOptionalExecutable(
                Paths.get("blech"), ImmutableList.of(dir1, dir2, dir3), ImmutableList.of()));
  }

  @Test
  public void testSearchPathsSymlinkToExecutableInsidePathReturnsPath() throws IOException {
    Path dir2 = tmp.newFolder("bar");
    createExecutable("bar/blech_target");
    Path file1 = dir2.resolve("blech");
    Files.createSymbolicLink(file1, Paths.get("blech_target"));

    assertEquals(
        Optional.of(file1),
        new ExecutableFinder()
            .getOptionalExecutable(Paths.get("blech"), ImmutableList.of(dir2), ImmutableList.of()));
  }

  @Test
  public void testSearchPathsSymlinkToExecutableOutsideSearchPathReturnsPath() throws IOException {
    Path dir1 = tmp.newFolder("foo");
    Path dir2 = tmp.newFolder("bar");
    Path dir3 = tmp.newFolder("baz");
    tmp.newFolder("unsearched");
    Path binary = createExecutable("unsearched/binary");
    Path file1 = dir2.resolve("blech");
    Files.createSymbolicLink(file1, binary);

    assertEquals(
        Optional.of(file1),
        new ExecutableFinder()
            .getOptionalExecutable(
                Paths.get("blech"), ImmutableList.of(dir1, dir2, dir3), ImmutableList.of()));
  }

  @Test
  public void testSearchPathsFileNotFoundReturnsAbsent() throws IOException {
    Path dir1 = tmp.newFolder("foo");
    Path dir2 = tmp.newFolder("bar");
    Path dir3 = tmp.newFolder("baz");

    assertEquals(
        Optional.empty(),
        new ExecutableFinder()
            .getOptionalExecutable(
                Paths.get("blech"), ImmutableList.of(dir1, dir2, dir3), ImmutableList.of()));
  }

  @Test
  public void testSearchPathsEmptyReturnsAbsent() {
    assertEquals(
        Optional.empty(),
        new ExecutableFinder()
            .getOptionalExecutable(Paths.get("blech"), ImmutableList.of(), ImmutableList.of()));
  }

  @Test
  public void testSearchPathsWithIsExecutableFunctionFailure() throws IOException {
    Assume.assumeFalse(Platform.detect() == Platform.WINDOWS);

    // Path to search
    Path baz = tmp.newFolder("baz");

    // Unexecutable "executable"
    Path bar = baz.resolve("bar");
    Files.write(bar, "".getBytes(UTF_8));
    assertTrue(bar.toFile().setExecutable(false));

    assertEquals(
        Optional.empty(),
        new ExecutableFinder()
            .getOptionalExecutable(Paths.get("bar"), ImmutableList.of(baz), ImmutableList.of()));
  }

  @Test
  public void testSearchPathsWithExtensions() throws IOException {
    Path dir = tmp.newFolder("foo");
    Path file = createExecutable("foo/bar.EXE");

    assertEquals(
        Optional.of(file),
        new ExecutableFinder()
            .getOptionalExecutable(
                Paths.get("bar"), ImmutableList.of(dir), ImmutableList.of(".BAT", ".EXE")));
  }

  @Test
  public void testSearchPathsWithExtensionsNoMatch() throws IOException {
    Path dir = tmp.newFolder("foo");
    createExecutable("foo/bar.COM");

    assertEquals(
        Optional.empty(),
        new ExecutableFinder()
            .getOptionalExecutable(
                Paths.get("bar"), ImmutableList.of(dir), ImmutableList.of(".BAT", ".EXE")));
  }

  @Test
  public void testThatADirectoryIsNotConsideredAnExecutable() throws IOException {
    Path dir = tmp.newFolder();
    Path exe = dir.resolve("exe");
    Files.createDirectories(exe);

    assertEquals(
        Optional.empty(),
        new ExecutableFinder().getOptionalExecutable(exe.toAbsolutePath(), ImmutableMap.of()));
  }

  @Test
  public void testThatDirectoryWithSwiftExecutableDoesHaveSwiftTool() throws IOException {
    Path dir = tmp.newFolder("foo");
    createExecutable("foo/swift");

    assertEquals(
        Optional.of(dir.resolve("swift")),
        new ExecutableFinder().getOptionalToolPath("swift", ImmutableList.of(dir)));
  }

  @Test
  public void testThatEmptyDirectoryDoesNotHaveSwift() {
    assertEquals(
        Optional.empty(), new ExecutableFinder().getOptionalToolPath("swift", ImmutableList.of()));
  }

  private Path createExecutable(String executablePath) throws IOException {
    Path file = tmp.newFile(executablePath);
    MostFiles.makeExecutable(file);
    return file;
  }
}
