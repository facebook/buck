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

package com.facebook.buck.io.filesystem.skylark;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.MoreCollectors;
import com.google.devtools.build.lib.vfs.AbstractFileSystemWithCustomStat;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Collection;

/**
 * {@link FileSystem} implementation that uses underlying {@link ProjectFilesystem} to resolve
 * {@link Path}s to {@link java.nio.file.Path} and perform most of the operations by delegation.
 *
 * <p>Skylark uses its own {@link FileSystem} API, which operates on {@link Path} abstraction not
 * used anywhere else in Buck, but it should be based on {@link ProjectFilesystem} for
 * interoperability.
 *
 * <p>Ideally {@link com.google.devtools.build.lib.vfs.JavaIoFileSystem} should be extended, but
 * unfortunately it resolves all paths to {@link java.io.File} instead of {@link java.nio.file.Path}
 * which means that it wouldn't play nicely with in-memory {@link ProjectFilesystem}.
 *
 * <p>Since every method has to resolve {@link Path} into {@link java.nio.file.Path}, it might
 * become expensive or cause excessive allocations, so caching might be beneficial.
 */
public class SkylarkFilesystem extends AbstractFileSystemWithCustomStat {
  private static final LinkOption[] NO_LINK_OPTION = new LinkOption[0];
  // This isn't generally safe; we rely on the file system APIs not modifying the array.
  private static final LinkOption[] NO_FOLLOW_LINKS_OPTION =
      new LinkOption[] {LinkOption.NOFOLLOW_LINKS};

  private final ProjectFilesystem filesystem;

  private SkylarkFilesystem(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  @Override
  public boolean supportsModifications() {
    return true;
  }

  @Override
  public boolean supportsSymbolicLinksNatively() {
    return true;
  }

  @Override
  protected boolean supportsHardLinksNatively() {
    return true;
  }

  @Override
  public boolean isFilePathCaseSensitive() {
    return true;
  }

  @Override
  protected boolean createDirectory(Path path) throws IOException {
    try {
      Files.createDirectory(toJavaPath(path));
      return true;
    } catch (FileAlreadyExistsException e) {
      return false;
    }
  }

  @Override
  protected long getFileSize(Path path, boolean followSymlinks) throws IOException {
    // TODO(ttsugrii): take followSymlinks into account
    return filesystem.getFileSize(toJavaPath(path));
  }

  @Override
  protected boolean delete(Path path) throws IOException {
    return filesystem.deleteFileAtPathIfExists(toJavaPath(path));
  }

  @Override
  protected long getLastModifiedTime(Path path, boolean followSymlinks) throws IOException {
    // TODO(ttsugrii): take followSymlinks into account
    return filesystem.getLastModifiedTime(toJavaPath(path)).toMillis();
  }

  @Override
  protected void setLastModifiedTime(Path path, long newTime) throws IOException {
    filesystem.setLastModifiedTime(toJavaPath(path), FileTime.fromMillis(newTime));
  }

  @Override
  protected void createSymbolicLink(Path linkPath, PathFragment targetFragment) throws IOException {
    filesystem.createSymLink(
        toJavaPath(linkPath), filesystem.resolve(targetFragment.getPathString()), /* force */ true);
  }

  @Override
  protected PathFragment readSymbolicLink(Path path) throws IOException {
    return PathFragment.create(filesystem.readSymLink(toJavaPath(path)).toString());
  }

  @Override
  protected boolean exists(Path path, boolean followSymlinks) {
    return filesystem.exists(toJavaPath(path), toLinkOptions(followSymlinks));
  }

  @Override
  protected Collection<Path> getDirectoryEntries(Path path) throws IOException {
    return Files.list(toJavaPath(path))
        .map(p -> getPath(p.toString()))
        .collect(MoreCollectors.toImmutableList());
  }

  /** @return The {@link java.nio.file.Path} that corresponds to {@code path}. */
  private java.nio.file.Path toJavaPath(Path path) {
    return filesystem.resolve(path.toString());
  }

  @Override
  protected boolean isReadable(Path path) throws IOException {
    return Files.isReadable(toJavaPath(path));
  }

  @Override
  protected void setReadable(Path path, boolean followSymlinks) throws IOException {
    // TODO(ttsugrii): take followSymlinks into account
    toJavaPath(path).toFile().setReadable(true);
  }

  @Override
  protected boolean isWritable(Path path) throws IOException {
    return Files.isWritable(toJavaPath(path));
  }

  @Override
  protected void setWritable(Path path, boolean followSymlinks) throws IOException {
    // TODO(ttsugrii): take followSymlinks into account
    toJavaPath(path).toFile().setWritable(true);
  }

  @Override
  protected boolean isExecutable(Path path) throws IOException {
    return filesystem.isExecutable(toJavaPath(path));
  }

  @Override
  protected void setExecutable(Path path, boolean followSymlinks) throws IOException {
    // TODO(ttsugrii): take followSymlinks into account
    toJavaPath(path).toFile().setExecutable(true);
  }

  @Override
  protected InputStream getInputStream(Path path) throws IOException {
    return filesystem.newFileInputStream(toJavaPath(path));
  }

  @Override
  protected OutputStream getOutputStream(Path path, boolean followSymlinks) throws IOException {
    // TODO(ttsugrii): take followSymlinks into account
    return filesystem.newFileOutputStream(toJavaPath(path));
  }

  @Override
  protected void renameTo(Path sourcePath, Path targetPath) throws IOException {
    Files.move(toJavaPath(sourcePath), toJavaPath(targetPath));
  }

  @Override
  protected void createFSDependentHardLink(Path linkPath, Path originalPath) throws IOException {
    Files.createSymbolicLink(toJavaPath(linkPath), toJavaPath(originalPath));
  }

  private LinkOption[] toLinkOptions(boolean followSymlinks) {
    return followSymlinks ? NO_LINK_OPTION : NO_FOLLOW_LINKS_OPTION;
  }

  @Override
  protected FileStatus stat(final Path path, final boolean followSymlinks) throws IOException {
    final BasicFileAttributes attributes;
    attributes =
        Files.readAttributes(
            toJavaPath(path), BasicFileAttributes.class, toLinkOptions(followSymlinks));

    return new FileStatus() {
      @Override
      public boolean isFile() {
        return attributes.isRegularFile() || isSpecialFile();
      }

      @Override
      public boolean isSpecialFile() {
        return attributes.isOther();
      }

      @Override
      public boolean isDirectory() {
        return attributes.isDirectory();
      }

      @Override
      public boolean isSymbolicLink() {
        return attributes.isSymbolicLink();
      }

      @Override
      public long getSize() throws IOException {
        return attributes.size();
      }

      @Override
      public long getLastModifiedTime() throws IOException {
        return attributes.lastModifiedTime().toMillis();
      }

      @Override
      public long getLastChangeTime() {
        return attributes.lastModifiedTime().toMillis();
      }

      @Override
      public long getNodeId() {
        return -1;
      }
    };
  }

  /** @return The {@link SkylarkFilesystem} which methods */
  public static SkylarkFilesystem using(ProjectFilesystem projectFilesystem) {
    return new SkylarkFilesystem(projectFilesystem);
  }
}
