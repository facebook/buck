/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.zip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.io.Files;

import org.easymock.EasyMock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ZipDirectoryWithMaxDeflateStepTest {
  private File outputApk;
  private File emptyTempDir;
  private final Path zipDirectory = Paths.get("testdata/com/facebook/buck/zip/zipdirectorytest");
  private File emptyOutputDir;

  @Before
  public void setUp() throws IOException {
    outputApk = File.createTempFile("ZipDirectoryWithMaxDeflateCommandTest", "zip");
    emptyTempDir = Files.createTempDir();
    emptyOutputDir = Files.createTempDir();
  }

  @After
  public void tearDown() {
    outputApk.delete();
    emptyTempDir.delete();
    emptyOutputDir.delete();
  }

  @Test
  public void testZipDirectory() throws IOException {

    ZipDirectoryWithMaxDeflateStep zipCommand = new ZipDirectoryWithMaxDeflateStep(
        zipDirectory, outputApk.toPath(), 128);

    ExecutionContext executionContext = EasyMock.createMock(ExecutionContext.class);
    EasyMock.replay(executionContext);

    zipCommand.execute(executionContext);

    assertTrue(outputApk.exists());

    try (ZipFile resultZip = new ZipFile(outputApk.getAbsoluteFile())) {
      ZipEntry storedFile = resultZip.getEntry("StoredFile");
      assertEquals("StoredFile should have been STORED else Froyo will crash on launch.",
          storedFile.getMethod(),
          ZipEntry.STORED);

      ZipEntry deflatedFile = resultZip.getEntry("subDir/DeflatedFile");
      assertEquals(
          "DeflatedFile should have been DEFLATED",
          deflatedFile.getMethod(),
          ZipEntry.DEFLATED);

      ZipEntry compressedFile = resultZip.getEntry("CompressedFile.xz");
      assertEquals("CompressedFile.xz should have been STORED, it's already compressed",
          compressedFile.getMethod(),
          ZipEntry.STORED);
    }
    EasyMock.verify(executionContext);
  }

  @Test
  public void testEmptyZipDirectory() throws IOException {

    File emptyOutput = new File(emptyOutputDir.getAbsolutePath() + "/output.zip");

    ZipDirectoryWithMaxDeflateStep zipCommand = new ZipDirectoryWithMaxDeflateStep(
        emptyTempDir.toPath(), emptyOutput.toPath(), 128);

    ExecutionContext executionContext = TestExecutionContext.newInstance();

    zipCommand.execute(executionContext);

    assertFalse(emptyOutput.exists());
  }
}
