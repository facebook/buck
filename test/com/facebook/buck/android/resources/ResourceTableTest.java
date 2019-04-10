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
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  public void setUp() {
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
                  Files.readAllLines(resourcesOutput).stream()
                      .map((s) -> re.matcher(s).matches() ? "      config (unknown):" : s)
                      .iterator());
      MoreAsserts.assertLargeStringsEqual(expected + "\n", content);
    }
  }

  @Test
  public void testRewriteResources() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(apkZip.getInputStream(apkZip.getEntry("resources.arsc"))));
      ResourceTable resourceTable = ResourceTable.get(buf);

      ReferenceMapper reversingMapper = ReversingMapper.construct(resourceTable);
      resourceTable.reassignIds(reversingMapper);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      resourceTable.dump(new PrintStream(baos));
      String content = new String(baos.toByteArray(), Charsets.UTF_8);

      Path resourcesOutput =
          filesystem.resolve(filesystem.getPath(APK_NAME + ".resources.reversed"));

      // We don't care about dumping the correct config string.
      Pattern re = Pattern.compile("      config.*:");
      String expected =
          Joiner.on("\n")
              .join(
                  Files.readAllLines(resourcesOutput).stream()
                      .map((s) -> re.matcher(s).matches() ? "      config (unknown):" : s)
                      .iterator());
      MoreAsserts.assertLargeStringsEqual(expected + "\n", content);
    }
  }

  @Test
  public void testDoubleReverseResources() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(apkZip.getInputStream(apkZip.getEntry("resources.arsc"))));
      ResourceTable resourceTable = ResourceTable.get(buf);

      ReferenceMapper reversingMapper = ReversingMapper.construct(resourceTable);
      resourceTable.reassignIds(reversingMapper);
      resourceTable.reassignIds(reversingMapper);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      resourceTable.dump(new PrintStream(baos));
      String content = new String(baos.toByteArray(), Charsets.UTF_8);

      Path resourcesOutput = filesystem.resolve(filesystem.getPath(APK_NAME + ".resources"));

      // We don't care about dumping the correct config string.
      Pattern re = Pattern.compile("      config.*:");
      String expected =
          Joiner.on("\n")
              .join(
                  Files.readAllLines(resourcesOutput).stream()
                      .map((s) -> re.matcher(s).matches() ? "      config (unknown):" : s)
                      .iterator());
      MoreAsserts.assertLargeStringsEqual(expected + "\n", content);
    }
  }

  @Test
  public void testFullSliceResourceTable() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(apkZip.getInputStream(apkZip.getEntry("resources.arsc"))));
      ResourceTable resourceTable = ResourceTable.get(buf);
      Map<Integer, Integer> counts = new HashMap<>();
      for (ResTableTypeSpec spec : resourceTable.getPackage().getTypeSpecs()) {
        counts.put(spec.getResourceType(), spec.getEntryCount());
      }
      // When we slice a resource table, we sort the string pool. The offsets into the
      // string pool are part of the dump output. For this test, we compare a single slice of
      // everything to a double slice so that the reordering is ignored.
      resourceTable = ResourceTable.slice(resourceTable, counts);
      ResourceTable copy = ResourceTable.slice(resourceTable, counts);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      resourceTable.dump(new PrintStream(baos));
      String expected = new String(baos.toByteArray(), Charsets.UTF_8);

      baos = new ByteArrayOutputStream();
      copy.dump(new PrintStream(baos));
      String content = new String(baos.toByteArray(), Charsets.UTF_8);

      MoreAsserts.assertLargeStringsEqual(expected, content);
    }
  }

  @Test
  public void testSliceResourceTable() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(apkZip.getInputStream(apkZip.getEntry("resources.arsc"))));
      ResourceTable resourceTable = ResourceTable.get(buf);
      Map<Integer, Integer> counts = new HashMap<>();
      for (ResTableTypeSpec spec : resourceTable.getPackage().getTypeSpecs()) {
        counts.put(spec.getResourceType(), Math.min(spec.getEntryCount(), 1));
      }
      resourceTable = ResourceTable.slice(resourceTable, counts);
      Path resourcesOutput = filesystem.resolve(filesystem.getPath(APK_NAME + ".resources.sliced"));
      String expected = filesystem.readFileIfItExists(resourcesOutput).get();

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      resourceTable.dump(new PrintStream(baos));
      String content = new String(baos.toByteArray(), Charsets.UTF_8);

      MoreAsserts.assertLargeStringsEqual(expected, content);
    }
  }

  @Test
  public void testSliceResourceTableStringsAreOptimized() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(apkZip.getInputStream(apkZip.getEntry("resources.arsc"))));
      ResourceTable resourceTable = ResourceTable.get(buf);
      Map<Integer, Integer> counts = new HashMap<>();
      for (ResTableTypeSpec spec : resourceTable.getPackage().getTypeSpecs()) {
        counts.put(spec.getResourceType(), Math.min(spec.getEntryCount(), 1));
      }
      resourceTable = ResourceTable.slice(resourceTable, counts);

      resourceTable.getStrings().dump(System.out);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      resourceTable.getStrings().dump(new PrintStream(baos));
      String content = new String(baos.toByteArray(), Charsets.UTF_8);

      assertEquals(
          "String pool of 4 unique UTF-8 non-sorted strings, "
              + "4 entries and 0 styles using 120 bytes:\n"
              + "String #0: \n"
              + "String #1: res/drawable/aaa_image.png\n"
              + "String #2: res/xml/meta_xml.xml\n"
              + "String #3: some other string\n",
          content);

      baos = new ByteArrayOutputStream();
      resourceTable.getPackage().getKeys().dump(new PrintStream(baos));
      content = new String(baos.toByteArray(), Charsets.UTF_8);

      assertEquals(
          "String pool of 5 unique UTF-8 non-sorted strings, "
              + "5 entries and 0 styles using 112 bytes:\n"
              + "String #0: aaa_array\n"
              + "String #1: aaa_image\n"
              + "String #2: aaa_string_other\n"
              + "String #3: meta_xml\n"
              + "String #4: some_id\n",
          content);

      baos = new ByteArrayOutputStream();
      resourceTable.getPackage().getTypes().dump(new PrintStream(baos));
      content = new String(baos.toByteArray(), Charsets.UTF_8);

      assertEquals(
          "String pool of 6 unique UTF-8 non-sorted strings, "
              + "6 entries and 0 styles using 100 bytes:\n"
              + "String #0: attr\n"
              + "String #1: drawable\n"
              + "String #2: xml\n"
              + "String #3: string\n"
              + "String #4: array\n"
              + "String #5: id\n",
          content);
    }
  }
}
