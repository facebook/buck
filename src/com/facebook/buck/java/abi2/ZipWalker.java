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

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

class ZipWalker implements Walker {
  private final Path zipFile;

  public ZipWalker(Path path) {
    this.zipFile = Preconditions.checkNotNull(path);
  }

  @Override
  public void walk(FileAction onFile) throws IOException {
    try (
        FileInputStream fis = new FileInputStream(zipFile.toFile());
        BufferedInputStream bis = new BufferedInputStream(fis);
        JarInputStream jis = new JarInputStream(bis)) {
      for (JarEntry entry = jis.getNextJarEntry(); entry != null; entry = jis.getNextJarEntry()) {
        if (entry.isDirectory()) {
          continue;
        }

        onFile.visit(Paths.get(entry.getName()), jis);
      }
    }
  }
}

