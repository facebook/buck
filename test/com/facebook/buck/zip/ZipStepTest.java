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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.ZipArchive;
import com.facebook.buck.util.CreateSymlinksForTests;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.facebook.buck.util.unarchive.ExistingFileMode;
import com.facebook.buck.util.zip.OverwritingZipOutputStreamImpl;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.facebook.buck.util.zip.ZipConstants;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Date;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ZipStepTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws InterruptedException {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void shouldCreateANewZipFileFromScratch() throws Exception {
    Path parent = tmp.newFolder("zipstep");
    Path out = parent.resolve("output.zip");

    Path toZip = tmp.newFolder("zipdir");
    Files.createFile(toZip.resolve("file1.txt"));
    Files.createFile(toZip.resolve("file2.txt"));
    Files.createFile(toZip.resolve("file3.txt"));

    ZipStep step =
        new ZipStep(
            filesystem,
            Paths.get("zipstep/output.zip"),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.DEFAULT,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    try (ZipArchive zipArchive = new ZipArchive(out, false)) {
      assertEquals(
          ImmutableSet.of("file1.txt", "file2.txt", "file3.txt"), zipArchive.getFileNames());
    }
  }

  @Test
  public void willOnlyIncludeEntriesInThePathsArgumentIfAnyAreSet() throws Exception {
    Path parent = tmp.newFolder("zipstep");
    Path out = parent.resolve("output.zip");

    Path toZip = tmp.newFolder("zipdir");

    Files.createFile(toZip.resolve("file1.txt"));
    Files.createFile(toZip.resolve("file2.txt"));
    Files.createFile(toZip.resolve("file3.txt"));

    ZipStep step =
        new ZipStep(
            filesystem,
            Paths.get("zipstep/output.zip"),
            ImmutableSet.of(Paths.get("zipdir/file2.txt")),
            false,
            ZipCompressionLevel.DEFAULT,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    try (ZipArchive zipArchive = new ZipArchive(out, false)) {
      assertEquals(ImmutableSet.of("file2.txt"), zipArchive.getFileNames());
    }
  }

  @Test
  public void willRecurseIntoSubdirectories() throws Exception {
    Path parent = tmp.newFolder("zipstep");
    Path out = parent.resolve("output.zip");

    Path toZip = tmp.newFolder("zipdir");
    Files.createFile(toZip.resolve("file1.txt"));
    Files.createDirectories(toZip.resolve("child"));
    Files.createFile(toZip.resolve("child/file2.txt"));

    ZipStep step =
        new ZipStep(
            filesystem,
            Paths.get("zipstep/output.zip"),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.DEFAULT,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    // Make sure we have the right attributes.
    try (ZipFile zipFile = new ZipFile(out.toFile())) {
      ZipArchiveEntry entry = zipFile.getEntry("child/");
      assertNotEquals(entry.getUnixMode() & MostFiles.S_IFDIR, 0);
    }

    try (ZipArchive zipArchive = new ZipArchive(out, false)) {
      assertEquals(ImmutableSet.of("file1.txt", "child/file2.txt"), zipArchive.getFileNames());
    }
  }

  @Test
  public void mustIncludeTheContentsOfFilesThatAreSymlinked() throws Exception {
    // Symlinks on Windows are _hard_. Let's go shopping.
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    Path parent = tmp.newFolder("zipstep");
    Path out = parent.resolve("output.zip");
    Path target = parent.resolve("target");
    Files.write(target, "example content".getBytes(UTF_8));

    Path toZip = tmp.newFolder("zipdir");
    Path path = toZip.resolve("file.txt");
    CreateSymlinksForTests.createSymLink(path, target);

    ZipStep step =
        new ZipStep(
            filesystem,
            Paths.get("zipstep/output.zip"),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.DEFAULT,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    try (ZipArchive zipArchive = new ZipArchive(out, false)) {
      assertEquals(ImmutableSet.of("file.txt"), zipArchive.getFileNames());
      byte[] contents = zipArchive.readFully("file.txt");

      assertArrayEquals("example content".getBytes(), contents);
    }
  }

  @Test
  public void overwritingAnExistingZipFileIsAnError() throws Exception {
    Path parent = tmp.newFolder("zipstep");
    Path out = parent.resolve("output.zip");

    try (ZipArchive zipArchive = new ZipArchive(out, true)) {
      zipArchive.add("file1.txt", "");
    }

    ZipStep step =
        new ZipStep(
            filesystem,
            Paths.get("zipstep"),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.DEFAULT,
            Paths.get("zipdir"));
    assertEquals(1, step.execute(TestExecutionContext.newInstance()).getExitCode());
  }

  @Test
  public void shouldBeAbleToJunkPaths() throws Exception {
    Path parent = tmp.newFolder("zipstep");
    Path out = parent.resolve("output.zip");

    Path toZip = tmp.newFolder("zipdir");
    Files.createDirectories(toZip.resolve("child"));
    Files.createFile(toZip.resolve("child/file1.txt"));

    ZipStep step =
        new ZipStep(
            filesystem,
            Paths.get("zipstep/output.zip"),
            ImmutableSet.of(),
            true,
            ZipCompressionLevel.DEFAULT,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    try (ZipArchive zipArchive = new ZipArchive(out, false)) {
      assertEquals(ImmutableSet.of("file1.txt"), zipArchive.getFileNames());
    }
  }

  @Test
  public void zipWithEmptyDir() throws Exception {
    Path parent = tmp.newFolder("zipstep");
    Path out = parent.resolve("output.zip");

    tmp.newFolder("zipdir");
    tmp.newFolder("zipdir/foo/");
    tmp.newFolder("zipdir/bar/");

    ZipStep step =
        new ZipStep(
            filesystem,
            Paths.get("zipstep/output.zip"),
            ImmutableSet.of(),
            true,
            ZipCompressionLevel.DEFAULT,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    try (ZipArchive zipArchive = new ZipArchive(out, false)) {
      assertEquals(ImmutableSet.of("", "foo/", "bar/"), zipArchive.getDirNames());
    }

    // Directories should be stored, not deflated as this sometimes causes issues
    // (e.g. installing an .ipa over the air in iOS 9.1)
    try (ZipInputStream is = new ZipInputStream(new FileInputStream(out.toFile()))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertEquals(entry.getName(), ZipEntry.STORED, entry.getMethod());
      }
    }
  }

  /**
   * Tests a couple bugs: 1) {@link OverwritingZipOutputStreamImpl} was writing uncompressed zip
   * entries incorrectly. 2) {@link ZipStep} wasn't setting the output size when writing
   * uncompressed entries.
   */
  @Test
  public void minCompressionWritesCorrectZipFile() throws Exception {
    Path parent = tmp.newFolder("zipstep");
    Path out = parent.resolve("output.zip");

    Path toZip = tmp.newFolder("zipdir");
    byte[] contents = "hello world".getBytes();
    Files.write(toZip.resolve("file1.txt"), contents);
    Files.write(toZip.resolve("file2.txt"), contents);
    Files.write(toZip.resolve("file3.txt"), contents);

    ZipStep step =
        new ZipStep(
            filesystem,
            Paths.get("zipstep/output.zip"),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.NONE,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    // Use apache's common-compress to parse the zip file, since it reads the central
    // directory and will verify it's valid.
    try (ZipFile zipFile = new ZipFile(out.toFile())) {
      Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
      ZipArchiveEntry entry1 = entries.nextElement();
      assertArrayEquals(contents, ByteStreams.toByteArray(zipFile.getInputStream(entry1)));
      ZipArchiveEntry entry2 = entries.nextElement();
      assertArrayEquals(contents, ByteStreams.toByteArray(zipFile.getInputStream(entry2)));
      ZipArchiveEntry entry3 = entries.nextElement();
      assertArrayEquals(contents, ByteStreams.toByteArray(zipFile.getInputStream(entry3)));
    }
  }

  @Test
  public void timesAreSanitized() throws Exception {
    Path parent = tmp.newFolder("zipstep");

    // Create a zip file with a file and a directory.
    Path toZip = tmp.newFolder("zipdir");
    Files.createDirectories(toZip.resolve("child"));
    Files.createFile(toZip.resolve("child/file.txt"));
    Path outputZip = parent.resolve("output.zip");
    ZipStep step =
        new ZipStep(
            filesystem,
            outputZip,
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.DEFAULT,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    assertTrue(Files.exists(outputZip));
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new FileInputStream(outputZip.toFile()))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertEquals(entry.getName(), dosEpoch, new Date(entry.getTime()));
      }
    }
  }

  @Test
  public void zipMaintainsExecutablePermissions() throws InterruptedException, IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    Path parent = tmp.newFolder("zipstep");
    Path toZip = tmp.newFolder("zipdir");
    Path file = toZip.resolve("foo.sh");
    ImmutableSet<PosixFilePermission> filePermissions =
        ImmutableSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);
    Files.createFile(file, PosixFilePermissions.asFileAttribute(filePermissions));
    Path outputZip = parent.resolve("output.zip");
    ZipStep step =
        new ZipStep(
            filesystem,
            outputZip,
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.NONE,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    Path destination = tmp.newFolder("output");

    ArchiveFormat.ZIP
        .getUnarchiver()
        .extractArchive(
            new DefaultProjectFilesystemFactory(),
            outputZip,
            destination,
            ExistingFileMode.OVERWRITE);
    assertTrue(Files.isExecutable(destination.resolve("foo.sh")));
  }

  @Test
  public void zipEntryOrderingIsFilesystemAgnostic() throws Exception {
    Path output = Paths.get("output");
    Path zipdir = Paths.get("zipdir");

    // Run the zip step on a filesystem with a particular ordering.
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    ExecutionContext context = TestExecutionContext.newInstance();
    filesystem.mkdirs(zipdir);
    filesystem.touch(zipdir.resolve("file1"));
    filesystem.touch(zipdir.resolve("file2"));
    ZipStep step =
        new ZipStep(filesystem, output, ImmutableSet.of(), false, ZipCompressionLevel.NONE, zipdir);
    assertEquals(0, step.execute(context).getExitCode());
    ImmutableList<String> entries1 = getEntries(filesystem, output);

    // Run the zip step on a filesystem with a different ordering.
    filesystem = new FakeProjectFilesystem();
    context = TestExecutionContext.newInstance();
    filesystem.mkdirs(zipdir);
    filesystem.touch(zipdir.resolve("file2"));
    filesystem.touch(zipdir.resolve("file1"));
    step =
        new ZipStep(filesystem, output, ImmutableSet.of(), false, ZipCompressionLevel.NONE, zipdir);
    assertEquals(0, step.execute(context).getExitCode());
    ImmutableList<String> entries2 = getEntries(filesystem, output);

    assertEquals(entries1, entries2);
  }

  private ImmutableList<String> getEntries(ProjectFilesystem filesystem, Path zip)
      throws IOException {
    ImmutableList.Builder<String> entries = ImmutableList.builder();
    try (ZipInputStream is = new ZipInputStream(filesystem.newFileInputStream(zip))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        entries.add(entry.getName());
      }
    }
    return entries.build();
  }
}
