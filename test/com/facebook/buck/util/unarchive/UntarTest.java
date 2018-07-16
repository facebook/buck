/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.util.unarchive;

import static com.google.common.collect.Iterables.concat;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class UntarTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths(true);

  // Windows writes files by default as executable...
  private static final boolean FilesAreExecutableByDefault = Platform.detect() == Platform.WINDOWS;

  private ProjectFilesystem filesystem;

  private static final Path OUTPUT_SUBDIR = Paths.get("output_dir");
  private static final FileTime expectedModifiedTime =
      FileTime.from(Instant.parse("2018-02-02T20:29:13Z"));
  private static final String mainDotJava =
      "class Main { public static void main(String[] args) { return; } }";
  private static final String otherDotJava =
      "class Other { public static void main(String[] args) { return; } }";
  private static final String echoDotSh = "#!/bin/sh\necho 'testing'";

  @Before
  public void setUp() {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmpFolder.getRoot());
  }

  private Path getTestFilePath(String extension) {
    Path testDataDirectory = TestDataHelper.getTestDataDirectory(this.getClass());
    return testDataDirectory.resolve("output" + extension);
  }

  private Path getDestPath(String component, String... components) {
    return OUTPUT_SUBDIR.resolve(Paths.get(component, components));
  }

  /** Assert that the modification time equals {@code expectedModifiedTime} for the given paths */
  private void assertModifiedTime(Iterable<Path> paths) throws IOException {
    for (Path path : paths) {
      assertModifiedTime(path);
    }
  }

  private void assertModifiedTime(Path path) throws IOException {
    Path fullPath = tmpFolder.getRoot().resolve(path);
    FileTime lastModifiedTime = filesystem.getLastModifiedTime(fullPath);
    Assert.assertEquals(
        String.format(
            "Expected %s to be modified at %s, but it was modified at %s",
            fullPath, expectedModifiedTime, lastModifiedTime),
        expectedModifiedTime,
        lastModifiedTime);
  }

  /** Assert that files exist and should/shouldn't be executable */
  private void assertExecutable(Iterable<Path> paths, boolean shouldBeExecutable) {
    for (Path path : paths) {
      assertExecutable(path, shouldBeExecutable);
    }
  }

  /** Assert that a file exists and should/shouldn't be executable */
  private void assertExecutable(Path path, boolean shouldBeExecutable) {
    assertExecutable(path, shouldBeExecutable, FilesAreExecutableByDefault);
  }

  private void assertExecutable(
      Path path, boolean shouldBeExecutable, boolean allowFilesToBeWrong) {
    Path fullPath = tmpFolder.getRoot().resolve(path);
    File file = fullPath.toFile();
    boolean isExecutable = file.canExecute();

    // Bit of a hack around windows writing normal files as exectuable by default
    if (!file.isDirectory() && isExecutable && allowFilesToBeWrong && !shouldBeExecutable) {
      return;
    }

    Assert.assertEquals(
        String.format(
            "Expected execute on %s to be %s, got %s", fullPath, shouldBeExecutable, isExecutable),
        shouldBeExecutable,
        isExecutable);
  }

  /** Assert that a directory exists inside of the temp directory */
  private void assertOutputDirExists(Path path) {
    Path fullPath = tmpFolder.getRoot().resolve(path);
    Assert.assertTrue(
        String.format("Expected %s to be a directory", fullPath), Files.isDirectory(fullPath));
  }

  /** Assert that a file exists inside of the temp directory with given contents */
  private void assertOutputFileExists(Path path, String expectedContents) throws IOException {
    Path fullPath = tmpFolder.getRoot().resolve(path);
    Assert.assertTrue(
        String.format("Expected %s to be a file", fullPath), Files.isRegularFile(fullPath));

    String contents = Joiner.on('\n').join(Files.readAllLines(fullPath));
    Assert.assertEquals(expectedContents, contents);
  }

  /**
   * Assert that a symlink exists inside of the temp directory with given contents and that links to
   * the right file
   */
  private void assertOutputSymlinkExists(
      Path symlinkPath, Path expectedLinkedToPath, String expectedContents) throws IOException {
    Path fullPath = tmpFolder.getRoot().resolve(symlinkPath);
    if (Platform.detect() != Platform.WINDOWS) {
      Assert.assertTrue(
          String.format("Expected %s to be a symlink", fullPath), Files.isSymbolicLink(fullPath));
      Path linkedToPath = Files.readSymbolicLink(fullPath);
      Assert.assertEquals(
          String.format(
              "Expected symlink at %s to point to %s, not %s",
              symlinkPath, expectedLinkedToPath, linkedToPath),
          expectedLinkedToPath,
          linkedToPath);
    }

    Path realExpectedLinkedToPath =
        filesystem
            .getRootPath()
            .resolve(symlinkPath.getParent().resolve(expectedLinkedToPath).normalize());
    Assert.assertTrue(
        String.format(
            "Expected link %s to be the same file as %s", fullPath, realExpectedLinkedToPath),
        Files.isSameFile(fullPath, realExpectedLinkedToPath));

    String contents = Joiner.on('\n').join(Files.readAllLines(fullPath));
    Assert.assertEquals(expectedContents, contents);
  }

  @Test
  public void extractsTarFiles() throws IOException {
    extractsFiles(ArchiveFormat.TAR, Optional.empty());
  }

  @Test
  public void extractsTarGzFiles() throws IOException {
    extractsFiles(ArchiveFormat.TAR_GZ, Optional.empty());
  }

  @Test
  public void extractsTarXzFiles() throws IOException {
    extractsFiles(ArchiveFormat.TAR_XZ, Optional.empty());
  }

  @Test
  public void extractsTarBz2Files() throws IOException {
    extractsFiles(ArchiveFormat.TAR_BZ2, Optional.empty());
  }

  @Test
  public void extractsTarFilesWindowsStyle() throws IOException {
    extractsFiles(ArchiveFormat.TAR, Optional.of(true));
  }

  @Test
  public void extractsTarGzFilesWindowsStyle() throws IOException {
    extractsFiles(ArchiveFormat.TAR_GZ, Optional.of(true));
  }

  @Test
  public void extractsTarXzFilesWindowsStyle() throws IOException {
    extractsFiles(ArchiveFormat.TAR_XZ, Optional.of(true));
  }

  @Test
  public void extractsTarBz2FilesWindowsStyle() throws IOException {
    extractsFiles(ArchiveFormat.TAR_BZ2, Optional.of(true));
  }

  @Test
  public void extractsTarFilesPosixStyle() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));
    extractsFiles(ArchiveFormat.TAR, Optional.of(false));
  }

  @Test
  public void extractsTarGzFilesPosixStyle() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));
    extractsFiles(ArchiveFormat.TAR_GZ, Optional.of(false));
  }

  @Test
  public void extractsTarXzFilesPosixStyle() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));
    extractsFiles(ArchiveFormat.TAR_XZ, Optional.of(false));
  }

  @Test
  public void extractsTarBz2FilesPosixStyle() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));
    extractsFiles(ArchiveFormat.TAR_BZ2, Optional.of(false));
  }

  private void extractsFiles(ArchiveFormat format, Optional<Boolean> writeSymlinksLast)
      throws IOException {
    ImmutableList<Path> expectedPaths =
        ImmutableList.of(
            getDestPath("root", "echo.sh"),
            getDestPath("root", "alternative", "Main.java"),
            getDestPath("root", "alternative", "Link.java"),
            getDestPath("root", "src", "com", "facebook", "buck", "Main.java"),
            getDestPath("root_sibling", "Other.java"));

    ImmutableList.Builder<Path> expectedDirsBuilder = ImmutableList.builder();
    expectedDirsBuilder.add(OUTPUT_SUBDIR);
    expectedDirsBuilder.add(getDestPath("root"));
    expectedDirsBuilder.add(getDestPath("root", "alternative"));
    expectedDirsBuilder.add(getDestPath("root", "src"));
    expectedDirsBuilder.add(getDestPath("root", "src", "com"));
    expectedDirsBuilder.add(getDestPath("root", "src", "com", "facebook"));
    expectedDirsBuilder.add(getDestPath("root", "src", "com", "facebook", "buck"));
    expectedDirsBuilder.add(getDestPath("root_sibling"));
    expectedDirsBuilder.add(getDestPath("root", "empty_dir"));
    ImmutableList<Path> expectedDirs = expectedDirsBuilder.build();

    Path archivePath = getTestFilePath(format.getExtension());
    Untar unarchiver = (Untar) format.getUnarchiver();
    ImmutableList<Path> unarchivedFiles;
    if (writeSymlinksLast.isPresent()) {
      unarchivedFiles =
          unarchiver.extractArchive(
              archivePath,
              filesystem,
              Paths.get("output_dir"),
              Optional.empty(),
              ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES,
              writeSymlinksLast.get());
    } else {
      unarchivedFiles =
          unarchiver.extractArchive(
              archivePath,
              filesystem,
              Paths.get("output_dir"),
              Optional.empty(),
              ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);
    }

    Assert.assertThat(unarchivedFiles, Matchers.containsInAnyOrder(expectedPaths.toArray()));
    Assert.assertEquals(expectedPaths.size(), unarchivedFiles.size());

    // Make sure we wrote the files
    assertOutputFileExists(expectedPaths.get(0), echoDotSh);
    assertOutputSymlinkExists(expectedPaths.get(1), Paths.get("Link.java"), mainDotJava);
    assertOutputSymlinkExists(
        expectedPaths.get(2),
        Paths.get("..", "src", "com", "facebook", "buck", "Main.java"),
        mainDotJava);
    assertOutputFileExists(expectedPaths.get(3), mainDotJava);
    assertOutputFileExists(expectedPaths.get(4), otherDotJava);

    // Make sure we make the dirs
    for (Path dir : expectedDirs) {
      assertOutputDirExists(dir);
      // Dest dir is created by buck, doesn't come from the archive
      if (!dir.equals(OUTPUT_SUBDIR)) {
        assertModifiedTime(dir);
      }
    }

    // Make sure that we set modified time and execute bit properly
    assertModifiedTime(expectedPaths);
    assertExecutable(expectedPaths.get(0), true);
    assertExecutable(expectedPaths.subList(1, expectedPaths.size()), false);
  }

  @Test
  public void extractsFilesWithStrippedPrefix() throws IOException {
    ArchiveFormat format = ArchiveFormat.TAR;

    ImmutableList<Path> expectedPaths =
        ImmutableList.of(
            getDestPath("echo.sh"),
            getDestPath("alternative", "Main.java"),
            getDestPath("alternative", "Link.java"),
            getDestPath("src", "com", "facebook", "buck", "Main.java"));

    ImmutableList.Builder<Path> expectedDirsBuilder = ImmutableList.builder();
    expectedDirsBuilder.add(OUTPUT_SUBDIR);
    expectedDirsBuilder.add(getDestPath("alternative"));
    expectedDirsBuilder.add(getDestPath("src"));
    expectedDirsBuilder.add(getDestPath("src", "com"));
    expectedDirsBuilder.add(getDestPath("src", "com", "facebook"));
    expectedDirsBuilder.add(getDestPath("src", "com", "facebook", "buck"));
    expectedDirsBuilder.add(getDestPath("empty_dir"));
    ImmutableList<Path> expectedDirs = expectedDirsBuilder.build();

    Path archivePath = getTestFilePath(format.getExtension());
    ImmutableList<Path> unarchivedFiles =
        format
            .getUnarchiver()
            .extractArchive(
                archivePath,
                filesystem,
                Paths.get("output_dir"),
                Optional.of(Paths.get("root")),
                ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    Assert.assertThat(unarchivedFiles, Matchers.containsInAnyOrder(expectedPaths.toArray()));
    Assert.assertEquals(expectedPaths.size(), unarchivedFiles.size());

    // Make sure we wrote the files
    assertOutputFileExists(expectedPaths.get(0), echoDotSh);
    assertOutputSymlinkExists(expectedPaths.get(1), Paths.get("Link.java"), mainDotJava);
    assertOutputSymlinkExists(
        expectedPaths.get(2),
        Paths.get("..", "src", "com", "facebook", "buck", "Main.java"),
        mainDotJava);
    assertOutputFileExists(expectedPaths.get(3), mainDotJava);

    // Make sure we make the dirs
    for (Path dir : expectedDirs) {
      assertOutputDirExists(dir);
      // Dest dir is created by buck, doesn't come from the archive
      if (!dir.equals(OUTPUT_SUBDIR)) {
        assertModifiedTime(dir);
      }
    }

    // Make sure that we set modified time and execute bit properly
    assertModifiedTime(expectedPaths);
    assertExecutable(expectedPaths.get(0), true);
    assertExecutable(expectedPaths.subList(1, expectedPaths.size()), false);

    Path siblingDirPath = getDestPath("root_sibling");
    Path siblingFilePath = getDestPath("root_sibling", "Other.java");
    Path siblingFile2Path = getDestPath("Other.java");
    Assert.assertFalse(
        String.format("Expected %s to not exist", siblingDirPath), Files.exists(siblingDirPath));
    Assert.assertFalse(
        String.format("Expected %s to not exist", siblingFilePath), Files.exists(siblingFilePath));
    Assert.assertFalse(
        String.format("Expected %s to not exist", siblingFile2Path),
        Files.exists(siblingFile2Path));
  }

  @Test
  public void deletesExistingFilesInPathIfShouldBeDirectories() throws IOException {
    ArchiveFormat format = ArchiveFormat.TAR;

    ImmutableList<Path> expectedPaths =
        ImmutableList.of(
            getDestPath("root", "echo.sh"),
            getDestPath("root", "alternative", "Main.java"),
            getDestPath("root", "alternative", "Link.java"),
            getDestPath("root", "src", "com", "facebook", "buck", "Main.java"),
            getDestPath("root_sibling", "Other.java"));

    ImmutableList<Path> expectedDirs = ImmutableList.of(OUTPUT_SUBDIR, getDestPath("root"));

    filesystem.mkdirs(OUTPUT_SUBDIR);
    filesystem.writeContentsToPath("testing", getDestPath("root"));
    assertOutputFileExists(getDestPath("root"), "testing");

    Path archivePath = getTestFilePath(format.getExtension());
    ImmutableList<Path> unarchivedFiles =
        format
            .getUnarchiver()
            .extractArchive(
                archivePath,
                filesystem,
                Paths.get("output_dir"),
                Optional.empty(),
                ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    Assert.assertThat(unarchivedFiles, Matchers.containsInAnyOrder(expectedPaths.toArray()));
    Assert.assertEquals(expectedPaths.size(), unarchivedFiles.size());

    assertOutputDirExists(expectedDirs.get(1));
    assertModifiedTime(expectedDirs.get(1));

    // Make sure we wrote the files
    assertOutputFileExists(expectedPaths.get(0), echoDotSh);
    assertOutputSymlinkExists(expectedPaths.get(1), Paths.get("Link.java"), mainDotJava);
    assertOutputSymlinkExists(
        expectedPaths.get(2),
        Paths.get("..", "src", "com", "facebook", "buck", "Main.java"),
        mainDotJava);
    assertOutputFileExists(expectedPaths.get(3), mainDotJava);
    assertOutputFileExists(expectedPaths.get(4), otherDotJava);

    // Make sure that we set modified time and execute bit properly
    assertModifiedTime(expectedPaths);
    assertExecutable(expectedPaths.get(0), true);
    assertExecutable(expectedPaths.subList(1, expectedPaths.size()), false);
  }

  @Test
  public void overwritesExistingFilesIfPresent() throws IOException {
    ArchiveFormat format = ArchiveFormat.TAR;

    ImmutableList<Path> expectedPaths =
        ImmutableList.of(
            getDestPath("root", "echo.sh"),
            getDestPath("root", "alternative", "Main.java"),
            getDestPath("root", "alternative", "Link.java"),
            getDestPath("root", "src", "com", "facebook", "buck", "Main.java"),
            getDestPath("root_sibling", "Other.java"));

    ImmutableList<Path> expectedDirs = ImmutableList.of(OUTPUT_SUBDIR, getDestPath("root"));

    filesystem.mkdirs(OUTPUT_SUBDIR);
    filesystem.mkdirs(getDestPath("root"));
    filesystem.writeContentsToPath("testing", getDestPath("root", "echo.sh"));
    assertOutputFileExists(getDestPath("root", "echo.sh"), "testing");

    Path archivePath = getTestFilePath(format.getExtension());
    ImmutableList<Path> unarchivedFiles =
        format
            .getUnarchiver()
            .extractArchive(
                archivePath,
                filesystem,
                Paths.get("output_dir"),
                Optional.empty(),
                ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    Assert.assertThat(unarchivedFiles, Matchers.containsInAnyOrder(expectedPaths.toArray()));
    Assert.assertEquals(expectedPaths.size(), unarchivedFiles.size());

    assertOutputDirExists(expectedDirs.get(1));
    assertModifiedTime(expectedDirs.get(1));

    // Make sure we wrote the files
    assertOutputFileExists(expectedPaths.get(0), echoDotSh);
    assertOutputSymlinkExists(expectedPaths.get(1), Paths.get("Link.java"), mainDotJava);
    assertOutputSymlinkExists(
        expectedPaths.get(2),
        Paths.get("..", "src", "com", "facebook", "buck", "Main.java"),
        mainDotJava);
    assertOutputFileExists(expectedPaths.get(3), mainDotJava);
    assertOutputFileExists(expectedPaths.get(4), otherDotJava);

    // Make sure that we set modified time and execute bit properly
    assertModifiedTime(expectedPaths);
    assertExecutable(expectedPaths.get(0), true);
    assertExecutable(expectedPaths.subList(1, expectedPaths.size()), false);
  }

  @Test
  public void cleansUpFilesThatExistInDirectoryButNotArchive() throws IOException {
    ArchiveFormat format = ArchiveFormat.TAR;

    ImmutableList<Path> expectedPaths =
        ImmutableList.of(
            getDestPath("root", "echo.sh"),
            getDestPath("root", "alternative", "Main.java"),
            getDestPath("root", "alternative", "Link.java"),
            getDestPath("root", "src", "com", "facebook", "buck", "Main.java"),
            getDestPath("root_sibling", "Other.java"));

    ImmutableList<Path> expectedDirs =
        ImmutableList.of(OUTPUT_SUBDIR, getDestPath("root"), getDestPath("root", "alternative"));

    ImmutableList<Path> junkDirs =
        ImmutableList.of(
            getDestPath("foo_bar"),
            getDestPath("root", "foo_bar"),
            getDestPath("root_sibling", "empty_dir"));
    ImmutableList<Path> junkFiles =
        ImmutableList.of(
            getDestPath("foo_bar", "test.file"), getDestPath("root", "foo_bar", "test.file2"));

    for (Path path : junkDirs) {
      filesystem.mkdirs(path);
    }

    for (Path path : junkFiles) {
      filesystem.writeContentsToPath("testing", path);
    }

    filesystem.mkdirs(OUTPUT_SUBDIR);
    filesystem.mkdirs(getDestPath("root"));
    filesystem.writeContentsToPath("testing", getDestPath("root", "echo.sh"));
    assertOutputFileExists(getDestPath("root", "echo.sh"), "testing");

    Path archivePath = getTestFilePath(format.getExtension());
    ImmutableList<Path> unarchivedFiles =
        format
            .getUnarchiver()
            .extractArchive(
                archivePath,
                filesystem,
                Paths.get("output_dir"),
                Optional.empty(),
                ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    Assert.assertThat(unarchivedFiles, Matchers.containsInAnyOrder(expectedPaths.toArray()));
    Assert.assertEquals(expectedPaths.size(), unarchivedFiles.size());

    assertOutputDirExists(expectedDirs.get(1));
    assertOutputDirExists(expectedDirs.get(2));
    assertModifiedTime(expectedDirs.get(2));

    // Make sure we wrote the files
    assertOutputFileExists(expectedPaths.get(0), echoDotSh);
    assertOutputSymlinkExists(expectedPaths.get(1), Paths.get("Link.java"), mainDotJava);
    assertOutputSymlinkExists(
        expectedPaths.get(2),
        Paths.get("..", "src", "com", "facebook", "buck", "Main.java"),
        mainDotJava);
    assertOutputFileExists(expectedPaths.get(3), mainDotJava);
    assertOutputFileExists(expectedPaths.get(4), otherDotJava);

    // Make sure that we set modified time and execute bit properly
    assertModifiedTime(expectedPaths);
    assertExecutable(expectedPaths.get(0), true);
    assertExecutable(expectedPaths.subList(1, expectedPaths.size()), false);
  }

  /**
   * cleanDirectoriesExactly asserts that OVERWRITE_AND_CLEAN_DIRECTORIES removes exactly the files
   * in any subdirectory of a directory entry in the tar archive.
   *
   * <p>This behavior supports unarchiving a Buck cache from multiple archives, each containing a
   * part.
   *
   * <p>Explanations:
   * <li>BUCK isn't removed because it's not in a subdirectory of a directory entry in the archive.
   * <li>buck-out/gen/pkg1/rule1.jar isn't removed, even though buck-out/gen/pkg1/rule2.jar is in
   *     the archive, because there's no directory entry for buck-out/gen/pkg1/.
   * <li>buck-out/gen/pkg1/rule2#foo/lib.so, however, is removed because the archive contains the
   *     directory buck-out/gen/pkg1/rule2#foo/
   */
  @Test
  public void cleanDirectoriesExactly() throws Exception {
    Path archive = filesystem.resolve("archive.tar");
    Path outDir = filesystem.getRootPath();

    List<String> toLeave =
        ImmutableList.of(
            "BUCK",
            "buck-out/gen/pkg1/rule1.jar",
            "buck-out/gen/pkg1/rule1#foo/lib.so",
            "buck-out/gen/pkg2/rule.jar",
            "buck-out/gen/pkg2/rule#foo/lib.so");
    List<String> toDelete = ImmutableList.of("buck-out/gen/pkg1/rule2#foo/lib.so");
    for (String s : concat(toDelete, toLeave)) {
      Path path = filesystem.resolve(s);
      filesystem.createParentDirs(path);
      filesystem.writeContentsToPath("", path);
    }

    // Write test archive.
    try (TarArchiveOutputStream stream =
        new TarArchiveOutputStream(
            new BufferedOutputStream(filesystem.newFileOutputStream(archive)))) {
      stream.putArchiveEntry(new TarArchiveEntry("buck-out/gen/pkg1/rule2#foo/"));
      stream.putArchiveEntry(new TarArchiveEntry("buck-out/gen/pkg1/rule2.jar"));
      stream.closeArchiveEntry();
    }

    // Untar test archive.
    Untar.tarUnarchiver()
        .extractArchive(
            archive,
            filesystem,
            outDir,
            Optional.empty(),
            ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

    // Assert expected file existence.
    for (String s : toDelete) {
      Assert.assertFalse(
          String.format("Expected file %s to be deleted, but it wasn't", s),
          filesystem.exists(filesystem.resolve(s)));
    }
    for (String s : toLeave) {
      Assert.assertTrue(
          String.format("Expected file %s to not be deleted, but it was", s),
          filesystem.exists(filesystem.resolve(s)));
    }
  }

  @Test
  public void doesNotCleanUpFilesThatExistInDirectoryButNotArchiveWithOverwriteMode()
      throws IOException {
    ArchiveFormat format = ArchiveFormat.TAR;

    ImmutableList<Path> expectedPaths =
        ImmutableList.of(
            getDestPath("root", "echo.sh"),
            getDestPath("root", "alternative", "Main.java"),
            getDestPath("root", "alternative", "Link.java"),
            getDestPath("root", "src", "com", "facebook", "buck", "Main.java"),
            getDestPath("root_sibling", "Other.java"));

    ImmutableList<Path> expectedDirs =
        ImmutableList.of(OUTPUT_SUBDIR, getDestPath("root"), getDestPath("root", "alternative"));

    ImmutableList<Path> junkDirs =
        ImmutableList.of(
            getDestPath("foo_bar"),
            getDestPath("root", "foo_bar"),
            getDestPath("root_sibling", "empty_dir"));
    ImmutableList<Path> junkFiles =
        ImmutableList.of(
            getDestPath("foo_bar", "test.file"), getDestPath("root", "foo_bar", "test.file2"));

    for (Path path : junkDirs) {
      filesystem.mkdirs(path);
    }

    for (Path path : junkFiles) {
      filesystem.writeContentsToPath("testing", path);
    }

    filesystem.mkdirs(OUTPUT_SUBDIR);
    filesystem.mkdirs(getDestPath("root"));
    filesystem.writeContentsToPath("testing", getDestPath("root", "echo.sh"));
    assertOutputFileExists(getDestPath("root", "echo.sh"), "testing");

    Path archivePath = getTestFilePath(format.getExtension());
    ImmutableList<Path> unarchivedFiles =
        format
            .getUnarchiver()
            .extractArchive(
                archivePath,
                filesystem,
                Paths.get("output_dir"),
                Optional.empty(),
                ExistingFileMode.OVERWRITE);

    Assert.assertThat(unarchivedFiles, Matchers.containsInAnyOrder(expectedPaths.toArray()));
    Assert.assertEquals(expectedPaths.size(), unarchivedFiles.size());

    assertOutputDirExists(expectedDirs.get(1));
    assertOutputDirExists(expectedDirs.get(2));
    assertModifiedTime(expectedDirs.get(2));

    // Make sure we wrote the files
    assertOutputFileExists(expectedPaths.get(0), echoDotSh);
    assertOutputSymlinkExists(expectedPaths.get(1), Paths.get("Link.java"), mainDotJava);
    assertOutputSymlinkExists(
        expectedPaths.get(2),
        Paths.get("..", "src", "com", "facebook", "buck", "Main.java"),
        mainDotJava);
    assertOutputFileExists(expectedPaths.get(3), mainDotJava);
    assertOutputFileExists(expectedPaths.get(4), otherDotJava);

    // Make sure that we set modified time and execute bit properly
    assertModifiedTime(expectedPaths);
    assertExecutable(expectedPaths.get(0), true);
    assertExecutable(expectedPaths.subList(1, expectedPaths.size()), false);

    Assert.assertTrue(filesystem.exists(junkDirs.get(0)));
    Assert.assertTrue(filesystem.exists(junkDirs.get(1)));
    Assert.assertTrue(filesystem.exists(junkDirs.get(2)));
    Assert.assertEquals("testing", filesystem.readLines(junkFiles.get(0)).get(0));
    Assert.assertEquals("testing", filesystem.readLines(junkFiles.get(1)).get(0));
  }
}
