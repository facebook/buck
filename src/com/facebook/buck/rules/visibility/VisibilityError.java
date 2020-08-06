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

package com.facebook.buck.rules.visibility;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Visibility error generated when verifying target graph visibility. */
@BuckStyleValue
public abstract class VisibilityError {

  /** Type of visibility error. */
  public enum ErrorType {
    WITHIN_VIEW,
    VISIBILITY
  }

  public abstract ErrorType getErrorType();

  public abstract BuildTarget getNode();

  public abstract BuildTarget getDep();

  public abstract ImmutableSet<VisibilityDefiningPath> getDefiningPaths();

  @Override
  public String toString() {
    return errorString(
        getErrorType(),
        getNode().getFullyQualifiedName(),
        getDep().getFullyQualifiedName(),
        formattedDefiningPaths());
  }

  public static String combinedErrorString(List<VisibilityError> errors) {
    String errorTest =
        errors.stream().map(error -> error.toString()).collect(Collectors.joining("\n\n"));
    return String.format(
        "%s\n\nMore info at:\nhttps://dev.buck.build/concept/visibility.html", errorTest);
  }

  @VisibleForTesting
  public static String errorString(
      ErrorType errorType, String node, String dep, Optional<String> formattedDefiningPaths) {
    return String.format(
        "%s depends on %s, which is not %s.%s",
        node,
        dep,
        errorType == VisibilityError.ErrorType.WITHIN_VIEW ? "within view" : "visible",
        formattedDefiningPaths.isPresent()
            ? " Rules defined at:\n" + formattedDefiningPaths.get()
            : "");
  }

  private Optional<String> formattedDefiningPaths() {
    // If there are no defining paths, visibility has not been defined on a dependent target
    if (getDefiningPaths().size() == 0) {
      return Optional.empty();
    }

    BuildTarget violatingTarget = getErrorType() == ErrorType.WITHIN_VIEW ? getNode() : getDep();

    // If there is only one defining path and it is the target's path and a build file we can safely
    // assume the visibility definition is on the target directly and we don't need to provide any
    // additional information.
    if (getDefiningPaths().size() == 1) {
      VisibilityDefiningPath definingPath = getDefiningPaths().iterator().next();
      Path parentDefiningPath =
          definingPath.getPath().getParent() == null
              ? Paths.get("")
              : definingPath.getPath().getParent().toPathDefaultFileSystem();
      if (violatingTarget
              .getCellRelativeBasePath()
              .getPath()
              .toPathDefaultFileSystem()
              .equals(parentDefiningPath)
          && definingPath.isBuildFile()) {
        return Optional.empty();
      }
    }

    String paths =
        getDefiningPaths().stream()
            .map(definingPath -> definingPath.getPath().toString())
            .sorted()
            .collect(Collectors.joining("\n"));

    return Optional.of(paths);
  }
}
