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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ResTableTypeSpecTest {
  private static final String APK_NAME = "example.apk";

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();
  private Path apkPath;

  @Before
  public void setUp() {
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(
            TestDataHelper.getTestDataDirectory(this).resolve("aapt_dump"));
    apkPath = filesystem.resolve(filesystem.getPath(APK_NAME));
  }

  @Test
  public void testGetAndSerialize() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(apkZip.getInputStream(apkZip.getEntry("resources.arsc"))));

      List<Integer> offsets = ChunkUtils.findChunks(buf, ResChunk.CHUNK_RES_TABLE_TYPE_SPEC);
      assertEquals(ImmutableList.of(1024, 1040, 1320, 1436, 1624, 1896), offsets);

      int expectedOffset = 1024;
      for (int offset : offsets) {
        assertEquals(expectedOffset, offset);
        ByteBuffer data = ResChunk.slice(buf, offset);
        ResTableTypeSpec resSpec = ResTableTypeSpec.get(data);

        byte[] expected =
            Arrays.copyOfRange(
                data.array(), data.arrayOffset(), data.arrayOffset() + resSpec.getTotalSize());
        byte[] actual = resSpec.serialize();

        assertArrayEquals(expected, actual);
        expectedOffset += resSpec.getTotalSize();
      }
    }
  }

  @Test
  public void testFullSliceResTableTypeSpec() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(apkZip.getInputStream(apkZip.getEntry("resources.arsc"))));

      ResourceTable resourceTable = ResourceTable.get(buf);
      ResTablePackage resPackage = resourceTable.getPackage();
      for (ResTableTypeSpec spec : resPackage.getTypeSpecs()) {
        int entryCount = spec.getEntryCount();
        ResTableTypeSpec copy = ResTableTypeSpec.slice(spec, entryCount);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        spec.dump(resourceTable.getStrings(), resPackage, new PrintStream(baos));
        String expected = new String(baos.toByteArray(), Charsets.UTF_8);

        baos = new ByteArrayOutputStream();
        copy.dump(resourceTable.getStrings(), resPackage, new PrintStream(baos));
        String content = new String(baos.toByteArray(), Charsets.UTF_8);

        MoreAsserts.assertLargeStringsEqual(expected, content);
      }
    }
  }
}
