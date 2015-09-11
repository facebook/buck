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

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * A {@link Walker} which iterates over entries of a ZIP file in sorted (name) order.
 */
class ZipWalker implements Walker {
  private final Path zipFile;

  public ZipWalker(Path path) {
    this.zipFile = Preconditions.checkNotNull(path);
  }

  @Override
  public void walk(FileAction onFile) throws IOException {
    Set<String> names = Sets.newTreeSet();

    // Get the set of all names and sort them, so that we get a deterministic iteration order.
    try (
        FileInputStream fis = new FileInputStream(zipFile.toFile());
        BufferedInputStream bis = new BufferedInputStream(fis);
        ZipInputStream zis = new ZipInputStream(bis)) {
      for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
        if (entry.isDirectory()) {
          continue;
        }
        names.add(entry.getName());
      }
    }

    // Iterate over the file entries, calling the action on each one.
    if (!names.isEmpty()) {
      try (ZipFile zip = new ZipFile(zipFile.toFile())) {
        for (String name : names) {
          try (InputStream is = zip.getInputStream(zip.getEntry(name))) {
            onFile.visit(Paths.get(name), is);
          }
        }
      }
    }
  }
}

