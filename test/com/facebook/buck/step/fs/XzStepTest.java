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

package com.facebook.buck.step.fs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.io.ByteSource;
import com.google.common.io.Files;

import org.easymock.EasyMock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.XZ;
import org.tukaani.xz.XZInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

public class XzStepTest {

  @Rule
  public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void testXzStepDefaultDestinationFile() {
    final Path sourceFile = Paths.get("/path/to/source.file");
    XzStep step = new XzStep(sourceFile);
    assertEquals(Paths.get(sourceFile + ".xz"), step.getDestinationFile());
  }

  @Test
  public void testXzStep() throws IOException {
    final File sourceFile = new File(
        TestDataHelper.getTestDataScenario(this, "xz_with_rm_and_check"),
        "xzstep.data");
    final File destinationFile = tmp.newFile("xzstep.data.xz");

    XzStep step = new XzStep(
        sourceFile.toPath(),
        destinationFile.toPath(),
        /* compressionLevel -- for faster testing */ 1,
        /* keep */ false,
        XZ.CHECK_CRC32);

    ProjectFilesystem fs = EasyMock.createMock(ProjectFilesystem.class);
    EasyMock.expect(fs.deleteFileAtPath(sourceFile.toPath())).andReturn(true);
    EasyMock.replay(fs);

    ExecutionContext context = TestExecutionContext.newBuilder()
        .setProjectFilesystem(fs)
        .build();

    assertEquals(0, step.execute(context));

    ByteSource original = Files.asByteSource(sourceFile);
    ByteSource decompressed = new ByteSource() {
      @Override
      public InputStream openStream() throws IOException {
        return new XZInputStream(new FileInputStream(destinationFile));
      }
    };

    assertTrue(
        "Decompressed file must be identical to original.",
        original.contentEquals(decompressed));

    EasyMock.verify(fs);
  }
}
