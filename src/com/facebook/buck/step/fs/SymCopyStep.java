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

package com.facebook.buck.step.fs;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * This step copies the content of a directory tree through symlinks,
 * copying only directories.
 *
 * Assuming that ' on filename denotes a symbolic link to filename,
 * then a directory tree like this:
 * RootFolder/
 *  folderA/
 *    fileA
 *    fileB
 *  FolderB/
 *    fileC
 *
 * Will be copied on a DestinationFolder like this:
 * DestinationFolder/
 *  folderA/
 *    fileA'
 *    fileB'
 *  FolderB/
 *    fileC'
 */

public class SymCopyStep implements Step {

  private final ProjectFilesystem filesystem;
  private final ImmutableList<Path> roots;
  private final Path dest;

  public SymCopyStep(
      ProjectFilesystem filesystem,
      ImmutableList<Path> rootsRelativeToProjectRoot,
      Path dest) {
    this.filesystem = filesystem;
    this.roots = rootsRelativeToProjectRoot;
    this.dest = dest;
  }

  @Override
  public int execute(ExecutionContext context) throws IOException, InterruptedException {
    for (Path source : roots) {
      Preconditions.checkArgument(
          !source.isAbsolute() && filesystem.exists(source));
      filesystem.walkRelativeFileTree(
          source,
          new SymCopyFileVisitor(source, dest));
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return "lns";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Symlink-Copy step";
  }

  final class SymCopyFileVisitor extends SimpleFileVisitor<Path> {

    private final Path sourceRoot;
    private final Path destRoot;

    public SymCopyFileVisitor(Path sourceRoot, Path destRoot) {
      this.sourceRoot = sourceRoot;
      this.destRoot = destRoot;
    }

    @Override
    public FileVisitResult preVisitDirectory(
        Path dir, BasicFileAttributes attrs) throws IOException {
      Path relativeVisitedDir = sourceRoot.relativize(dir);
      Path newDir = destRoot.resolve(relativeVisitedDir);
      filesystem.mkdirs(newDir);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      Path relativeVisitedFile = sourceRoot.relativize(file);
      Path link = destRoot.resolve(relativeVisitedFile);
      filesystem.createSymLink(
          filesystem.resolve(link),
          filesystem.resolve(file),
          true);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
      throw new IOException("Failed while accessing " + file);
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
      return FileVisitResult.CONTINUE;
    }
  }
}
