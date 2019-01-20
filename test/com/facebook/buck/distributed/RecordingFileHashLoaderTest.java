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

package com.facebook.buck.distributed;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.FakeProjectFileHashCache;
import com.facebook.buck.testutil.FileHashEntryMatcher;
import com.facebook.buck.util.CreateSymlinksForTests;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.RawConfig;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RecordingFileHashLoaderTest {
  private static final HashCode EXAMPLE_HASHCODE = HashCode.fromString("1234");
  private static final Path CELL1 = Paths.get("cell1");
  private static final Path CELL2 = Paths.get("cell2");

  @Rule public TemporaryFolder projectDir = new TemporaryFolder();
  @Rule public TemporaryFolder externalDir = new TemporaryFolder();

  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() throws IOException {
    projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(projectDir.getRoot().toPath().toRealPath());
  }

  @Test
  public void testRecordsInternalSymlinks() throws IOException {
    // Scenario:
    // /project/link -> /project/file
    // => /project/link should be recorded as a file with the same contents as /project/file.

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    Path fileAbsPath = projectFilesystem.resolve("file");
    Path fileRelPath = projectFilesystem.relativize(fileAbsPath);
    Files.write(fileAbsPath, "This file is so cool.".getBytes());
    fileAbsPath.toFile().setExecutable(true);

    Path symlinkAbsPath = projectFilesystem.resolve("link");
    Path symlinkRelPath = projectFilesystem.relativize(symlinkAbsPath);
    CreateSymlinksForTests.createSymLink(symlinkAbsPath, fileAbsPath);

    symlinkAbsPath.toFile().setExecutable(true);

    RecordedFileHashes recordedFileHashes = new RecordedFileHashes(0);
    BuildJobStateFileHashes fileHashes = recordedFileHashes.getRemoteFileHashes();
    FakeProjectFileHashCache delegateCache =
        new FakeProjectFileHashCache(
            projectFilesystem,
            ImmutableMap.of(symlinkRelPath, EXAMPLE_HASHCODE, fileRelPath, EXAMPLE_HASHCODE));

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.of(projectFilesystem.getRootPath(), new Config());

    RecordingProjectFileHashCache recordingLoader =
        RecordingProjectFileHashCache.createForCellRoot(
            delegateCache,
            recordedFileHashes,
            new DistBuildConfig(FakeBuckConfig.builder().build()),
            cellPathResolver);

    recordingLoader.get(symlinkRelPath);

    assertThat(fileHashes.getEntries().size(), Matchers.equalTo(1));

    BuildJobStateFileHashEntry fileHashEntry = fileHashes.getEntries().get(0);
    assertFalse(fileHashEntry.isSetIsDirectory() && fileHashEntry.isIsDirectory());
    assertFalse(fileHashEntry.isSetRootSymLink());
    assertThat(fileHashEntry.getPath(), Matchers.equalTo(unixPath(symlinkRelPath.toString())));
    assertThat(fileHashEntry.getSha1(), Matchers.equalTo(EXAMPLE_HASHCODE.toString()));
    assertFalse(fileHashEntry.isSetPathIsAbsolute() && fileHashEntry.isPathIsAbsolute());
    assertTrue(fileHashEntry.isSetIsExecutable() && fileHashEntry.isIsExecutable());
    // We may or may not read the file inline here.
    if (fileHashEntry.isSetContents()) {
      assertThat(fileHashEntry.getContents(), Matchers.equalTo(Files.readAllBytes(fileAbsPath)));
    }
  }

  @Test
  public void testRecordsCrossCellSymlinkWithCrossCellRealPath() throws IOException {
    // Scenario:
    // /cell1/link -> /cell2/file
    // => /cell1/link should be recorded as a file with the same contents as /cell2/file.

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    // Create cells
    projectFilesystem.mkdirs(CELL1);
    projectFilesystem.mkdirs(CELL2);

    ProjectFilesystem cellRootFs =
        TestProjectFilesystems.createProjectFilesystem(projectFilesystem.resolve(CELL1));

    Path fileAbsPath = projectFilesystem.resolve(CELL2.resolve("file"));
    Path fileRelPath = cellRootFs.relativize(fileAbsPath);
    Files.write(fileAbsPath, "This file is so cool.".getBytes());
    fileAbsPath.toFile().setExecutable(true);

    Path symlinkAbsPath = cellRootFs.resolve("link");
    Path symlinkRelPath = cellRootFs.relativize(symlinkAbsPath);
    CreateSymlinksForTests.createSymLink(symlinkAbsPath, fileAbsPath);
    symlinkAbsPath.toFile().setExecutable(true);

    RecordedFileHashes recordedFileHashes = new RecordedFileHashes(0);
    BuildJobStateFileHashes fileHashes = recordedFileHashes.getRemoteFileHashes();
    FakeProjectFileHashCache delegateCache =
        new FakeProjectFileHashCache(
            cellRootFs,
            ImmutableMap.of(symlinkRelPath, EXAMPLE_HASHCODE, fileRelPath, EXAMPLE_HASHCODE));

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.of(
            projectFilesystem.getRootPath(),
            new Config(
                RawConfig.builder()
                    .put("repositories", "cell1", projectFilesystem.resolve(CELL1).toString())
                    .put("repositories", "cell2", projectFilesystem.resolve(CELL2).toString())
                    .build()));

    RecordingProjectFileHashCache recordingLoader =
        RecordingProjectFileHashCache.createForCellRoot(
            delegateCache,
            recordedFileHashes,
            new DistBuildConfig(FakeBuckConfig.builder().build()),
            cellPathResolver);

    recordingLoader.get(symlinkRelPath);

    assertThat(fileHashes.getEntries().size(), Matchers.equalTo(1));

    BuildJobStateFileHashEntry fileHashEntry = fileHashes.getEntries().get(0);
    assertFalse(fileHashEntry.isSetIsDirectory() && fileHashEntry.isIsDirectory());
    assertFalse(fileHashEntry.isSetRootSymLink());
    assertThat(fileHashEntry.getPath(), Matchers.equalTo(unixPath(symlinkRelPath.toString())));
    assertThat(fileHashEntry.getSha1(), Matchers.equalTo(EXAMPLE_HASHCODE.toString()));
    assertFalse(fileHashEntry.isSetPathIsAbsolute() && fileHashEntry.isPathIsAbsolute());
    assertTrue(fileHashEntry.isSetIsExecutable() && fileHashEntry.isIsExecutable());
    // We may or may not read the file inline here.
    if (fileHashEntry.isSetContents()) {
      assertThat(fileHashEntry.getContents(), Matchers.equalTo(Files.readAllBytes(fileAbsPath)));
    }
  }

  @Test
  public void testRecordsCrossCellSymlinkWithExternalRealPath() throws IOException {
    // Scenario:
    // /cell1/link -> /cell2/link -> /external/file
    // => create direct link: /cell1/link -> /external/file

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    // Create cells
    projectFilesystem.mkdirs(CELL1);
    projectFilesystem.mkdirs(CELL2);

    ProjectFilesystem cell1 =
        TestProjectFilesystems.createProjectFilesystem(projectFilesystem.resolve(CELL1));
    ProjectFilesystem cell2 =
        TestProjectFilesystems.createProjectFilesystem(projectFilesystem.resolve(CELL2));

    Path externalFile = externalDir.newFile("file").toPath();

    Path intermediateSymLinkAbsPath = cell2.resolve("link");
    Path intermediateSymLinkRelPath = cell1.relativize(intermediateSymLinkAbsPath);

    Path symlinkAbsPath = cell1.resolve("link");
    Path symlinkRelPath = cell1.relativize(symlinkAbsPath);

    CreateSymlinksForTests.createSymLink(symlinkAbsPath, intermediateSymLinkRelPath);
    CreateSymlinksForTests.createSymLink(intermediateSymLinkAbsPath, externalFile);

    RecordedFileHashes recordedFileHashes = new RecordedFileHashes(0);
    BuildJobStateFileHashes fileHashes = recordedFileHashes.getRemoteFileHashes();
    FakeProjectFileHashCache delegateCache =
        new FakeProjectFileHashCache(
            cell1,
            ImmutableMap.of(
                symlinkRelPath, EXAMPLE_HASHCODE, intermediateSymLinkRelPath, EXAMPLE_HASHCODE));

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.of(
            projectFilesystem.getRootPath(),
            new Config(
                RawConfig.builder()
                    .put("repositories", "cell1", projectFilesystem.resolve(CELL1).toString())
                    .put("repositories", "cell2", projectFilesystem.resolve(CELL2).toString())
                    .build()));

    RecordingProjectFileHashCache recordingLoader =
        RecordingProjectFileHashCache.createForCellRoot(
            delegateCache,
            recordedFileHashes,
            new DistBuildConfig(FakeBuckConfig.builder().build()),
            cellPathResolver);

    recordingLoader.get(symlinkRelPath);

    assertThat(fileHashes.getEntries().size(), Matchers.equalTo(1));

    BuildJobStateFileHashEntry fileHashEntry = fileHashes.getEntries().get(0);
    assertTrue(fileHashEntry.isSetRootSymLink());
    assertThat(
        fileHashEntry.getRootSymLink(), Matchers.equalTo(unixPath(symlinkRelPath.toString())));
    assertTrue(fileHashEntry.isSetRootSymLinkTarget());
    assertThat(
        fileHashEntry.getRootSymLinkTarget(), Matchers.equalTo(unixPath(externalFile.toString())));
  }

  @Test
  public void testRecordsDirectSymlinkToFile() throws IOException {
    // Scenario:
    // /project/linktoexternal -> /externalDir/externalfile
    // => create direct link: /project/linktoexternal -> /externalDir/externalfile

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    Path externalFile = externalDir.newFile("externalfile").toPath();
    Path symlinkAbsPath = projectFilesystem.resolve("linktoexternal");
    Path symlinkRelPath = projectFilesystem.relativize(symlinkAbsPath);
    CreateSymlinksForTests.createSymLink(symlinkAbsPath, externalFile);

    RecordedFileHashes recordedFileHashes = new RecordedFileHashes(0);
    BuildJobStateFileHashes fileHashes = recordedFileHashes.getRemoteFileHashes();
    FakeProjectFileHashCache delegateCache =
        new FakeProjectFileHashCache(
            projectFilesystem, ImmutableMap.of(symlinkRelPath, EXAMPLE_HASHCODE));

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.of(projectFilesystem.getRootPath(), new Config());

    RecordingProjectFileHashCache recordingLoader =
        RecordingProjectFileHashCache.createForCellRoot(
            delegateCache,
            recordedFileHashes,
            new DistBuildConfig(FakeBuckConfig.builder().build()),
            cellPathResolver);

    recordingLoader.get(symlinkRelPath);

    assertThat(fileHashes.getEntries().size(), Matchers.equalTo(1));

    BuildJobStateFileHashEntry fileHashEntry = fileHashes.getEntries().get(0);
    assertTrue(fileHashEntry.isSetRootSymLink());
    assertThat(
        fileHashEntry.getRootSymLink(), Matchers.equalTo(unixPath(symlinkRelPath.toString())));
    assertTrue(fileHashEntry.isSetRootSymLinkTarget());
    assertThat(
        fileHashEntry.getRootSymLinkTarget(), Matchers.equalTo(unixPath(externalFile.toString())));
  }

  @Test
  public void testRecordsSymlinkToFileWithinExternalDirectory() throws IOException {
    // Scenario:
    // /project/linktoexternaldir/externalfile -> /externalDir/externalfile
    // => create link for parent dir: /project/linktoexternaldir -> /externalDir

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    externalDir.newFile("externalfile");
    Path symlinkRoot = projectFilesystem.resolve("linktoexternaldir");
    CreateSymlinksForTests.createSymLink(symlinkRoot, externalDir.getRoot().toPath());

    Path fileWithinSymlink =
        projectFilesystem.relativize(
            symlinkRoot.resolve("externalfile")); // /project/linktoexternaldir/externalfile

    RecordedFileHashes recordedFileHashes = new RecordedFileHashes(0);
    BuildJobStateFileHashes fileHashes = recordedFileHashes.getRemoteFileHashes();

    FakeProjectFileHashCache delegateCache =
        new FakeProjectFileHashCache(
            projectFilesystem, ImmutableMap.of(fileWithinSymlink, EXAMPLE_HASHCODE));

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.of(projectFilesystem.getRootPath(), new Config());

    RecordingProjectFileHashCache recordingLoader =
        RecordingProjectFileHashCache.createForCellRoot(
            delegateCache,
            recordedFileHashes,
            new DistBuildConfig(FakeBuckConfig.builder().build()),
            cellPathResolver);

    recordingLoader.get(fileWithinSymlink);

    assertThat(fileHashes.getEntries().size(), Matchers.equalTo(1));

    BuildJobStateFileHashEntry fileHashEntry = fileHashes.getEntries().get(0);
    assertTrue(fileHashEntry.isSetRootSymLink());
    assertThat(
        fileHashEntry.getRootSymLink(),
        Matchers.equalTo(unixPath(fileWithinSymlink.getParent().toString())));
    assertTrue(fileHashEntry.isSetRootSymLinkTarget());
    assertThat(
        fileHashEntry.getRootSymLinkTarget(),
        Matchers.equalTo((unixPath(externalDir.getRoot().toPath().toString()))));
  }

  @Test
  public void testRecordsExternalSymlinkWithMissingTargetInWhitelist() throws IOException {
    // Scenario:
    // /project/linktoexternal -> /externalDir/externalfile -- which does not exist.
    // => create direct link: /project/linktoexternal -> /externalDir/externalfile

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    Path externalFile = externalDir.getRoot().toPath().resolve("externalfile");
    Path symlinkAbsPath = projectFilesystem.resolve("linktoexternal");
    Path symlinkRelPath = projectFilesystem.relativize(symlinkAbsPath);
    CreateSymlinksForTests.createSymLink(symlinkAbsPath, externalFile);

    RecordedFileHashes recordedFileHashes = new RecordedFileHashes(0);
    BuildJobStateFileHashes fileHashes = recordedFileHashes.getRemoteFileHashes();
    FakeProjectFileHashCache delegateCache =
        new FakeProjectFileHashCache(
            projectFilesystem, ImmutableMap.of()); // Hash lookup for non-existent target will fail.

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.of(projectFilesystem.getRootPath(), new Config());

    RecordingProjectFileHashCache.createForCellRoot(
        delegateCache,
        recordedFileHashes,
        new DistBuildConfig(
            FakeBuckConfig.builder()
                .setFilesystem(projectFilesystem)
                .setSections(
                    ImmutableMap.of(
                        DistBuildConfig.STAMPEDE_SECTION,
                        ImmutableMap.of(
                            DistBuildConfig.ALWAYS_MATERIALIZE_WHITELIST,
                            symlinkRelPath.toString())))
                .build()),
        cellPathResolver);

    assertThat(fileHashes.getEntries().size(), Matchers.equalTo(1));

    BuildJobStateFileHashEntry fileHashEntry = fileHashes.getEntries().get(0);
    assertTrue(fileHashEntry.isSetRootSymLink());
    assertThat(
        fileHashEntry.getRootSymLink(), Matchers.equalTo(unixPath(symlinkRelPath.toString())));
    assertTrue(fileHashEntry.isSetRootSymLinkTarget());
    assertThat(
        fileHashEntry.getRootSymLinkTarget(), Matchers.equalTo(unixPath(externalFile.toString())));
  }

  @Test
  public void testRecordsInternalSymlinkWithExternalRealPath() throws IOException {
    // Scenario:
    // Path to record: /project/a/b, should resolve to /externalDir/x/f/b
    // /project/a -> /project/e/f
    // /project/e -> /externalDir/x
    // => create direct link: /project/a -> /externalDir/x/f

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    Path externalDirX = externalDir.newFolder("x").toPath();
    Path externalDirXF = externalDirX.resolve("f");
    externalDirXF.toFile().mkdir();
    Path realExternalFile = externalDirXF.resolve("b");
    realExternalFile.toFile().createNewFile();

    Path dirE = projectFilesystem.resolve("e");
    CreateSymlinksForTests.createSymLink(dirE, externalDirX);

    Path dirA = projectFilesystem.resolve("a");
    CreateSymlinksForTests.createSymLink(dirA, dirE.resolve("f"));

    Path relPathToRecord = projectFilesystem.relativize(dirA.resolve("b"));

    RecordedFileHashes recordedFileHashes = new RecordedFileHashes(0);
    BuildJobStateFileHashes fileHashes = recordedFileHashes.getRemoteFileHashes();
    FakeProjectFileHashCache delegateCache =
        new FakeProjectFileHashCache(
            projectFilesystem, ImmutableMap.of(relPathToRecord, EXAMPLE_HASHCODE));

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.of(projectFilesystem.getRootPath(), new Config());

    RecordingProjectFileHashCache recordingLoader =
        RecordingProjectFileHashCache.createForCellRoot(
            delegateCache,
            recordedFileHashes,
            new DistBuildConfig(FakeBuckConfig.builder().build()),
            cellPathResolver);

    recordingLoader.get(relPathToRecord);

    assertThat(fileHashes.getEntries().size(), Matchers.equalTo(1));

    BuildJobStateFileHashEntry fileHashEntry = fileHashes.getEntries().get(0);
    assertTrue(fileHashEntry.isSetRootSymLink());
    assertThat(
        fileHashEntry.getRootSymLink(),
        Matchers.equalTo(unixPath(projectFilesystem.relativize(dirA).toString())));
    assertTrue(fileHashEntry.isSetRootSymLinkTarget());
    assertThat(
        fileHashEntry.getRootSymLinkTarget(), Matchers.equalTo(unixPath(externalDirXF.toString())));
  }

  @Test
  public void testRecordsDirectoryAndRecursivelyRecordsChildren() throws IOException {
    // Scenario:
    // /a - folder
    // /a/b - folder
    // /a/b/c - file
    // /a/b/d - folder
    // /a/e - file
    // => entries for all files and folders.
    // => entries for dirs /a and /a/b list their direct children

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    Path pathDirA = Files.createDirectories(projectFilesystem.getRootPath().resolve("a"));
    Files.createDirectories(projectFilesystem.getRootPath().resolve("a/b"));
    Files.createFile(projectFilesystem.getRootPath().resolve("a/b/c"));
    Files.createDirectories(projectFilesystem.getRootPath().resolve("a/b/d"));
    Files.createFile(projectFilesystem.getRootPath().resolve("a/e"));

    RecordedFileHashes recordedFileHashes = new RecordedFileHashes(0);
    BuildJobStateFileHashes fileHashes = recordedFileHashes.getRemoteFileHashes();

    ProjectFileHashCache delegateCacheMock = EasyMock.createMock(ProjectFileHashCache.class);
    expect(delegateCacheMock.getFilesystem()).andReturn(projectFilesystem);
    expect(delegateCacheMock.get(anyObject(Path.class))).andReturn(EXAMPLE_HASHCODE).anyTimes();
    replay(delegateCacheMock);

    DefaultCellPathResolver cellPathResolver =
        DefaultCellPathResolver.of(projectFilesystem.getRootPath(), new Config());

    RecordingProjectFileHashCache recordingLoader =
        RecordingProjectFileHashCache.createForCellRoot(
            delegateCacheMock,
            recordedFileHashes,
            new DistBuildConfig(FakeBuckConfig.builder().build()),
            cellPathResolver);

    recordingLoader.get(projectFilesystem.relativize(pathDirA));

    assertThat(fileHashes.getEntries().size(), Matchers.equalTo(5)); // all folders and files

    assertThat(
        fileHashes.getEntries(),
        Matchers.containsInAnyOrder(
            new FileHashEntryMatcher("a", true),
            new FileHashEntryMatcher("a/b", true),
            new FileHashEntryMatcher("a/b/c", false),
            new FileHashEntryMatcher("a/b/d", true),
            new FileHashEntryMatcher("a/e", false)));
  }

  private static PathWithUnixSeparators unixPath(String path) {
    return new PathWithUnixSeparators().setPath(MorePaths.pathWithUnixSeparators(path));
  }
}
