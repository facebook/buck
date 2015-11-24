/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util;

import static com.facebook.buck.zip.Unzip.ExistingFileMode.OVERWRITE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.zip.Unzip;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Represents a zip that has been packaged as a resource with Buck, but which should be expanded at
 * most once during Buck's execution (not per-build).
 */
public class PackagedResource implements Supplier<Path> {

  static {
    Runtime.getRuntime().addShutdownHook(new Thread(new Cleaner(), "packaged-resource-cleanup"));
  }
  private final String name;
  private final Class<?> relativeTo;
  private final Path filename;
  private final Supplier<Path> supplier;


  public PackagedResource(
      Class<?> relativeTo, String pathRelativeToClass) {
    this.relativeTo = relativeTo;
    // We could magically detect the class we're relative to by examining the stacktrace but that
    // would be incredibly fragile. So we won't.

    this.name = pathRelativeToClass;

    this.filename = Paths.get(pathRelativeToClass).getFileName();

    this.supplier = Suppliers.memoize(
        new Supplier<Path>() {
          @Override
          public Path get() {
            return unpack();
          }
        });
  }

  @Override
  public Path get() {
    return supplier.get();
  }

  /**
   * Use this as unique ID for resource when hashing is not enabled
   * @return Class name followed by relative file path.
   * E.g. com.facebook.buck.MyClass#some_resource_file.abc
   */
  public String getResourceIdentifier() {
    return relativeTo.getName() + "#" + name;
  }

  /**
   * Use this combined with file hash as unique ID when hashing is enabled.
   * @return {@link Path} representing filename of packaged resource
   */
  public Path getFilenamePath() {
    return filename;
  }

  private Path unpack() {
    try (
        InputStream inner = Preconditions.checkNotNull(
            Resources.getResource(relativeTo, name),
            "Unable to find: %s", name).openStream();
        BufferedInputStream stream = new BufferedInputStream(inner)) {

      Path outputDir = Files.createTempDirectory("buck-resource");
      Cleaner.addRoot(outputDir);

      String extension = com.google.common.io.Files.getFileExtension(filename.toString());
      if (extension.equals("zip")) {
        // Copy the zip to a temporary file, and mark that for deletion once the VM exits.
        Path zip = Files.createTempFile(filename.toString(), ".zip");
        // Ensure we tidy up
        Cleaner.addRoot(zip);
        Files.copy(stream, zip, REPLACE_EXISTING);
        Unzip.extractZipFile(zip, outputDir, OVERWRITE);
        return outputDir;
      } else {
        Path tempFilePath = Files.createFile(outputDir.resolve(filename));
        Cleaner.addRoot(tempFilePath);
        Files.copy(stream, tempFilePath, REPLACE_EXISTING);
        return tempFilePath;
      }
    } catch (IOException ioe) {
      throw new RuntimeException("Unable to unpack " + name, ioe);
    }
  }

  private static class Cleaner implements Runnable {
    private static final Set<Path> ROOTS_TO_DELETE = Sets.newConcurrentHashSet();

    static void addRoot(Path root) {
      ROOTS_TO_DELETE.add(root);
    }

    @Override
    @SuppressWarnings("PMD.EmptyCatchBlock")
    public void run() {
      for (Path root : ROOTS_TO_DELETE) {
        try {
          MoreFiles.deleteRecursively(root);
        } catch (IOException e) {
          // Ignore and carry on. The JVM is shutting down and we wrote files to a temp dir, which
          // should get cleaned up at some point anyway.
        }
      }
    }
  }
}
