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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.PathByteSource;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.io.ByteSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.XZ;
import org.tukaani.xz.XZInputStream;

public class XzStepTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void testXzStepDefaultDestinationFile() {
    Path sourceFile = Paths.get("/path/to/source.file");
    XzStep step =
        new XzStep(
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toPath()), sourceFile);
    assertEquals(Paths.get(sourceFile + ".xz"), step.getDestinationFile());
  }

  @Test
  public void testXzStep() throws InterruptedException, IOException {
    Path sourceFile =
        TestDataHelper.getTestDataScenario(this, "compression_test").resolve("step.data");
    File destinationFile = tmp.newFile("step.data.xz");

    XzStep step =
        new XzStep(
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toPath()),
            sourceFile,
            destinationFile.toPath(),
            /* compressionLevel -- for faster testing */ 1,
            /* keep */ true,
            XZ.CHECK_CRC32);

    ExecutionContext context = TestExecutionContext.newInstance();

    assertEquals(0, step.execute(context).getExitCode());

    ByteSource original = PathByteSource.asByteSource(sourceFile);
    ByteSource decompressed =
        new ByteSource() {
          @Override
          public InputStream openStream() throws IOException {
            return new XZInputStream(new FileInputStream(destinationFile));
          }
        };

    assertTrue(Files.exists(sourceFile));
    assertTrue(
        "Decompressed file must be identical to original.", original.contentEquals(decompressed));
  }

  @Test
  public void testXzStepDeletesOriginal() throws InterruptedException, IOException {
    Path sourceFileOriginal =
        TestDataHelper.getTestDataScenario(this, "compression_test").resolve("step.data");
    Path sourceFile = tmp.newFile("step.data").toPath();
    Files.copy(sourceFileOriginal, sourceFile, StandardCopyOption.REPLACE_EXISTING);
    File destinationFile = tmp.newFile("step.data.xz");

    XzStep step =
        new XzStep(
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toPath()),
            sourceFile,
            destinationFile.toPath(),
            /* compressionLevel -- for faster testing */ 1,
            /* keep */ false,
            XZ.CHECK_CRC32);

    ExecutionContext context = TestExecutionContext.newInstance();

    assertEquals(0, step.execute(context).getExitCode());

    ByteSource original = PathByteSource.asByteSource(sourceFileOriginal);
    ByteSource decompressed =
        new ByteSource() {
          @Override
          public InputStream openStream() throws IOException {
            return new XZInputStream(new FileInputStream(destinationFile));
          }
        };

    assertFalse(Files.exists(sourceFile));
    assertTrue(
        "Decompressed file must be identical to original.", original.contentEquals(decompressed));
  }
}
