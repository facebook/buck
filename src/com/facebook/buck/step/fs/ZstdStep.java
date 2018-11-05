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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

/**
 * A {@link Step} to compress a file with zstd
 *
 * @see <a href="https://github.com/luben/zstd-jni">ZSTD</a>
 */
public class ZstdStep implements Step {

  public static final int DEFAULT_COMPRESSION_LEVEL = 19;

  private final ProjectFilesystem filesystem;
  private final Path sourceFile;
  private final Path outputPath;

  /**
   * Creates an ZstdStep to compress a file to given output path.
   *
   * @param sourceFile file to compress
   * @param outputPath the desired output path.
   */
  public ZstdStep(ProjectFilesystem filesystem, Path sourceFile, Path outputPath) {
    this.filesystem = filesystem;
    this.sourceFile = sourceFile;
    this.outputPath = outputPath;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    try (InputStream in = filesystem.newFileInputStream(sourceFile);
        OutputStream out = filesystem.newFileOutputStream(outputPath);
        ZstdCompressorOutputStream zstdOut =
            new ZstdCompressorOutputStream(out, DEFAULT_COMPRESSION_LEVEL, false, true)) {
      ByteStreams.copy(in, zstdOut);
    } finally {
      filesystem.deleteFileAtPath(sourceFile);
    }
    return StepExecutionResults.SUCCESS;
  }

  public Path getOutputPath() {
    return outputPath;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("zstd -z -%d -C %s", DEFAULT_COMPRESSION_LEVEL, sourceFile);
  }

  @Override
  public String getShortName() {
    return "zstd";
  }
}
