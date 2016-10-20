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

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.distributed.thrift.PathWithUnixSeparators;
import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class RecordingFileHashLoaderTest {
  @Rule
  public TemporaryFolder projectDir = new TemporaryFolder();

  @Rule
  public TemporaryFolder externalDir = new TemporaryFolder();

  private static final HashCode EXAMPLE_HASHCODE = HashCode.fromString("1234");

  @Test
  public void testRecordsDirectSymlinkToFile() throws IOException {
    // Scenario:
    // /project/linktoexternal -> /externalDir/externalfile
    // => create direct link: /project/linktoexternal -> /externalDir/externalfile

    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());
    Path externalFile = externalDir.newFile("externalfile").toPath();
    Path symlink = projectDir.getRoot().toPath().resolve("linktoexternal");
    Files.createSymbolicLink(symlink, externalFile);

    BuildJobStateFileHashes fileHashes = new BuildJobStateFileHashes();
    FakeFileHashLoader delegateLoader = new FakeFileHashLoader(ImmutableMap.of(
        symlink,
        EXAMPLE_HASHCODE));

    RecordingFileHashLoader recordingLoader = new RecordingFileHashLoader(
        delegateLoader,
        projectFilesystem,
        fileHashes);

    recordingLoader.get(symlink);

    assertThat(
        fileHashes.getEntries().size(),
        Matchers.equalTo(1));

    BuildJobStateFileHashEntry fileHashEntry = fileHashes.getEntries().get(0);
    assertTrue(fileHashEntry.isSetRootSymLink());
    assertThat(
        fileHashEntry.getRootSymLink(),
        Matchers.equalTo((unixPath("linktoexternal"))));
    assertTrue(fileHashEntry.isSetRootSymLink());
    assertThat(
        fileHashEntry.getRootSymLinkTarget(),
        Matchers.equalTo((unixPath(externalFile.toRealPath().toString()))));
  }

  @Test
  public void testRecordsSymlinkToFileWithinExternalDirectory() throws IOException {
    assumeTrue(!Platform.detect().equals(Platform.WINDOWS));

    // Scenario:
    // /project/linktoexternaldir/externalfile -> /externalDir/externalfile
    // => create link for parent dir: /project/linktoexternaldir -> /externalDir

    ProjectFilesystem projectFilesystem = new ProjectFilesystem(projectDir.getRoot().toPath());
    externalDir.newFile("externalfile");
    Path symlinkRoot = projectDir.getRoot().toPath().resolve("linktoexternaldir");
    Files.createSymbolicLink(symlinkRoot, externalDir.getRoot().toPath());
    Path symlink = symlinkRoot.resolve("externalfile"); // /project/linktoexternaldir/externalfile

    BuildJobStateFileHashes fileHashes = new BuildJobStateFileHashes();
    FakeFileHashLoader delegateLoader = new FakeFileHashLoader(ImmutableMap.of(
        symlink,
        EXAMPLE_HASHCODE));

    RecordingFileHashLoader recordingLoader = new RecordingFileHashLoader(
        delegateLoader,
        projectFilesystem,
        fileHashes);

    recordingLoader.get(symlink);

    assertThat(
        fileHashes.getEntries().size(),
        Matchers.equalTo(1));

    BuildJobStateFileHashEntry fileHashEntry = fileHashes.getEntries().get(0);
    assertTrue(fileHashEntry.isSetRootSymLink());
    assertThat(
        fileHashEntry.getRootSymLink(),
        Matchers.equalTo((unixPath("linktoexternaldir"))));
    assertTrue(fileHashEntry.isSetRootSymLink());
    assertThat(
        fileHashEntry.getRootSymLinkTarget(),
        Matchers.equalTo((unixPath(externalDir.getRoot().toPath().toRealPath().toString()))));
  }

  private static PathWithUnixSeparators unixPath(String path) {
    return new PathWithUnixSeparators(MorePaths.pathWithUnixSeparators(
        path));
  }

  public class FakeFileHashLoader implements FileHashLoader {
    private final Map<Path, HashCode> hashCodesByPath;

    public FakeFileHashLoader(Map<Path, HashCode> hashCodesByPath) {
      this.hashCodesByPath = hashCodesByPath;
    }

    @Override
    public HashCode get(Path path) throws IOException {
      return hashCodesByPath.get(path);
    }

    @Override
    public long getSize(Path path) throws IOException {
      return 0;
    }

    @Override
    public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
      return null;
    }
  }

}
