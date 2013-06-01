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

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitor;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;

/**
 * An injectable service for interacting with the filesystem.
 */
public class ProjectFilesystem {

  private final File projectRoot;

  public ProjectFilesystem(File projectRoot) {
    this.projectRoot = Preconditions.checkNotNull(projectRoot);
    Preconditions.checkArgument(projectRoot.isDirectory());
  }

  public File getProjectRoot() {
    return projectRoot;
  }

  public File getFileForRelativePath(String pathRelativeToProjectRoot) {
    return pathRelativeToProjectRoot.isEmpty()
        ? projectRoot
        : new File(projectRoot, pathRelativeToProjectRoot);
  }

  public boolean exists(String pathRelativeToProjectRoot) {
    return getFileForRelativePath(pathRelativeToProjectRoot).exists();
  }

  public boolean isMatchingFileContents(Iterable<String> lines, String pathRelativeToProjectRoot)
      throws IOException {
    return MoreFiles.isMatchingFileContents(lines,
        getFileForRelativePath(pathRelativeToProjectRoot));
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
    return new File(pathRelativeToProjectRoot).isFile();
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
  public boolean isDirectory(Path child, LinkOption linkOption) {
    return java.nio.file.Files.isDirectory(child, linkOption);
  }

  /**
   * Recursively delete everything under the specified path.
   */
  public void rmdir(String path, ProcessExecutor processExecutor) throws IOException {
    // TODO(mbolin): Reimplement this so it no longer requires a processExecutor using the
    // SimpleFileVisitor introduced in Java 7.
    MoreFiles.rmdir(path, processExecutor);
  }

  public void createParentDirs(File file) throws IOException {
    Files.createParentDirs(file);
  }

  public void writeLinesToPath(Iterable<String> lines, String pathToFile)
      throws IOException {
    MoreFiles.writeLinesToFile(lines, pathToFile);
  }

  public Optional<File> getFileIfExists(String path) {
    File file = new File(path);
    if (file.exists()) {
      return Optional.of(file);
    } else {
      return Optional.absent();
    }
  }
}
