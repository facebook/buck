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

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.file.PathListing;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemDelegate;
import com.facebook.buck.io.filesystem.ProjectFilesystemDelegatePair;
import com.facebook.buck.io.filesystem.RecursiveFileMatcher;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.jar.Manifest;

/** An injectable service for interacting with the filesystem relative to the project root. */
public class DefaultProjectFilesystem implements Cloneable, ProjectFilesystem {

  private final AbsPath projectRoot;

  private final Path edenMagicPathElement;

  private final BuckPaths buckPaths;
  /** Supplier that returns an absolute path that is guaranteed to exist. */
  private final Supplier<Path> tmpDir;

  private final ImmutableSet<PathMatcher> ignoredPaths;
  private final ImmutableSet<PathMatcher> ignoredDirectories;

  private ProjectFilesystemDelegate delegate;
  private final ProjectFilesystemDelegatePair delegatePair;

  /** This function should be only used in tests, because it ignores hashes-in-path buckconfig. */
  @VisibleForTesting
  protected DefaultProjectFilesystem(
      CanonicalCellName cellName,
      AbsPath root,
      ProjectFilesystemDelegate delegate,
      boolean buckOutIncludeTargetConfigHash) {
    this(
        root,
        ImmutableSet.of(),
        BuckPaths.createDefaultBuckPaths(cellName, root.getPath(), buckOutIncludeTargetConfigHash),
        delegate,
        new ProjectFilesystemDelegatePair(delegate, delegate));
  }

  public DefaultProjectFilesystem(
      AbsPath root,
      ImmutableSet<PathMatcher> ignoredPaths,
      BuckPaths buckPaths,
      ProjectFilesystemDelegate delegate) {
    this(
        root,
        ignoredPaths,
        buckPaths,
        delegate,
        new ProjectFilesystemDelegatePair(delegate, delegate));
  }

