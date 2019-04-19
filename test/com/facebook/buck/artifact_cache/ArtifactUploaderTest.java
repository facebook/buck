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

package com.facebook.buck.artifact_cache;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.MorePosixFilePermissions;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TarInspector;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ArtifactUploaderTest {

  private static final BuildTarget BUILD_TARGET = BuildTargetFactory.newInstance("//foo:bar");

  @Test
  public void testPerformUploadToArtifactCache() throws IOException {

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    byte[] contents = "contents".getBytes();

    Path file = Paths.get("file");
    filesystem.writeBytesToPath(contents, file);

    Path dirFile = Paths.get("dir", "file");
    filesystem.createParentDirs(dirFile);
    filesystem.writeBytesToPath(contents, dirFile);

    Path metadataFile =
        Paths.get("buck-out", "bin", "foo", ".bar", "metadata", "artifact", "metadata");
    filesystem.createParentDirs(metadataFile);
    filesystem.writeBytesToPath(contents, metadataFile);

    Path dir = Paths.get("buck-out", "bin", "foo", ".bar/");
    filesystem.mkdirs(dir);

    AtomicBoolean stored = new AtomicBoolean(false);
    ArtifactCache cache =
        new NoopArtifactCache() {
          @Override
          public CacheReadMode getCacheReadMode() {
            return CacheReadMode.READWRITE;
          }

          @Override
          public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath output) {
            stored.set(true);

            // Verify the build metadata.
            assertThat(
                info.getMetadata().get("build-metadata"), Matchers.equalTo("build-metadata"));
            assertThat(
                info.getBuildTarget().get().getFullyQualifiedName(),
                Matchers.equalTo(BUILD_TARGET.getFullyQualifiedName()));

            // Unarchive file.
            final ImmutableMap<String, byte[]> archiveContents;
            try {
              archiveContents = TarInspector.readTarZst(output.getPath());
            } catch (IOException | CompressorException e) {
              fail(e.getMessage());
              return Futures.immediateFuture(null);
            }

            // Verify archive contents.
            assertEquals(
                ImmutableSet.of(
                    "buck-out/bin/foo/.bar/",
                    "dir/file",
                    "file",
                    "buck-out/bin/foo/.bar/metadata/artifact/metadata"),
                archiveContents.keySet());
            assertArrayEquals(contents, archiveContents.get("file"));
            assertArrayEquals(contents, archiveContents.get("dir/file"));
            assertArrayEquals(
                contents, archiveContents.get("buck-out/bin/foo/.bar/metadata/artifact/metadata"));
            return Futures.immediateFuture(null);
          }
        };

    ArtifactUploader.performUploadToArtifactCache(
        ImmutableSet.of(new RuleKey("aa")),
        cache,
        BuckEventBusForTests.newInstance(),
        ImmutableMap.of("metadata", "metadata", "build-metadata", "build-metadata"),
        ImmutableSortedSet.of(dir, file, dirFile, metadataFile),
        BUILD_TARGET,
        filesystem,
        1000);

    assertTrue(stored.get());
  }

  /** compressSavesExecutableBit asserts that compress()-ing an executable file stores the x bit. */
  @Test
  public void compressSavesExecutableBit() throws Exception {
    ProjectFilesystem fs = FakeProjectFilesystem.createJavaOnlyFilesystem("/");

    Path out = fs.getRootPath().resolve("out");
    Path file = fs.getRootPath().resolve("file");
    fs.writeContentsToPath("foo", file);
    Files.setPosixFilePermissions(
        fs.getPathForRelativePath(file), ImmutableSet.of(PosixFilePermission.OWNER_EXECUTE));

    // Compress
    ArtifactUploader.compress(fs, ImmutableList.of(file), out);

    // Decompress+unarchive, and check that the only file is an executable.
    try (TarArchiveInputStream fin =
        new TarArchiveInputStream(new ZstdCompressorInputStream(Files.newInputStream(out)))) {
      ArrayList<TarArchiveEntry> entries = new ArrayList<>();

      TarArchiveEntry entry;
      while ((entry = fin.getNextTarEntry()) != null) {
        entries.add(entry);
      }

      assertThat(entries, Matchers.hasSize(1));
      assertThat(
          MorePosixFilePermissions.fromMode(entries.get(0).getMode()),
          Matchers.contains(PosixFilePermission.OWNER_EXECUTE));
    }
  }
}
