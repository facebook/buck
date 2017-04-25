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

import com.facebook.buck.io.ProjectFilesystem;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileManager;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

public class StubJar {
  private final Supplier<LibraryReader> libraryReaderSupplier;

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

  public void writeTo(ProjectFilesystem filesystem, Path path) throws IOException {
    try (StubJarWriter writer = new FilesystemStubJarWriter(filesystem, path)) {
      writeTo(writer);
    }
  }

  public void writeTo(JavaFileManager fileManager) throws IOException {
    try (StubJarWriter writer = new JavaFileManagerStubJarWriter(fileManager)) {
      writeTo(writer);
    }
  }

  private void writeTo(StubJarWriter writer) throws IOException {
    try (LibraryReader input = libraryReaderSupplier.get()) {
      List<Path> paths = new ArrayList<>(input.getRelativePaths());
      Collections.sort(paths);

      for (Path path : paths) {
        if (isStubbableResource(input, path)) {
          try (InputStream resourceContents = input.openResourceFile(path)) {
            writer.writeResource(path, resourceContents);
          }
        } else if (input.isClass(path)) {
          ClassNode stub = new ClassNode(Opcodes.ASM5);
          input.visitClass(path, new AbiFilteringClassVisitor(stub));
          if (!isAnonymousOrLocalClass(stub)) {
            writer.writeClass(path, stub);
          }
        }
      }
    }
  }

  private static boolean isAnonymousOrLocalClass(ClassNode node) {
    InnerClassNode innerClass = getInnerClassMetadata(node);
    if (innerClass == null) {
      return false;
    }

    return innerClass.outerName == null;
  }

  @Nullable
  private static InnerClassNode getInnerClassMetadata(ClassNode node) {
    for (InnerClassNode innerClass : node.innerClasses) {
      if (innerClass.name.equals(node.name)) {
        return innerClass;
      }
    }

    return null;
  }

  private boolean isStubbableResource(LibraryReader input, Path path) {
    return input.isResource(path) && !path.endsWith("META-INF" + File.separator + "MANIFEST.MF");
  }
}
