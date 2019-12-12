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

import com.facebook.buck.core.exceptions.HumanReadableException;
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
import java.util.Optional;

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
  static Optional<Path> getUserFacingOutputPath(
      SourcePathResolverAdapter pathResolver,
      BuildRule rule,
      boolean buckOutCompatLink,
      OutputLabel outputLabel,
      boolean showOutputLabels) {
    Optional<Path> outputPathOptional;
    if (rule instanceof HasMultipleOutputs) {
      if (!showOutputLabels && !outputLabel.isDefault()) {
        throw new HumanReadableException(
            "%s target %s[%s] should use --show-outputs",
            rule.getType(), rule.getFullyQualifiedName(), outputLabel);
      }
      ImmutableSortedSet<SourcePath> sourcePaths =
          ((HasMultipleOutputs) rule).getSourcePathToOutput(outputLabel);
      outputPathOptional =
          sourcePaths == null || sourcePaths.isEmpty()
              ? Optional.empty()
              : Optional.of(pathResolver.getRelativePath(Iterables.getOnlyElement(sourcePaths)));
    } else {
      Preconditions.checkState(
          outputLabel.isDefault(),
          "Multiple outputs not supported for %s target %s",
          rule.getType(),
          rule.getFullyQualifiedName());
      outputPathOptional =
          Optional.ofNullable(rule.getSourcePathToOutput()).map(pathResolver::getRelativePath);
    }
    // When using buck out compat mode, we favor using the default buck output path in the UI, so
    // amend the output paths when this is set.
    if (outputPathOptional.isPresent() && buckOutCompatLink) {
      BuckPaths paths = rule.getProjectFilesystem().getBuckPaths();
      if (outputPathOptional.get().startsWith(paths.getConfiguredBuckOut())) {
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

    return outputPathOptional.map(rule.getProjectFilesystem()::resolve);
  }
}
