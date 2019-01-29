/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.PathByteSource;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.io.ByteSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ZstdStepTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void testZstdStep() throws Exception {
    Path sourceFileOriginal =
        TestDataHelper.getTestDataScenario(this, "compression_test").resolve("step.data");
    Path sourceFile = tmp.newFile("step.data").toPath();
    Files.copy(sourceFileOriginal, sourceFile, StandardCopyOption.REPLACE_EXISTING);
    File destinationFile = tmp.newFile("step.data.zstd");

    ZstdStep step =
        new ZstdStep(
            TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().toPath()),
            sourceFile,
            destinationFile.toPath());

    ExecutionContext context = TestExecutionContext.newInstance();

    assertEquals(0, step.execute(context).getExitCode());

    ByteSource original = PathByteSource.asByteSource(sourceFileOriginal);
    ByteSource decompressed =
        new ByteSource() {
          @Override
          public InputStream openStream() throws IOException {
            return new ZstdCompressorInputStream(new FileInputStream(destinationFile));
          }
        };

    assertFalse(Files.exists(sourceFile));
    assertTrue(
        "Decompressed file must be identical to original.", original.contentEquals(decompressed));
  }
}
