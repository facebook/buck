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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InnerClassNode;

class StubJarClassEntry extends StubJarEntry {
  private final Path path;
  private final ClassNode stub;
  private boolean sourceAbiCompatible;

  @Nullable
  public static StubJarClassEntry of(LibraryReader input, Path path) throws IOException {
    ClassNode stub = new ClassNode(Opcodes.ASM5);
    input.visitClass(path, new AbiFilteringClassVisitor(stub));

    if (!isAnonymousOrLocalClass(stub)) {
      return new StubJarClassEntry(path, stub);
    }

    return null;
  }

  private StubJarClassEntry(Path path, ClassNode stub) {
    this.path = path;
    this.stub = stub;
  }

  /**
   * Filters the stub class through {@link SourceAbiCompatibleVisitor}. See that class for details.
   */
  public void setSourceAbiCompatible(boolean sourceAbiCompatible) {
    this.sourceAbiCompatible = sourceAbiCompatible;
  }

  @Override
  public void write(StubJarWriter writer) throws IOException {
    writer.writeEntry(path, this::openInputStream);
  }

  private InputStream openInputStream() throws IOException {
    ClassWriter writer = new ClassWriter(0);
    ClassVisitor visitor = writer;
    if (sourceAbiCompatible) {
      visitor = new SourceAbiCompatibleVisitor(visitor);
    }
    visitor = new AbiFilteringClassVisitor(visitor);
    stub.accept(visitor);

    return new ByteArrayInputStream(writer.toByteArray());
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
}
