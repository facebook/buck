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

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.hash.HashCode;

import org.easymock.EasyMock;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class DistBuildFileMaterializerTest {
  @Rule
  public TemporaryFolder projectDir = new TemporaryFolder();

  @Rule
  public TemporaryFolder externalDir = new TemporaryFolder();

  private static final HashCode EXAMPLE_HASHCODE = HashCode.fromString("1234");
  private static final String FILE_CONTENTS = "filecontents";

  interface MaterializeFunction {
    void execute(DistBuildFileMaterializer materializer, Path symlink) throws IOException;
  }

  private Path testEntryForRealFile(MaterializeFunction materializeFunction) throws IOException {
    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());
    Path realFile = projectFilesystem.resolve("realfile");
    Path relativeRealFile = Paths.get("realfile");

    BuildJobStateFileHashEntry realFileHashEntry = new BuildJobStateFileHashEntry();
    realFileHashEntry.setPath(unixPath(relativeRealFile));
    realFileHashEntry.setHashCode(EXAMPLE_HASHCODE.toString());
    realFileHashEntry.setContents(FILE_CONTENTS.getBytes(StandardCharsets.UTF_8));
    BuildJobStateFileHashes fileHashes = new BuildJobStateFileHashes();
    fileHashes.addToEntries(realFileHashEntry);

    FileContentsProvider mockFileProvider = EasyMock.createMock(FileContentsProvider.class);
    InputStream fileContentStream =
        new ByteArrayInputStream(FILE_CONTENTS.getBytes(StandardCharsets.UTF_8));
    expect(mockFileProvider.getFileContents(realFileHashEntry))
        .andReturn(Optional.of(fileContentStream));
    replay(mockFileProvider);

    DistBuildFileMaterializer fileMaterializer = new DistBuildFileMaterializer(
        projectFilesystem, fileHashes, mockFileProvider);
    materializeFunction.execute(fileMaterializer, realFile);

    return realFile;
  }

  @Test
  public void testMaterializeRealFileSetsContents() throws IOException {
    // Scenario:
    //  path: /project/linktoexternaldir/externalfile
    //  contents: "filecontents"
    // => materialize creates file with correct contents
    Path realFile = testEntryForRealFile((m, p) -> m.get(p));

    assertTrue(realFile.toFile().exists());
    String actualFileContents = new String(Files.readAllBytes(realFile));
    assertThat(
        actualFileContents,
        Matchers.equalTo(FILE_CONTENTS));
  }

  @Test
  public void testPreloadRealFileTouchesFile() throws IOException {
    // Scenario:
    //  path: /project/linktoexternaldir/externalfile
    //  contents: "filecontents"
    // => preload touches file, but doesn't set contents
    Path realFile = testEntryForRealFile((m, p) -> m.preloadAllFiles());

    assertTrue(realFile.toFile().exists());
    assertThat(realFile.toFile().length(),
        Matchers.equalTo(0L));
  }

  public void testSymlinkToFileWithinExternalDirectory(
      MaterializeFunction materializeFunction) throws IOException {
    // Scenario:
    //  path: /project/linktoexternaldir/externalfile
    //  symlink root: /project/linktoexternaldir -> /externalDir
    // => check that /project/linktoexternaldir/externalfile -> /externalDir/externalfile

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());
    File externalFile = externalDir.newFile("externalfile");
    Path symlinkRoot = projectFilesystem.resolve("linktoexternaldir");
    Path relativeSymlinkRoot = Paths.get("linktoexternaldir");
    Path symlink = symlinkRoot.resolve("externalfile"); // /project/linktoexternaldir/externalfile
    Path relativeSymlink = projectFilesystem.getPathRelativeToProjectRoot(symlink).get();

    BuildJobStateFileHashEntry symlinkFileHashEntry = new BuildJobStateFileHashEntry();
    symlinkFileHashEntry.setRootSymLink(unixPath(relativeSymlinkRoot));
    symlinkFileHashEntry.setRootSymLinkTarget(unixPath(externalDir.getRoot().toPath()));
    symlinkFileHashEntry.setPath(unixPath(relativeSymlink));
    symlinkFileHashEntry.setHashCode(EXAMPLE_HASHCODE.toString());
    BuildJobStateFileHashes fileHashes = new BuildJobStateFileHashes();
    fileHashes.addToEntries(symlinkFileHashEntry);

    FileContentsProvider mockFileProvider = EasyMock.createMock(FileContentsProvider.class);

    DistBuildFileMaterializer fileMaterializer = new DistBuildFileMaterializer(
        projectFilesystem, fileHashes, mockFileProvider);

    assertFalse(symlink.toFile().exists());

    materializeFunction.execute(fileMaterializer, symlink);

    assertTrue(symlink.toFile().exists());
    assertThat(
        symlink.toRealPath(),
        Matchers.equalTo(externalFile.toPath().toRealPath()));
  }

  @Test
  public void testPreloadSymlinkToFileWithinExternalDirectory() throws IOException {
    testSymlinkToFileWithinExternalDirectory(
        (fileMaterializer, symlink) -> fileMaterializer.preloadAllFiles());
  }

  @Test
  public void testMaterializeSymlinkToFileWithinExternalDirectory() throws IOException {
    testSymlinkToFileWithinExternalDirectory(
        (fileMaterializer, symlink) -> fileMaterializer.get(symlink));
  }

  @Test
  public void testPreloadMaterializeSymlinkToFileWithinExternalDirectory() throws IOException {
    testSymlinkToFileWithinExternalDirectory(
        (fileMaterializer, symlink) -> {
          fileMaterializer.preloadAllFiles();
          fileMaterializer.get(symlink);
        });
  }

  private static PathWithUnixSeparators unixPath(Path path) {
    return new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(
        path));
  }

}
