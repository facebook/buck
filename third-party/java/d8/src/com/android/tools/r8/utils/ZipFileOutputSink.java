// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import static com.android.tools.r8.utils.FileUtils.CLASS_EXTENSION;
import static com.android.tools.r8.utils.FileUtils.DEX_EXTENSION;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipFileOutputSink extends FileSystemOutputSink {

  private final ZipOutputStream outputStream;

  public ZipFileOutputSink(Path outputPath, InternalOptions options) throws IOException {
    super(options);
    outputStream = new ZipOutputStream(
        Files.newOutputStream(outputPath, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING));
  }

  @Override
  public void writeDexFile(byte[] contents, Set<String> classDescriptors, int fileId)
      throws IOException {
    writeToZipFile(getOutputFileName(fileId), contents);
  }

  @Override
  public void writeDexFile(byte[] contents, Set<String> classDescriptors, String primaryClassName)
      throws IOException {
    writeToZipFile(getOutputFileName(primaryClassName, DEX_EXTENSION), contents);
  }

  @Override
  public void writeClassFile(byte[] contents, Set<String> classDescriptors, String primaryClassName)
      throws IOException {
    writeToZipFile(getOutputFileName(primaryClassName, CLASS_EXTENSION), contents);
  }

  @Override
  public void close() throws IOException {
    outputStream.close();
  }

  private synchronized void writeToZipFile(String outputPath, byte[] content) throws IOException {
    ZipEntry zipEntry = new ZipEntry(outputPath);
    zipEntry.setSize(content.length);
    outputStream.putNextEntry(zipEntry);
    outputStream.write(content);
    outputStream.closeEntry();
  }
}
