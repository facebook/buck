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

package com.facebook.buck.util.zip;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

public class Zip {
  private static final Logger LOG = Logger.get(Zip.class);

  private Zip() {}

  /**
   * Takes a sequence of paths relative to the project root and writes a zip file to {@code out}
   * with the contents and structure that matches that of the specified paths.
   */
  public static void create(
      ProjectFilesystem projectFilesystem, Collection<Path> pathsToIncludeInZip, Path out)
      throws IOException {
    try (CustomZipOutputStream zip = ZipOutputStreams.newOutputStream(out)) {
      for (Path path : pathsToIncludeInZip) {

        boolean isDirectory = projectFilesystem.isDirectory(path);
        CustomZipEntry entry = new CustomZipEntry(path, isDirectory);

        // We want deterministic ZIPs, so avoid mtimes.
        entry.setFakeTime();

        entry.setExternalAttributes(projectFilesystem.getFileAttributesForZipEntry(path));

        zip.putNextEntry(entry);
        if (!isDirectory) {
          try (InputStream input = projectFilesystem.newFileInputStream(path)) {
            ByteStreams.copy(input, zip);
          }
        }
        zip.closeEntry();
      }
    }
  }

  /**
   * Walks the file tree rooted in {@code baseDirectory} to create zip entries
   *
   * @param filesystem {@link ProjectFilesystem} the filesystem based in the current working
   *     directory.
   * @param entries The map of zip entries {@link ZipEntry} to append to while walking the file
   *     directory.
   * @param baseDir The directory from which the file walker starts from.
   * @param paths The paths to include in {@code entries}.
   * @param junkPaths If true, then remove any directories from the base directory to the file. i.e.
   *     flattens the output.
   * @param compressionLevel Compression level for the files in the final zip.
   * @param checkRelativePathForSkip If true, then check if absolute path from {@paths}, or the
   *     parent directory matches the relative path from the base directory.
   */
  public static void walkBaseDirectoryToCreateEntries(
      ProjectFilesystem filesystem,
      ImmutableSortedMap.Builder<String, Pair<CustomZipEntry, Optional<Path>>> entries,
      Path baseDir,
      ImmutableSet<Path> paths,
      boolean junkPaths,
      ZipCompressionLevel compressionLevel,
      boolean checkRelativePathForSkip)
      throws IOException {

    Predicate<Path> skipCheck;
    if (checkRelativePathForSkip) {
      skipCheck =
          file -> {
            for (Path path : paths) {
              if (path.endsWith(file) || path.getParent().endsWith(file)) {
                return false;
              }
            }
            return true;
          };
    } else {
      skipCheck = file -> !paths.isEmpty() && !paths.contains(file);
    }

    // Since filesystem traversals can be non-deterministic, sort the entries we find into
    // a tree map before writing them out.
    FileVisitor<Path> pathFileVisitor =
        new SimpleFileVisitor<Path>() {
          private boolean isSkipFile(Path file) {
            return skipCheck.test(file);
          }

          private String getEntryName(Path path) {
            Path relativePath = junkPaths ? path.getFileName() : baseDir.relativize(path);
            return MorePaths.pathWithUnixSeparators(relativePath);
          }

          private CustomZipEntry getZipEntry(String entryName, Path path, BasicFileAttributes attr)
              throws IOException {
            boolean isDirectory = filesystem.isDirectory(path);
            if (isDirectory) {
              entryName += "/";
            }

            CustomZipEntry entry = new CustomZipEntry(entryName);
            // We want deterministic ZIPs, so avoid mtimes.
            entry.setFakeTime();
            entry.setCompressionLevel(
                isDirectory ? ZipCompressionLevel.NONE.getValue() : compressionLevel.getValue());
            // If we're using STORED files, we must manually set the CRC, size, and compressed size.
            if (entry.getMethod() == ZipEntry.STORED && !isDirectory) {
              entry.setSize(attr.size());
              entry.setCompressedSize(attr.size());
              entry.setCrc(
                  new ByteSource() {
                    @Override
                    public InputStream openStream() throws IOException {
                      return filesystem.newFileInputStream(path);
                    }
                  }.hash(Hashing.crc32()).padToLong());
            }

            long externalAttributes = filesystem.getFileAttributesForZipEntry(path);
            LOG.verbose(
                "Setting mode for entry %s path %s to 0x%08X", entryName, path, externalAttributes);
            entry.setExternalAttributes(externalAttributes);
            return entry;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (!isSkipFile(file)) {
              CustomZipEntry entry = getZipEntry(getEntryName(file), file, attrs);
              entries.put(entry.getName(), new Pair<>(entry, Optional.of(file)));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (!dir.equals(baseDir) && !isSkipFile(dir)) {
              CustomZipEntry entry = getZipEntry(getEntryName(dir), dir, attrs);
              entries.put(entry.getName(), new Pair<>(entry, Optional.empty()));
            }
            return FileVisitResult.CONTINUE;
          }
        };
    filesystem.walkRelativeFileTree(baseDir, pathFileVisitor);
  }

  /** Creates a {@link CustomZipEntry} from {@link ZipArchiveEntry} */
  public static CustomZipEntry getZipEntry(
      ZipFile zip, ZipArchiveEntry entry, ZipCompressionLevel compressionLevel) throws IOException {
    CustomZipEntry zipOutEntry = new CustomZipEntry(entry.getName());
    zipOutEntry.setFakeTime();
    zipOutEntry.setCompressionLevel(
        entry.isDirectory() ? ZipCompressionLevel.NONE.getValue() : compressionLevel.getValue());

    // If we're using STORED files, we must manually set the CRC, size, and compressed size.
    if (zipOutEntry.getMethod() == ZipEntry.STORED && !entry.isDirectory()) {
      zipOutEntry.setSize(entry.getSize());
      entry.setCompressedSize(entry.getCompressedSize());
      entry.setCrc(
          new ByteSource() {
            @Override
            public InputStream openStream() throws IOException {
              return zip.getInputStream(entry);
            }
          }.hash(Hashing.crc32()).padToLong());
    }
    return zipOutEntry;
  }

  /** Writes entries to {@code zipOut} stream */
  public static void writeEntriesToZip(
      ProjectFilesystem filesystem,
      CustomZipOutputStream zipOut,
      ImmutableSortedMap<String, Pair<CustomZipEntry, Optional<Path>>> entries)
      throws IOException {
    // Write the entries out using the iteration order of the tree map above.
    for (Pair<CustomZipEntry, Optional<Path>> entry : entries.values()) {
      zipOut.putNextEntry(entry.getFirst());
      if (entry.getSecond().isPresent()) {
        try (InputStream input = filesystem.newFileInputStream(entry.getSecond().get())) {
          ByteStreams.copy(input, zipOut);
        }
      }
      zipOut.closeEntry();
    }
  }
}
