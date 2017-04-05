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
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.io.ByteStreams;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

public class StringPoolTest {
  private static final String APK_NAME = "example.apk";

  @Rule
  public TemporaryPaths tmpFolder = new TemporaryPaths();
  private ProjectFilesystem filesystem;
  private Path apkPath;

  @Before
  public void setUp() throws IOException {
    filesystem = new ProjectFilesystem(
        TestDataHelper.getTestDataDirectory(this).resolve("aapt_dump"));
    apkPath = filesystem.resolve(filesystem.getPath(APK_NAME));
  }

  @Test
  public void testArscPoolGetAndSerialize() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf = ResChunk.wrap(
          ByteStreams.toByteArray(
              apkZip.getInputStream(apkZip.getEntry("resources.arsc"))));

      List<Integer> stringPoolOffsets = ChunkUtils.findChunks(buf, ResChunk.CHUNK_STRING_POOL);
      // Should be 1 main stringpool, and 2 for the package (types and keys).
      assertEquals(3, stringPoolOffsets.size());

      for (int offset : stringPoolOffsets) {
        ByteBuffer data = ResChunk.slice(buf, offset);
        StringPool strings = StringPool.get(data);

        byte[] expected = Arrays.copyOfRange(
            data.array(),
            data.arrayOffset(),
            data.arrayOffset() + strings.getTotalSize());
        byte[] actual = strings.serialize();

        assertArrayEquals(expected, actual);
      }
    }
  }

  @Test
  public void testManifestPoolGetAndSerialize() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf = ResChunk.wrap(
          ByteStreams.toByteArray(
              apkZip.getInputStream(apkZip.getEntry("AndroidManifest.xml"))));

      List<Integer> stringPoolOffsets = ChunkUtils.findChunks(buf, ResChunk.CHUNK_STRING_POOL);
      assertEquals(1, stringPoolOffsets.size());

      for (int offset : stringPoolOffsets) {
        ByteBuffer data = ResChunk.slice(buf, offset);
        StringPool strings = StringPool.get(data);

        byte[] expected = Arrays.copyOfRange(
            data.array(),
            data.arrayOffset(),
            data.arrayOffset() + strings.getTotalSize());
        byte[] actual = strings.serialize();

        assertArrayEquals(expected, actual);
      }
    }
  }
}
