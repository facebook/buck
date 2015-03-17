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

package com.facebook.buck.io;

import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Collections2;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * An injectable service for interacting with the filesystem relative to the project root.
 */
public class ProjectFilesystem {

  /**
   * Controls the behavior of how the source should be treated when copying.
   */
  public enum CopySourceMode {
      /**
       * Copy the single source file into the destination path.
       */
      FILE,

      /**
       * Treat the source as a directory and copy each file inside it
       * to the destination path, which must be a directory.
       */
      DIRECTORY_CONTENTS_ONLY,

      /**
       * Treat the source as a directory. Copy the directory and its
       * contents to the destination path, which must be a directory.
       */
      DIRECTORY_AND_CONTENTS,
  }

  private final Path projectRoot;

  private final Function<Path, Path> pathAbsolutifier;

  private final ImmutableSet<Path> ignorePaths;

  // Defaults to false, and so paths should be valid.
  @VisibleForTesting
  protected boolean ignoreValidityOfPaths;

  public ProjectFilesystem(Path projectRoot) {
    this(projectRoot, ImmutableSet.<Path>of());
  }

  /**
   * There should only be one {@link ProjectFilesystem} created per process.
   * <p>
   * When creating a {@code ProjectFilesystem} for a test, rather than create a filesystem with an
   * arbitrary argument for the project root, such as {@code new File(".")}, prefer the creation of
   * a mock filesystem via EasyMock instead. Note that there are cases (such as integration tests)
   * where specifying {@code new File(".")} as the project root might be the appropriate thing.
   */
  public ProjectFilesystem(Path projectRoot, ImmutableSet<Path> ignorePaths) {
    this(projectRoot.getFileSystem(), projectRoot, ignorePaths);
  }

  protected ProjectFilesystem(FileSystem vfs, Path projectRoot, ImmutableSet<Path> ignorePaths) {
    Preconditions.checkArgument(Files.isDirectory(projectRoot));
    Preconditions.checkState(vfs.equals(projectRoot.getFileSystem()));
    this.projectRoot = projectRoot;
    this.pathAbsolutifier = new Function<Path, Path>() {
      @Override
      public Path apply(Path path) {
        return resolve(path);
      }
    };
    this.ignorePaths = MorePaths.filterForSubpaths(ignorePaths, this.projectRoot);
    this.ignoreValidityOfPaths = false;
  }

  public Path getRootPath() {
    return projectRoot;
  }

  /**
   * @return the specified {@code path} resolved against {@link #getRootPath()} to an absolute path.
   */
  public Path resolve(Path path) {
    return getRootPath().resolve(path).toAbsolutePath().normalize();
  }

  public Path resolve(String path) {
    return getRootPath().resolve(path).toAbsolutePath().normalize();
  }

  /**
   * @return A {@link Function} that applies {@link #resolve(Path)} to its parameter.
   */
  public Function<Path, Path> getAbsolutifier() {
    return pathAbsolutifier;
  }

  /**
   * @return A {@link ImmutableSet} of {@link Path} objects to have buck ignore.  All paths will be
   *     relative to the {@link ProjectFilesystem#getRootPath()}.
   */
  public ImmutableSet<Path> getIgnorePaths() {
    return ignorePaths;
  }

  /**
   * // @deprecated Prefer operating on {@code Path}s directly, replaced by
   *    {@link #getPathForRelativePath(java.nio.file.Path)}.
   */
  public File getFileForRelativePath(String pathRelativeToProjectRoot) {
    return pathRelativeToProjectRoot.isEmpty()
        ? projectRoot.toFile()
        : getPathForRelativePath(Paths.get(pathRelativeToProjectRoot)).toFile();
  }

  /**
   * // @deprecated Prefer operating on {@code Path}s directly, replaced by
   *    {@link #getPathForRelativePath(java.nio.file.Path)}.
   */
  public File getFileForRelativePath(Path pathRelativeToProjectRoot) {
    return getPathForRelativePath(pathRelativeToProjectRoot).toFile();
  }

  public Path getPathForRelativePath(Path pathRelativeToProjectRoot) {
    return projectRoot.resolve(pathRelativeToProjectRoot);
  }

