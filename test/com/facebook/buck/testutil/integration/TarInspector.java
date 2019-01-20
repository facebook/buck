/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.testutil.integration;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

/**
 * TarInspector reads .tar files into a {filename --> content} map, which is easy to use in tests.
 */
public class TarInspector {

  /**
   * readTarZst returns a file name --> contents map for the files contained in 'tar'.
   *
   * <p>The returned map is a freshly created one, and changes to the byte[] in it do not affect the
   * underlying archive.
   */
  public static ImmutableMap<String, byte[]> readTarZst(Path tar)
      throws IOException, CompressorException {
    return readTar(Optional.of(CompressorStreamFactory.ZSTANDARD), tar);
  }

  private static ImmutableMap<String, byte[]> readTar(Optional<String> compressorType, Path tar)
      throws IOException, CompressorException {

    HashMap<String, byte[]> result = new HashMap<>();

    try (TarArchiveInputStream archiveStream = getArchiveInputStream(compressorType, tar)) {
      TarArchiveEntry entry;
      while ((entry = archiveStream.getNextTarEntry()) != null) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ByteStreams.copy(archiveStream, buffer);
        result.put(entry.getName(), buffer.toByteArray());
      }
    }

    return ImmutableMap.copyOf(result);
  }

  private static TarArchiveInputStream getArchiveInputStream(
      Optional<String> compressorType, Path tar) throws IOException, CompressorException {
    BufferedInputStream inputStream = new BufferedInputStream(Files.newInputStream(tar));
    if (compressorType.isPresent()) {
      return new TarArchiveInputStream(
          new CompressorStreamFactory()
              .createCompressorInputStream(compressorType.get(), inputStream));
    }
    return new TarArchiveInputStream(inputStream);
  }
}
