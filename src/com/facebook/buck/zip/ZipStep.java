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

package com.facebook.buck.zip;

import static com.facebook.buck.zip.ZipOutputStreams.HandleDuplicates.OVERWRITE_EXISTING;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.zip.ZipEntry;

/**
 * A {@link com.facebook.buck.step.Step} that creates a ZIP archive..
 */
public class ZipStep implements Step {

  public static final int MIN_COMPRESSION_LEVEL = 0;
  public static final int DEFAULT_COMPRESSION_LEVEL = 6;
  public static final int MAX_COMPRESSION_LEVEL = 9;

  private final Path pathToZipFile;
  private final ImmutableSet<Path> paths;
  private final boolean junkPaths;
  private final int compressionLevel;
  private final Path baseDir;


  /**
   * Create a {@link ZipStep} to create or update a zip archive.
   *
   * Note that paths added to the archive are always relative to the working directory.<br/>
   * For example, if you're in {@code /dir} and you add {@code file.txt}, you get
   * an archive containing just the file. If you were in {@code /} and added
   * {@code dir/file.txt}, you would get an archive containing the file within a directory.
   *
   * @param pathToZipFile path to archive to create, relative to project root.
   * @param paths a set of files to work on. The entire working directory is assumed if this set
   *    is empty.
   * @param junkPaths if {@code true}, the relative paths of added archive entries are discarded,
   *    i.e. they are all placed in the root of the archive.
   * @param compressionLevel between 0 (store) and 9.
   * @param baseDir working directory for {@code zip} command.
   */
  public ZipStep(
      Path pathToZipFile,
      Set<Path> paths,
      boolean junkPaths,
      int compressionLevel,
      Path baseDir) {
    Preconditions.checkArgument(compressionLevel >= MIN_COMPRESSION_LEVEL &&
        compressionLevel <= MAX_COMPRESSION_LEVEL, "compressionLevel out of bounds.");
    this.pathToZipFile = pathToZipFile;
    this.paths = ImmutableSet.copyOf(paths);
    this.junkPaths = junkPaths;
    this.compressionLevel = compressionLevel;
    this.baseDir = baseDir;
  }

  @Override
  public int execute(ExecutionContext context) {
    final ProjectFilesystem filesystem = context.getProjectFilesystem();
    if (filesystem.exists(pathToZipFile)) {
      context.postEvent(
          ConsoleEvent.severe("Attempting to overwrite an existing zip: %s", pathToZipFile));
      return 1;
    }

    try (
      BufferedOutputStream baseOut =
          new BufferedOutputStream(filesystem.newFileOutputStream(pathToZipFile));
      final CustomZipOutputStream out =
          ZipOutputStreams.newOutputStream(baseOut, OVERWRITE_EXISTING)) {

      final FileVisitor<Path> pathFileVisitor = new SimpleFileVisitor<Path>() {
        private boolean isSkipFile(Path file) {
          if (!paths.isEmpty() && !paths.contains(file)) {
            return true;
          }
          return false;
        }

        private String getEntryName(Path path) {
          Path relativePath = junkPaths ? path.getFileName() : baseDir.relativize(path);
          return MorePaths.pathWithUnixSeparators(relativePath);
        }

        private CustomZipEntry getZipEntry(
            String entryName,
            Path path,
            BasicFileAttributes attr) throws IOException {
          boolean isDirectory = path.toFile().isDirectory();
          if (isDirectory) {
            entryName += "/";
          }

          CustomZipEntry entry = new CustomZipEntry(entryName);
          entry.setTime(attr.lastModifiedTime().toMillis());
          entry.setCompressionLevel(compressionLevel);
          // If we're using STORED files, we must manually set the CRC, size, and compressed size.
          if (entry.getMethod() == ZipEntry.STORED && !isDirectory) {
            entry.setSize(attr.size());
            entry.setCompressedSize(attr.size());
            entry.setCrc(
                com.google.common.io.Files.hash(
                    path.toFile(),
                    Hashing.crc32()).padToLong());
          }
          return entry;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            throws IOException {
          if (!isSkipFile(file)) {
            Path path = filesystem.resolve(file);
            out.putNextEntry(getZipEntry(getEntryName(file), path, attrs));
            Files.copy(path, out);
            out.closeEntry();
          }
          return FileVisitResult.CONTINUE;
        }
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            throws IOException {
          if (!dir.equals(baseDir) && !isSkipFile(dir)) {
            out.putNextEntry(getZipEntry(getEntryName(dir), filesystem.resolve(dir), attrs));
            out.closeEntry();
          }
          return FileVisitResult.CONTINUE;
        }
      };
      filesystem.walkRelativeFileTree(baseDir, pathFileVisitor);
    } catch (IOException e) {
      context.logError(e, "Error creating zip file %s", pathToZipFile);
      return 1;
    }

    return 0;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder args = new StringBuilder("zip ");

    // Don't add extra fields, neither do the Android tools.
    args.append("-X ");

    // recurse
    args.append("-r ");

    // compression level
    args.append("-").append(compressionLevel).append(" ");

    // unk paths
    if (junkPaths) {
      args.append("-j ");
    }

    // destination archive
    args.append(pathToZipFile.toString()).append(" ");

    // files to add to archive
    if (paths.isEmpty()) {
      // Add the contents of workingDirectory to archive.
      args.append("-i* ");
      args.append(". ");
    } else {
      // Add specified paths, relative to workingDirectory.
      for (Path path : paths) {
        args.append(path.toString()).append(" ");
      }
    }

    return args.toString();
  }

  @Override
  public String getShortName() {
    return "zip";
  }

}
