/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android.resources;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class ExoResourcesRewriterTest {
  private static final String APK_NAME = "example.apk";

  private ProjectFilesystem filesystem;
  private RelPath apkPath;

  @Before
  public void setUp() {
    filesystem =
        TestProjectFilesystems.createProjectFilesystem(
            TestDataHelper.getTestDataDirectory(this).resolve("aapt_dump"));
    apkPath = RelPath.of(filesystem.getPath(APK_NAME));
  }

  @Test
  public void testRewriteResources() throws IOException {
    RelPath primaryOutput = RelPath.of(Paths.get("primary.apk"));
    RelPath exoOutput = RelPath.of(Paths.get("exo.apk"));
    ExoResourcesRewriter.rewriteResources(
        filesystem.getRootPath(), apkPath, primaryOutput, exoOutput);

    ZipInspector primaryApkInspector =
        new ZipInspector(filesystem.getRootPath().resolve(primaryOutput));
    assertEquals(
        ImmutableList.of(
            "resources.arsc",
            "AndroidManifest.xml",
            "res/drawable-nodpi-v4/exo_icon.png",
            "res/xml/meta_xml.xml"),
        primaryApkInspector.getZipFileEntries());
    ZipInspector baseApkInspector = new ZipInspector(filesystem.getRootPath().resolve(apkPath));
    ZipInspector exoApkInspector = new ZipInspector(filesystem.getRootPath().resolve(exoOutput));
    assertEquals(baseApkInspector.getZipFileEntries(), exoApkInspector.getZipFileEntries());

    assertArrayEquals(
        primaryApkInspector.getFileContents("AndroidManifest.xml"),
        exoApkInspector.getFileContents("AndroidManifest.xml"));

    assertArrayEquals(
        primaryApkInspector.getFileContents("res/xml/meta_xml.xml"),
        exoApkInspector.getFileContents("res/xml/meta_xml.xml"));

    ResourceTable primaryResourceTable =
        ResourceTable.get(ResChunk.wrap(primaryApkInspector.getFileContents("resources.arsc")));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    primaryResourceTable.dump(new PrintStream(baos));
    String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
    Path expectedPath = filesystem.resolve(filesystem.getPath(APK_NAME + ".resources.primary"));
    String expected = filesystem.readFileIfItExists(expectedPath).get();

    assertEquals(expected, content);

    ResourceTable exoResourceTable =
        ResourceTable.get(ResChunk.wrap(exoApkInspector.getFileContents("resources.arsc")));

    baos = new ByteArrayOutputStream();
    exoResourceTable.dump(new PrintStream(baos));
    content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
    expectedPath = filesystem.resolve(filesystem.getPath(APK_NAME + ".resources.exo"));
    expected = filesystem.readFileIfItExists(expectedPath).get();

    assertEquals(expected, content);
  }

  @Test
  public void testRewriteRTxt() throws IOException {
    assumeTrue(Platform.detect() != Platform.WINDOWS);
    RelPath inputRTxt = RelPath.of(Paths.get("input.R.txt"));
    String rtxtContent =
        "int style Widget_AppCompat_Light_PopupMenu 0x7f0b0025\n"
            + "int style Widget_AppCompat_Light_PopupMenu_Overflow 0x7f0b0023\n"
            + "int[] styleable ActionMode { 0x7f010000, 0x7f01005a, 0x7f01005b, 0x7f01005e, 0x7f010060, 0x7f01006e }\n"
            + "int styleable ActionMode_background 3\n"
            + "int styleable ActionMode_backgroundSplit 4\n"
            + "int styleable ActionMode_closeItemLayout 5\n"
            + "int styleable ActionMode_height 0\n"
            + "int styleable ActionMode_subtitleTextStyle 2\n"
            + "int styleable ActionMode_titleTextStyle 1\n";
    String expectedOutput =
        "int style Widget_AppCompat_Light_PopupMenu 0x7f0b0001\n"
            + "int style Widget_AppCompat_Light_PopupMenu_Overflow 0x7f0b0023\n"
            + "int[] styleable ActionMode { 0x7f010000, 0x7f010001, 0x7f010002, 0x7f01005a, 0x7f01005e, 0x7f01006e }\n"
            + "int styleable ActionMode_background 4\n"
            + "int styleable ActionMode_backgroundSplit 1\n"
            + "int styleable ActionMode_closeItemLayout 5\n"
            + "int styleable ActionMode_height 0\n"
            + "int styleable ActionMode_subtitleTextStyle 2\n"
            + "int styleable ActionMode_titleTextStyle 3\n";
    filesystem.writeContentsToPath(rtxtContent, inputRTxt);

    RelPath outputRTxt = RelPath.of(Paths.get("output.R.txt"));
    ExoResourcesRewriter.rewriteRDotTxt(
        filesystem.getRootPath(),
        new ReferenceMapper() {
          @Override
          public int map(int id) {
            switch (id) {
              case 0x7f0b0025:
                return 0x7f0b0001;
              case 0x7f01005b:
                return 0x7f010002;
              case 0x7f010060:
                return 0x7f010001;
            }
            return id;
          }

          @Override
          public void rewrite(int type, IntBuffer buf) {
            throw new UnsupportedOperationException();
          }
        },
        inputRTxt,
        outputRTxt);

    assertEquals(expectedOutput, filesystem.readFileIfItExists(outputRTxt).get());
  }
}