  DefaultProjectFilesystem(
      AbsPath root,
      ImmutableSet<PathMatcher> ignoredPaths,
      BuckPaths buckPaths,
      ProjectFilesystemDelegate delegate,
      ProjectFilesystemDelegatePair delegatePair) {
    this.projectRoot = MorePaths.normalize(root);
    if (shouldVerifyConstructorArguments()) {
      Preconditions.checkArgument(
          Files.isDirectory(projectRoot.getPath()), "%s must be a directory", projectRoot);
    }
    this.delegate = delegate;
    this.delegatePair = delegatePair;
    this.buckPaths = buckPaths;

    String cacheDirString = buckPaths.getCacheDir().toString();
    Path buckOutPath = buckPaths.getBuckOut().getPath();
    Path rootPath = root.getPath();
    Path trashDir = buckPaths.getTrashDir();

    this.ignoredPaths =
        FluentIterable.from(ignoredPaths)
            .append(
                FluentIterable.from(
                        // "Path" is Iterable, so avoid adding each segment.
                        // We use the default value here because that's what we've always done.
                        MorePaths.filterForSubpaths(
                            ImmutableSet.of(
                                getCacheDir(rootPath, Optional.of(cacheDirString), buckPaths)),
                            rootPath))
                    .append(ImmutableSet.of(trashDir))
                    .transform(basePath -> RecursiveFileMatcher.of(RelPath.of(basePath))))
            .toSet();

    this.ignoredDirectories =
        FluentIterable.from(this.ignoredPaths)
            .filter(RecursiveFileMatcher.class)
            .transform(
                matcher -> {
                  Path path = matcher.getPath().getPath();
                  ImmutableSet<Path> filtered =
                      MorePaths.filterForSubpaths(ImmutableSet.of(path), rootPath);
                  if (filtered.isEmpty()) {
                    return path;
                  }
                  return Iterables.getOnlyElement(filtered);
                })
            // TODO(#10068334) So we claim to ignore this path to preserve existing behaviour, but
            // we really don't end up ignoring it in reality (see extractIgnorePaths).
            .append(ImmutableSet.of(buckOutPath))
            .transform(basePath -> RecursiveFileMatcher.of(RelPath.of(basePath)))
            .transform(matcher -> (PathMatcher) matcher)
            .append(
                // RecursiveFileMatcher instances are handled separately above because they all
                // must be relative to the project root, but all other matchers are not relative
                // to the root and do not require any special treatment.
                Iterables.filter(
                    this.ignoredPaths, matcher -> !(matcher instanceof RecursiveFileMatcher)))
            .toSet();
    this.tmpDir =
        MoreSuppliers.memoize(
            () -> {
              Path relativeTmpDir = buckPaths.getTmpDir();
              try {
                mkdirs(relativeTmpDir);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
              return relativeTmpDir;
            });
    this.edenMagicPathElement = getPath(".eden");
  }

  static Path getCacheDir(Path root, Optional<String> value, BuckPaths buckPaths) {
    String cacheDir = value.orElse(root.resolve(buckPaths.getCacheDir()).toString());
    Path toReturn = root.getFileSystem().getPath(cacheDir);
    toReturn = MorePaths.expandHomeDir(toReturn);
    if (toReturn.isAbsolute()) {
      return toReturn;
    }
    ImmutableSet<Path> filtered = MorePaths.filterForSubpaths(ImmutableSet.of(toReturn), root);
    if (filtered.isEmpty()) {
      // OK. For some reason the relative path managed to be out of our directory.
      return toReturn;
    }
    return Iterables.getOnlyElement(filtered);
  }

  protected boolean shouldIgnoreValidityOfPaths() {
    return false;
  }

  /**
   * For testing purposes, subclasses might want to skip some of the verification done by the
   * constructor on its arguments.
   */
  protected boolean shouldVerifyConstructorArguments() {
    return true;
  }

  @Override
  public DefaultProjectFilesystem clone() throws CloneNotSupportedException {
    return (DefaultProjectFilesystem) super.clone();
  }

  protected DefaultProjectFilesystem useBuckOutProjectDelegate() {
    delegate = delegatePair.getBuckOutDelegate();
    return this;
  }

  @Override
  public DefaultProjectFilesystem createBuckOutProjectFilesystem() {
    // This method is used to generate the proper delegate for a buck-out path at creation time of
    // the FileHashCache. This avoids having to check the path on each lookup to determine if we're
    // in a buck-out path.
    try {
      // If the delegate is already the proper one for a buck-out path, then we don't need to do
      // anything here.
      if (delegate == delegatePair.getBuckOutDelegate()) {
        return this;
      }
      // Otherwise, we need to make sure the delegate is set properly. The DefaultProjectFilesystem
      // may be used in other places, so we must clone the object so we don't set the delegate to
      // the buck-out delegate when the filesystem may be used in non-buck-out cells.
      return clone().useBuckOutProjectDelegate();
    } catch (CloneNotSupportedException e) {
      throw new UnsupportedOperationException(e);
    }
  }

  @Override
  public DefaultProjectFilesystemView asView() {
    return new DefaultProjectFilesystemView(
        this, getPath(""), projectRoot.getPath(), ImmutableMap.of());
  }

  @Override
  public final AbsPath getRootPath() {
    return projectRoot;
  }

  @Override
  public ImmutableMap<String, ?> getDelegateDetails() {
    return delegate.getDetailsForLogging();
  }

  public ProjectFilesystemDelegate getDelegate() {
    return delegate;
  }

  /**
   * @return the specified {@code path} resolved against {@link #getRootPath()} to an absolute path.
   */
  @Override
  public Path resolve(Path path) {
    return ProjectFilesystemUtils.getPathForRelativePath(projectRoot, path);
  }

  @Override
  public AbsPath resolve(String path) {
    return ProjectFilesystemUtils.getPathForRelativePath(projectRoot, path);
  }

  /** Construct a relative path between the project root and a given path. */
  @Override
  public RelPath relativize(Path path) {
    return ProjectFilesystemUtils.relativize(projectRoot, path);
  }

  @Override
  public ImmutableSet<PathMatcher> getIgnoredPaths() {
    return ignoredPaths;
  }

  /** @return A {@link ImmutableSet} of {@link PathMatcher} objects to have buck ignore. */
  @Override
  public ImmutableSet<PathMatcher> getIgnoredDirectories() {
    return ignoredDirectories;
  }

  @Override
  public Path getPathForRelativePath(Path pathRelativeToProjectRoot) {
    return ProjectFilesystemUtils.getPathForRelativePath(projectRoot, pathRelativeToProjectRoot);
  }

  @Override
  public AbsPath getPathForRelativePath(String pathRelativeToProjectRoot) {
    return ProjectFilesystemUtils.getPathForRelativePath(projectRoot, pathRelativeToProjectRoot);
  }

  /**
   * @param path Absolute path or path relative to the project root.
   * @return If {@code path} is relative, it is returned. If it is absolute and is inside the
   *     project root, it is relativized to the project root and returned. Otherwise an absent value
   *     is returned.
   */
  @Override
  public Optional<Path> getPathRelativeToProjectRoot(Path path) {
    return ProjectFilesystemUtils.getPathRelativeToProjectRoot(
        projectRoot, buckPaths.getConfiguredBuckOut(), path);
  }

  @Override
  public boolean exists(Path pathRelativeToProjectRoot, LinkOption... options) {
    return ProjectFilesystemUtils.exists(projectRoot, pathRelativeToProjectRoot, options);
  }

  @Override
  public long getFileSize(Path pathRelativeToProjectRoot) throws IOException {
    return ProjectFilesystemUtils.getFileSize(projectRoot, pathRelativeToProjectRoot);
  }

  /**
   * Deletes a file specified by its path relative to the project root.
   *
   * <p>Ignores the failure if the file does not exist.
   *
   * @param pathRelativeToProjectRoot path to the file
   * @return {@code true} if the file was deleted, {@code false} if it did not exist
   */
  @Override
  public boolean deleteFileAtPathIfExists(Path pathRelativeToProjectRoot) throws IOException {
    return ProjectFilesystemUtils.deleteFileAtPathIfExists(projectRoot, pathRelativeToProjectRoot);
  }

  /**
   * Deletes a file specified by its path relative to the project root.
   *
   * @param pathRelativeToProjectRoot path to the file
   */
  @Override
  public void deleteFileAtPath(Path pathRelativeToProjectRoot) throws IOException {
    ProjectFilesystemUtils.deleteFileAtPath(projectRoot, pathRelativeToProjectRoot);
  }

  @Override
  public Properties readPropertiesFile(Path propertiesFile) throws IOException {
    return ProjectFilesystemUtils.readPropertiesFile(projectRoot, propertiesFile);
  }

  /** Checks whether there is a normal file at the specified path. */
  @Override
  public boolean isFile(Path pathRelativeToProjectRoot, LinkOption... options) {
    return ProjectFilesystemUtils.isFile(projectRoot, pathRelativeToProjectRoot, options);
  }

  @Override
  public boolean isHidden(Path pathRelativeToProjectRoot) throws IOException {
    return ProjectFilesystemUtils.isHidden(projectRoot, pathRelativeToProjectRoot);
  }

  /** Walks a project-root relative file tree with a visitor and visit options. */
  @Override
  public void walkRelativeFileTree(
      Path pathRelativeToProjectRoot,
      EnumSet<FileVisitOption> visitOptions,
      FileVisitor<Path> fileVisitor,
      boolean skipIgnored)
      throws IOException {
    DirectoryStream.Filter<? super Path> ignoreFilter =
        ProjectFilesystemUtils.getIgnoreFilter(projectRoot, skipIgnored, getIgnoredPaths());
    ProjectFilesystemUtils.walkRelativeFileTree(
        projectRoot,
        edenMagicPathElement,
        pathRelativeToProjectRoot,
        visitOptions,
        fileVisitor,
        ignoreFilter);
  }

  void walkFileTreeWithPathMapping(
      Path root,
      EnumSet<FileVisitOption> visitOptions,
      FileVisitor<Path> fileVisitor,
      DirectoryStream.Filter<? super Path> ignoreFilter,
      Function<Path, Path> pathMapper)
      throws IOException {
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
    walkFileTree(root, visitOptions, pathMappingVisitor, ignoreFilter);
  }

  @Override
  public void walkFileTree(Path root, Set<FileVisitOption> options, FileVisitor<Path> fileVisitor)
      throws IOException {
    walkFileTree(
        root,
        options,
        fileVisitor,
        ProjectFilesystemUtils.getIgnoreFilter(projectRoot, true, getIgnoredPaths()));
  }

  /**
   * Similar to {@link #walkFileTree(Path, FileVisitor)} except this takes in a path relative to the
   * project root.
   */
  @Override
  public void walkRelativeFileTree(Path pathRelativeToProjectRoot, FileVisitor<Path> fileVisitor)
      throws IOException {
    walkRelativeFileTree(
        pathRelativeToProjectRoot, EnumSet.of(FileVisitOption.FOLLOW_LINKS), fileVisitor, true);
  }

  @Override
  public void walkRelativeFileTree(
      Path pathRelativeToProjectRoot, FileVisitor<Path> fileVisitor, boolean skipIgnored)
      throws IOException {
    walkRelativeFileTree(
        pathRelativeToProjectRoot,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS),
        fileVisitor,
        skipIgnored);
  }

