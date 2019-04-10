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

package com.facebook.buck.util.zip;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import javax.annotation.Nullable;

/** Provides all entries of a given zip or jar file, so they can be added to another jar. */
class ZipFileJarEntryContainer implements JarEntryContainer {
  private final String owner;
  private final Path jarFilePath;
  @Nullable private JarFile jar;

  public ZipFileJarEntryContainer(Path jarFilePath) {
    this.jarFilePath = jarFilePath;
    this.owner = jarFilePath.toString();
  }

  @Nullable
  @Override
  public Manifest getManifest() throws IOException {
    return getJarFile().getManifest();
  }

  @Override
  public Stream<JarEntrySupplier> stream() throws IOException {
    return getJarFile().stream()
        .map(
            entry ->
                new JarEntrySupplier(
                    makeCustomEntry(entry), owner, () -> getJarFile().getInputStream(entry)));
  }

  @Override
  public void close() throws IOException {
    getJarFile().close();
  }

  private JarFile getJarFile() throws IOException {
    if (jar == null) {
      try {
        File jarFile = jarFilePath.toFile();
        jar = new JarFile(jarFile);
      } catch (IOException e) {
        throw new IOException("Failed to process ZipFile " + owner, e);
      }
    }

    return jar;
  }

  private static CustomZipEntry makeCustomEntry(ZipEntry entry) {
    CustomZipEntry wrappedEntry = new CustomZipEntry(entry);

    // For deflated entries, the act of re-"putting" this entry means we're re-compressing
    // the data that we've just uncompressed.  Due to various environmental issues (e.g. a
    // newer version of zlib, changed compression settings), we may end up with a different
    // compressed size.  This causes an issue in java's `java.util.zip.ZipOutputStream`
    // implementation, as it only updates the compressed size field if one of `crc`,
    // `compressedSize`, or `size` is -1.  When we copy the entry as-is, none of these are
    // -1, and we may end up with an incorrect compressed size, in which case, we'll get an
    // exception.  So, for deflated entries, reset the compressed size to -1 (as the
    // ZipEntry(String) would).
    // See https://github.com/spearce/buck/commit/8338c1c3d4a546f577eed0c9941d9f1c2ba0a1b7.
    if (wrappedEntry.getMethod() == ZipEntry.DEFLATED) {
      wrappedEntry.setCompressedSize(-1);
    }

    return wrappedEntry;
  }
}
