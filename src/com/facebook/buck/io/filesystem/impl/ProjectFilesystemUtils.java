/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.io.filesystem.impl;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.file.MorePosixFilePermissions;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.file.PathListing;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.UnmodifiableIterator;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import javax.annotation.Nullable;

/**
 * A stateless filesystem utils that helps to interact with the filesystem relative to the project
 * root.
 */
public class ProjectFilesystemUtils {

  public static final String EDEN_MAGIC_PATH_ELEMENT = ".eden";

  private ProjectFilesystemUtils() {}

  /**
   * Gets a list of paths of the contents of the given directory, obeying the ignores. All paths are
   * relative to the root of this view.
   */
  public static ImmutableCollection<Path> getDirectoryContents(
      AbsPath root, ImmutableCollection<PathMatcher> ignores, Path pathRelativeToProjectRoot)
      throws IOException {
    Path path = getPathForRelativePath(root, pathRelativeToProjectRoot);
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
      return FluentIterable.from(stream)
          .filter(input -> !isIgnored(relativize(root, input), ignores))
          .transform(absolutePath -> MorePaths.relativize(root.getPath(), absolutePath))
          .toSortedList(Comparator.naturalOrder());
    }
  }

  /** Get path that is an absolute path resolved against the root path. */
  public static Path getPathForRelativePath(AbsPath root, Path pathRelativeToProjectRoot) {
    return getAbsPathForRelativePath(root, pathRelativeToProjectRoot).getPath();
  }

  /** Get path that is an absolute path resolved against the root path. */
  public static Path getPathForRelativePath(AbsPath root, RelPath pathRelativeToProjectRoot) {
    return getPathForRelativePath(root, pathRelativeToProjectRoot.getPath());
  }

  /** Get absolute path that is resolved against the root path. */
  public static AbsPath getAbsPathForRelativePath(AbsPath root, Path pathRelativeToProjectRoot) {
    AbsPath result;
    // We often create {@link Path} instances using
    // {@link java.nio.file.Paths#get(String, String...)}, but there's no guarantee that the
    // underlying {@link FileSystem} is the default one.
    if (pathRelativeToProjectRoot.getFileSystem().equals(root.getFileSystem())) {
      result = root.resolve(pathRelativeToProjectRoot);
    } else {
      result = root.resolve(pathRelativeToProjectRoot.toString());
    }

    return MorePaths.normalize(result);
  }

  /** Get absolute path that is resolved against the root path. */
  public static AbsPath getAbsPathForRelativePath(AbsPath root, RelPath pathRelativeToProjectRoot) {
    return getAbsPathForRelativePath(root, pathRelativeToProjectRoot.getPath());
  }

  /** Construct a relative path between the project root and a given path. */
  public static RelPath relativize(AbsPath root, Path path) {
    return root.relativize(path);
  }

  /** Construct a relative path between the project root and a given path. */
  public static RelPath relativize(AbsPath root, AbsPath path) {
    return root.relativize(path);
  }

  /**
   * @param path the path to check.
   * @return whether ignored paths contains path or any of its ancestors.
   */
  public static boolean isIgnored(
      RelPath path, ImmutableCollection<? extends PathMatcher> ignores) {
    return ignores.stream().anyMatch(pathMatcher -> pathMatcher.matches(path.getPath()));
  }

  /**
   * @return the absolute path of {@code pathRelativeToProjectRoot} resolved against {@code root}.
   */
  public static AbsPath getPathForRelativePath(AbsPath root, String pathRelativeToProjectRoot) {
    return MorePaths.normalize(root.resolve(pathRelativeToProjectRoot));
  }

  /** Tests whether a file exists. */
  public static boolean exists(
      AbsPath root, Path pathRelativeToProjectRoot, LinkOption... options) {
    return Files.exists(getPathForRelativePath(root, pathRelativeToProjectRoot), options);
  }

  /** Returns file size. */
  public static long getFileSize(AbsPath root, Path pathRelativeToProjectRoot) throws IOException {
    Path path = getPathForRelativePath(root, pathRelativeToProjectRoot);
    if (!Files.isRegularFile(path)) {
      throw new IOException("Cannot get size of " + path + " because it is not an ordinary file.");
    }
    return Files.size(path);
  }

  /** Returns the posix file mode of a file */
  public static long getPosixFileModes(AbsPath root, Path pathRelativeToProjectRoot)
      throws IOException {
    long mode = 0;
    // Support executable files.  If we detect this file is executable, store this
    // information as 0100 in the field typically used in zip implementations for
    // POSIX file permissions.  We'll use this information when unzipping.
    Path path = getPathForRelativePath(root, pathRelativeToProjectRoot);
    if (isExecutable(root, path)) {
      mode |= MorePosixFilePermissions.toMode(EnumSet.of(PosixFilePermission.OWNER_EXECUTE));
    }

    if (isDirectory(root, path)) {
      mode |= MostFiles.S_IFDIR;
    } else if (isFile(root, path)) {
      mode |= MostFiles.S_IFREG;
    }

    // Propagate any additional permissions
    mode |= MorePosixFilePermissions.toMode(getPosixFilePermissions(root, path));

    return mode;
  }

  public static Path getDefaultEdenMagicPathElement(AbsPath rootPath) {
    return getPath(rootPath, EDEN_MAGIC_PATH_ELEMENT);
  }

  /**
   * Deletes a file specified by its path relative to the {@code root}.
   *
   * <p>Ignores the failure if the file does not exist.
   *
   * @param pathRelativeToProjectRoot path to the file
   * @return {@code true} if the file was deleted, {@code false} if it did not exist
   */
  public static boolean deleteFileAtPathIfExists(AbsPath root, Path pathRelativeToProjectRoot)
      throws IOException {
    return Files.deleteIfExists(getPathForRelativePath(root, pathRelativeToProjectRoot));
  }

  /**
   * Deletes a file specified by its path relative to the project root.
   *
   * @param pathRelativeToProjectRoot path to the file
   */
  public static void deleteFileAtPath(AbsPath root, Path pathRelativeToProjectRoot)
      throws IOException {
    Files.delete(getPathForRelativePath(root, pathRelativeToProjectRoot));
  }

  /** Reads properties file. */
  public static Properties readPropertiesFile(AbsPath root, Path propertiesFile)
      throws IOException {
    Properties properties = new Properties();
    if (exists(root, propertiesFile)) {
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(
                  newFileInputStream(root, propertiesFile), StandardCharsets.UTF_8))) {
        properties.load(reader);
      }
      return properties;
    } else {
      throw new FileNotFoundException(propertiesFile.toString());
    }
  }

  /** Opens a file, returning an input stream to read from the file. */
  public static InputStream newFileInputStream(AbsPath root, Path pathRelativeToProjectRoot)
      throws IOException {
    return new BufferedInputStream(
        Files.newInputStream(getPathForRelativePath(root, pathRelativeToProjectRoot)));
  }

  /** Checks whether there is a normal file at the specified path. */
  public static boolean isFile(
      AbsPath root, Path pathRelativeToProjectRoot, LinkOption... options) {
    return Files.isRegularFile(getPathForRelativePath(root, pathRelativeToProjectRoot), options);
  }

  /** Tests whether a file is hidden. */
  public static boolean isHidden(AbsPath root, Path pathRelativeToProjectRoot) throws IOException {
    return Files.isHidden(getPathForRelativePath(root, pathRelativeToProjectRoot));
  }

  /** Allows {@link Files#isDirectory} to be faked in tests. */
  public static boolean isDirectory(
      AbsPath root, Path pathRelativeToProjectRoot, LinkOption... linkOptions) {
    return MorePaths.isDirectory(
        getPathForRelativePath(root, pathRelativeToProjectRoot), linkOptions);
  }

  /** Allows {@link Files#isExecutable} to be faked in tests. */
  public static boolean isExecutable(AbsPath root, Path pathRelativeToProjectRoot) {
    return Files.isExecutable(getPathForRelativePath(root, pathRelativeToProjectRoot));
  }

  /** Returns a file's last modified time. */
  public static FileTime getLastModifiedTime(AbsPath root, Path pathRelativeToProjectRoot)
      throws IOException {
    Path path = getPathForRelativePath(root, pathRelativeToProjectRoot);
    return Files.getLastModifiedTime(path);
  }

  /**
   * Returns the files inside {@code pathRelativeToProjectRoot} which match {@code globPattern},
   * ordered in descending last modified time order.
   */
  public static ImmutableSortedSet<Path> getMtimeSortedMatchingDirectoryContents(
      AbsPath root, Path pathRelativeToProjectRoot, String globPattern) throws IOException {
    Path pathToGlob = getPathForRelativePath(root, pathRelativeToProjectRoot);
    return PathListing.listMatchingPaths(
        pathToGlob, globPattern, path -> getLastModifiedTime(root, path));
  }

  /** Sets the last modified time for the given path. */
  public static Path setLastModifiedTime(
      AbsPath root, Path pathRelativeToProjectRoot, FileTime time) throws IOException {
    Path path = getPathForRelativePath(root, pathRelativeToProjectRoot);
    return Files.setLastModifiedTime(path, time);
  }

  /**
   * Recursively delete everything under the specified path. Ignore the failure if the file at the
   * specified path does not exist.
   */
  public static void deleteRecursivelyIfExists(AbsPath root, Path pathRelativeToProjectRoot)
      throws IOException {
    MostFiles.deleteRecursivelyIfExists(getPathForRelativePath(root, pathRelativeToProjectRoot));
  }

  /**
   * @deprecated Prefer operating on {@code Path}s directly, replaced by {@link
   *     #createParentDirs(AbsPath, Path)}.
   */
  @Deprecated
  public static void createParentDirs(AbsPath root, String pathRelativeToProjectRoot)
      throws IOException {
    AbsPath path = getPathForRelativePath(root, pathRelativeToProjectRoot);
    mkdirs(root, path.getParent().getPath());
  }

  /**
   * @param pathRelativeToProjectRoot Must identify a file, not a directory. (Unfortunately, we have
   *     no way to assert this because the path is not expected to exist yet.)
   */
  public static void createParentDirs(AbsPath root, Path pathRelativeToProjectRoot)
      throws IOException {
    Path path = getPathForRelativePath(root, pathRelativeToProjectRoot);
    mkdirs(root, path.getParent());
  }

  /**
   * Resolves the relative path against the project root and then calls {@link
   * Files#createDirectories(Path, FileAttribute[])}
   */
  public static void mkdirs(AbsPath root, Path pathRelativeToProjectRoot) throws IOException {
    Path resolved = getPathForRelativePath(root, pathRelativeToProjectRoot);
    try {
      Files.createDirectories(resolved);
    } catch (FileAlreadyExistsException e) {
      // Don't complain if the file is a symlink that points to a valid directory.
      // This check is done only on exception as it's a rare case, and lstat is not free.
      if (!Files.isDirectory(resolved)) {
        throw e;
      }
    }
  }

  /** Creates a new file relative to the project root. */
  public static Path createNewFile(AbsPath root, Path pathRelativeToProjectRoot)
      throws IOException {
    Path path = getPathForRelativePath(root, pathRelativeToProjectRoot);
    return Files.createFile(path);
  }

  /**
   * Writes each line in {@code lines} with a trailing newline to a file at the specified path.
   *
   * <p>The parent path of {@code pathRelativeToProjectRoot} must exist.
   */
  public static void writeLinesToPath(
      AbsPath root,
      Iterable<String> lines,
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs)
      throws IOException {
    try (Writer writer =
        new BufferedWriter(
            new OutputStreamWriter(
                newFileOutputStream(root, pathRelativeToProjectRoot, attrs),
                StandardCharsets.UTF_8))) {
      for (String line : lines) {
        writer.write(line);
        writer.write('\n');
      }
    }
  }

  /** Returns a new buffered file output stream */
  public static OutputStream newFileOutputStream(
      AbsPath root, Path pathRelativeToProjectRoot, FileAttribute<?>... attrs) throws IOException {
    return newFileOutputStream(root, pathRelativeToProjectRoot, /* append */ false, attrs);
  }

  /** Returns a new buffered file output stream */
  public static OutputStream newFileOutputStream(
      AbsPath root, Path pathRelativeToProjectRoot, boolean append, FileAttribute<?>... attrs)
      throws IOException {
    return new BufferedOutputStream(
        newUnbufferedFileOutputStream(root, pathRelativeToProjectRoot, append, attrs));
  }

  /** Returns a new uUnbuffered file output stream */
  public static OutputStream newUnbufferedFileOutputStream(
      AbsPath root, Path pathRelativeToProjectRoot, boolean append, FileAttribute<?>... attrs)
      throws IOException {
    return Channels.newOutputStream(
        Files.newByteChannel(
            getPathForRelativePath(root, pathRelativeToProjectRoot),
            append
                ? ImmutableSet.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                : ImmutableSet.of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE),
            attrs));
  }

  /** Writes contents to a path. */
  public static void writeContentsToPath(
      AbsPath root, String contents, Path pathRelativeToProjectRoot, FileAttribute<?>... attrs)
      throws IOException {
    writeBytesToPath(
        root, contents.getBytes(StandardCharsets.UTF_8), pathRelativeToProjectRoot, attrs);
  }

  /** Writes bytes to a path. */
  public static void writeBytesToPath(
      AbsPath root, byte[] bytes, Path pathRelativeToProjectRoot, FileAttribute<?>... attrs)
      throws IOException {
    // No need to buffer writes when writing a single piece of data.
    try (OutputStream outputStream =
        newUnbufferedFileOutputStream(root, pathRelativeToProjectRoot, /* append */ false, attrs)) {
      outputStream.write(bytes);
    }
  }

  /** Reads a file's attributes as a bulk operation. */
  public static <A extends BasicFileAttributes> A readAttributes(
      AbsPath root, Path pathRelativeToProjectRoot, Class<A> type, LinkOption... options)
      throws IOException {
    return Files.readAttributes(
        getPathForRelativePath(root, pathRelativeToProjectRoot), type, options);
  }

  /** @param inputStream Source of the bytes. This method does not close this stream. */
  public static void copyToPath(
      AbsPath root, InputStream inputStream, Path pathRelativeToProjectRoot, CopyOption... options)
      throws IOException {
    Files.copy(inputStream, getPathForRelativePath(root, pathRelativeToProjectRoot), options);
  }

  /** Copies a file to an output stream. */
  public static void copyToOutputStream(
      AbsPath root, Path pathRelativeToProjectRoot, OutputStream out) throws IOException {
    Files.copy(getPathForRelativePath(root, pathRelativeToProjectRoot), out);
  }

  /** Reads a file content if file exists. */
  public static Optional<String> readFileIfItExists(AbsPath root, Path pathRelativeToProjectRoot) {
    Path fileToRead = getPathForRelativePath(root, pathRelativeToProjectRoot);
    return readFileIfItExists(fileToRead, pathRelativeToProjectRoot.toString());
  }

  /** Reads a file content if file exists. */
  private static Optional<String> readFileIfItExists(
      Path fileToRead, String pathRelativeToProjectRoot) {
    if (Files.isRegularFile(fileToRead)) {
      String contents;
      try {
        contents = new String(Files.readAllBytes(fileToRead), StandardCharsets.UTF_8);
      } catch (IOException e) {
        // Alternatively, we could return Optional.empty(), though something seems suspicious if we
        // have already verified that fileToRead is a file and then we cannot read it.
        throw new RuntimeException("Error reading " + pathRelativeToProjectRoot, e);
      }
      return Optional.of(contents);
    } else {
      return Optional.empty();
    }
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#empty()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   *
   * <p>@deprecated PRefero operation on {@code Path}s directly, replaced by {@link
   * #readFirstLine(AbsPath, Path)}
   */
  public static Optional<String> readFirstLine(AbsPath root, String pathRelativeToProjectRoot) {
    return readFirstLine(root, getPath(root, pathRelativeToProjectRoot));
  }

  /**
   * Converts a path string (or sequence of strings) to a Path with the same VFS as this instance.
   */
  public static Path getPath(AbsPath root, String first, String... rest) {
    return root.getFileSystem().getPath(first, rest);
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#empty()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   */
  public static Optional<String> readFirstLine(AbsPath root, Path pathRelativeToProjectRoot) {
    Path file = getPathForRelativePath(root, pathRelativeToProjectRoot);
    return readFirstLineFromFile(file);
  }

  /**
   * Attempts to read the first line of the specified file. If the file does not exist, is empty, or
   * encounters an error while being read, {@link Optional#empty()} is returned. Otherwise, an
   * {@link Optional} with the first line of the file will be returned.
   */
  public static Optional<String> readFirstLineFromFile(Path file) {
    try {
      try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        return Optional.ofNullable(reader.readLine());
      }
    } catch (IOException e) {
      // Because the file is not even guaranteed to exist, swallow the IOException.
      return Optional.empty();
    }
  }

  /** Reads a file content as list of lines. */
  public static List<String> readLines(AbsPath root, Path pathRelativeToProjectRoot)
      throws IOException {
    Path file = getPathForRelativePath(root, pathRelativeToProjectRoot);
    return Files.readAllLines(file, StandardCharsets.UTF_8);
  }

  /**
   * @deprecated Prefer operation on {@code Path}s directly, replaced by {@link
   *     Files#newInputStream(Path, java.nio.file.OpenOption...)}.
   */
  @Deprecated
  public static InputStream getInputStreamForRelativePath(
      AbsPath root, Path pathRelativeToProjectRoot) throws IOException {
    Path file = getPathForRelativePath(root, pathRelativeToProjectRoot);
    return Files.newInputStream(file);
  }

  /** Copies a file. */
  public static void copy(AbsPath root, Path source, Path target, CopySourceMode sourceMode)
      throws IOException {
    switch (sourceMode) {
      case FILE:
        Files.copy(
            getPathForRelativePath(root, source),
            getPathForRelativePath(root, target),
            StandardCopyOption.REPLACE_EXISTING);
        break;
      case DIRECTORY_CONTENTS_ONLY:
        MostFiles.copyRecursively(
            getPathForRelativePath(root, source), getPathForRelativePath(root, target));
        break;
      case DIRECTORY_AND_CONTENTS:
        MostFiles.copyRecursively(
            getPathForRelativePath(root, source),
            getPathForRelativePath(root, target.resolve(source.getFileName())));
        break;
    }
  }

  /** Moves a file. */
  public static void move(AbsPath root, Path source, Path target, CopyOption... options)
      throws IOException {
    Files.move(getPathForRelativePath(root, source), getPathForRelativePath(root, target), options);
  }

  /** Copies a folder. */
  public static void copyFolder(AbsPath root, Path source, Path target) throws IOException {
    copy(root, source, target, CopySourceMode.DIRECTORY_CONTENTS_ONLY);
  }

  /** Copies a file. */
  public static void copyFile(AbsPath root, Path source, Path target) throws IOException {
    copy(root, source, target, CopySourceMode.FILE);
  }

  /** Creates a symlink. */
  public static void createSymLink(AbsPath root, Path symLink, Path realFile, boolean force)
      throws IOException {
    symLink = getPathForRelativePath(root, symLink);
    if (force) {
      MostFiles.deleteRecursivelyIfExists(symLink);
    }
    MorePaths.createSymLink(MorePaths.WIN_FS_INSTANCE, symLink, realFile);
  }

  /**
   * Returns the set of POSIX file permissions, or the empty set if the underlying file system does
   * not support POSIX file attributes.
   */
  public static Set<PosixFilePermission> getPosixFilePermissions(
      AbsPath root, Path pathRelativeToProjectRoot) throws IOException {
    Path resolvedPath = getPathForRelativePath(root, pathRelativeToProjectRoot);
    if (Files.getFileAttributeView(resolvedPath, PosixFileAttributeView.class) != null) {
      return Files.getPosixFilePermissions(resolvedPath);
    } else {
      return ImmutableSet.of();
    }
  }

  /** Returns true if the file under {@code path} exists and is a symbolic link, false otherwise. */
  public static boolean isSymLink(AbsPath root, Path pathRelativeToProjectRoot) {
    return Files.isSymbolicLink(getPathForRelativePath(root, pathRelativeToProjectRoot));
  }

  /** Returns the target of the specified symbolic link. */
  public static Path readSymLink(AbsPath root, Path pathRelativeToProjectRoot) throws IOException {
    return Files.readSymbolicLink(getPathForRelativePath(root, pathRelativeToProjectRoot));
  }

  /** Returns Jar Manifest. */
  public static Manifest getJarManifest(AbsPath root, Path pathRelativeToProjectRoot)
      throws IOException {
    Path absolutePath = getPathForRelativePath(root, pathRelativeToProjectRoot);
    try (JarFile jarFile = new JarFile(absolutePath.toFile())) {
      return jarFile.getManifest();
    }
  }

  /** Creates temp path. */
  public static Path createTempFile(
      AbsPath root,
      RelPath buckOut,
      Path directory,
      String prefix,
      String suffix,
      FileAttribute<?>... attrs)
      throws IOException {
    Path tmp = Files.createTempFile(getPathForRelativePath(root, directory), prefix, suffix, attrs);
    return getPathRelativeToProjectRoot(root, buckOut, tmp).orElse(tmp);
  }

  /**
   * @param root Absolute path that defines a project root
   * @param buckOut Relative path that defines a buck out directory
   * @param path Absolute or relative path to the project root.
   * @return If {@code path} is relative, it is returned. If it is absolute and is inside the
   *     project root, it is relativized to the project root and returned. Otherwise an absent value
   *     is returned.
   */
  public static Optional<Path> getPathRelativeToProjectRoot(
      AbsPath root, RelPath buckOut, Path path) {
    Path normalizedPath = MorePaths.normalize(path);
    if (normalizedPath.isAbsolute()) {
      AbsPath pathAbs = AbsPath.of(normalizedPath);
      AbsPath configuredBuckOut = MorePaths.normalize(root.resolve(buckOut));
      // If the path is in the configured buck-out, it's also part of the filesystem.
      if (pathAbs.startsWith(configuredBuckOut) || pathAbs.startsWith(root)) {
        return Optional.of(MorePaths.relativize(root.getPath(), normalizedPath));
      } else {
        return Optional.empty();
      }
    } else {
      return Optional.of(normalizedPath);
    }
  }

  /** Touches a file. */
  public static void touch(AbsPath root, Path fileToTouch) throws IOException {
    if (exists(root, fileToTouch)) {
      setLastModifiedTime(root, fileToTouch, FileTime.fromMillis(System.currentTimeMillis()));
    } else {
      createNewFile(root, fileToTouch);
    }
  }

  /**
   * As {@link #getPathForRelativePath(AbsPath, Path)}, but with the added twist that the existence
   * of the path is checked before returning.
   */
  public static Path getPathForRelativeExistingPath(AbsPath root, Path pathRelativeToProjectRoot) {
    Path file = getPathForRelativePath(root, pathRelativeToProjectRoot);
    if (exists(root, file)) {
      return file;
    }
    throw new RuntimeException(String.format("Not an ordinary file: '%s'.", file));
  }

  /** Merges children. */
  public static void mergeChildren(
      AbsPath projectRoot,
      ImmutableCollection<PathMatcher> ignores,
      Path source,
      Path target,
      CopyOption... options)
      throws IOException {
    Path resolvedSource = getPathForRelativePath(projectRoot, source);
    Path resolvedTarget = getPathForRelativePath(projectRoot, target);
    FileVisitor<Path> fileVisitor =
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Path relative = resolvedSource.relativize(dir);
            Path destDir = resolvedTarget.resolve(relative);
            if (!Files.exists(destDir)) {
              // Short circuit any copying
              Files.move(dir, destDir, options);
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            if (!attrs.isDirectory()) {
              Path relative = resolvedSource.relativize(file);
              Files.move(file, resolvedTarget.resolve(relative), options);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (!dir.equals(resolvedSource)) {
              Files.deleteIfExists(dir);
            }
            return FileVisitResult.CONTINUE;
          }
        };

    walkFileTree(
        projectRoot,
        resolvedSource,
        EnumSet.noneOf(FileVisitOption.class),
        fileVisitor,
        getIgnoreFilter(projectRoot, true, ignores));
  }

  /** Walks a project-root relative file tree with a visitor and visit options. */
  public static void walkRelativeFileTree(
      AbsPath projectRoot,
      Path pathRelativeToProjectRoot,
      EnumSet<FileVisitOption> visitOptions,
      FileVisitor<Path> fileVisitor,
      DirectoryStream.Filter<? super Path> ignoreFilter)
      throws IOException {
    Path rootPath = getPathForRelativePath(projectRoot, pathRelativeToProjectRoot);
    Path edenMagicPathElement = getDefaultEdenMagicPathElement(projectRoot);
    Function<Path, Path> pathMapper = path -> relativize(projectRoot, path).getPath();
    FileVisitor<Path> pathMappingVisitor =
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            // TODO(mbolin): We should not have hardcoded logic for Eden here. Instead, we should
            // properly handle cyclic symlinks in a general way.
            // Failure to perform this check will result in a java.nio.file.FileSystemLoopException
            // in Eden.
            if (edenMagicPathElement.equals(dir.getFileName())) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return fileVisitor.preVisitDirectory(pathMapper.apply(dir), attrs);
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            return fileVisitor.visitFile(pathMapper.apply(file), attrs);
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            return fileVisitor.visitFileFailed(pathMapper.apply(file), exc);
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            return fileVisitor.postVisitDirectory(pathMapper.apply(dir), exc);
          }
        };
    walkFileTree(projectRoot, rootPath, visitOptions, pathMappingVisitor, ignoreFilter);
  }

  /** Returns ignore filter. */
  public static DirectoryStream.Filter<? super Path> getIgnoreFilter(
      AbsPath projectRoot, boolean skipIgnored, ImmutableCollection<PathMatcher> ignores) {
    return skipIgnored
        ? input -> !isIgnored(relativize(projectRoot, input), ignores)
        : input -> true;
  }

  public static void walkFileTree(
      AbsPath root,
      Path path,
      Set<FileVisitOption> options,
      FileVisitor<Path> fileVisitor,
      DirectoryStream.Filter<? super Path> ignoreFilter)
      throws IOException {
    new FileTreeWalker(getPathForRelativePath(root, path), options, fileVisitor, ignoreFilter)
        .walk();
  }

  /** Returns relative paths for all files under the given path */
  public static ImmutableSet<Path> getFilesUnderPath(
      AbsPath root,
      Path pathRelativeToProjectRoot,
      EnumSet<FileVisitOption> visitOptions,
      DirectoryStream.Filter<? super Path> ignoresFilter)
      throws IOException {
    return getFilesUnderPath(
        root, pathRelativeToProjectRoot, x -> true, visitOptions, ignoresFilter);
  }

  /** Returns relative paths for all files under the given path */
  public static ImmutableSet<Path> getFilesUnderPath(
      AbsPath root,
      Path pathRelativeToProjectRoot,
      Predicate<Path> predicate,
      EnumSet<FileVisitOption> visitOptions,
      DirectoryStream.Filter<? super Path> ignoresFilter)
      throws IOException {
    ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    walkRelativeFileTree(
        root,
        pathRelativeToProjectRoot,
        visitOptions,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
            if (predicate.test(path)) {
              paths.add(path);
            }
            return FileVisitResult.CONTINUE;
          }
        },
        ignoresFilter);

    return paths.build();
  }

  /**
   * FileTreeWalker is used to walk files similar to Files.walkFileTree.
   *
   * <p>It has two major differences from walkFileTree.
   *
   * <ol>
   *   <li>It ignores files and directories ignored by this ProjectFilesystem.
   *   <li>The walk is in a deterministic order.
   * </ol>
   *
   * <p>And it has two minor differences.
   *
   * <ol>
   *   <li>It doesn't accept a depth limit.
   *   <li>It doesn't handle the presence of a security manager the same way.
   * </ol>
   */
  private static class FileTreeWalker {
    private final FileVisitor<Path> visitor;
    private final Path root;
    private final boolean followLinks;
    private final ArrayDeque<DirWalkState> state;
    private final DirectoryStream.Filter<? super Path> ignoreFilter;

    FileTreeWalker(
        Path root,
        Set<FileVisitOption> options,
        FileVisitor<Path> pathFileVisitor,
        DirectoryStream.Filter<? super Path> ignoreFilter) {
      this.followLinks = options.contains(FileVisitOption.FOLLOW_LINKS);
      this.visitor = pathFileVisitor;
      this.root = root;
      this.state = new ArrayDeque<>();
      this.ignoreFilter = ignoreFilter;
    }

    private ImmutableList<Path> getContents(Path root) throws IOException {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, ignoreFilter)) {
        return FluentIterable.from(stream).toSortedList(Comparator.naturalOrder());
      }
    }

    private class DirWalkState {
      private final Path dir;
      private final BasicFileAttributes attrs;
      private final boolean isRootSentinel;
      private UnmodifiableIterator<Path> iter;
      private @Nullable IOException ioe = null;

      DirWalkState(Path directory, BasicFileAttributes attributes, boolean isRootSentinel) {
        this.dir = directory;
        this.attrs = attributes;
        this.isRootSentinel = isRootSentinel;

        if (isRootSentinel) {
          this.iter = ImmutableList.of(root).iterator();
        } else {
          try {
            this.iter = getContents(directory).iterator();
          } catch (IOException e) {
            this.iter = ImmutableList.<Path>of().iterator();
            this.ioe = e;
          }
        }
      }
    }

    private void walk() throws IOException {
      state.add(new DirWalkState(root, getAttributes(root), true));

      while (true) {
        FileVisitResult result;
        if (state.getLast().iter.hasNext()) {
          result = visitPath(state.getLast().iter.next());
        } else {
          DirWalkState dirState = state.removeLast();
          if (dirState.isRootSentinel) {
            return;
          }
          result = visitor.postVisitDirectory(dirState.dir, dirState.ioe);
        }
        Objects.requireNonNull(result, "FileVisitor returned a null FileVisitResult.");
        if (result == FileVisitResult.SKIP_SIBLINGS) {
          state.getLast().iter = ImmutableList.<Path>of().iterator();
        } else if (result == FileVisitResult.TERMINATE) {
          return;
        }
      }
    }

    private FileVisitResult visitPath(Path p) throws IOException {
      BasicFileAttributes attrs;
      try {
        attrs = getAttributes(p);
        ensureNoLoops(p, attrs);
      } catch (IOException ioe) {
        return visitor.visitFileFailed(p, ioe);
      }

      if (attrs.isDirectory()) {
        FileVisitResult result = visitor.preVisitDirectory(p, attrs);
        if (result == FileVisitResult.CONTINUE) {
          state.add(new DirWalkState(p, attrs, false));
        }
        return result;
      } else {
        return visitor.visitFile(p, attrs);
      }
    }

    private void ensureNoLoops(Path p, BasicFileAttributes attrs) throws FileSystemLoopException {
      if (!followLinks) {
        return;
      }
      if (!attrs.isDirectory()) {
        return;
      }
      if (willLoop(p, attrs)) {
        throw new FileSystemLoopException(p.toString());
      }
    }

    private boolean willLoop(Path p, BasicFileAttributes attrs) {
      try {
        Object thisKey = attrs.fileKey();
        for (DirWalkState s : state) {
          if (s.isRootSentinel) {
            continue;
          }
          Object thatKey = s.attrs.fileKey();
          if (thisKey != null && thatKey != null) {
            if (thisKey.equals(thatKey)) {
              return true;
            }
          } else if (Files.isSameFile(p, s.dir)) {
            return true;
          }
        }
      } catch (IOException e) {
        return true;
      }
      return false;
    }

    private BasicFileAttributes getAttributes(Path root) throws IOException {
      if (!followLinks) {
        return Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      }
      try {
        return Files.readAttributes(root, BasicFileAttributes.class);
      } catch (IOException e) {
        return Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      }
    }
  }
}
