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

import static com.facebook.buck.util.unarchive.ExistingFileMode.OVERWRITE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Represents a zip that has been packaged as a resource with Buck, but which should be expanded at
 * most once during Buck's execution (not per-build).
 */
public class PackagedResource implements Supplier<Path> {

  private final ProjectFilesystem filesystem;
  private final String name;
  private final Class<?> relativeTo;
  private final Path filename;
  private final Supplier<Path> supplier;

  public PackagedResource(
      ProjectFilesystem filesystem, Class<?> relativeTo, String pathRelativeToClass) {
    this.filesystem = filesystem;
    this.relativeTo = relativeTo;

    // We could magically detect the class we're relative to by examining the stacktrace but that
    // would be incredibly fragile. So we won't.
    this.name = pathRelativeToClass;
    this.filename = Paths.get(pathRelativeToClass).getFileName();

    this.supplier = MoreSuppliers.memoize(this::unpack);
  }

  @Override
  public Path get() {
    return supplier.get();
  }

  /**
   * Use this as unique ID for resource when hashing is not enabled
   *
   * @return Class name followed by relative file path. E.g.
   *     com.facebook.buck.MyClass#some_resource_file.abc
   */
  public String getResourceIdentifier() {
    return relativeTo.getName() + "#" + name;
  }

  /**
   * Use this combined with file hash as unique ID when hashing is enabled.
   *
   * @return {@link Path} representing filename of packaged resource
   */
  public Path getFilenamePath() {
    return filename;
  }

  private Path unpack() {
    try (InputStream inner =
            Preconditions.checkNotNull(
                    Resources.getResource(relativeTo, name), "Unable to find: %s", name)
                .openStream();
        BufferedInputStream stream = new BufferedInputStream(inner)) {

      Path outputPath =
          filesystem
              .getBuckPaths()
              .getResDir()
              .resolve(relativeTo.getCanonicalName())
              .resolve(filename);

      // If the path already exists, delete it.
      if (filesystem.exists(outputPath)) {
        filesystem.deleteRecursivelyIfExists(outputPath);
      }

      String extension = com.google.common.io.Files.getFileExtension(filename.toString());
      if (extension.equals("zip")) {
        filesystem.mkdirs(outputPath);
        // Copy the zip to a temporary file, and mark that for deletion once the VM exits.
        Path zip = Files.createTempFile(filename.toString(), ".zip");
        // Ensure we tidy up
        Files.copy(stream, zip, REPLACE_EXISTING);
        ArchiveFormat.ZIP
            .getUnarchiver()
            .extractArchive(zip, filesystem, outputPath, Optional.empty(), OVERWRITE);
        Files.delete(zip);
      } else {
        filesystem.createParentDirs(outputPath);
        Path tempFilePath =
            filesystem.createTempFile(
                outputPath.getParent(), outputPath.getFileName().toString() + ".", ".tmp");
        try (OutputStream outputStream = filesystem.newFileOutputStream(tempFilePath)) {
          ByteStreams.copy(stream, outputStream);
        }
        filesystem.move(tempFilePath, outputPath);
      }
      return outputPath;
    } catch (IOException ioe) {
      throw new RuntimeException("Unable to unpack " + name, ioe);
    }
  }
}
