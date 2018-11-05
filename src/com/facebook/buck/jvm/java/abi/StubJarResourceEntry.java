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

package com.facebook.buck.jvm.java.abi;

import java.nio.file.Path;

class StubJarResourceEntry extends StubJarEntry {
  private final LibraryReader input;
  private final Path path;

  public static StubJarResourceEntry of(LibraryReader input, Path path) {
    return new StubJarResourceEntry(input, path);
  }

  private StubJarResourceEntry(LibraryReader input, Path path) {
    this.input = input;
    this.path = path;
  }

  @Override
  public void write(StubJarWriter writer) {
    writer.writeEntry(path, () -> input.openResourceFile(path));
  }
}
