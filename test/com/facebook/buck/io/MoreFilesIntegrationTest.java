/*
 * Copyright 2014-present Facebook, Inc.
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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MoreFilesIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testCopyTestdataDirectoryWithSymlinks() throws IOException, InterruptedException {
    Platform platform = Platform.detect();
    Assume.assumeTrue(platform == Platform.LINUX || platform == Platform.MACOS);

    Path root = tmp.newFolder().toPath();

    Path srcDir = root.resolve("src");
    Files.createDirectory(srcDir);
    Path sourceFile = srcDir.resolve("file.txt");
    Files.write(sourceFile, "contents\n".getBytes(UTF_8));

    Files.createSymbolicLink(srcDir.resolve("link.txt"), srcDir.relativize(sourceFile));

    MoreFiles.copyRecursively(root.resolve("src"), root.resolve("out"));
    assertTrue(Files.isSymbolicLink(root.resolve("src/link.txt")));
    assertTrue(Files.isSymbolicLink(root.resolve("out/link.txt")));

    byte[] bytes = Files.readAllBytes(root.resolve("out/link.txt"));
    assertArrayEquals("contents\n".getBytes(), bytes);

    assertEquals(
        "link.txt should point to file.txt in the same directory.",
        Paths.get("file.txt"),
        Files.readSymbolicLink(root.resolve("out/link.txt")));

    Files.write(root.resolve("src/link.txt"), "replacement\n".getBytes());

    assertArrayEquals(
        "replacement\n".getBytes(),
        Files.readAllBytes(root.resolve("src/file.txt")));
    assertArrayEquals(
        "The replacement bytes should be reflected in the symlink.",
        "replacement\n".getBytes(),
        Files.readAllBytes(root.resolve("src/link.txt")));
    assertArrayEquals(
        "contents\n".getBytes(),
        Files.readAllBytes(root.resolve("out/file.txt")));
    assertArrayEquals(
        "The copied symlink should be unaffected.",
        "contents\n".getBytes(),
        Files.readAllBytes(root.resolve("out/link.txt")));
  }

  @Test
  public void testDiffFileContents() throws IOException {
    Path inputFile = tmp.newFolder().toPath().resolve("MoreFiles.txt");
    Files.write(
        inputFile,
        Joiner.on("\n").join("AAA", "BBB", "CCC").getBytes(UTF_8));

    List<String> diffLines;
    String testPath = inputFile.toAbsolutePath().toString();
    File testFile = new File(testPath);

    diffLines = MoreFiles.diffFileContents(
        Arrays.asList("AAA", "BBB", "CCC"),
        testFile);
    assertEquals(diffLines, new ArrayList<String>());

    diffLines = MoreFiles.diffFileContents(Arrays.asList("AAA", "BBB"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| CCC |  |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("AAA", "CCC"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| BBB | CCC |",
            "| CCC |  |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("BBB", "CCC"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| AAA | BBB |",
            "| BBB | CCC |",
            "| CCC |  |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("AAA"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| BBB |  |",
            "| CCC |  |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("BBB"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| AAA | BBB |",
            "| BBB |  |",
            "| CCC |  |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("CCC"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| AAA | CCC |",
            "| BBB |  |",
            "| CCC |  |"));

    diffLines = MoreFiles.diffFileContents(new ArrayList<String>(), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| AAA |  |",
            "| BBB |  |",
            "| CCC |  |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("AAA", "BBB", "CCC", "xxx"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "|  | xxx |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("AAA", "BBB", "xxx", "CCC"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| CCC | xxx |",
            "|  | CCC |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("AAA", "xxx", "BBB", "CCC"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| BBB | xxx |",
            "| CCC | BBB |",
            "|  | CCC |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("xxx", "AAA", "BBB", "CCC"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| AAA | xxx |",
            "| BBB | AAA |",
            "| CCC | BBB |",
            "|  | CCC |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("AAA", "BBB", "xxx"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| CCC | xxx |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("AAA", "xxx", "CCC"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| BBB | xxx |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("xxx", "BBB", "CCC"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| AAA | xxx |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("AAA", "xxx", "yyy"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| BBB | xxx |",
            "| CCC | yyy |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("xxx", "BBB", "yyy"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| AAA | xxx |",
            "| CCC | yyy |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("xxx", "yyy", "CCC"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| AAA | xxx |",
            "| BBB | yyy |"));

    diffLines = MoreFiles.diffFileContents(Arrays.asList("xxx", "yyy", "zzz"), testFile);
    assertEquals(diffLines, Arrays.asList(
            "| AAA | xxx |",
            "| BBB | yyy |",
            "| CCC | zzz |"));
  }
}