  /**
   * @param path Absolute path or path relative to the project root.
   * @return If {@code path} is relative, it is returned. If it is absolute and is inside the
   *         project root, it is relativized to the project root and returned. Otherwise an absent
   *         value is returned.
   */
  public Optional<Path> getPathRelativeToProjectRoot(Path path) {
    path = path.normalize();
    if (path.isAbsolute()) {
      if (path.startsWith(projectRoot)) {
        return Optional.of(MorePaths.relativize(projectRoot, path));
      } else {
        return Optional.absent();
      }
    } else {
      return Optional.of(path);
    }
  }

  /**
   * As {@link #getFileForRelativePath(java.nio.file.Path)}, but with the added twist that the
   * existence of the path is checked before returning.
   */
  public Path getPathForRelativeExistingPath(Path pathRelativeToProjectRoot) {
    Path file = getPathForRelativePath(pathRelativeToProjectRoot);

    if (ignoreValidityOfPaths) {
      return file;
    }

    // TODO(mbolin): Eliminate this temporary exemption for symbolic links.
    if (Files.isSymbolicLink(file)) {
      return file;
    }

    if (!Files.exists(file)) {
      throw new RuntimeException(
          String.format("Not an ordinary file: '%s'.", pathRelativeToProjectRoot));
    }

    return file;
  }

  public boolean exists(Path pathRelativeToProjectRoot) {
    return Files.exists(getPathForRelativePath(pathRelativeToProjectRoot));
  }

