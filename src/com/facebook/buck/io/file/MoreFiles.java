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

package com.facebook.buck.io.file;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.base.Functions;
import com.google.common.base.Objects;
import com.google.common.io.ByteStreams;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class MoreFiles {

  // Extended attribute bits for directories and symlinks; see:
  // http://unix.stackexchange.com/questions/14705/the-zip-formats-external-file-attribute
  @SuppressWarnings("PMD.AvoidUsingOctalValues")
  public static final long S_IFDIR = 0040000;

  @SuppressWarnings("PMD.AvoidUsingOctalValues")
  public static final long S_IFREG = 0100000;

  @SuppressWarnings("PMD.AvoidUsingOctalValues")
  public static final long S_IFLNK = 0120000;

  public enum DeleteRecursivelyOptions {
    IGNORE_NO_SUCH_FILE_EXCEPTION,
    DELETE_CONTENTS_ONLY,
  }

  // Unix has two illegal characters - '/', and '\0'.  Windows has ten, which includes those two.
  // The full list can be found at https://msdn.microsoft.com/en-us/library/aa365247
  private static final String ILLEGAL_FILE_NAME_CHARACTERS = "<>:\"/\\|?*\0";

  private static class FileAccessedEntry {
    public final File file;
    public final FileTime lastAccessTime;

    public File getFile() {
      return file;
    }

    public FileTime getLastAccessTime() {
      return lastAccessTime;
    }

    private FileAccessedEntry(File file, FileTime lastAccessTime) {
      this.file = file;
      this.lastAccessTime = lastAccessTime;
    }
  }

  /** Sorts by the lastAccessTime in descending order (more recently accessed files are first). */
  private static final Comparator<FileAccessedEntry> SORT_BY_LAST_ACCESSED_TIME_DESC =
      (a, b) -> b.getLastAccessTime().compareTo(a.getLastAccessTime());

  /** Utility class: do not instantiate. */
  private MoreFiles() {}

  public static void deleteRecursivelyIfExists(Path path) throws IOException {
    deleteRecursivelyWithOptions(
        path, EnumSet.of(DeleteRecursivelyOptions.IGNORE_NO_SUCH_FILE_EXCEPTION));
  }

  /** Recursively copies all files under {@code fromPath} to {@code toPath}. */
  public static void copyRecursively(final Path fromPath, final Path toPath) throws IOException {
    copyRecursively(fromPath, toPath, Functions.identity());
  }

  /** Recursively copies all files under {@code fromPath} to {@code toPath}. */
  public static void copyRecursivelyWithFilter(
      final Path fromPath, final Path toPath, final Function<Path, Boolean> filter)
      throws IOException {
    copyRecursively(fromPath, toPath, Functions.identity(), filter);
  }

  /**
   * Recursively copies all files under {@code fromPath} to {@code toPath}. The {@code transform}
   * will be applied after the destination path for a file has been relativized.
   *
   * @param fromPath item to copy
   * @param toPath destination of copy
   * @param transform renaming function to apply when copying. If this function returns null, then
   *     the file is not copied.
   */
  public static void copyRecursively(
      final Path fromPath, final Path toPath, final Function<Path, Path> transform)
      throws IOException {
    copyRecursively(fromPath, toPath, transform, input -> true);
  }

  public static void copyRecursively(
      final Path fromPath,
      final Path toPath,
      final Function<Path, Path> transform,
      final Function<Path, Boolean> filter)
      throws IOException {
    // Adapted from http://codingjunkie.net/java-7-copy-move/.
    SimpleFileVisitor<Path> copyDirVisitor =
        new SimpleFileVisitor<Path>() {

          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Path targetPath = toPath.resolve(fromPath.relativize(dir));
            if (!Files.exists(targetPath)) {
              Files.createDirectory(targetPath);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (!filter.apply(file)) {
              return FileVisitResult.CONTINUE;
            }
            Path destPath = toPath.resolve(fromPath.relativize(file));
            Path transformedDestPath = transform.apply(destPath);
            if (transformedDestPath != null) {
              if (Files.isSymbolicLink(file)) {
                Files.deleteIfExists(transformedDestPath);
                Files.createSymbolicLink(transformedDestPath, Files.readSymbolicLink(file));
              } else {
                Files.copy(file, transformedDestPath, StandardCopyOption.REPLACE_EXISTING);
              }
            }
            return FileVisitResult.CONTINUE;
          }
        };
    Files.walkFileTree(fromPath, copyDirVisitor);
  }

  public static void deleteRecursively(final Path path) throws IOException {
    deleteRecursivelyWithOptions(path, EnumSet.noneOf(DeleteRecursivelyOptions.class));
  }

  public static void deleteRecursivelyWithOptions(
      final Path path, final EnumSet<DeleteRecursivelyOptions> options) throws IOException {
    try {
      // Adapted from http://codingjunkie.net/java-7-copy-move/.
      SimpleFileVisitor<Path> deleteDirVisitor =
          new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
              if (e == null) {
                // Allow leaving the top-level directory in place (e.g. for deleting the contents of
                // the trash dir but not the trash dir itself)
                if (!(options.contains(DeleteRecursivelyOptions.DELETE_CONTENTS_ONLY)
                    && dir.equals(path))) {
                  try {
                    Files.delete(dir);
                  } catch (DirectoryNotEmptyException notEmpty) {
                    throw new IOException(
                        String.format(
                            "Could not delete non-empty directory %s. Contents:\n%s",
                            dir,
                            Files.list(dir).map(Path::toString).collect(Collectors.joining("\n"))),
                        notEmpty);
                  }
                }
                return FileVisitResult.CONTINUE;
              } else {
                throw e;
              }
            }
          };
      Files.walkFileTree(path, deleteDirVisitor);
    } catch (NoSuchFileException e) {
      if (!options.contains(DeleteRecursivelyOptions.IGNORE_NO_SUCH_FILE_EXCEPTION)) {
        throw e;
      }
    }
  }

  /** Writes the specified lines to the specified file, encoded as UTF-8. */
  public static void writeLinesToFile(Iterable<String> lines, Path file) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(file, Charsets.UTF_8)) {
      for (String line : lines) {
        writer.write(line);
        writer.newLine();
      }
    }
  }

  /** Log a simplistic diff between lines and the contents of file. */
  @VisibleForTesting
  static List<String> diffFileContents(Iterable<String> lines, File file) throws IOException {
    List<String> diffLines = new ArrayList<>();
    Iterator<String> iter = lines.iterator();
    try (BufferedReader reader = Files.newBufferedReader(file.toPath(), Charsets.UTF_8)) {
      while (iter.hasNext()) {
        String lineA = reader.readLine();
        String lineB = iter.next();
        if (!Objects.equal(lineA, lineB)) {
          diffLines.add(String.format("| %s | %s |", lineA == null ? "" : lineA, lineB));
        }
      }

      String lineA;
      while ((lineA = reader.readLine()) != null) {
        diffLines.add(String.format("| %s |  |", lineA));
      }
    }
    return diffLines;
  }

  /**
   * Does an in-place sort of the specified {@code files} array. Most recently accessed files will
   * be at the front of the array when sorted.
   */
  public static void sortFilesByAccessTime(File[] files) {
    FileAccessedEntry[] fileAccessedEntries = new FileAccessedEntry[files.length];
    for (int i = 0; i < files.length; ++i) {
      FileTime lastAccess;
      try {
        lastAccess =
            Files.readAttributes(files[i].toPath(), BasicFileAttributes.class).lastAccessTime();
      } catch (IOException e) {
        lastAccess = FileTime.fromMillis(files[i].lastModified());
      }
      fileAccessedEntries[i] = new FileAccessedEntry(files[i], lastAccess);
    }
    Arrays.sort(fileAccessedEntries, SORT_BY_LAST_ACCESSED_TIME_DESC);

    for (int i = 0; i < files.length; i++) {
      files[i] = fileAccessedEntries[i].getFile();
    }
  }

  /**
   * Tries to make the specified file executable. For file systems that do support the POSIX-style
   * permissions, the executable permission is set for each category of users that already has the
   * read permission.
   *
   * <p>If the file system does not support the executable permission or the operation fails, a
   * {@code java.io.IOException} is thrown.
   */
  public static void makeExecutable(Path file) throws IOException {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);

      if (permissions.contains(PosixFilePermission.OWNER_READ)) {
        permissions.add(PosixFilePermission.OWNER_EXECUTE);
      }
      if (permissions.contains(PosixFilePermission.GROUP_READ)) {
        permissions.add(PosixFilePermission.GROUP_EXECUTE);
      }
      if (permissions.contains(PosixFilePermission.OTHERS_READ)) {
        permissions.add(PosixFilePermission.OTHERS_EXECUTE);
      }

      Files.setPosixFilePermissions(file, permissions);
    } else {
      if (!file.toFile().setExecutable(/* executable */ true, /* ownerOnly */ true)) {
        throw new IOException("The file could not be made executable");
      }
    }
  }

  /**
   * Given a file name, replace any illegal characters from it.
   *
   * @param name The file name to sanitize
   * @return a properly sanitized filename
   */
  public static String sanitize(String name) {
    return CharMatcher.anyOf(ILLEGAL_FILE_NAME_CHARACTERS).replaceFrom(name, "_");
  }

  /**
   * Concatenates the contents of one or more files.
   *
   * @param dest The path to which the concatenated files' contents are written.
   * @param pathsToConcatenate The paths whose contents are concatenated to {@code dest}.
   * @return {@code true} if any data was concatenated to {@code dest}, {@code false} otherwise.
   */
  public static boolean concatenateFiles(Path dest, Iterable<Path> pathsToConcatenate)
      throws IOException {
    // Concatenate all the logs to a temp file, then atomically rename it to the
    // passed-in concatenatedPath if any log data was collected.
    String tempFilename = "." + dest.getFileName() + ".tmp." + UUID.randomUUID().toString();
    Path tempPath = dest.resolveSibling(tempFilename);

    try {
      long bytesCollected = 0;
      try (OutputStream os =
          Files.newOutputStream(
              tempPath,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE)) {
        for (Path path : pathsToConcatenate) {
          try (InputStream is = Files.newInputStream(path)) {
            bytesCollected += ByteStreams.copy(is, os);
          } catch (NoSuchFileException e) {
            continue;
          }
        }
      }
      if (bytesCollected > 0) {
        Files.move(
            tempPath, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return true;
      } else {
        return false;
      }
    } finally {
      Files.deleteIfExists(tempPath);
    }
  }
}
