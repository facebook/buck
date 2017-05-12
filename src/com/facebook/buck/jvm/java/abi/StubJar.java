/*
 * Copyright 2014-present Facebook, Inc.
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
import com.facebook.buck.io.ProjectFilesystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileManager;

public class StubJar {
  private final Supplier<LibraryReader> libraryReaderSupplier;
  private boolean sourceAbiCompatible;

  public StubJar(Path toMirror) {
    libraryReaderSupplier = () -> LibraryReader.of(toMirror);
  }

  /**
   * @param targetVersion the class file version to output, expressed as the corresponding Java
   *     source version
   */
  public StubJar(
      SourceVersion targetVersion, Elements elements, Iterable<TypeElement> topLevelTypes) {
    libraryReaderSupplier = () -> LibraryReader.of(targetVersion, elements, topLevelTypes);
  }

  /**
   * Filters the stub jar through {@link SourceAbiCompatibleVisitor}. See that class for details.
   */
  public StubJar setSourceAbiCompatible(boolean sourceAbiCompatible) {
    this.sourceAbiCompatible = sourceAbiCompatible;
    return this;
  }

  public void writeTo(ProjectFilesystem filesystem, Path path) throws IOException {
    // The order of these declarations is important -- FilesystemStubJarWriter actually uses
    // the LibraryReader in its close method, and try-with-resources closes the items in the
    // opposite order of their creation.
    try (LibraryReader input = libraryReaderSupplier.get();
        StubJarWriter writer = new FilesystemStubJarWriter(filesystem, path)) {
      writeTo(input, writer);
    }
  }

  public void writeTo(JavaFileManager fileManager) throws IOException {
    try (LibraryReader input = libraryReaderSupplier.get();
        StubJarWriter writer = new JavaFileManagerStubJarWriter(fileManager)) {
      writeTo(input, writer);
    }
  }

  private void writeTo(LibraryReader input, StubJarWriter writer) throws IOException {
    List<Path> paths =
        input
            .getRelativePaths()
            .stream()
            .sorted(Comparator.comparing(MorePaths::pathWithUnixSeparators))
            .collect(Collectors.toList());

    for (Path path : paths) {
      StubJarEntry entry = StubJarEntry.of(input, path, sourceAbiCompatible);
      if (entry == null) {
        continue;
      }
      entry.write(writer);
    }
  }
}