  public long getFileSize(Path pathRelativeToProjectRoot) throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    if (!Files.isRegularFile(path)) {
      throw new IOException("Cannot get size of " + path + " because it is not an ordinary file.");
    }
    return Files.size(path);
  }

  /**
   * Deletes a file specified by its path relative to the project root.
   * @param pathRelativeToProjectRoot path to the file
   * @return true if the file was successfully deleted, false otherwise
   */
  public boolean deleteFileAtPath(Path pathRelativeToProjectRoot) {
    try {
      Files.delete(getPathForRelativePath(pathRelativeToProjectRoot));
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public Properties readPropertiesFile(Path pathToPropertiesFileRelativeToProjectRoot)
      throws IOException {
    Properties properties = new Properties();
    Path propertiesFile = getPathForRelativePath(pathToPropertiesFileRelativeToProjectRoot);
    if (Files.exists(propertiesFile)) {
      properties.load(Files.newBufferedReader(propertiesFile, Charsets.UTF_8));
      return properties;
    } else {
      throw new FileNotFoundException(propertiesFile.toString());
    }
  }

  /**
   * Checks whether there is a normal file at the specified path.
   */
  public boolean isFile(Path pathRelativeToProjectRoot) {
    return Files.isRegularFile(
        getPathForRelativePath(pathRelativeToProjectRoot));
  }

  public boolean isHidden(Path pathRelativeToProjectRoot) throws IOException {
    return Files.isHidden(getPathForRelativePath(pathRelativeToProjectRoot));
  }

  /**
   * Similar to {@link #walkFileTree(Path, FileVisitor)} except this takes in a path relative to
   * the project root.
   */
  public void walkRelativeFileTree(
      Path pathRelativeToProjectRoot,
      final FileVisitor<Path> fileVisitor) throws IOException {
    walkRelativeFileTree(pathRelativeToProjectRoot,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS),
        fileVisitor);
  }

  /**
   * Walks a project-root relative file tree with a visitor and visit options.
   */
  public void walkRelativeFileTree(
      Path pathRelativeToProjectRoot,
      EnumSet<FileVisitOption> visitOptions,
      final FileVisitor<Path> fileVisitor) throws IOException {
    Path rootPath = getPathForRelativePath(pathRelativeToProjectRoot);
    Files.walkFileTree(
        rootPath,
        visitOptions,
        Integer.MAX_VALUE,
        new FileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(
              Path dir, BasicFileAttributes attrs) throws IOException {
            return fileVisitor.preVisitDirectory(projectRoot.relativize(dir), attrs);
          }

          @Override
          public FileVisitResult visitFile(
              Path file, BasicFileAttributes attrs) throws IOException {
            return fileVisitor.visitFile(projectRoot.relativize(file), attrs);
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            return fileVisitor.visitFileFailed(projectRoot.relativize(file), exc);
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            return fileVisitor.postVisitDirectory(projectRoot.relativize(dir), exc);
          }
        });
  }

  /**
   * Allows {@link Files#walkFileTree} to be faked in tests.
   */
  public void walkFileTree(Path root, FileVisitor<Path> fileVisitor) throws IOException {
    Files.walkFileTree(root, fileVisitor);
  }

  public ImmutableSet<Path> getFilesUnderPath(Path pathRelativeToProjectRoot) throws IOException {
    return getFilesUnderPath(pathRelativeToProjectRoot, Predicates.<Path>alwaysTrue());
  }

  public ImmutableSet<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot,
      Predicate<Path> predicate) throws IOException {
    return getFilesUnderPath(
        pathRelativeToProjectRoot,
        predicate,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS));
  }

  public ImmutableSet<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot,
      final Predicate<Path> predicate,
      EnumSet<FileVisitOption> visitOptions) throws IOException {
    final ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    walkRelativeFileTree(
        pathRelativeToProjectRoot,
        visitOptions,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
            if (predicate.apply(path)) {
              paths.add(path);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return paths.build();
  }

  /**
   * Allows {@link Files#isDirectory} to be faked in tests.
   */
  public boolean isDirectory(Path child, LinkOption... linkOptions) {
    return Files.isDirectory(resolve(child), linkOptions);
  }

  /**
   * Allows {@link java.io.File#listFiles} to be faked in tests.
   *
   * // @deprecated Replaced by {@link #getDirectoryContents}
   */
  public File[] listFiles(Path pathRelativeToProjectRoot) throws IOException {
    Collection<Path> paths = getDirectoryContents(pathRelativeToProjectRoot);

    File[] result = new File[paths.size()];
    return Collections2.transform(paths, new Function<Path, File>() {
      @Override
      public File apply(Path input) {
        return input.toFile();
      }
    }).toArray(result);
  }

  public ImmutableCollection<Path> getDirectoryContents(Path pathRelativeToProjectRoot)
      throws IOException {
    Preconditions.checkArgument(!pathRelativeToProjectRoot.isAbsolute());
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
      return FluentIterable.from(stream)
          .transform(
              new Function<Path, Path>() {
                @Override
                public Path apply(Path absolutePath) {
                  return MorePaths.relativize(projectRoot, absolutePath);
                }
              })
          .toList();
    }
  }

  @VisibleForTesting
  protected PathListing.PathModifiedTimeFetcher getLastModifiedTimeFetcher() {
    return new PathListing.PathModifiedTimeFetcher() {
        @Override
        public FileTime getLastModifiedTime(Path path) throws IOException {
          return FileTime.fromMillis(ProjectFilesystem.this.getLastModifiedTime(path));
        }
    };
  }

  /**
   * Returns the files inside {@code pathRelativeToProjectRoot} which match
   * {@code globPattern}, ordered in descending last modified time order.
   */
  public ImmutableSortedSet<Path> getSortedMatchingDirectoryContents(
      Path pathRelativeToProjectRoot,
      String globPattern)
    throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    return PathListing.listMatchingPaths(
        path,
        globPattern,
        getLastModifiedTimeFetcher());
  }

  public long getLastModifiedTime(Path pathRelativeToProjectRoot) throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    return Files.getLastModifiedTime(path).toMillis();
  }

  /**
   * Sets the last modified time for the given path.
   */
  public Path setLastModifiedTime(Path pathRelativeToProjectRoot, FileTime time)
      throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    return Files.setLastModifiedTime(path, time);
  }

  /**
   * Recursively delete everything under the specified path.
   */
  public void rmdir(Path pathRelativeToProjectRoot) throws IOException {
    MoreFiles.rmdir(resolve(pathRelativeToProjectRoot));
  }

  /**
   * Resolves the relative path against the project root and then calls
   * {@link Files#createDirectories(java.nio.file.Path,
   *            java.nio.file.attribute.FileAttribute[])}
   */
  public void mkdirs(Path pathRelativeToProjectRoot) throws IOException {
    Files.createDirectories(resolve(pathRelativeToProjectRoot));
  }

  /**
   * Creates a new file relative to the project root.
   */
  public Path createNewFile(Path pathRelativeToProjectRoot) throws IOException {
    Path path = getPathForRelativePath(pathRelativeToProjectRoot);
    return Files.createFile(path);
  }

  /**
   * // @deprecated Prefer operating on {@code Path}s directly, replaced by
   *  {@link #createParentDirs(java.nio.file.Path)}.
   */
  public void createParentDirs(String pathRelativeToProjectRoot) throws IOException {
    Path file = getPathForRelativePath(Paths.get(pathRelativeToProjectRoot));
    mkdirs(file.getParent());
  }

  /**
   * @param pathRelativeToProjectRoot Must identify a file, not a directory. (Unfortunately, we have
   *     no way to assert this because the path is not expected to exist yet.)
   */
  public void createParentDirs(Path pathRelativeToProjectRoot) throws IOException {
    Path file = resolve(pathRelativeToProjectRoot);
    Path directory = file.getParent();
    mkdirs(directory);
  }

  /**
   * Writes each line in {@code lines} with a trailing newline to a file at the specified path.
   * <p>
   * The parent path of {@code pathRelativeToProjectRoot} must exist.
   */
  public void writeLinesToPath(
      Iterable<String> lines,
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs)
      throws IOException {
    try (Writer writer =
         new BufferedWriter(
             new OutputStreamWriter(
                 newFileOutputStream(pathRelativeToProjectRoot, attrs),
                 Charsets.UTF_8))) {
      for (String line : lines) {
        writer.write(line);
        writer.write('\n');
      }
    }
  }

  public void writeContentsToPath(
      String contents,
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs)
      throws IOException {
    writeBytesToPath(contents.getBytes(Charsets.UTF_8), pathRelativeToProjectRoot, attrs);
  }

  public void writeBytesToPath(
      byte[] bytes,
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs) throws IOException {
    try (OutputStream outputStream = newFileOutputStream(pathRelativeToProjectRoot, attrs)) {
      outputStream.write(bytes);
    }
  }

  public OutputStream newFileOutputStream(
      Path pathRelativeToProjectRoot,
      FileAttribute<?>... attrs)
    throws IOException {
    return new BufferedOutputStream(
        Channels.newOutputStream(
            Files.newByteChannel(
                getPathForRelativePath(pathRelativeToProjectRoot),
                ImmutableSet.<OpenOption>of(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE),
                attrs)));
  }

  public <A extends BasicFileAttributes> A readAttributes(
      Path pathRelativeToProjectRoot,
      Class<A> type,
      LinkOption... options)
    throws IOException {
    return Files.readAttributes(
        getPathForRelativePath(pathRelativeToProjectRoot), type, options);
  }

  public InputStream newFileInputStream(Path pathRelativeToProjectRoot)
    throws IOException {
    return new BufferedInputStream(
        Files.newInputStream(getPathForRelativePath(pathRelativeToProjectRoot)));
  }

  /**
   * @param inputStream Source of the bytes. This method does not close this stream.
   */
  public void copyToPath(
      InputStream inputStream,
      Path pathRelativeToProjectRoot,
      CopyOption... options)
      throws IOException {
    Files.copy(inputStream, getPathForRelativePath(pathRelativeToProjectRoot),
        options);
  }

  public Optional<String> readFileIfItExists(Path pathRelativeToProjectRoot) {
    Path fileToRead = getPathForRelativePath(pathRelativeToProjectRoot);
    return readFileIfItExists(fileToRead, pathRelativeToProjectRoot.toString());
  }

  private Optional<String> readFileIfItExists(Path fileToRead, String pathRelativeToProjectRoot) {
    if (Files.isRegularFile(fileToRead)) {
      String contents;
      try {
        contents = new String(Files.readAllBytes(fileToRead), Charsets.UTF_8);
      } catch (IOException e) {
        // Alternatively, we could return Optional.absent(), though something seems suspicious if we
        // have already verified that fileToRead is a file and then we cannot read it.
        throw new RuntimeException("Error reading " + pathRelativeToProjectRoot, e);
      }
      return Optional.of(contents);
    } else {
      return Optional.absent();
    }
  }

  /**
   * Attempts to open the file for future read access. Returns {@link Optional#absent()} if the file
   * does not exist.
   */
  public Optional<Reader> getReaderIfFileExists(Path pathRelativeToProjectRoot) {
    Path fileToRead = getPathForRelativePath(pathRelativeToProjectRoot);
    if (Files.isRegularFile(fileToRead)) {
      try {
        return Optional.of(
            (Reader) new BufferedReader(
                new InputStreamReader(newFileInputStream(pathRelativeToProjectRoot))));
      } catch (Exception e) {
        throw new RuntimeException("Error reading " + pathRelativeToProjectRoot, e);
      }
    } else {
      return Optional.absent();
    }
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#absent()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   *
   * // @deprecated PRefero operation on {@code Path}s directly, replaced by
   *  {@link #readFirstLine(java.nio.file.Path)}
   */
  public Optional<String> readFirstLine(String pathRelativeToProjectRoot) {
    return readFirstLine(Paths.get(pathRelativeToProjectRoot));
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#absent()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   */
  public Optional<String> readFirstLine(Path pathRelativeToProjectRoot) {
    Path file = getPathForRelativePath(pathRelativeToProjectRoot);
    return readFirstLineFromFile(file);
  }

  /**
   * Attempts to read the first line of the specified file. If the file does not exist, is empty,
   * or encounters an error while being read, {@link Optional#absent()} is returned. Otherwise, an
   * {@link Optional} with the first line of the file will be returned.
   */
  public Optional<String> readFirstLineFromFile(Path file) {
    try {
      return Optional.fromNullable(
          Files.newBufferedReader(file, Charsets.UTF_8).readLine());
    } catch (IOException e) {
      // Because the file is not even guaranteed to exist, swallow the IOException.
      return Optional.absent();
    }
  }

  public List<String> readLines(Path pathRelativeToProjectRoot) throws IOException {
    Path file = getPathForRelativePath(pathRelativeToProjectRoot);
    return Files.readAllLines(file, Charsets.UTF_8);
  }

  /**
   * // @deprecated Prefer operation on {@code Path}s directly, replaced by
   *  {@link Files#newInputStream(java.nio.file.Path, java.nio.file.OpenOption...)}.
   */
  public InputStream getInputStreamForRelativePath(Path path) throws IOException {
    Path file = getPathForRelativePath(path);
    return Files.newInputStream(file);
  }

  public String computeSha1(Path pathRelativeToProjectRoot) throws IOException {
    Path fileToHash = getPathForRelativePath(pathRelativeToProjectRoot);
    return Hashing.sha1().hashBytes(Files.readAllBytes(fileToHash)).toString();
  }

  /**
   * @param event The event to be tested.
   * @return true if event is a path change notification.
   */
  public boolean isPathChangeEvent(WatchEvent<?> event) {
    return event.kind() == StandardWatchEventKinds.ENTRY_CREATE ||
        event.kind() == StandardWatchEventKinds.ENTRY_MODIFY ||
        event.kind() == StandardWatchEventKinds.ENTRY_DELETE;
  }

  public void copy(Path source, Path target, CopySourceMode sourceMode) throws IOException {
    switch (sourceMode) {
      case FILE:
        Files.copy(
            resolve(source),
            resolve(target),
            StandardCopyOption.REPLACE_EXISTING);
        break;
      case DIRECTORY_CONTENTS_ONLY:
        MoreFiles.copyRecursively(resolve(source), resolve(target));
        break;
      case DIRECTORY_AND_CONTENTS:
        MoreFiles.copyRecursively(resolve(source), resolve(target.resolve(source.getFileName())));
        break;
    }
  }

  public void move(Path source, Path target, CopyOption... options) throws IOException {
    Files.move(resolve(source), resolve(target), options);

  }

  public void copyFolder(Path source, Path target) throws IOException {
    copy(source, target, CopySourceMode.DIRECTORY_CONTENTS_ONLY);
  }

  public void copyFile(Path source, Path target) throws IOException {
    copy(source, target, CopySourceMode.FILE);
  }

  public void createSymLink(Path sourcePath, Path targetPath, boolean force)
      throws IOException {
    if (force) {
      Files.deleteIfExists(targetPath);
    }
    if (Platform.detect() == Platform.WINDOWS) {
      if (isDirectory(sourcePath)) {
        // Creating symlinks to directories on Windows requires escalated privileges. We're just
        // going to have to copy things recursively.
        MoreFiles.copyRecursively(sourcePath, targetPath);
      } else {
        // When sourcePath is relative, resolve it from the targetPath. We're creating a hard link
        // anyway.
        sourcePath = targetPath.getParent().resolve(sourcePath).normalize();
        Files.createLink(targetPath, sourcePath);
      }
    } else {
      Files.createSymbolicLink(targetPath, sourcePath);
    }
  }

  /**
   * Returns true if the file under {@code path} exists and is a symbolic
   * link, false otherwise.
   */
  public boolean isSymLink(Path path) throws IOException {
    return Files.isSymbolicLink(getPathForRelativePath(path));
  }

  /**
   * Takes a sequence of paths relative to the project root and writes a zip file to {@code out}
   * with the contents and structure that matches that of the specified paths.
   */
  public void createZip(Collection<Path> pathsToIncludeInZip, File out) throws IOException {
    createZip(pathsToIncludeInZip, out, ImmutableMap.<Path, String>of());
  }

  /**
   * Similar to {@link #createZip(Collection, File)}, but also takes a list of additional files to
   * write in the zip, including their contents, as a map.
   */
  public void createZip(
      Collection<Path> pathsToIncludeInZip,
      File out,
      ImmutableMap<Path, String> additionalFileContents) throws IOException {
    Preconditions.checkState(!Iterables.isEmpty(pathsToIncludeInZip));
    try (CustomZipOutputStream zip = ZipOutputStreams.newOutputStream(out)) {
      for (Path path : pathsToIncludeInZip) {
        Path full = getPathForRelativePath(path);
        File file = full.toFile();
        boolean isDirectory = isDirectory(full);

        String entryName = path.toString();
        if (isDirectory) {
          entryName += "/";
        }
        CustomZipEntry entry = new CustomZipEntry(entryName);

        // Support executable files.  If we detect this file is executable, store this
        // information as 0100 in the field typically used in zip implementations for
        // POSIX file permissions.  We'll use this information when unzipping.
        if (file.canExecute()) {
          entry.setExternalAttributes(
              MorePosixFilePermissions.toMode(
                  EnumSet.of(PosixFilePermission.OWNER_EXECUTE)) << 16);
        }

        zip.putNextEntry(entry);
        if (!isDirectory) {
          try (InputStream input = Files.newInputStream(getPathForRelativePath(path))) {
            ByteStreams.copy(input, zip);
          }
        }
        zip.closeEntry();
      }

      for (Map.Entry<Path, String> fileContentsEntry : additionalFileContents.entrySet()) {
        CustomZipEntry entry = new CustomZipEntry(fileContentsEntry.getKey().toString());
        zip.putNextEntry(entry);
        try (InputStream stream =
                 new ByteArrayInputStream(fileContentsEntry.getValue().getBytes(Charsets.UTF_8))) {
          ByteStreams.copy(stream, zip);
        }
        zip.closeEntry();
      }
    }
  }

  /**
   *
   * @param event the event to format.
   * @return the formatted event context string.
   */
  public String createContextString(WatchEvent<?> event) {
    if (isPathChangeEvent(event)) {
      Path path = (Path) event.context();
      return path.toAbsolutePath().normalize().toString();
    }
    return String.valueOf(event.context());
  }


  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof ProjectFilesystem)) {
      return false;
    }

    ProjectFilesystem that = (ProjectFilesystem) other;

    return Objects.equals(projectRoot, that.projectRoot) &&
        Objects.equals(ignorePaths, that.ignorePaths);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectRoot, ignorePaths);
  }

  /**
   * @param path the path to check.
   * @return whether ignoredPaths contains path or any of its ancestors.
   */
  public boolean isIgnored(Path path) {
    Preconditions.checkArgument(!path.isAbsolute());
    for (Path ignoredPath : getIgnorePaths()) {
      if (path.startsWith(ignoredPath)) {
        return true;
      }
    }
    return false;
  }

  public Path createTempFile(
      Path directory,
      String prefix,
      String suffix,
      FileAttribute<?>... attrs)
      throws IOException {
    return Files.createTempFile(directory, prefix, suffix, attrs);
  }

  public void touch(Path fileToTouch) throws IOException {
    if (exists(fileToTouch)) {
      setLastModifiedTime(fileToTouch, FileTime.fromMillis(System.currentTimeMillis()));
    } else {
      createNewFile(fileToTouch);
    }
  }
}
