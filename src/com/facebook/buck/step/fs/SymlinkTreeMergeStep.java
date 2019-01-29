/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMultimap;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map.Entry;

/** A step to merge the contents of provided directories into a symlink tree */
public class SymlinkTreeMergeStep implements Step {

  private final String name;
  private final ProjectFilesystem filesystem;
  private final Path root;
  private final ImmutableMultimap<Path, Path> links;

  /**
   * Creates an instance of {@link SymlinkTreeMergeStep}
   *
   * @param category The type of link tree that will be used. This is used in the name
   * @param filesystem The filesystem that the root resides on
   * @param root The root of the link tree
   * @param links A map of relative paths within the link tree into which files from the value will
   *     be recursively linked. e.g. if a file at /tmp/foo/bar should be linked as
   *     /tmp/symlink-root/subdir/bar, the map should contain {Paths.get("subdir"),
   */
  public SymlinkTreeMergeStep(
      String category,
      ProjectFilesystem filesystem,
      Path root,
      ImmutableMultimap<Path, Path> links) {
    this.name = category + "_link_merge_dir";
    this.filesystem = filesystem;
    this.root = root;
    this.links = links;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return getShortName() + " @ " + root;
  }

  @Override
  public String getShortName() {
    return name;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {

    for (Entry<Path, Path> sourceToRelative : links.entries()) {
      merge(sourceToRelative.getKey(), sourceToRelative.getValue());
    }
    return StepExecutionResults.SUCCESS;
  }

  private void merge(Path relativeDestination, Path dirPath) throws IOException {
    Path destination = root.resolve(relativeDestination);
    if (destination != dirPath) {
      filesystem.mkdirs(destination);
    }
    filesystem.walkFileTree(
        dirPath,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path childPath, BasicFileAttributes attrs)
              throws IOException {
            Path relativePath = dirPath.relativize(childPath);
            Path destPath = destination.resolve(relativePath);
            filesystem.mkdirs(destPath);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path childPath, BasicFileAttributes attrs)
              throws IOException {
            Path relativePath = dirPath.relativize(childPath);
            Path destPath = destination.resolve(relativePath);
            try {
              filesystem.createSymLink(filesystem.resolve(destPath), childPath, false);
            } catch (FileAlreadyExistsException e) {
              if (filesystem.isSymLink(destPath)) {
                throw new HumanReadableException(
                    "Tried to link %s to %s, but %s already links to %s",
                    destPath, childPath, destPath, filesystem.readSymLink(destPath));
              } else {
                throw new HumanReadableException(
                    "Tried to link %s to %s, but %s already exists", destPath, childPath, destPath);
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof SymlinkTreeMergeStep)) {
      return false;
    }
    SymlinkTreeMergeStep that = (SymlinkTreeMergeStep) obj;
    return Objects.equal(this.name, that.name)
        && Objects.equal(this.root, that.root)
        && Objects.equal(this.links, that.links);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(root, links);
  }
}
