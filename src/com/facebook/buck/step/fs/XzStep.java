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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZ;
import org.tukaani.xz.XZOutputStream;

/**
 * A {@link Step} to compress a file with XZ / LZMA2.
 *
 * @see <a href="http://tukaani.org/xz/">XZ</a>
 * @see <a href="http://tukaani.org/xz/java.html">XZ for Java</a>
 * @see <a href="http://tukaani.org/xz/embedded.html">XZ Embedded</a>
 */
public class XzStep implements Step {
  public static final int DEFAULT_COMPRESSION_LEVEL = 4;

  private final ProjectFilesystem filesystem;
  private final Path sourceFile;
  private final Path destinationFile;
  private final int compressionLevel;
  private final boolean keep;
  private final int check;

  /**
   * Create an {@link XzStep} to compress a file using XZ.
   *
   * @param sourceFile file to compress
   * @param destinationFile where to store compressed data
   * @param compressionLevel a value between 0-9, it impacts memory requirements for decompression
   * @param keep by default, {@code xz} deletes the source file when compressing. This argument
   *     forces {@code xz} to keep it.
   * @param check integrity check to use. Must be one of {@link XZ#CHECK_CRC32}, {@link
   *     XZ#CHECK_CRC64}, {@link XZ#CHECK_SHA256}, {@link XZ#CHECK_NONE} (Note: XZ Embedded can only
   *     verify CRC32).
   */
  @VisibleForTesting
  XzStep(
      ProjectFilesystem filesystem,
      Path sourceFile,
      Path destinationFile,
      int compressionLevel,
      boolean keep,
      int check) {
    this.filesystem = filesystem;
    this.sourceFile = sourceFile;
    this.destinationFile = destinationFile;
    Preconditions.checkArgument(
        compressionLevel >= LZMA2Options.PRESET_MIN && compressionLevel <= LZMA2Options.PRESET_MAX,
        "compressionLevel out of bounds.");
    this.compressionLevel = compressionLevel;
    this.keep = keep;
    this.check = check;
  }

  /**
   * Creates an XzStep to compress a file with XZ compression level {@value
   * #DEFAULT_COMPRESSION_LEVEL}.
   *
   * <p>The destination file will be {@code sourceFile} with the added {@code .xz} extension.
   *
   * <p>Decompression will require 5MiB of RAM.
   *
   * @param sourceFile file to compress
   */
  public XzStep(ProjectFilesystem filesystem, Path sourceFile) {
    this(filesystem, sourceFile, DEFAULT_COMPRESSION_LEVEL);
  }

  /**
   * Creates an XzStep to compress a file with the given XZ compression level and output path.
   *
   * <p>Decompression will require up to 64MiB of RAM.
   *
   * @param sourceFile file to compress
   * @param outputPath the desired output path.
   * @param compressionLevel level of compression (from 0-9)
   */
  public XzStep(
      ProjectFilesystem filesystem, Path sourceFile, Path outputPath, int compressionLevel) {
    this(filesystem, sourceFile, outputPath, compressionLevel, /* keep */ false, XZ.CHECK_CRC32);
  }

  /**
   * Creates an XzStep to compress a file with XZ at a user supplied compression level .
   *
   * <p>The destination file will be {@code sourceFile} with the added {@code .xz} extension.
   *
   * <p>Decompression will require up to 64MiB of RAM.
   *
   * @param sourceFile file to compress
   * @param compressionLevel value from 0 to 9. Higher values result in better compression, but also
   *     need more time to compress and will need more RAM to decompress.
   */
  public XzStep(ProjectFilesystem filesystem, Path sourceFile, int compressionLevel) {
    this(
        filesystem,
        sourceFile,
        Paths.get(sourceFile + ".xz"),
        compressionLevel,
        /* keep */ false,
        XZ.CHECK_CRC32);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    boolean deleteSource = false;
    try (InputStream in = filesystem.newFileInputStream(sourceFile);
        OutputStream out = filesystem.newFileOutputStream(destinationFile);
        XZOutputStream xzOut = new XZOutputStream(out, new LZMA2Options(compressionLevel), check)) {
      XzMemorySemaphore.acquireMemory(compressionLevel);
      ByteStreams.copy(in, xzOut);
      xzOut.finish();
      deleteSource = !keep;
    } finally {
      XzMemorySemaphore.releaseMemory(compressionLevel);
    }
    if (deleteSource) {
      filesystem.deleteFileAtPath(sourceFile);
    }
    return StepExecutionResults.SUCCESS;
  }

  public Path getDestinationFile() {
    return destinationFile;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return Joiner.on(" ")
        .skipNulls()
        .join(
            "xz",
            "-z",
            "-" + compressionLevel,
            (keep ? "--keep" : null),
            "--check=crc32",
            sourceFile);
  }

  @Override
  public String getShortName() {
    return "xz";
  }
}
