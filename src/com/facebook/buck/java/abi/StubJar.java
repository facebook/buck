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

package com.facebook.buck.java.abi;

import static org.objectweb.asm.ClassReader.SKIP_CODE;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassReader.SKIP_FRAMES;

import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.base.Preconditions;

import org.objectweb.asm.ClassReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;

public class StubJar {

  private final Path toMirror;

  public StubJar(Path toMirror) {
    this.toMirror = Preconditions.checkNotNull(toMirror);
  }

  public void writeTo(ProjectFilesystem filesystem, Path path) throws IOException {
    Preconditions.checkState(!filesystem.exists(path), "Output file already exists: %s)", path);

    if (path.getParent() != null && !filesystem.exists(path.getParent())) {
      filesystem.createParentDirs(path);
    }

    Walker walker = Walkers.getWalkerFor(toMirror);
    try (
        OutputStream fos = filesystem.newFileOutputStream(path);
        JarOutputStream jar = new JarOutputStream(fos)) {
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
              classReader.accept(visitor, SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES);
              visitor.writeTo(jar);
            }
          });
    }
  }
}
