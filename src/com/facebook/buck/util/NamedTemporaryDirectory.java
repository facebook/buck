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

package com.facebook.buck.util;

import com.facebook.buck.io.file.MostFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;

/** Creates a temporary directory that is (recursively) deleted when this object is closed. */
public class NamedTemporaryDirectory implements AutoCloseable {
  private final Path path;

  public NamedTemporaryDirectory(String prefix, FileAttribute<?>... attrs) throws IOException {
    this.path = Files.createTempDirectory(prefix, attrs);
  }

  @Override
  public void close() throws IOException {
    MostFiles.deleteRecursively(path);
  }

  public Path getPath() {
    return path;
  }
}
