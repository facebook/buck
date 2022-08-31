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

package com.facebook.buck.jvm.java.abi;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/** Write an entry to stub jar by directly copy an existing abi. */
class StubJarExistingEntry extends StubJarEntry {
  private final Path existingAbiPath;
  private final Path path;

  public static StubJarExistingEntry of(Path existingAbiPath, Path path) {
    return new StubJarExistingEntry(existingAbiPath, path);
  }

  private StubJarExistingEntry(Path existingAbiPath, Path path) {
    this.existingAbiPath = existingAbiPath;
    this.path = path;
  }

  @Override
  public void write(StubJarWriter writer) {
    writer.writeEntry(path, () -> openInputStream(existingAbiPath));
  }

  private InputStream openInputStream(Path existingAbiPath) throws IOException {
    return new BufferedInputStream(Files.newInputStream(existingAbiPath));
  }

  @Override
  public List<String> getInlineFunctions() {
    return Collections.emptyList();
  }

  @Override
  public boolean extendsInlineFunctionScope() {
    return false;
  }
}
