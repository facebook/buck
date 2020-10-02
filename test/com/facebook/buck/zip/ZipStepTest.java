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

package com.facebook.buck.zip;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.ZipStep;
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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
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
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
  }

  @Test
  public void shouldCreateANewZipFileFromScratch() throws Exception {
    AbsPath parent = tmp.newFolder("zipstep");
    AbsPath out = parent.resolve("output.zip");

    AbsPath toZip = tmp.newFolder("zipdir");
    Files.createFile(toZip.resolve("file1.txt").getPath());
    Files.createFile(toZip.resolve("file2.txt").getPath());
    Files.createFile(toZip.resolve("file3.txt").getPath());

    ZipStep step =
        ZipStep.of(
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

  @Test(timeout = 600000)
  public void handlesLargeFiles() throws Exception {
    AbsPath parent = tmp.newFolder("zipstep");
    AbsPath out = parent.resolve("output.zip");

    AbsPath toZip = tmp.newFolder("zipdir");
    long entrySize = 4_500_000_000L; // More than 2**32 so zip64 extension needs to be used.
    try (OutputStream outputStream = Files.newOutputStream(toZip.resolve("file1.bin").getPath())) {
      byte[] writeBuffer = new byte[64_000];
      long written = 0;
      while (written != entrySize) {
        int toWrite = (int) Math.min(writeBuffer.length, entrySize - written);
        outputStream.write(writeBuffer, 0, toWrite);
        written += toWrite;
      }
    }
    Files.createFile(toZip.resolve("file2.bin").getPath()); // To test file offset > 2**32.

    ZipStep step =
        ZipStep.of(
            filesystem,
            Paths.get("zipstep/output.zip"),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.NONE,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(out.getPath()))) {
      ZipEntry entry = zipInputStream.getNextEntry();
      assertEquals("file1.bin", entry.getName());
      assertEquals(entrySize, ByteStreams.exhaust(zipInputStream));
      entry = zipInputStream.getNextEntry();
      assertEquals("file2.bin", entry.getName());
      assertEquals(0, ByteStreams.exhaust(zipInputStream));
      assertNull(zipInputStream.getNextEntry());
    }

    // JarFile reads files differently than ZipInputStream, test that both work.
    try (JarFile jarFile = new JarFile(out.toFile())) {
      List<ZipEntry> entries = jarFile.stream().collect(Collectors.toList());
      assertEquals(2, entries.size());
      assertEquals("file1.bin", entries.get(0).getName());
      assertEquals(entrySize, ByteStreams.exhaust(jarFile.getInputStream(entries.get(0))));
      assertEquals("file2.bin", entries.get(1).getName());
      assertEquals(0, ByteStreams.exhaust(jarFile.getInputStream(entries.get(1))));
    }
  }

  @Test
  public void willOnlyIncludeEntriesInThePathsArgumentIfAnyAreSet() throws Exception {
    AbsPath parent = tmp.newFolder("zipstep");
    AbsPath out = parent.resolve("output.zip");

    AbsPath toZip = tmp.newFolder("zipdir");

    Files.createFile(toZip.resolve("file1.txt").getPath());
    Files.createFile(toZip.resolve("file2.txt").getPath());
    Files.createFile(toZip.resolve("file3.txt").getPath());

    ZipStep step =
        ZipStep.of(
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
    AbsPath parent = tmp.newFolder("zipstep");
    AbsPath out = parent.resolve("output.zip");

    AbsPath toZip = tmp.newFolder("zipdir");
    Files.createFile(toZip.resolve("file1.txt").getPath());
    Files.createDirectories(toZip.resolve("child").getPath());
    Files.createFile(toZip.resolve("child/file2.txt").getPath());

    ZipStep step =
        ZipStep.of(
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

    AbsPath parent = tmp.newFolder("zipstep");
    AbsPath out = parent.resolve("output.zip");
    AbsPath target = parent.resolve("target");
    Files.write(target.getPath(), "example content".getBytes(UTF_8));

    AbsPath toZip = tmp.newFolder("zipdir");
    AbsPath path = toZip.resolve("file.txt");
    CreateSymlinksForTests.createSymLink(path, target);

    ZipStep step =
        ZipStep.of(
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
    AbsPath parent = tmp.newFolder("zipstep");
    AbsPath out = parent.resolve("output.zip");

    try (ZipArchive zipArchive = new ZipArchive(out, true)) {
      zipArchive.add("file1.txt", "");
    }

    ZipStep step =
        ZipStep.of(
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
    AbsPath parent = tmp.newFolder("zipstep");
    AbsPath out = parent.resolve("output.zip");

    AbsPath toZip = tmp.newFolder("zipdir");
    Files.createDirectories(toZip.resolve("child").getPath());
    Files.createFile(toZip.resolve("child/file1.txt").getPath());

    ZipStep step =
        ZipStep.of(
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
    AbsPath parent = tmp.newFolder("zipstep");
    AbsPath out = parent.resolve("output.zip");

    tmp.newFolder("zipdir");
    tmp.newFolder("zipdir/foo/");
    tmp.newFolder("zipdir/bar/");

    ZipStep step =
        ZipStep.of(
            filesystem,
            Paths.get("zipstep/output.zip"),
            ImmutableSet.of(),
            true,
            ZipCompressionLevel.DEFAULT,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    try (ZipArchive zipArchive = new ZipArchive(out, false)) {
      assertEquals(ImmutableSet.of("", "foo", "bar"), zipArchive.getDirNames());
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
    AbsPath parent = tmp.newFolder("zipstep");
    AbsPath out = parent.resolve("output.zip");

    AbsPath toZip = tmp.newFolder("zipdir");
    byte[] contents = "hello world".getBytes();
    Files.write(toZip.resolve("file1.txt").getPath(), contents);
    Files.write(toZip.resolve("file2.txt").getPath(), contents);
    Files.write(toZip.resolve("file3.txt").getPath(), contents);

    ZipStep step =
        ZipStep.of(
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
    AbsPath parent = tmp.newFolder("zipstep");

    // Create a zip file with a file and a directory.
    AbsPath toZip = tmp.newFolder("zipdir");
    Files.createDirectories(toZip.resolve("child").getPath());
    Files.createFile(toZip.resolve("child/file.txt").getPath());
    AbsPath outputZip = parent.resolve("output.zip");
    ZipStep step =
        ZipStep.of(
            filesystem,
            outputZip.getPath(),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.DEFAULT,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    assertTrue(Files.exists(outputZip.getPath()));
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(new FileInputStream(outputZip.toFile()))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertEquals(entry.getName(), dosEpoch, new Date(entry.getTime()));
      }
    }
  }

  @Test
  public void zipMaintainsExecutablePermissions() throws IOException, InterruptedException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);

    AbsPath parent = tmp.newFolder("zipstep");
    AbsPath toZip = tmp.newFolder("zipdir");
    AbsPath file = toZip.resolve("foo.sh");
    ImmutableSet<PosixFilePermission> filePermissions =
        ImmutableSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);
    Files.createFile(file.getPath(), PosixFilePermissions.asFileAttribute(filePermissions));
    AbsPath outputZip = parent.resolve("output.zip");
    ZipStep step =
        ZipStep.of(
            filesystem,
            outputZip.getPath(),
            ImmutableSet.of(),
            false,
            ZipCompressionLevel.NONE,
            Paths.get("zipdir"));
    assertEquals(0, step.execute(TestExecutionContext.newInstance()).getExitCode());

    AbsPath destination = tmp.newFolder("output");

    ArchiveFormat.ZIP
        .getUnarchiver()
        .extractArchive(
            new DefaultProjectFilesystemFactory(),
            outputZip.getPath(),
            destination.getPath(),
            ExistingFileMode.OVERWRITE);
    assertTrue(Files.isExecutable(destination.resolve("foo.sh").getPath()));
  }

  @Test
  public void zipEntryOrderingIsFilesystemAgnostic() throws Exception {
    Path output = Paths.get("output");
    Path zipdir = Paths.get("zipdir");
    Path root2 = Paths.get("root2");

    // Run the zip step on a filesystem with a particular ordering.
    StepExecutionContext context = TestExecutionContext.newInstance();
    filesystem.mkdirs(zipdir);
    filesystem.touch(zipdir.resolve("file1"));
    filesystem.touch(zipdir.resolve("file2"));
    ZipStep step =
        ZipStep.of(filesystem, output, ImmutableSet.of(), false, ZipCompressionLevel.NONE, zipdir);
    assertEquals(0, step.execute(context).getExitCode());
    ImmutableList<String> entries1 = getEntries(filesystem, output);

    // Run the zip step on a filesystem with a different ordering.
    filesystem.mkdirs(root2);
    AbsPath rootPath2 = ProjectFilesystemUtils.getAbsPathForRelativePath(tmp.getRoot(), root2);
    filesystem = TestProjectFilesystems.createProjectFilesystem(rootPath2);
    context = TestExecutionContext.newInstance();
    filesystem.mkdirs(zipdir);
    filesystem.touch(zipdir.resolve("file2"));
    filesystem.touch(zipdir.resolve("file1"));
    step =
        ZipStep.of(filesystem, output, ImmutableSet.of(), false, ZipCompressionLevel.NONE, zipdir);
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
