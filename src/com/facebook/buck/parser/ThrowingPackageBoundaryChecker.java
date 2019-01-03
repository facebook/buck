/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildFileTree;
import com.facebook.buck.core.model.BuildTarget;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link PackageBoundaryChecker} implementation that throws an exception if any file in a set does
 * not belong to the same package as provided build target only if cell configuration allows that,
 * otherwise noop.
 */
public class ThrowingPackageBoundaryChecker implements PackageBoundaryChecker {

  private final LoadingCache<Cell, BuildFileTree> buildFileTrees;

  public ThrowingPackageBoundaryChecker(LoadingCache<Cell, BuildFileTree> buildFileTrees) {
    this.buildFileTrees = buildFileTrees;
  }

  @Override
  public void enforceBuckPackageBoundaries(
      Cell targetCell, BuildTarget target, ImmutableSet<Path> paths) {

    Path basePath = target.getBasePath();

    if (!targetCell.isEnforcingBuckPackageBoundaries(basePath)) {
      return;
    }

    BuildFileTree buildFileTree = buildFileTrees.getUnchecked(targetCell);
    boolean isBasePathEmpty = basePath.equals(targetCell.getFilesystem().getPath(""));

    for (Path path : paths) {
      if (!isBasePathEmpty && !path.startsWith(basePath)) {
        throw new HumanReadableException(
            "'%s' in '%s' refers to a parent directory.", basePath.relativize(path), target);
      }

      Optional<Path> ancestor = buildFileTree.getBasePathOfAncestorTarget(path);
      // It should not be possible for us to ever get an Optional.empty() for this because that
      // would require one of two conditions:
      // 1) The source path references parent directories, which we check for above.
      // 2) You don't have a build file above this file, which is impossible if it is referenced in
      //    a build file *unless* you happen to be referencing something that is ignored.
      if (!ancestor.isPresent()) {
        throw new IllegalStateException(
            String.format(
                "Target '%s' refers to file '%s', which doesn't belong to any package. "
                    + "More info at:\nhttps://buckbuild.com/about/overview.html\n",
                target, path));
      }

      if (!ancestor.get().equals(basePath)) {
        String buildFileName = targetCell.getBuildFileName();
        Path buckFile = ancestor.get().resolve(buildFileName);
        // TODO(cjhopman): If we want to manually split error message lines ourselves, we should
        // have a utility to do it correctly after formatting instead of doing it manually.
        throw new HumanReadableException(
            "The target '%1$s' tried to reference '%2$s'.\n"
                + "This is not allowed because '%2$s' can only be referenced from '%3$s' \n"
                + "which is its closest parent '%4$s' file.\n"
                + "\n"
                + "You should find or create a rule in '%3$s' that references\n"
                + "'%2$s' and use that in '%1$s'\n"
                + "instead of directly referencing '%2$s'.\n"
                + "More info at:\nhttps://buckbuild.com/concept/build_rule.html\n"
                + "\n"
                + "This issue might also be caused by a bug in buckd's caching.\n"
                + "Please check whether using `buck kill` resolves it.",
            target, path, buckFile, buildFileName);
      }
    }
  }
}
