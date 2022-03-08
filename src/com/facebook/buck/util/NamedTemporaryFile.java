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

package com.facebook.buck.util;

import com.facebook.buck.core.util.log.Logger;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.function.Supplier;

/** Creates a temporary file that is deleted when this object is closed. */
public class NamedTemporaryFile implements Closeable, Supplier<Path> {
  private static final Logger LOG = Logger.get(NamedTemporaryFile.class);

  private final Path tempPath;

  public NamedTemporaryFile(String prefix, String suffix, FileAttribute<?>... attrs)
      throws IOException {
    tempPath = Files.createTempFile(prefix, suffix, attrs);
  }

  @Override
  public Path get() {
    return tempPath;
  }

  @Override
  public synchronized void close() {
    if (Files.exists(tempPath)) {
      try {
        Files.delete(tempPath);
      } catch (IOException e) {
        LOG.warn(e, "Cannot delete time file: %s", tempPath);
      }
    }
  }
}
