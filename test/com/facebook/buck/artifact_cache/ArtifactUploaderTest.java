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

import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.ZipArchive;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
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

    Path dir = Paths.get("dir");
    Path dirFile = dir.resolve("file");
    filesystem.mkdirs(dir);
    filesystem.writeBytesToPath(contents, dirFile);

    Path metadataDir =
        Paths.get("buck-out")
            .resolve("bin")
            .resolve("foo")
            .resolve(".bar")
            .resolve("metadata")
            .resolve("artifact");
    Path metadataFile = metadataDir.resolve("metadata");
    filesystem.mkdirs(metadataDir);
    filesystem.writeBytesToPath(contents, metadataFile);

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

            // Verify zip contents
            try (ZipArchive zip = new ZipArchive(output.getPath(), /* forWriting */ false)) {
              assertEquals(
                  ImmutableSet.of(
                      "",
                      "dir/",
                      "buck-out/",
                      "buck-out/bin/",
                      "buck-out/bin/foo/",
                      "buck-out/bin/foo/.bar/",
                      "buck-out/bin/foo/.bar/metadata/",
                      "buck-out/bin/foo/.bar/metadata/artifact/"),
                  zip.getDirNames());
              assertEquals(
                  ImmutableSet.of(
                      "dir/file", "file", "buck-out/bin/foo/.bar/metadata/artifact/metadata"),
                  zip.getFileNames());
              assertArrayEquals(contents, zip.readFully("file"));
              assertArrayEquals(contents, zip.readFully("dir/file"));
            } catch (IOException e) {
              Throwables.throwIfUnchecked(e);
              throw new RuntimeException(e);
            }
            return Futures.immediateFuture(null);
          }
        };

    ArtifactUploader.performUploadToArtifactCache(
        ImmutableSet.of(new RuleKey("aa")),
        cache,
        BuckEventBusForTests.newInstance(),
        ImmutableMap.of("metadata", "metadata", "build-metadata", "build-metadata"),
        ImmutableSortedSet.of(file, dirFile, metadataFile),
        BUILD_TARGET,
        filesystem);

    assertTrue(stored.get());
  }
}
