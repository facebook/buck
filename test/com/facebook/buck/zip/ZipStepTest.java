/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.zip;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;

public class ZipStepTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  private ExecutionContext executionContext;

  @Before
  public void setUp() {
    executionContext = TestExecutionContext.newBuilder()
        .setProjectFilesystem(new ProjectFilesystem(tmp.getRoot().toPath()))
        .build();
  }

  @Test
  public void shouldCreateANewZipFileFromScratch() throws IOException {
    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");

    File toZip = tmp.newFolder("zipdir");
    Files.touch(new File(toZip, "file1.txt"));
    Files.touch(new File(toZip, "file2.txt"));
    Files.touch(new File(toZip, "file3.txt"));

    ZipStep step = new ZipStep(
        Paths.get("zipstep/output.zip"),
        ImmutableSet.<Path>of(),
        false,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        Paths.get("zipdir"));
    assertEquals(0, step.execute(executionContext));

    try (Zip zip = new Zip(out, false)) {
      assertEquals(ImmutableSet.of("file1.txt", "file2.txt", "file3.txt"), zip.getFileNames());
    }
  }

  @Test
  public void willOnlyIncludeEntriesInThePathsArgumentIfAnyAreSet() throws IOException {
    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");

    File toZip = tmp.newFolder("zipdir");
    Files.touch(new File(toZip, "file1.txt"));
    Files.touch(new File(toZip, "file2.txt"));
    Files.touch(new File(toZip, "file3.txt"));

    ZipStep step = new ZipStep(
        Paths.get("zipstep/output.zip"),
        ImmutableSet.of(Paths.get("zipdir/file2.txt")),
        false,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        Paths.get("zipdir"));
    assertEquals(0, step.execute(executionContext));

    try (Zip zip = new Zip(out, false)) {
      assertEquals(ImmutableSet.of("file2.txt"), zip.getFileNames());
    }
  }

  @Test
  public void willRecurseIntoSubdirectories() throws IOException {
    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");

    File toZip = tmp.newFolder("zipdir");
    Files.touch(new File(toZip, "file1.txt"));
    assertTrue(new File(toZip, "child").mkdir());
    Files.touch(new File(toZip, "child/file2.txt"));

    ZipStep step = new ZipStep(
        Paths.get("zipstep/output.zip"),
        ImmutableSet.<Path>of(),
        false,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        Paths.get("zipdir"));
    assertEquals(0, step.execute(executionContext));

    try (Zip zip = new Zip(out, false)) {
      assertEquals(ImmutableSet.of("file1.txt", "child/file2.txt"), zip.getFileNames());
    }
  }

  @Test
  public void mustIncludeTheContentsOfFilesThatAreSymlinked() throws IOException {
    // Symlinks on Windows are _hard_. Let's go shopping.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");
    File target = new File(parent, "target");
    Files.write("example content", target, Charsets.UTF_8);

    File toZip = tmp.newFolder("zipdir");
    Path path = toZip.toPath().resolve("file.txt");
    java.nio.file.Files.createSymbolicLink(path, target.toPath());

    ZipStep step = new ZipStep(
        Paths.get("zipstep/output.zip"),
        ImmutableSet.<Path>of(),
        false,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        Paths.get("zipdir"));
    assertEquals(0, step.execute(executionContext));

    try (Zip zip = new Zip(out, false)) {
      assertEquals(ImmutableSet.of("file.txt"), zip.getFileNames());
      byte[] contents = zip.readFully("file.txt");

      assertArrayEquals("example content".getBytes(), contents);
    }
  }

  @Test
  public void overwritingAnExistingZipFileIsAnError() throws IOException {
    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");

    try (Zip zip = new Zip(out, true)) {
      zip.add("file1.txt", "");
    }


    ZipStep step = new ZipStep(
        Paths.get("zipstep"),
        ImmutableSet.<Path>of(),
        false,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        Paths.get("zipdir"));
    assertEquals(1, step.execute(executionContext));
  }

  @Test
  public void shouldBeAbleToJunkPaths() throws IOException {
    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");

    File toZip = tmp.newFolder("zipdir");
    assertTrue(new File(toZip, "child").mkdir());
    Files.touch(new File(toZip, "child/file1.txt"));

    ZipStep step = new ZipStep(
        Paths.get("zipstep/output.zip"),
        ImmutableSet.<Path>of(),
        true,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        Paths.get("zipdir"));
    assertEquals(0, step.execute(executionContext));

    try (Zip zip = new Zip(out, false)) {
      assertEquals(ImmutableSet.of("file1.txt"), zip.getFileNames());
    }
  }

  @Test
  public void zipWithEmptyDir() throws IOException {
    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");

    tmp.newFolder("zipdir");
    tmp.newFolder("zipdir/foo/");
    tmp.newFolder("zipdir/bar/");

    ZipStep step = new ZipStep(
        Paths.get("zipstep/output.zip"),
        ImmutableSet.<Path>of(),
        true,
        ZipStep.DEFAULT_COMPRESSION_LEVEL,
        Paths.get("zipdir"));
    assertEquals(0, step.execute(executionContext));

    try (Zip zip = new Zip(out, false)) {
      assertEquals(ImmutableSet.of("", "foo/", "bar/"), zip.getDirNames());
    }
  }

  /**
   * Tests a couple bugs:
   *     1) {@link com.facebook.buck.zip.OverwritingZipOutputStream} was writing uncompressed zip
   *        entries incorrectly.
   *     2) {@link ZipStep} wasn't setting the output size when writing uncompressed entries.
   */
  @Test
  public void minCompressionWritesCorrectZipFile() throws IOException {
    File parent = tmp.newFolder("zipstep");
    File out = new File(parent, "output.zip");

    File toZip = tmp.newFolder("zipdir");
    byte[] contents = "hello world".getBytes();
    Files.write(contents, new File(toZip, "file1.txt"));
    Files.write(contents, new File(toZip, "file2.txt"));
    Files.write(contents, new File(toZip, "file3.txt"));

    ZipStep step = new ZipStep(
        Paths.get("zipstep/output.zip"),
        ImmutableSet.<Path>of(),
        false,
        ZipStep.MIN_COMPRESSION_LEVEL,
        Paths.get("zipdir"));
    assertEquals(0, step.execute(executionContext));

    // Use apache's common-compress to parse the zip file, since it reads the central
    // directory and will verify it's valid.
    try (ZipFile zip = new ZipFile(out)) {
      Enumeration<ZipArchiveEntry> entries = zip.getEntries();
      ZipArchiveEntry entry1 = entries.nextElement();
      assertArrayEquals(contents, ByteStreams.toByteArray(zip.getInputStream(entry1)));
      ZipArchiveEntry entry2 = entries.nextElement();
      assertArrayEquals(contents, ByteStreams.toByteArray(zip.getInputStream(entry2)));
      ZipArchiveEntry entry3 = entries.nextElement();
      assertArrayEquals(contents, ByteStreams.toByteArray(zip.getInputStream(entry3)));
    }
  }

}
