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

package com.facebook.buck.step.fs;

import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Objects;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;

/** A step to merge the contents of provided directories into a symlink tree */
public class SymlinkTreeMergeStep implements Step {

  private final String name;
  private final ProjectFilesystem filesystem;
  private final Path root;
  private final SymlinkPaths links;
  private final BiFunction<ProjectFilesystem, Path, Boolean> deleteExistingLinkPredicate;

  /**
   * Creates an instance of {@link SymlinkTreeMergeStep}
   *
   * @param category The type of link tree that will be used. This is used in the name
   * @param filesystem The filesystem that the root resides on
   * @param root The root of the link tree
   * @param links A map of relative paths within the link tree into which files from the value will
   *     be recursively linked. e.g. if a file at /tmp/foo/bar should be linked as
   *     /tmp/symlink-root/subdir/bar, the map should contain {Paths.get("subdir"),
   * @param deleteExistingLinkPredicate A predicate that, given an existing filesystem and target of
   *     an existing symlink, can return 'true' if the original link should be deleted. This is used
   *     in the case that there are conflicting files when merging {@code links} into {@code root}.
   *     A common example is dummy __init__.py files placed by {@link
   *     com.facebook.buck.features.python.PythonInPlaceBinary} which may be deleted safely in the
   *     destination directory if one of the other directories being merged has a file with some
   *     substance.
   */
  public SymlinkTreeMergeStep(
      String category,
      ProjectFilesystem filesystem,
      Path root,
      SymlinkPaths links,
      BiFunction<ProjectFilesystem, Path, Boolean> deleteExistingLinkPredicate) {
    this.name = category + "_link_merge_dir";
    this.filesystem = filesystem;
    this.root = root;
    this.links = links;
    this.deleteExistingLinkPredicate = deleteExistingLinkPredicate;
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
    Set<Path> dirs = new HashSet<>();
    links.forEachSymlink(
        (relativePath, srcPath) -> {
          Path destPath = root.resolve(relativePath);
          if (destPath.getParent() != null) {
            if (dirs.add(destPath.getParent())) {
              filesystem.mkdirs(destPath.getParent());
            }
          }
          try {
            filesystem.createSymLink(filesystem.resolve(destPath), srcPath, false);
          } catch (FileAlreadyExistsException e) {
            if (filesystem.isSymLink(destPath)) {
              if (!filesystem.readSymLink(destPath).equals(srcPath)) {
                if (deleteExistingLinkPredicate.apply(filesystem, destPath)) {
                  filesystem.deleteFileAtPath(destPath);
                  filesystem.createSymLink(filesystem.resolve(destPath), srcPath, true);
                } else {
                  throw new HumanReadableException(
                      "Tried to link %s to %s, but %s already links to %s",
                      destPath, srcPath, destPath, filesystem.readSymLink(destPath));
                }
              }
            } else {
              throw new HumanReadableException(
                  "Tried to link %s to %s, but %s already exists", destPath, srcPath, destPath);
            }
          }
        });
    return StepExecutionResults.SUCCESS;
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
