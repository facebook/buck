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

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A command that creates a copy of a ZIP archive, making sure that certain user-specified entries
 * are packed with a certain compression level.
 *
 * Can be used, for instance, to force the resources.arsc file in an Android .apk to be compressed.
 */
public class RepackZipEntriesStep implements Step {

  private final String inputFile;
  private final String outputFile;
  private final ImmutableSet<String> entries;
  private final int compressionLevel;

  /**
   * Creates a {@link RepackZipEntriesStep}. A temporary directory will be created and used
   * to extract entries. Entries will be packed with the maximum compression level.
   * @param inputFile input archive
   * @param outputFile destination archive
   * @param entries files to repack (e.g. {@code ImmutableSet.of("resources.arsc")})
   */
  public RepackZipEntriesStep(
      String inputFile,
      String outputFile,
      ImmutableSet<String> entries) {
    this(inputFile, outputFile, entries, ZipStep.MAX_COMPRESSION_LEVEL);
  }

  /**
   * Creates a {@link RepackZipEntriesStep}.
   * @param inputFile input archive
   * @param outputFile destination archive
   * @param entries files to repack (e.g. {@code ImmutableSet.of("resources.arsc")})
   * @param compressionLevel 0 to 9
   */
  public RepackZipEntriesStep(
      String inputFile,
      String outputFile,
      ImmutableSet<String> entries,
      int compressionLevel) {
    this.inputFile = inputFile;
    this.outputFile = outputFile;
    this.entries = entries;
    this.compressionLevel = compressionLevel;
  }

  @Override
  public int execute(ExecutionContext context) {
    try (
        ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(inputFile)));
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(new File(outputFile));
    ) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        CustomZipEntry customEntry = new CustomZipEntry(entry);
        if (entries.contains(customEntry.getName())) {
          customEntry.setCompressionLevel(compressionLevel);
        }

        InputStream toUse;
        // If we're using STORED files, we must pre-calculate the CRC.
        if (customEntry.getMethod() == ZipEntry.STORED) {
          try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ByteStreams.copy(in, bos);
            byte[] bytes = bos.toByteArray();
            customEntry.setCrc(Hashing.crc32().hashBytes(bytes).padToLong());
            customEntry.setSize(bytes.length);
            customEntry.setCompressedSize(bytes.length);
            toUse = new ByteArrayInputStream(bytes);
          }
        } else {
          toUse = in;
        }

        out.putNextEntry(customEntry);
        ByteStreams.copy(toUse, out);
        out.closeEntry();
      }

      return 0;
    } catch (IOException e) {
      context.logError(e, "Unable to repack zip");
      return 1;
    }
  }

  @Override
  public String getShortName() {
    return "repack zip";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("repack %s in %s", inputFile, outputFile);
  }
}
