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

package com.facebook.buck.util;

import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.zip.CustomZipOutputStream;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.FileVisitor;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;

/**
 * An injectable service for interacting with the filesystem.
 */
public class ProjectFilesystem {

  // TODO(mbolin): This file is heavy on the use of java.lang.String rather than java.nio.file.Path.
  // Migrate from String to Path.

  private final File projectRoot;
  private final Path pathToRoot;

  // Over time, we hope to replace our uses of String with Path, as appropriate. When that happens,
  // the Function<String, String> can go away, as Function<Path, Path> should be used exclusively.

  private final Function<Path, Path> pathAbsolutifier;
  private final Function<String, Path> pathRelativizer;

  private final ImmutableSet<Path> ignorePaths;

  /**
   * There should only be one {@link ProjectFilesystem} created per process.
   * <p>
   * When creating a {@code ProjectFilesystem} for a test, rather than create a filesystem with an
   * arbitrary argument for the project root, such as {@code new File(".")}, prefer the creation of
   * a mock filesystem via EasyMock instead. Note that there are cases (such as integration tests)
   * where specifying {@code new File(".")} as the project root might be the appropriate thing.
   */
  public ProjectFilesystem(File projectRoot, ImmutableSet<Path> ignorePaths) {
    this.projectRoot = Preconditions.checkNotNull(projectRoot);
    this.pathToRoot = projectRoot.toPath();
    Preconditions.checkArgument(projectRoot.isDirectory());
    this.pathAbsolutifier = new Function<Path, Path>() {
      @Override
      public Path apply(Path path) {
        return resolve(path);
      }
    };
    this.pathRelativizer = new Function<String, Path>() {
      @Override
      public Path apply(String relativePath) {
        return MorePaths.absolutify(getFileForRelativePath(relativePath).toPath());
      }
    };
    this.ignorePaths = MorePaths.filterForSubpaths(ignorePaths, this.pathToRoot);
  }

  public ProjectFilesystem(File projectRoot) {
    this(projectRoot, ImmutableSet.<Path>of());
  }

  public Path getRootPath() {
    return pathToRoot;
  }

  /**
   * @return the specified {@code path} resolved against {@link #getRootPath()} to an absolute path.
   */
  public Path resolve(Path path) {
    return pathToRoot.resolve(path).toAbsolutePath();
  }

  /**
   * @return A {@link Function} that applies {@link #resolve(Path)} to its parameter.
   */
  public Function<Path, Path> getAbsolutifier() {
    return pathAbsolutifier;
  }

  public File getProjectRoot() {
    return projectRoot;
  }

  /**
   * @return A {@link ImmutableSet} of {@link Path} objects to have buck ignore.  All paths will be
   *     relative to the {@link ProjectFilesystem#getRootPath()}.
   */
  public ImmutableSet<Path> getIgnorePaths() {
    return ignorePaths;
  }

  public File getFileForRelativePath(String pathRelativeToProjectRoot) {
    return pathRelativeToProjectRoot.isEmpty()
        ? projectRoot
        : new File(projectRoot, pathRelativeToProjectRoot);
  }

  public File getFileForRelativePath(Path pathRelativeToProjectRoot) {
    return projectRoot.toPath().resolve(pathRelativeToProjectRoot).toFile();
  }

  public boolean exists(String pathRelativeToProjectRoot) {
    return getFileForRelativePath(pathRelativeToProjectRoot).exists();
  }

  public long getFileSize(Path pathRelativeToProjectRoot) throws IOException {
    File file = getFileForRelativePath(pathRelativeToProjectRoot);
    // TODO(mbolin): Decide if/how symlinks should be supported and add unit test.
    if (!file.isFile()) {
      throw new IOException("Cannot get size of " + file + " because it is not an ordinary file.");
    }
    return file.length();
  }

