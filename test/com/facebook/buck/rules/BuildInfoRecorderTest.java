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

package com.facebook.buck.rules;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.MorePathsForTests;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.Zip;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.timing.FakeClock;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

public class BuildInfoRecorderTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private static final String RULE_KEY = Strings.repeat("a", 40);
  private static final String RULE_KEY_WITHOUT_DEPS = Strings.repeat("b", 40);

  private static final BuildTarget BUILD_TARGET = BuildTargetFactory.newInstance("//foo:bar");

  @Test
  public void testAddMetadataMultipleValues() {
    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder(new FakeProjectFilesystem());
    buildInfoRecorder.addMetadata("foo", ImmutableList.of("bar", "biz", "baz"));
    assertEquals("[\"bar\",\"biz\",\"baz\"]",
        buildInfoRecorder.getMetadataFor("foo"));
  }

  @Test
  public void testWriteMetadataToDisk() throws IOException {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder(filesystem);
    buildInfoRecorder.addMetadata("key1", "value1");

    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ true);

    OnDiskBuildInfo onDiskBuildInfo = new DefaultOnDiskBuildInfo(BUILD_TARGET, filesystem);
    assertOnDiskBuildInfoHasMetadata(onDiskBuildInfo, "key1", "value1");

    buildInfoRecorder = createBuildInfoRecorder(filesystem);
    buildInfoRecorder.addMetadata("key2", "value2");

    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ false);

    onDiskBuildInfo = new DefaultOnDiskBuildInfo(BUILD_TARGET, filesystem);
    assertOnDiskBuildInfoHasMetadata(onDiskBuildInfo, "key1", "value1");
    assertOnDiskBuildInfoHasMetadata(onDiskBuildInfo, "key2", "value2");

    buildInfoRecorder = createBuildInfoRecorder(filesystem);
    buildInfoRecorder.addMetadata("key3", "value3");

    buildInfoRecorder.writeMetadataToDisk(/* clearExistingMetadata */ true);

    onDiskBuildInfo = new DefaultOnDiskBuildInfo(BUILD_TARGET, filesystem);
    assertOnDiskBuildInfoHasMetadata(onDiskBuildInfo, "key3", "value3");
    assertOnDiskBuildInfoDoesNotHaveMetadata(onDiskBuildInfo, "key1");
    assertOnDiskBuildInfoDoesNotHaveMetadata(onDiskBuildInfo, "key2");
  }

  @Test
  public void testCannotRecordArtifactWithAbsolutePath() {
    Path absPath = MorePathsForTests.rootRelativePath("some/absolute/path.txt");
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage(
        String.format(
            BuildInfoRecorder.ABSOLUTE_PATH_ERROR_FORMAT,
            BUILD_TARGET,
            absPath));

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder(filesystem);
    buildInfoRecorder.recordArtifact(absPath);
  }

  @Test
  public void testPerformUploadToArtifactCache()
      throws IOException, InterruptedException {

    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildInfoRecorder buildInfoRecorder = createBuildInfoRecorder(filesystem);
    BuckEventBus bus = new BuckEventBus(new FakeClock(0), new BuildId("BUILD"));

    buildInfoRecorder.writeMetadataToDisk(true);

    final byte[] contents = "contents".getBytes();

    Path file = Paths.get("file");
    filesystem.writeBytesToPath(contents, file);
    buildInfoRecorder.recordArtifact(file);

    Path dir = Paths.get("dir");
    filesystem.mkdirs(dir);
    filesystem.writeBytesToPath(contents, dir.resolve("file"));
    buildInfoRecorder.recordArtifact(dir);

    final AtomicBoolean stored = new AtomicBoolean(false);
    final ArtifactCache cache =
        new NoopArtifactCache() {
          @Override
          public boolean isStoreSupported() {
            return true;
          }
          @Override
          public void store(RuleKey ruleKey, File output) {
            stored.set(true);
            try (Zip zip = new Zip(output, /* forWriting */ false)) {
              assertEquals(
                  ImmutableSet.of(
                      "",
                      "dir/",
                      "buck-out/",
                      "buck-out/log/",
                      "buck-out/bin/",
                      "buck-out/bin/foo/",
                      "buck-out/bin/foo/.bar/",
                      "buck-out/bin/foo/.bar/metadata/"),
                  zip.getDirNames());
              assertEquals(
                  ImmutableSet.of(
                      "dir/file",
                      "file",
                      "buck-out/log/cache_artifact.txt",
                      "buck-out/bin/foo/.bar/metadata/RULE_KEY",
                      "buck-out/bin/foo/.bar/metadata/RULE_KEY_NO_DEPS"),
                  zip.getFileNames());
              assertArrayEquals(contents, zip.readFully("file"));
              assertArrayEquals(contents, zip.readFully("dir/file"));
            } catch (IOException e) {
              throw Throwables.propagate(e);
            }
          }
        };

    buildInfoRecorder.performUploadToArtifactCache(cache, bus);
    assertTrue(stored.get());
  }

  private static void assertOnDiskBuildInfoHasMetadata(
      OnDiskBuildInfo onDiskBuildInfo,
      String key,
      String value) {
    MoreAsserts.assertOptionalValueEquals(
        String.format("BuildInfoRecorder must record '%s:%s' to the filesystem.", key, value),
        value,
        onDiskBuildInfo.getValue(key));
  }

  private static void assertOnDiskBuildInfoDoesNotHaveMetadata(
      OnDiskBuildInfo onDiskBuildInfo,
      String key) {
    assertFalse(
        String.format("BuildInfoRecorder should have cleared this metadata key: %s", key),
        onDiskBuildInfo.getValue(key).isPresent());
  }

  private static BuildInfoRecorder createBuildInfoRecorder(ProjectFilesystem filesystem) {
    return new BuildInfoRecorder(
        BUILD_TARGET,
        filesystem,
        new DefaultClock(),
        new BuildId(),
        ImmutableMap.<String, String>of(),
        new RuleKey(RULE_KEY),
        new RuleKey(RULE_KEY_WITHOUT_DEPS));
  }
}
