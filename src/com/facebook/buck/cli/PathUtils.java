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

package com.facebook.buck.cli;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Optional;
import java.util.TreeMap;

/** Path-related utility methods for the command-line interface. */
public class PathUtils {
  private PathUtils() {}

  /**
   * Returns absolute path to the output rule, if the rule has an output. Cannot currently handle
   * multiple outputs since it returns either no path or one path only.
   *
   * @throws IllegalStateException if the given rule implements {@link HasMultipleOutputs} and
   *     returns more than one output from {@link
   *     HasMultipleOutputs#getSourcePathToOutput(OutputLabel)}
   */
  static Optional<AbsPath> getUserFacingOutputPath(
      SourcePathResolverAdapter pathResolver,
      BuildRule rule,
      boolean buckOutCompatLink,
      OutputLabel outputLabel) {
    Optional<Path> outputPathOptional;
    if (rule instanceof HasMultipleOutputs) {
      ImmutableSortedSet<SourcePath> sourcePaths =
          ((HasMultipleOutputs) rule).getSourcePathToOutput(outputLabel);
      outputPathOptional =
          sourcePaths == null || sourcePaths.isEmpty()
              ? Optional.empty()
              : Optional.of(
                  pathResolver
                      .getCellUnsafeRelPath(Iterables.getOnlyElement(sourcePaths))
                      .getPath());
    } else {
      Preconditions.checkState(
          outputLabel.isDefault(),
          "Multiple outputs not supported for %s target %s",
          rule.getType(),
          rule.getFullyQualifiedName());
      outputPathOptional =
          Optional.ofNullable(rule.getSourcePathToOutput())
              .map(sourcePath -> pathResolver.getCellUnsafeRelPath(sourcePath).getPath());
    }
    // When using buck out compat mode, we favor using the default buck output path in the UI, so
    // amend the output paths when this is set.
    if (outputPathOptional.isPresent() && buckOutCompatLink) {
      BuckPaths paths = rule.getProjectFilesystem().getBuckPaths();
      if (outputPathOptional.get().startsWith(paths.getConfiguredBuckOut().getPath())) {
        outputPathOptional =
            Optional.of(
                paths
                    .getBuckOut()
                    .resolve(
                        outputPathOptional
                            .get()
                            .subpath(
                                paths.getConfiguredBuckOut().getNameCount(),
                                outputPathOptional.get().getNameCount())));
      }
    }

    return outputPathOptional.map(path -> AbsPath.of(rule.getProjectFilesystem().resolve(path)));
  }

  /**
   * @return all ancestor {@link OutputLabel}s from the given rule, such that no output contains
   *     another.
   */
  static Iterable<OutputLabel> getAncestorOutputsLabels(
      SourcePathResolverAdapter resolver, HasMultipleOutputs rule) {

    // Gather and sort all output paths of this rule by their path.
    TreeMap<AbsPath, OutputLabel> pathsToLabels =
        new TreeMap<>(Comparator.comparing(AbsPath::getPath));
    for (OutputLabel outputLabel : rule.getOutputLabels()) {
      ImmutableSortedSet<SourcePath> sourcePaths = rule.getSourcePathToOutput(outputLabel);
      // TODO(agallagher): Should handle labels with multiple outputs?
      if (sourcePaths != null && sourcePaths.size() == 1) {
        pathsToLabels.put(
            resolver.getAbsolutePath(Iterables.getOnlyElement(sourcePaths)), outputLabel);
      }
    }

    // Remove outputs which are children of other output.
    AbsPath prev = null;
    for (Iterator<AbsPath> itr = pathsToLabels.keySet().iterator(); itr.hasNext(); ) {
      AbsPath element = itr.next();
      // If this output is contained by the previous one, then remove it.
      if (prev != null && element.startsWith(prev)) {
        itr.remove();
        continue;
      }
      prev = element;
    }

    return pathsToLabels.values();
  }
}
