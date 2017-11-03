// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import static com.android.tools.r8.utils.FileUtils.CLASS_EXTENSION;
import static com.android.tools.r8.utils.FileUtils.DEX_EXTENSION;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DirectoryOutputSink extends FileSystemOutputSink {

  private final Path outputDirectory;

  public DirectoryOutputSink(Path outputDirectory, InternalOptions options) throws IOException {
    super(options);
    this.outputDirectory = outputDirectory;
    cleanUpOutputDirectory();
  }

  private void cleanUpOutputDirectory() throws IOException {
    if (getOutputMode() == OutputMode.Indexed) {
      try (Stream<Path> filesInDir = Files.list(outputDirectory)) {
        for (Path path : filesInDir.collect(Collectors.toList())) {
          if (FileUtils.isClassesDexFile(path)) {
            Files.delete(path);
          }
        }
      }
    }
  }

  @Override
  public void writeDexFile(byte[] contents, Set<String> classDescriptors, int fileId)
      throws IOException {
    Path target = outputDirectory.resolve(getOutputFileName(fileId));
    Files.createDirectories(target.getParent());
    writeToFile(target, null, contents);
  }

  @Override
  public void writeDexFile(byte[] contents, Set<String> classDescriptors, String primaryClassName)
      throws IOException {
    writeFileFromDescriptor(contents, primaryClassName, DEX_EXTENSION);
  }

  @Override
  public void writeClassFile(byte[] contents, Set<String> classDescriptors, String primaryClassName)
      throws IOException {
    writeFileFromDescriptor(contents, primaryClassName, CLASS_EXTENSION);
  }

  private void writeFileFromDescriptor(byte[] contents, String descriptor, String extension)
      throws IOException {
    Path target = outputDirectory.resolve(getOutputFileName(descriptor, extension));
    Files.createDirectories(target.getParent());
    writeToFile(target, null, contents);
  }

  @Override
  public void close() throws IOException {
    // Intentionally left empty.
  }
}
