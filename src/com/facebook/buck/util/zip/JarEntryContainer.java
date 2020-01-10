/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Represents a container for entries to be added to a jar file by @{link JarBuilder}. Examples
 * include a directory tree or another jar file.
 */
public interface JarEntryContainer extends AutoCloseable {
  static JarEntryContainer of(Path source) {
    Preconditions.checkArgument(source.isAbsolute());

    if (Files.isDirectory(source)) {
      return new DirectoryJarEntryContainer(source);
    } else if (Files.isRegularFile(source)) {
      // Assume a zip or jar file.
      return new ZipFileJarEntryContainer(source);
    } else {
      throw new IllegalStateException("Must be a file or directory: " + source);
    }
  }

  @Nullable
  Manifest getManifest() throws IOException;

  Stream<JarEntrySupplier> stream() throws IOException;

  @Override
  void close() throws IOException;
}
