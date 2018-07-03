/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.core.build.engine.buildinfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.file.MorePathsForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuildInfoRecorderTest {

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private static final BuildTarget BUILD_TARGET = BuildTargetFactory.newInstance("//foo:bar");

  @Test
  public void testAddMetadataMultipleValues() {
    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder(new FakeProjectFilesystem());
    buildInfoRecorder.addMetadata("foo", ImmutableList.of("bar", "biz", "baz"));
    assertEquals("[\"bar\",\"biz\",\"baz\"]", buildInfoRecorder.getMetadataFor("foo"));
  }

  @Test
  public void testWriteMetadataToDisk() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildInfoStore store = new FilesystemBuildInfoStore(filesystem);
    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder(filesystem);
    buildInfoRecorder.addMetadata("key1", "value1");

    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ true);

    OnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(BUILD_TARGET, filesystem, store);
    assertOnDiskBuildInfoHasMetadata(onDiskBuildInfo, "key1", "value1");

    buildInfoRecorder = createBuildInfoRecorder(filesystem);
    buildInfoRecorder.addMetadata("key2", "value2");

    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ false);

    onDiskBuildInfo = new DefaultOnDiskBuildInfo(BUILD_TARGET, filesystem, store);
    assertOnDiskBuildInfoHasMetadata(onDiskBuildInfo, "key1", "value1");
    assertOnDiskBuildInfoHasMetadata(onDiskBuildInfo, "key2", "value2");

    buildInfoRecorder = createBuildInfoRecorder(filesystem);
    buildInfoRecorder.addMetadata("key3", "value3");

    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ true);

    onDiskBuildInfo = new DefaultOnDiskBuildInfo(BUILD_TARGET, filesystem, store);
    assertOnDiskBuildInfoHasMetadata(onDiskBuildInfo, "key3", "value3");
    assertOnDiskBuildInfoDoesNotHaveMetadata(onDiskBuildInfo, "key1");
    assertOnDiskBuildInfoDoesNotHaveMetadata(onDiskBuildInfo, "key2");

    // Verify build metadata gets written correctly.
    buildInfoRecorder = createBuildInfoRecorder(filesystem);
    buildInfoRecorder.addBuildMetadata("build", "metadata");
    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ true);
    onDiskBuildInfo = new DefaultOnDiskBuildInfo(BUILD_TARGET, filesystem, store);
    assertOnDiskBuildInfoHasBuildMetadata(onDiskBuildInfo, "build", "metadata");

    // Verify additional info build metadata always gets written.
    buildInfoRecorder = createBuildInfoRecorder(filesystem);
    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ true);
    onDiskBuildInfo = new DefaultOnDiskBuildInfo(BUILD_TARGET, filesystem, store);
    assertTrue(onDiskBuildInfo.getBuildValue(BuildInfo.MetadataKey.ADDITIONAL_INFO).isPresent());
  }

  @Test
  public void testCannotRecordArtifactWithAbsolutePath() {
    Path absPath = MorePathsForTests.rootRelativePath("some/absolute/path.txt");
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage(
        String.format(BuildInfoRecorder.ABSOLUTE_PATH_ERROR_FORMAT, BUILD_TARGET, absPath));

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder(filesystem);
    buildInfoRecorder.recordArtifact(absPath);
  }

  @Test
  public void testGetOutputSize() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder(filesystem);

    byte[] contents = "contents".getBytes();

    Path file = Paths.get("file");
    filesystem.writeBytesToPath(contents, file);
    buildInfoRecorder.recordArtifact(file);

    Path dir = Paths.get("dir");
    filesystem.mkdirs(dir);
    filesystem.writeBytesToPath(contents, dir.resolve("file1"));
    filesystem.writeBytesToPath(contents, dir.resolve("file2"));
    buildInfoRecorder.recordArtifact(dir);

    assertEquals(3 * contents.length, buildInfoRecorder.getOutputSize());
  }

  @Test
  public void testGetOutputHash() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache fileHashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT)));
    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder(filesystem);

    byte[] contents = "contents".getBytes();

    Path file = Paths.get("file");
    filesystem.writeBytesToPath(contents, file);
    buildInfoRecorder.recordArtifact(file);

    Path dir = Paths.get("dir");
    filesystem.mkdirs(dir);
    filesystem.writeBytesToPath(contents, dir.resolve("file1"));
    filesystem.writeBytesToPath(contents, dir.resolve("file2"));
    buildInfoRecorder.recordArtifact(dir);

    fileHashCache.invalidateAll();
    HashCode current = buildInfoRecorder.getOutputHash(fileHashCache);

    // Test that getting the hash again results in the same hashcode.
    fileHashCache.invalidateAll();
    assertEquals(current, buildInfoRecorder.getOutputHash(fileHashCache));

    // Verify that changing a file changes the hash.
    filesystem.writeContentsToPath("something else", file);
    fileHashCache.invalidateAll();
    HashCode updated = buildInfoRecorder.getOutputHash(fileHashCache);
    assertNotEquals(current, updated);

    // Verify that changing a file under a directory changes the hash.
    filesystem.writeContentsToPath("something else", dir.resolve("file1"));
    current = updated;
    fileHashCache.invalidateAll();
    updated = buildInfoRecorder.getOutputHash(fileHashCache);
    assertNotEquals(current, updated);

    // Test that adding a file updates the hash.
    Path added = Paths.get("added");
    filesystem.writeBytesToPath(contents, added);
    buildInfoRecorder.recordArtifact(added);
    current = updated;
    fileHashCache.invalidateAll();
    updated = buildInfoRecorder.getOutputHash(fileHashCache);
    assertNotEquals(current, updated);

    // Test that adding a file under a recorded directory updates the hash.
    Path addedUnderDir = dir.resolve("added");
    filesystem.writeBytesToPath(contents, addedUnderDir);
    current = updated;
    fileHashCache.invalidateAll();
    updated = buildInfoRecorder.getOutputHash(fileHashCache);
    assertNotEquals(current, updated);
  }

  private static void assertOnDiskBuildInfoHasMetadata(
      OnDiskBuildInfo onDiskBuildInfo, String key, String value) {
    MoreAsserts.assertOptionalValueEquals(
        String.format(
            "BuildInfoRecorder must record '%s:%s' to the artifact metadata.", key, value),
        value,
        onDiskBuildInfo.getValue(key));
  }

  private static void assertOnDiskBuildInfoHasBuildMetadata(
      OnDiskBuildInfo onDiskBuildInfo, String key, String value) {
    MoreAsserts.assertOptionalValueEquals(
        String.format("BuildInfoRecorder must record '%s:%s' to the build metadata.", key, value),
        value,
        onDiskBuildInfo.getBuildValue(key));
  }

  private static void assertOnDiskBuildInfoDoesNotHaveMetadata(
      OnDiskBuildInfo onDiskBuildInfo, String key) {
    assertFalse(
        String.format("BuildInfoRecorder should have cleared this metadata key: %s", key),
        onDiskBuildInfo.getValue(key).isPresent());
    assertFalse(
        String.format("BuildInfoRecorder should have cleared this metadata key: %s", key),
        onDiskBuildInfo.getBuildValue(key).isPresent());
  }

  private static BuildInfoRecorder createBuildInfoRecorder(ProjectFilesystem filesystem) {
    return new BuildInfoRecorder(
        BUILD_TARGET,
        filesystem,
        new FilesystemBuildInfoStore(filesystem),
        new DefaultClock(),
        new BuildId(),
        ImmutableMap.of());
  }
}
