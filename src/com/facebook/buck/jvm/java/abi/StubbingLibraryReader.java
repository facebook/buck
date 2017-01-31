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

import com.facebook.buck.io.MorePaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * A {@link LibraryReader} that stubs classes in the process of reading them.
 */
class StubbingLibraryReader implements LibraryReader<ClassMirror> {
  private final LibraryReader<?> inner;
  private final StubDriver<Path> stubDriver;

  /**
  * @param <UnstubbedClass> the type used to work with the unstubbed class
  */
  <UnstubbedClass> StubbingLibraryReader(
      LibraryReader<UnstubbedClass> inner,
      StubDriver<UnstubbedClass> stubDriver) {
    this.inner = inner;
    this.stubDriver = (path, mirror) -> stubDriver.accept(inner.openClass(path), mirror);
  }

  @Override
  public List<Path> getRelativePaths() throws IOException {
    return inner.getRelativePaths();
  }

  @Override
  public InputStream openResourceFile(Path relativePath) throws IOException {
    return inner.openResourceFile(relativePath);
  }

  /**
   * Returns a {@link ClassMirror} representing the stub of the class at the given path.
   */
  @Override
  public ClassMirror openClass(Path relativePath) throws IOException {
    String fileName = MorePaths.pathWithUnixSeparators(relativePath);
    ClassMirror stub = new ClassMirror(fileName);
    stubDriver.accept(relativePath, stub);
    return stub;
  }

  @Override
  public void close() throws IOException {
    inner.close();
  }

  interface StubDriver<UnstubbedClass> {
    void accept(UnstubbedClass input, ClassMirror output) throws IOException;
  }
}
