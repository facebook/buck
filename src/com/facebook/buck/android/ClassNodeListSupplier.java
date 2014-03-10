/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * This Supplier returns a list of all the ClassNode objects in a set of jar files.
 */
class ClassNodeListSupplier implements Supplier<ImmutableList<ClassNode>> {

  private final Iterable<Path> jarPaths;

  private ClassNodeListSupplier(Iterable<Path> jarPaths) {
    this.jarPaths = jarPaths;
  }

  public static Supplier<ImmutableList<ClassNode>> createMemoized(Iterable<Path> jarPaths) {
    return Suppliers.memoize(new ClassNodeListSupplier(jarPaths));
  }

  @Override
  public ImmutableList<ClassNode> get() {
    return loadAllClassNodes();
  }

  private ImmutableList<ClassNode> loadAllClassNodes() {
    ImmutableList.Builder<ClassNode> builder = ImmutableList.builder();

    for (Path jarPath : jarPaths) {
      try (JarFile jarFile = new JarFile(jarPath.toFile())) {
        loadClassNodes(jarFile, builder);
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }

    return builder.build();
  }

  private void loadClassNodes(JarFile jarFile, ImmutableList.Builder<ClassNode> builder)
      throws IOException {
    for (JarEntry entry : Collections.list(jarFile.entries())) {
      String name = entry.getName();
      if (entry.isDirectory() || (name == null) || !name.endsWith(".class")) {
        continue;
      }

      ClassNode node = new ClassNode();
      try (InputStream stream = jarFile.getInputStream(entry)) {
        ClassReader reader = new ClassReader(stream);
        reader.accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
      }
      builder.add(node);
    }
  }
}
