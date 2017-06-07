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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.util.function.ThrowingSupplier;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Provides all files and directories recursively contained in a given directory as entries to be
 * added to a jar.
 */
class DirectoryJarEntryContainer implements JarEntryContainer {
  private final Path directory;
  private final String owner;

  public DirectoryJarEntryContainer(Path directory) {
    this.directory = directory;
    this.owner = directory.toString();
  }

  @Nullable
  @Override
  public Manifest getManifest() throws IOException {
    Path manifestPath = directory.resolve("META-INF").resolve("MANIFEST.MF");

    if (!Files.isRegularFile(manifestPath)) {
      return null;
    }

    try (InputStream manifestStream = Files.newInputStream(manifestPath)) {
      return new Manifest(manifestStream);
    }
  }

  @Override
  public Stream<JarEntrySupplier> stream() throws IOException {
    return Files.walk(directory, FileVisitOption.FOLLOW_LINKS)
        .map(
            path -> {
              String relativePath =
                  MorePaths.pathWithUnixSeparators(MorePaths.relativize(directory, path));

              if (relativePath.isEmpty()) {
                return null;
              }

              ThrowingSupplier<InputStream, IOException> inputStreamSupplier;
              if (Files.isDirectory(path)) {
                relativePath += '/';
                inputStreamSupplier = () -> null;
              } else {
                inputStreamSupplier = () -> Files.newInputStream(path);
              }

              return new JarEntrySupplier(
                  new CustomZipEntry(relativePath), owner, inputStreamSupplier);
            })
        .filter(Objects::nonNull)
        .sorted(Comparator.comparing(entrySupplier -> entrySupplier.getEntry().getName()));
  }

  @Override
  public void close() {}
}
