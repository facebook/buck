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

package com.facebook.buck.jvm.java.testutil;

import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

class ClassesImpl implements Classes {
  private final TemporaryFolder root;

  public ClassesImpl(TemporaryFolder root) {
    this.root = root;
  }

  @Override
  public void acceptClassVisitor(
      String qualifiedName,
      int flags,
      ClassVisitor cv) throws IOException {
    Path classFilePath = resolveClassFilePath(qualifiedName);

    try (InputStream stream = Files.newInputStream(classFilePath)) {
      ClassReader reader = new ClassReader(stream);

      reader.accept(cv, flags);
    }
  }

  private Path resolveClassFilePath(String qualifiedName) {
    return root.getRoot().toPath().resolve(
        qualifiedName.replace('.', File.separatorChar) + ".class");
  }
}
