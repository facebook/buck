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

package com.facebook.buck.java.abi2;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

import org.objectweb.asm.ClassReader;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.jar.JarOutputStream;

public class StubJar {

  private final Path toMirror;
  private final SortedSet<ClassMirror> classes;

  public StubJar(Path toMirror) {
    this.toMirror = Preconditions.checkNotNull(toMirror);
    this.classes = Sets.newTreeSet();
  }

  public void writeTo(Path path) throws IOException {
    Preconditions.checkState(!Files.exists(path), "Output file already exists: %s)", path);

    if (!Files.exists(path.getParent())) {
      Files.createDirectories(path.getParent());
    }

    Walker walker = Walkers.getWalkerFor(toMirror);
    walker.walk(
        new FileAction() {
          @Override
          public void visit(Path relativizedPath, InputStream stream) throws IOException {
            String fileName = relativizedPath.toString();
            if (!fileName.endsWith(".class")) {
              return;
            }

            ClassReader classReader = new ClassReader(stream);
            ClassMirror visitor = new ClassMirror(fileName);
            classes.add(visitor);
            classReader.accept(visitor, ClassReader.SKIP_DEBUG);
          }
        });

    try (
        FileOutputStream fos = new FileOutputStream(path.toFile());
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        JarOutputStream jar = new JarOutputStream(bos)) {
      for (ClassMirror aClass : classes) {
        aClass.writeTo(jar);
      }
    }
  }
}