  /**
   * Deletes a file specified by its path relative to the project root.
   * @param pathRelativeToProjectRoot path to the file
   * @return true if the file was successfully deleted, false otherwise
   */
  public boolean deleteFileAtPath(String pathRelativeToProjectRoot) {
    return getFileForRelativePath(pathRelativeToProjectRoot).delete();
  }

  public Properties readPropertiesFile(String pathToPropertiesFileRelativeToProjectRoot)
      throws IOException {
    Properties properties = new Properties();
    File propertiesFile = getFileForRelativePath(pathToPropertiesFileRelativeToProjectRoot);
    properties.load(Files.newReader(propertiesFile, Charsets.UTF_8));
    return properties;
  }

  /**
   * Checks whether there is a normal file at the specified path.
   */
  public boolean isFile(String pathRelativeToProjectRoot) {
    return getFileForRelativePath(pathRelativeToProjectRoot).isFile();
  }

  /**
   * Allows {@link java.nio.file.Files#walkFileTree} to be faked in tests.
   */
  public void walkFileTree(Path root, FileVisitor<Path> fileVisitor) throws IOException {
    java.nio.file.Files.walkFileTree(root, fileVisitor);
  }

  /**
   * Allows {@link java.nio.file.Files#isDirectory} to be faked in tests.
   */
  public boolean isDirectory(Path child, LinkOption... linkOptions) {
    return java.nio.file.Files.isDirectory(child, linkOptions);
  }

  /**
   * Allows {@link java.io.File#listFiles} to be faked in tests.
   */
  public File[] listFiles(String pathRelativeToProjectRoot) {
    return getFileForRelativePath(pathRelativeToProjectRoot).listFiles();
  }

  /**
   * Recursively delete everything under the specified path.
   */
  public void rmdir(String path) throws IOException {
    MoreFiles.rmdir(pathRelativizer.apply(path));
  }

  /**
   * Recursively delete everything under the specified path.
   */
  public void rmdir(Path pathRelativeToProjectRoot) throws IOException {
    MoreFiles.rmdir(resolve(pathRelativeToProjectRoot));
  }

  /**
   * Resolves the relative path against the project root and then calls
   * {@link java.nio.file.Files#createDirectories(java.nio.file.Path, java.nio.file.attribute.FileAttribute[])}
   */
  public void mkdirs(Path pathRelativeToProjectRoot) throws IOException {
    java.nio.file.Files.createDirectories(resolve(pathRelativeToProjectRoot));
  }

