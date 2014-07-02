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
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;

/**
 * Command to zip up a directory while respecting a file size limit to be deflated.
 * <p>
 * Currently, Froyo has a limitation where it will not deflate files out of a zip if the inflated
 * size exceeds 1 MiB (1 << 20 bytes).  This utility is useful for including assets into the APK
 * where we deflate those that we can, and store the ones we can't.
 */
public class ZipDirectoryWithMaxDeflateStep implements Step {

  /**
   * Extensions of files we don't want to DEFLATE (e.g. already compressed).
   */
  private static final Set<String> EXTENSIONS_NOT_TO_DEFLATE = ImmutableSet.of(
      "gzip",
      "jar",
      "jpg",
      "png",
      "xz");

  private final Path inputDirectoryPath;
  private final Path outputZipPath;
  private final long maxDeflatedBytes;

  public ZipDirectoryWithMaxDeflateStep(Path inputDirectoryPath, Path outputZipPath) {
    this(inputDirectoryPath, outputZipPath, 0);
  }

  public ZipDirectoryWithMaxDeflateStep(Path inputDirectoryPath,
                                        Path outputZipPath,
                                        long maxDeflatedBytes) {
    Preconditions.checkState(maxDeflatedBytes >= 0);

    this.maxDeflatedBytes = maxDeflatedBytes;
    this.inputDirectoryPath = Preconditions.checkNotNull(inputDirectoryPath);
    this.outputZipPath = Preconditions.checkNotNull(outputZipPath);
  }

  @Override
  public int execute(ExecutionContext context) {
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    File inputDirectory = filesystem.getFileForRelativePath(inputDirectoryPath);
    Preconditions.checkState(inputDirectory.exists() && inputDirectory.isDirectory(),
        "%s must be a directory.",
        inputDirectoryPath);

    try {
      ImmutableMap.Builder<File, ZipEntry> zipEntriesBuilder = ImmutableMap.builder();
      addDirectoryToZipEntryList(inputDirectory, "", zipEntriesBuilder);
      ImmutableMap<File, ZipEntry> zipEntries = zipEntriesBuilder.build();

      if (!zipEntries.isEmpty()) {
        File outputZipFile = filesystem.getFileForRelativePath(outputZipPath);
        try (CustomZipOutputStream outputStream = ZipOutputStreams.newOutputStream(outputZipFile)) {
          for (Map.Entry<File, ZipEntry> zipEntry : zipEntries.entrySet()) {
            outputStream.putNextEntry(zipEntry.getValue());
            ByteStreams.copy(Files.newInputStreamSupplier(zipEntry.getKey()), outputStream);
            outputStream.closeEntry();
          }
        }
      }
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
    return 0;
  }

  private void addDirectoryToZipEntryList(File directory,
                                 String currentPath,
                                 ImmutableMap.Builder<File, ZipEntry> zipEntriesBuilder)
      throws IOException {
    Preconditions.checkNotNull(currentPath);

    for (File inputFile : directory.listFiles()) {
      String childPath = currentPath +
          (currentPath.isEmpty() ? "" : "/") +
          inputFile.getName();

      if (inputFile.isDirectory()) {
        addDirectoryToZipEntryList(inputFile, childPath, zipEntriesBuilder);
      } else {
        ZipEntry nextEntry = new ZipEntry(childPath);
        long fileLength = inputFile.length();
        if (fileLength > maxDeflatedBytes ||
            EXTENSIONS_NOT_TO_DEFLATE.contains(Files.getFileExtension(inputFile.getName()))) {
          nextEntry.setMethod(ZipEntry.STORED);
          nextEntry.setCompressedSize(inputFile.length());
          nextEntry.setSize(inputFile.length());
          HashCode crc = ByteStreams.hash(Files.newInputStreamSupplier(inputFile), Hashing.crc32());
          nextEntry.setCrc(crc.padToLong());
        }

        zipEntriesBuilder.put(inputFile, nextEntry);
      }
    }
  }

  @Override
  public String getShortName() {
    return "zip_directory";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("zip -r %s %s", outputZipPath, inputDirectoryPath);
  }
}
