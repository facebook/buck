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
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ResourcesXmlTest {
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
      byte[] data =
          ByteStreams.toByteArray(apkZip.getInputStream(apkZip.getEntry("AndroidManifest.xml")));
      ByteBuffer buf = ResChunk.wrap(data);

      List<Integer> xmltreeOffsets = ChunkUtils.findChunks(buf, ResChunk.CHUNK_XML_TREE);
      assertEquals(ImmutableList.of(0), xmltreeOffsets);

      ResourcesXml resXml = ResourcesXml.get(buf);
      assertEquals(buf.limit(), resXml.getTotalSize());
      assertArrayEquals(data, resXml.serialize());
    }
  }

  @Test
  public void testAaptDumpXmlTree() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(
                  apkZip.getInputStream(apkZip.getEntry("AndroidManifest.xml"))));

      ResourcesXml xml = ResourcesXml.get(buf);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      xml.dump(new PrintStream(baos));
      String content = new String(baos.toByteArray(), Charsets.UTF_8);

      Path xmltreeOutput = filesystem.resolve(filesystem.getPath(APK_NAME + ".manifest"));
      String expected = new String(Files.readAllBytes(xmltreeOutput));
      MoreAsserts.assertLargeStringsEqual(expected, content);
    }
  }

  @Test
  public void testVisitReferences() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(
                  apkZip.getInputStream(apkZip.getEntry("AndroidManifest.xml"))));

      // Convert to hex strings so that a failed assertion is easier to read.
      List<String> refs = new ArrayList<>();
      ResourcesXml xml = ResourcesXml.get(buf);
      xml.visitReferences(i -> refs.add(String.format("0x%08x", i)));
      assertEquals(
          ImmutableList.of(
              "0x0101020c",
              "0x01010270",
              "0x01010003",
              "0x01010001",
              "0x01010002",
              "0x0101000f",
              "0x01010025",
              "0x01010010",
              "0x7f040001",
              "0x7f020001",
              "0x7f050001",
              "0x7f050002",
              "0x7f030000"),
          refs);
    }
  }

  @Test
  public void testAaptDumpReversedXmlTree() throws Exception {
    try (ZipFile apkZip = new ZipFile(apkPath.toFile())) {
      ResourceTable resourceTable =
          ResourceTable.get(
              ResChunk.wrap(
                  ByteStreams.toByteArray(
                      apkZip.getInputStream(apkZip.getEntry("resources.arsc")))));
      ReferenceMapper reversingMapper = ReversingMapper.construct(resourceTable);

      ByteBuffer buf =
          ResChunk.wrap(
              ByteStreams.toByteArray(
                  apkZip.getInputStream(apkZip.getEntry("AndroidManifest.xml"))));

      ResourcesXml xml = ResourcesXml.get(buf);
      xml.transformReferences(reversingMapper::map);
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      xml.dump(new PrintStream(baos));
      String content = new String(baos.toByteArray(), Charsets.UTF_8);

      Path xmltreeOutput = filesystem.resolve(filesystem.getPath(APK_NAME + ".manifest.reversed"));
      String expected = new String(Files.readAllBytes(xmltreeOutput));
      MoreAsserts.assertLargeStringsEqual(expected, content);
    }
  }

  @Test
  public void testRewrittenAttrsAreSorted() throws Exception {
    try (InputStream inputStream =
        Files.newInputStream(filesystem.resolve("row_with_button.xml"))) {
      ByteBuffer buf = ResChunk.wrap(ByteStreams.toByteArray(inputStream));
      ResourcesXml xml = ResourcesXml.get(buf);
      Map<Integer, Integer> mapping =
          ImmutableMap.of(0x7f010000, 0x7f010001, 0x7f010001, 0x7f010000);

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      xml.dump(new PrintStream(baos));
      String original = new String(baos.toByteArray(), Charsets.UTF_8);

      xml.transformReferences((i) -> mapping.getOrDefault(i, i));

      baos = new ByteArrayOutputStream();
      xml.dump(new PrintStream(baos));
      String mapped = new String(baos.toByteArray(), Charsets.UTF_8);

      // Just look at the last three lines.
      original = Joiner.on("\n").join(Arrays.asList(original.split("\\r?\\n")).subList(9, 12));
      mapped = Joiner.on("\n").join(Arrays.asList(mapped.split("\\r?\\n")).subList(9, 12));

      assertEquals(
          "        A: buck:actionType(0x7f010000)=\"glyph\" (Raw: \"glyph\")\n"
              + "        A: buck:actionName(0x7f010001)=\"large_size\" (Raw: \"large_size\")\n"
              + "        A: buck:actionColor(0x7f010002)=\"red\" (Raw: \"red\")",
          original);
      assertEquals(
          "        A: buck:actionName(0x7f010000)=\"large_size\" (Raw: \"large_size\")\n"
              + "        A: buck:actionType(0x7f010001)=\"glyph\" (Raw: \"glyph\")\n"
              + "        A: buck:actionColor(0x7f010002)=\"red\" (Raw: \"red\")",
          mapped);
    }
  }
}