  public void createParentDirs(String pathRelativeToProjectRoot) throws IOException {
    File file = getFileForRelativePath(pathRelativeToProjectRoot);
    mkdirs(file.getParentFile().toPath());
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
  public void writeLinesToPath(Iterable<String> lines, Path pathRelativeToProjectRoot)
      throws IOException {
    try (Writer writer = new BufferedWriter(
        new FileWriter(
            getFileForRelativePath(pathRelativeToProjectRoot)))) {
      for (String line : lines) {
        writer.write(line);
        writer.write('\n');
      }
    }
  }

  public void writeContentsToPath(String contents, Path pathRelativeToProjectRoot)
      throws IOException {
    Files.write(contents, getFileForRelativePath(pathRelativeToProjectRoot), Charsets.UTF_8);
  }

  public void writeBytesToPath(byte[] bytes, Path pathRelativeToProjectRoot) throws IOException {
    Files.write(bytes, getFileForRelativePath(pathRelativeToProjectRoot));
  }

  /**
   * @param inputStream Source of the bytes. This method does not close this stream.
   */
  public void copyToPath(final InputStream inputStream, Path pathRelativeToProjectRoot)
      throws IOException {
    InputSupplier<? extends InputStream> inputSupplier = new InputSupplier<InputStream>() {
      @Override
      public InputStream getInput() throws IOException {
        return inputStream;
      }
    };
    Files.copy(inputSupplier, getFileForRelativePath(pathRelativeToProjectRoot));
  }

  public Optional<String> readFileIfItExists(Path pathRelativeToProjectRoot) {
    File fileToRead = getFileForRelativePath(pathRelativeToProjectRoot);
    return readFileIfItExists(fileToRead, pathRelativeToProjectRoot.toString());
  }

  private Optional<String> readFileIfItExists(File fileToRead, String pathRelativeToProjectRoot) {
    if (fileToRead.isFile()) {
      String contents;
      try {
        contents = Files.toString(fileToRead, Charsets.UTF_8);
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
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#absent()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
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
    Preconditions.checkNotNull(pathRelativeToProjectRoot);
    File file = getFileForRelativePath(pathRelativeToProjectRoot);
    return readFirstLineFromFile(file);
  }

  /**
   * Attempts to read the first line of the specified file. If the file does not exist, is empty,
   * or encounters an error while being read, {@link Optional#absent()} is returned. Otherwise, an
   * {@link Optional} with the first line of the file will be returned.
   */
  public Optional<String> readFirstLineFromFile(File file) {
    try {
      String firstLine = Files.readFirstLine(file, Charsets.UTF_8);
      return Optional.fromNullable(firstLine);
    } catch (IOException e) {
      // Because the file is not even guaranteed to exist, swallow the IOException.
      return Optional.absent();
    }
  }

  public List<String> readLines(Path pathRelativeToProjectRoot) throws IOException {
    File file = getFileForRelativePath(pathRelativeToProjectRoot);
    return Files.readLines(file, Charsets.UTF_8);
  }

  public InputSupplier<? extends InputStream> getInputSupplierForRelativePath(String path) {
    File file = getFileForRelativePath(path);
    return Files.newInputStreamSupplier(file);
  }

  public String computeSha1(Path pathRelativeToProjectRoot) throws IOException {
    File fileToHash = getFileForRelativePath(pathRelativeToProjectRoot);
    return Files.hash(fileToHash, Hashing.sha1()).toString();
  }

  /**
   * @return a function that takes a path relative to the project root and resolves it to an
   *     absolute path. This is particularly useful for {@link com.facebook.buck.step.Step}s that do
   *     not extend {@link com.facebook.buck.shell.ShellStep} because they are not guaranteed to be
   *     run from the project root.
   */
  public Function<String, Path> getPathRelativizer() {
    return pathRelativizer;
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

  public void copyFolder(Path source, Path target) throws IOException {
    MoreFiles.copyRecursively(resolve(source), resolve(target));
  }

  public void copyFile(Path source, Path target) throws IOException {
    java.nio.file.Files.copy(resolve(source), resolve(target), StandardCopyOption.REPLACE_EXISTING);
  }

  public void createSymLink(Path sourcePath, Path targetPath, boolean force)
      throws IOException {
    if (force) {
      java.nio.file.Files.deleteIfExists(targetPath);
    }
    if (Platform.detect() == Platform.WINDOWS) {
      if (isDirectory(sourcePath)) {
        // Creating symlinks to directories on Windows requires escalated privileges. We're just
        // going to have to copy things recursively.
        MoreFiles.copyRecursively(sourcePath, targetPath);
      } else {
        java.nio.file.Files.createLink(targetPath, sourcePath);
      }
    } else {
      java.nio.file.Files.createSymbolicLink(targetPath, sourcePath);
    }
  }

  /**
   * Takes a sequence of paths relative to the project root and writes a zip file to {@code out}
   * with the contents and structure that matches that of the specified paths.
   */
  public void createZip(Iterable<Path> pathsToIncludeInZip, File out) throws IOException {
    Preconditions.checkState(!Iterables.isEmpty(pathsToIncludeInZip));
    try (CustomZipOutputStream zip = ZipOutputStreams.newOutputStream(out)) {
      for (Path path : pathsToIncludeInZip) {
        ZipEntry entry = new ZipEntry(path.toString());
        zip.putNextEntry(entry);
        try (InputStream input = new FileInputStream(getFileForRelativePath(path))) {
          ByteStreams.copy(input, zip);
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
    return event.context().toString();
  }
}
