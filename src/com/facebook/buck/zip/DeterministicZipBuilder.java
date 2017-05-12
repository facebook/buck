/*
 * Copyright 2017-present Facebook, Inc.
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

import com.google.common.io.ByteStreams;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DeterministicZipBuilder implements Closeable {
  // TODO(cjhopman): Should this buffer the entries and then sort them by name? We may have to
  // buffer them on disk to keep memory use sensible.
  private final CustomZipOutputStream output;

  public DeterministicZipBuilder(Path path) throws IOException {
    this.output = ZipOutputStreams.newOutputStream(path);
  }

  public void addEntry(
      InputStream data,
      long dataLength,
      long crc,
      String name,
      int compressionLevel,
      boolean isDirectory)
      throws IOException {
    CustomZipEntry outputEntry = new CustomZipEntry(Paths.get(name), isDirectory);
    outputEntry.setCompressionLevel(compressionLevel);
    outputEntry.setCrc(crc);
    if (compressionLevel == 0) {
      outputEntry.setCompressedSize(dataLength);
    }
    outputEntry.setSize(dataLength);
    output.putNextEntry(outputEntry);
    ByteStreams.copy(data, output);
    output.closeEntry();
  }

  @Override
  public void close() throws IOException {
    output.close();
  }
}
