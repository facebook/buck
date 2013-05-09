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

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ProGuardObfuscateStepTest {
  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  @Test
  public void testCreateEmptyZip() throws Exception {
    File tmpFile = tmpDir.newFile();
    ProGuardObfuscateStep.createEmptyZip(tmpFile);

    // Try to read it.
    ZipFile zipFile = new ZipFile(tmpFile);
    int totalSize = 0;
    List<? extends ZipEntry> entries = Collections.list(zipFile.entries());

    assertTrue("Expected either 0 or 1 entry", entries.size() <= 1);
    for (ZipEntry entry : entries) {
      totalSize += entry.getSize();
    }
    assertEquals("Zip file should have zero-length contents", 0, totalSize);
  }
}
