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

import com.facebook.buck.io.PathByteSource;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.io.ByteSource;

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
    XzStep step = new XzStep(new ProjectFilesystem(tmp.getRoot().toPath()), sourceFile);
    assertEquals(Paths.get(sourceFile + ".xz"), step.getDestinationFile());
  }

  @Test
  public void testXzStep() throws IOException {
    final Path sourceFile =
        TestDataHelper.getTestDataScenario(this, "xz_with_rm_and_check").resolve("xzstep.data");
    final File destinationFile = tmp.newFile("xzstep.data.xz");

    XzStep step = new XzStep(
        new ProjectFilesystem(tmp.getRoot().toPath()),
        sourceFile,
        destinationFile.toPath(),
        /* compressionLevel -- for faster testing */ 1,
        /* keep */ true,
        XZ.CHECK_CRC32);

    ExecutionContext context = TestExecutionContext.newInstance();

    assertEquals(0, step.execute(context));

    ByteSource original = PathByteSource.asByteSource(sourceFile);
    ByteSource decompressed = new ByteSource() {
      @Override
      public InputStream openStream() throws IOException {
        return new XZInputStream(new FileInputStream(destinationFile));
      }
    };

    assertTrue(
        "Decompressed file must be identical to original.",
        original.contentEquals(decompressed));
  }
}
