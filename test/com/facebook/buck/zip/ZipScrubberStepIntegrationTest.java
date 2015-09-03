/*
 * Copyright 2015-present Facebook, Inc.
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
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Charsets;

import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipScrubberStepIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void modificationTimes() throws Exception {

    // Create a dummy ZIP file.
    Path zip = tmp.newFile("output.zip").toPath();
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      ZipEntry entry = new ZipEntry("file1");
      byte[] data = "data1".getBytes(Charsets.UTF_8);
      entry.setSize(data.length);
      out.putNextEntry(entry);
      out.write(data);
      out.closeEntry();

      entry = new ZipEntry("file2");
      data = "data2".getBytes(Charsets.UTF_8);
      entry.setSize(data.length);
      out.putNextEntry(entry);
      out.write(data);
      out.closeEntry();
    }

    // Execute the zip scrubber step.
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    ZipScrubberStep step = new ZipScrubberStep(
        new ProjectFilesystem(tmp.getRootPath()),
        Paths.get("output.zip"));
    assertEquals(0, step.execute(executionContext));

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_EPOCH_START));
    try (ZipInputStream is = new ZipInputStream(new FileInputStream(zip.toFile()))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

}
