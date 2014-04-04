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

package com.facebook.buck.zip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.testutil.Zip;
import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;

public class UnzipTest {
  private static final byte[] DUMMY_FILE_CONTENTS = "BUCK Unzip Test String!\nNihao\n".getBytes();

  @Rule
  public TemporaryFolder tmpFolder = new TemporaryFolder();

  private File zipFile;

  @Before
  public void setUp() throws Exception {
    zipFile = new File(tmpFolder.getRoot(), "tmp.zip");
    try (Zip zip = new Zip(zipFile, true)) {
      zip.add("1.bin", DUMMY_FILE_CONTENTS);
      zip.add("subdir/2.bin", DUMMY_FILE_CONTENTS);
      zip.addDir("emptydir");
    }
  }

  @Test
  public void testExtractZipFile() throws IOException {
    File extractFolder = tmpFolder.newFolder();
    ImmutableList<Path> result = Unzip.extractZipFile(
        zipFile.getAbsolutePath(),
        extractFolder.getAbsolutePath(),
        false);
    assertTrue(new File(extractFolder.getAbsolutePath() + "/1.bin").exists());
    File bin2 = new File(extractFolder.getAbsolutePath() + "/subdir/2.bin");
    assertTrue(bin2.exists());
    assertTrue(new File(extractFolder.getAbsolutePath() + "/emptydir").isDirectory());
    try (FileInputStream input = new FileInputStream(bin2)) {
      byte[] buffer = new byte[DUMMY_FILE_CONTENTS.length];
      int bytesRead = input.read(buffer, 0, DUMMY_FILE_CONTENTS.length);
      assertEquals(DUMMY_FILE_CONTENTS.length, bytesRead);
      for (int i = 0; i < DUMMY_FILE_CONTENTS.length; i++) {
        assertEquals(DUMMY_FILE_CONTENTS[i], buffer[i]);
      }
    }
    assertEquals(
        ImmutableList.of(
          extractFolder.toPath().resolve("1.bin"),
          extractFolder.toPath().resolve("subdir/2.bin")),
        result);
  }
}
