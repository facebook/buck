/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util.zip;

import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Class for repacking ZIP archives. */
public class RepackZipEntries {
  /**
   * A command that creates a copy of a ZIP archive, making sure that certain user-specified entries
   * are packed with a certain compression level.
   *
   * <p>Can be used, for instance, to force the resources.arsc file in an Android .apk to be
   * compressed.
   */
  public static void repack(
      Path inputFile,
      Path outputFile,
      ImmutableSet<String> entries,
      ZipCompressionLevel compressionLevel)
      throws IOException {
    try (ZipInputStream in =
            new ZipInputStream(new BufferedInputStream(Files.newInputStream(inputFile)));
        CustomZipOutputStream out = ZipOutputStreams.newOutputStream(outputFile)) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        CustomZipEntry customEntry = new CustomZipEntry(entry);
        if (entries.contains(customEntry.getName())) {
          customEntry.setCompressionLevel(compressionLevel.getValue());
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
    }
  }
}
