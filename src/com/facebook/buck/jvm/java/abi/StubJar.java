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

import com.facebook.buck.io.HashingDeterministicJarWriter;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.base.Preconditions;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.jar.JarOutputStream;

public class StubJar {
  private final Supplier<LibraryReader> libraryReaderSupplier;
  private final StubDriver stubDriver;

  public StubJar(Path toMirror) {
    libraryReaderSupplier = () -> LibraryReader.of(toMirror);
    stubDriver = BytecodeStubber::createStub;
  }

  public void writeTo(ProjectFilesystem filesystem, Path path) throws IOException {
    Preconditions.checkState(!filesystem.exists(path), "Output file already exists: %s)", path);

    if (path.getParent() != null && !filesystem.exists(path.getParent())) {
      filesystem.createParentDirs(path);
    }

    try (HashingDeterministicJarWriter jar = new HashingDeterministicJarWriter(
        new JarOutputStream(
            filesystem.newFileOutputStream(path)))) {
      writeTo(jar);
    }
  }

  private void writeTo(HashingDeterministicJarWriter jar) throws IOException {
    try (LibraryReader input = libraryReaderSupplier.get()) {
      List<Path> paths = new ArrayList<>(input.getRelativePaths());
      Collections.sort(paths);
      for (Path path : paths) {
        if (isStubbableResource(input, path)) {
          try (InputStream resourceContents = input.openResourceFile(path)) {
            writeResource(jar, path, resourceContents);
          }
        } else if (input.isClass(path)) {
          String fileName = MorePaths.pathWithUnixSeparators(path);
          ClassMirror stub = new ClassMirror(fileName);
          stubDriver.accept(input.openClass(path), stub);
          writeClass(jar, path, stub);
        }
      }
    }
  }

  private void writeResource(
      HashingDeterministicJarWriter jar,
      Path relativePath,
      InputStream resourceContents) throws IOException {
    jar.writeEntry(MorePaths.pathWithUnixSeparators(relativePath), resourceContents);
  }

  private void writeClass(
      HashingDeterministicJarWriter jar,
      Path relativePath,
      ClassMirror stub) throws IOException {
    try (InputStream contents = stub.getStubClassBytes().openStream()) {
      jar.writeEntry(MorePaths.pathWithUnixSeparators(relativePath), contents);
    }
  }

  private boolean isStubbableResource(LibraryReader input, Path path) {
    return input.isResource(path) && !path.endsWith("META-INF" + File.separator + "MANIFEST.MF");
  }

  interface StubDriver {
    void accept(InputStream input, ClassMirror output) throws IOException;
  }
}
