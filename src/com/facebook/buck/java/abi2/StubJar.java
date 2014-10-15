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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
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

    try (
        FileInputStream fis = new FileInputStream(toMirror.toFile());
        BufferedInputStream bis = new BufferedInputStream(fis);
        JarInputStream jis = new JarInputStream(bis)) {
      for (JarEntry entry = jis.getNextJarEntry(); entry != null; entry = jis.getNextJarEntry()) {
        if (!entry.getName().endsWith(".class")) {
          continue;
        }

        ClassReader classReader = new ClassReader(jis);
        ClassMirror visitor = new ClassMirror(entry.getName());
        classes.add(visitor);
        classReader.accept(visitor, ClassReader.SKIP_DEBUG);
      }
    }

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
