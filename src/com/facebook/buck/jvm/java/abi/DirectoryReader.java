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

package com.facebook.buck.jvm.java.abi;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;

/** A {@link LibraryReader} that reads from a directory (recursively). */
class DirectoryReader implements LibraryReader {
  private final Path root;

  public DirectoryReader(Path root) {
    this.root = root;
  }

  @Override
  public List<Path> getRelativePaths() throws IOException {
    return Files.walk(root)
        .filter(path -> !Files.isDirectory(path))
        .map(root::relativize)
        .collect(Collectors.toList());
  }

  @Override
  public InputStream openResourceFile(Path relativePath) throws IOException {
    if (!isResource(relativePath)) {
      throw new IllegalArgumentException();
    }
    return openInputStream(relativePath);
  }

  @Override
  public void visitClass(Path relativePath, ClassVisitor cv, boolean skipCode) throws IOException {
    if (!isClass(relativePath)) {
      throw new IllegalArgumentException();
    }

    int parsingOptions = ClassReader.SKIP_FRAMES;
    if (skipCode) {
      parsingOptions |= ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE;
    }

    try (InputStream inputStream = openInputStream(relativePath)) {
      ClassReader reader = new ClassReader(inputStream);
      reader.accept(cv, parsingOptions);
    }
  }

  @Override
  public void close() {
    // Nothing in particular needed
  }

  private InputStream openInputStream(Path relativePath) throws IOException {
    return new BufferedInputStream(Files.newInputStream(root.resolve(relativePath)));
  }
}
