// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.OutputSink;
import com.google.common.io.Closer;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public abstract class FileSystemOutputSink implements OutputSink {

  private final InternalOptions options;

  protected FileSystemOutputSink(InternalOptions options) {
    this.options = options;
  }

  public static FileSystemOutputSink create(Path outputPath, InternalOptions options)
      throws IOException {
    if (FileUtils.isArchive(outputPath)) {
      return new ZipFileOutputSink(outputPath, options);
    } else {
      return new DirectoryOutputSink(outputPath, options);
    }
  }

  String getOutputFileName(int index) {
    assert !options.outputClassFiles;
    return index == 0 ? "classes.dex" : ("classes" + (index + 1) + FileUtils.DEX_EXTENSION);
  }

  String getOutputFileName(String classDescriptor, String extension) throws IOException {
    assert classDescriptor != null && DescriptorUtils.isClassDescriptor(classDescriptor);
    return DescriptorUtils.getClassBinaryNameFromDescriptor(classDescriptor) + extension;
  }


  @Override
  public void writePrintUsedInformation(byte[] contents) throws IOException {
    writeToFile(options.proguardConfiguration.getPrintUsageFile(), System.out, contents);
  }

  @Override
  public void writeProguardMapFile(byte[] contents) throws IOException {
    if (options.proguardConfiguration.getPrintMappingFile() != null) {
      writeToFile(options.proguardConfiguration.getPrintMappingFile(), System.out, contents);
    }
    if (options.proguardMapOutput != null) {
      writeToFile(options.proguardMapOutput, System.out, contents);
    }
  }

  @Override
  public void writeProguardSeedsFile(byte[] contents) throws IOException {
    writeToFile(options.proguardConfiguration.getSeedFile(), System.out, contents);
  }

  @Override
  public void writeMainDexListFile(byte[] contents) throws IOException {
    writeToFile(options.printMainDexListFile, System.out, contents);
  }

  protected void writeToFile(Path output, OutputStream defValue, byte[] contents)
      throws IOException {
    try (Closer closer = Closer.create()) {
      OutputStream outputStream =
          FileUtils.openPathWithDefault(
              closer,
              output,
              defValue,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
      outputStream.write(contents);
    }
  }

  protected OutputMode getOutputMode() {
    return options.outputMode;
  }
}
