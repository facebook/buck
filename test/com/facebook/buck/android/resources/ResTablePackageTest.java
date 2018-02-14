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
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ResTablePackageTest {
  private static final String APK_NAME = "example.apk";

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();
  private ProjectFilesystem filesystem;
  private Path apkPath;

  @Before
  public void setUp() throws InterruptedException, IOException {
    filesystem =
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

      List<Integer> offsets = ChunkUtils.findChunks(buf, ResChunk.CHUNK_RES_TABLE_PACKAGE);
      assertEquals(ImmutableList.of(372), offsets);

      int offset = 372;
      ByteBuffer data = ResChunk.slice(buf, offset);
      ResTablePackage resPackage = ResTablePackage.get(data);

      byte[] expected =
          Arrays.copyOfRange(
              data.array(), data.arrayOffset(), data.arrayOffset() + resPackage.getTotalSize());
      byte[] actual = resPackage.serialize();

      assertArrayEquals(expected, actual);
    }
  }

  @Test
  public void testFullSliceResTablePackage() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(apkZip.getInputStream(apkZip.getEntry("resources.arsc"))));

      ResourceTable resourceTable = ResourceTable.get(buf);
      ResTablePackage resPackage = resourceTable.getPackage();
      Map<Integer, Integer> counts = new HashMap<>();
      for (ResTableTypeSpec spec : resPackage.getTypeSpecs()) {
        counts.put(spec.getResourceType(), spec.getEntryCount());
      }
      ResTablePackage copy = ResTablePackage.slice(resPackage, counts);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      resPackage.dump(resourceTable.getStrings(), new PrintStream(baos));
      String expected = new String(baos.toByteArray(), Charsets.UTF_8);

      baos = new ByteArrayOutputStream();
      copy.dump(resourceTable.getStrings(), new PrintStream(baos));
      String content = new String(baos.toByteArray(), Charsets.UTF_8);

      MoreAsserts.assertLargeStringsEqual(expected, content);
    }
  }
}
