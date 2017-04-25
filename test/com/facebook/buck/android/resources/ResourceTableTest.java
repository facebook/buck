/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android.resources;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ResourceTableTest {
  private static final String APK_NAME = "example.apk";

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();
  private ProjectFilesystem filesystem;
  private Path apkPath;

  @Before
  public void setUp() throws InterruptedException, IOException {
    filesystem =
        new ProjectFilesystem(TestDataHelper.getTestDataDirectory(this).resolve("aapt_dump"));
    apkPath = filesystem.resolve(filesystem.getPath(APK_NAME));
  }

  @Test
  public void testGetAndSerialize() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(apkZip.getInputStream(apkZip.getEntry("resources.arsc"))));

      List<Integer> offsets = ChunkUtils.findChunks(buf, ResChunk.CHUNK_RESOURCE_TABLE);
      assertEquals(ImmutableList.of(0), offsets);

      int offset = 0;
      ByteBuffer data = ResChunk.slice(buf, offset);
      ResourceTable resTable = ResourceTable.get(data);

      byte[] expected =
          Arrays.copyOfRange(
              data.array(), data.arrayOffset(), data.arrayOffset() + resTable.getTotalSize());
      byte[] actual = resTable.serialize();

      assertArrayEquals(expected, actual);
    }
  }

  @Test
  public void testAaptDumpResources() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(apkZip.getInputStream(apkZip.getEntry("resources.arsc"))));
      ResourceTable resourceTable = ResourceTable.get(buf);
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      resourceTable.dump(new PrintStream(baos));
      String content = new String(baos.toByteArray(), Charsets.UTF_8);

      Path resourcesOutput = filesystem.resolve(filesystem.getPath(APK_NAME + ".resources"));

      // We don't care about dumping the correct config string.
      Pattern re = Pattern.compile("      config.*:");
      String expected =
          Joiner.on("\n")
              .join(
                  Files.readAllLines(resourcesOutput)
                      .stream()
                      .map((s) -> re.matcher(s).matches() ? "      config (unknown):" : s)
                      .iterator());
      MoreAsserts.assertLargeStringsEqual(expected + "\n", content);
    }
  }
}