  /** Allows {@link Files#walkFileTree} to be faked in tests. */
  @Override
  public void walkFileTree(Path root, FileVisitor<Path> fileVisitor) throws IOException {
    walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), fileVisitor);
  }

  void walkFileTree(
      Path root,
      Set<FileVisitOption> options,
      FileVisitor<Path> fileVisitor,
      DirectoryStream.Filter<? super Path> ignoreFilter)
      throws IOException {
    ProjectFilesystemUtils.walkFileTree(projectRoot, root, options, fileVisitor, ignoreFilter);
  }

  @Override
  public ImmutableSet<Path> getFilesUnderPath(Path pathRelativeToProjectRoot) throws IOException {
    return getFilesUnderPath(pathRelativeToProjectRoot, x -> true);
  }

  @Override
  public ImmutableSet<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot, Predicate<Path> predicate) throws IOException {
    return getFilesUnderPath(
        pathRelativeToProjectRoot, predicate, EnumSet.of(FileVisitOption.FOLLOW_LINKS));
  }

  @Override
  public ImmutableSet<Path> getFilesUnderPath(
      Path pathRelativeToProjectRoot,
      Predicate<Path> predicate,
      EnumSet<FileVisitOption> visitOptions)
      throws IOException {
    ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    walkRelativeFileTree(
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
        true);
    return paths.build();
  }

  @Override
  public ImmutableCollection<Path> getDirectoryContents(Path pathToUse) throws IOException {
    return ProjectFilesystemUtils.getDirectoryContents(projectRoot, getIgnoredPaths(), pathToUse);
  }

  /** Allows {@link Files#isDirectory} to be faked in tests. */
  @Override
  public boolean isDirectory(Path child, LinkOption... linkOptions) {
    return ProjectFilesystemUtils.isDirectory(projectRoot, child, linkOptions);
  }

  /** Allows {@link Files#isExecutable} to be faked in tests. */
  @Override
  public boolean isExecutable(Path child) {
    return ProjectFilesystemUtils.isExecutable(projectRoot, child);
  }

  /** @return returns sorted absolute paths of everything under the given directory */
  DirectoryStream<Path> getDirectoryContentsStream(Path absolutePath) throws IOException {
    return Files.newDirectoryStream(absolutePath);
  }

  @VisibleForTesting
  protected PathListing.PathModifiedTimeFetcher getLastModifiedTimeFetcher() {
    return this::getLastModifiedTime;
  }

  /**
   * Returns the files inside {@code pathRelativeToProjectRoot} which match {@code globPattern},
   * ordered in descending last modified time order. This will not obey the results of {@link
   * ProjectFilesystem#isIgnored(RelPath)}.
   */
  @Override
  public ImmutableSortedSet<Path> getMtimeSortedMatchingDirectoryContents(
      Path pathRelativeToProjectRoot, String globPattern) throws IOException {
    return ProjectFilesystemUtils.getMtimeSortedMatchingDirectoryContents(
        projectRoot, pathRelativeToProjectRoot, globPattern);
  }

  @Override
  public FileTime getLastModifiedTime(Path pathRelativeToProjectRoot) throws IOException {
    return ProjectFilesystemUtils.getLastModifiedTime(projectRoot, pathRelativeToProjectRoot);
  }

  /** Sets the last modified time for the given path. */
  @Override
  public Path setLastModifiedTime(Path pathRelativeToProjectRoot, FileTime time)
      throws IOException {
    return ProjectFilesystemUtils.setLastModifiedTime(projectRoot, pathRelativeToProjectRoot, time);
  }

  /**
   * Recursively delete everything under the specified path. Ignore the failure if the file at the
   * specified path does not exist.
   */
  @Override
  public void deleteRecursivelyIfExists(Path pathRelativeToProjectRoot) throws IOException {
    ProjectFilesystemUtils.deleteRecursivelyIfExists(projectRoot, pathRelativeToProjectRoot);
  }

  /**
   * Resolves the relative path against the project root and then calls {@link
   * Files#createDirectories(Path, FileAttribute[])}
   */
  @Override
  public void mkdirs(Path pathRelativeToProjectRoot) throws IOException {
    ProjectFilesystemUtils.mkdirs(projectRoot, pathRelativeToProjectRoot);
  }

  /** Creates a new file relative to the project root. */
  @Override
  public Path createNewFile(Path pathRelativeToProjectRoot) throws IOException {
    return ProjectFilesystemUtils.createNewFile(projectRoot, pathRelativeToProjectRoot);
  }

  /**
   * @deprecated Prefer operating on {@code Path}s directly, replaced by {@link
   *     #createParentDirs(Path)}.
   */
  @Deprecated
  @Override
  public void createParentDirs(String pathRelativeToProjectRoot) throws IOException {
    AbsPath file =
        ProjectFilesystemUtils.getPathForRelativePath(projectRoot, pathRelativeToProjectRoot);
    mkdirs(file.getParent().getPath());
  }

  /**
   * @param pathRelativeToProjectRoot Must identify a file, not a directory. (Unfortunately, we have
   *     no way to assert this because the path is not expected to exist yet.)
   */
  @Override
  public void createParentDirs(Path pathRelativeToProjectRoot) throws IOException {
    Path directory =
        ProjectFilesystemUtils.getPathForRelativePath(projectRoot, pathRelativeToProjectRoot)
            .getParent();
    mkdirs(directory);
  }

  /**
   * Writes each line in {@code lines} with a trailing newline to a file at the specified path.
   *
   * <p>The parent path of {@code pathRelativeToProjectRoot} must exist.
   */
  @Override
  public void writeLinesToPath(
      Iterable<String> lines, Path pathRelativeToProjectRoot, FileAttribute<?>... attrs)
      throws IOException {
    ProjectFilesystemUtils.writeLinesToPath(projectRoot, lines, pathRelativeToProjectRoot, attrs);
  }

  @Override
  public void writeContentsToPath(
      String contents, Path pathRelativeToProjectRoot, FileAttribute<?>... attrs)
      throws IOException {
    ProjectFilesystemUtils.writeContentsToPath(
        projectRoot, contents, pathRelativeToProjectRoot, attrs);
  }

  @Override
  public void writeBytesToPath(
      byte[] bytes, Path pathRelativeToProjectRoot, FileAttribute<?>... attrs) throws IOException {
    ProjectFilesystemUtils.writeBytesToPath(projectRoot, bytes, pathRelativeToProjectRoot, attrs);
  }

  @Override
  public OutputStream newFileOutputStream(Path pathRelativeToProjectRoot, FileAttribute<?>... attrs)
      throws IOException {
    return ProjectFilesystemUtils.newFileOutputStream(
        projectRoot, pathRelativeToProjectRoot, attrs);
  }

  @Override
  public OutputStream newFileOutputStream(
      Path pathRelativeToProjectRoot, boolean append, FileAttribute<?>... attrs)
      throws IOException {
    return ProjectFilesystemUtils.newFileOutputStream(
        projectRoot, pathRelativeToProjectRoot, append, attrs);
  }

  @Override
  public OutputStream newUnbufferedFileOutputStream(
      Path pathRelativeToProjectRoot, boolean append, FileAttribute<?>... attrs)
      throws IOException {
    return ProjectFilesystemUtils.newUnbufferedFileOutputStream(
        projectRoot, pathRelativeToProjectRoot, append, attrs);
  }

  @Override
  public <A extends BasicFileAttributes> A readAttributes(
      Path pathRelativeToProjectRoot, Class<A> type, LinkOption... options) throws IOException {
    return ProjectFilesystemUtils.readAttributes(
        projectRoot, pathRelativeToProjectRoot, type, options);
  }

  @Override
  public InputStream newFileInputStream(Path pathRelativeToProjectRoot) throws IOException {
    return ProjectFilesystemUtils.newFileInputStream(projectRoot, pathRelativeToProjectRoot);
  }

  /** @param inputStream Source of the bytes. This method does not close this stream. */
  @Override
  public void copyToPath(
      InputStream inputStream, Path pathRelativeToProjectRoot, CopyOption... options)
      throws IOException {
    ProjectFilesystemUtils.copyToPath(projectRoot, inputStream, pathRelativeToProjectRoot, options);
  }

  /** Copies a file to an output stream. */
  @Override
  public void copyToOutputStream(Path pathRelativeToProjectRoot, OutputStream out)
      throws IOException {
    ProjectFilesystemUtils.copyToOutputStream(projectRoot, pathRelativeToProjectRoot, out);
  }

  @Override
  public Optional<String> readFileIfItExists(Path pathRelativeToProjectRoot) {
    return ProjectFilesystemUtils.readFileIfItExists(projectRoot, pathRelativeToProjectRoot);
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#empty()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   *
   * <p>@deprecated PRefero operation on {@code Path}s directly, replaced by {@link
   * #readFirstLine(Path)}
   */
  @Override
  public Optional<String> readFirstLine(String pathRelativeToProjectRoot) {
    return ProjectFilesystemUtils.readFirstLine(projectRoot, pathRelativeToProjectRoot);
  }

  /**
   * Attempts to read the first line of the file specified by the relative path. If the file does
   * not exist, is empty, or encounters an error while being read, {@link Optional#empty()} is
   * returned. Otherwise, an {@link Optional} with the first line of the file will be returned.
   */
  @Override
  public Optional<String> readFirstLine(Path pathRelativeToProjectRoot) {
    return ProjectFilesystemUtils.readFirstLine(projectRoot, pathRelativeToProjectRoot);
  }

  /**
   * Attempts to read the first line of the specified file. If the file does not exist, is empty, or
   * encounters an error while being read, {@link Optional#empty()} is returned. Otherwise, an
   * {@link Optional} with the first line of the file will be returned.
   */
  @Override
  public Optional<String> readFirstLineFromFile(Path file) {
    return ProjectFilesystemUtils.readFirstLineFromFile(file);
  }

  @Override
  public List<String> readLines(Path pathRelativeToProjectRoot) throws IOException {
    return ProjectFilesystemUtils.readLines(projectRoot, pathRelativeToProjectRoot);
  }

  /**
   * @deprecated Prefer operation on {@code Path}s directly, replaced by {@link
   *     Files#newInputStream(Path, java.nio.file.OpenOption...)}.
   */
  @Deprecated
  @Override
  public InputStream getInputStreamForRelativePath(Path path) throws IOException {
    return ProjectFilesystemUtils.getInputStreamForRelativePath(projectRoot, path);
  }

  @Override
  public Sha1HashCode computeSha1(Path pathRelativeToProjectRootOrJustAbsolute) throws IOException {
    return delegate.computeSha1(pathRelativeToProjectRootOrJustAbsolute);
  }

  @Override
  public String computeSha256(Path pathRelativeToProjectRoot) throws IOException {
    Path fileToHash = getPathForRelativePath(pathRelativeToProjectRoot);
    return Hashing.sha256().hashBytes(Files.readAllBytes(fileToHash)).toString();
  }

  @Override
  public void copy(Path source, Path target, CopySourceMode sourceMode) throws IOException {
    ProjectFilesystemUtils.copy(projectRoot, source, target, sourceMode);
  }

  @Override
  public void move(Path source, Path target, CopyOption... options) throws IOException {
    ProjectFilesystemUtils.move(projectRoot, source, target, options);
  }

  @Override
  public void mergeChildren(Path source, Path target, CopyOption... options) throws IOException {
    ProjectFilesystemUtils.mergeChildren(projectRoot, getIgnoredPaths(), source, target, options);
  }

  @Override
  public void copyFolder(Path source, Path target) throws IOException {
    ProjectFilesystemUtils.copyFolder(projectRoot, source, target);
  }

  @Override
  public void copyFile(Path source, Path target) throws IOException {
    ProjectFilesystemUtils.copyFile(projectRoot, source, target);
  }

  @Override
  public void createSymLink(Path symLink, Path realFile, boolean force) throws IOException {
    ProjectFilesystemUtils.createSymLink(projectRoot, symLink, realFile, force);
  }

  /**
   * Returns the set of POSIX file permissions, or the empty set if the underlying file system does
   * not support POSIX file attributes.
   */
  @Override
  public Set<PosixFilePermission> getPosixFilePermissions(Path path) throws IOException {
    return ProjectFilesystemUtils.getPosixFilePermissions(projectRoot, path);
  }

  /** Returns true if the file under {@code path} exists and is a symbolic link, false otherwise. */
  @Override
  public boolean isSymLink(Path path) {
    return ProjectFilesystemUtils.isSymLink(projectRoot, path);
  }

  /** Returns the target of the specified symbolic link. */
  @Override
  public Path readSymLink(Path path) throws IOException {
    return ProjectFilesystemUtils.readSymLink(projectRoot, path);
  }

  @Override
  public Manifest getJarManifest(Path path) throws IOException {
    return ProjectFilesystemUtils.getJarManifest(projectRoot, path);
  }

  @Override
  public long getPosixFileMode(Path path) throws IOException {
    return ProjectFilesystemUtils.getPosixFileModes(projectRoot, path);
  }

  @Override
  public long getFileAttributesForZipEntry(Path path) throws IOException {
    return getPosixFileMode(path) << 16;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof DefaultProjectFilesystem)) {
      return false;
    }

    DefaultProjectFilesystem that = (DefaultProjectFilesystem) other;
    if (!Objects.equals(projectRoot, that.projectRoot)) {
      return false;
    }

    return Objects.equals(ignoredPaths, that.ignoredPaths);
  }

  @Override
  public String toString() {
    return String.format(
        "%s (projectRoot=%s, hash(ignoredPaths)=%s)",
        super.toString(), projectRoot, ignoredPaths.hashCode());
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectRoot, ignoredPaths);
  }

  @Override
  public BuckPaths getBuckPaths() {
    return buckPaths;
  }

  /**
   * @param path the path to check.
   * @return whether ignoredPaths contains path or any of its ancestors.
   */
  @Override
  public boolean isIgnored(RelPath path) {
    return ProjectFilesystemUtils.isIgnored(path, getIgnoredPaths());
  }

  /**
   * Returns a relative path whose parent directory is guaranteed to exist. The path will be under
   * {@code buck-out}, so it is safe to write to.
   */
  @Override
  public Path createTempFile(String prefix, String suffix, FileAttribute<?>... attrs)
      throws IOException {
    return createTempFile(tmpDir.get(), prefix, suffix, attrs);
  }

  /**
   * Prefer {@link #createTempFile(String, String, FileAttribute[])} so that temporary files are
   * guaranteed to be created under {@code buck-out}. This method will be deprecated once t12079608
   * is resolved.
   */
  @Override
  public Path createTempFile(
      Path directory, String prefix, String suffix, FileAttribute<?>... attrs) throws IOException {
    return ProjectFilesystemUtils.createTempFile(
        projectRoot, getBuckPaths().getConfiguredBuckOut(), directory, prefix, suffix, attrs);
  }

  @Override
  public void touch(Path fileToTouch) throws IOException {
    ProjectFilesystemUtils.touch(projectRoot, fileToTouch);
  }

  /**
   * Converts a path string (or sequence of strings) to a Path with the same VFS as this instance.
   */
  @Override
  public Path getPath(String first, String... rest) {
    return ProjectFilesystemUtils.getPath(projectRoot, first, rest);
  }

  /**
   * As {@link #getPathForRelativePath(Path)}, but with the added twist that the existence of the
   * path is checked before returning.
   */
  @Override
  public Path getPathForRelativeExistingPath(Path pathRelativeToProjectRoot) {
    if (shouldIgnoreValidityOfPaths()) {
      return ProjectFilesystemUtils.getPathForRelativePath(projectRoot, pathRelativeToProjectRoot);
    }

    return ProjectFilesystemUtils.getPathForRelativeExistingPath(
        projectRoot, pathRelativeToProjectRoot);
  }
}
