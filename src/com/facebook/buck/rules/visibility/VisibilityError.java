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
import java.util.List;
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

  @Override
  public String toString() {
    return errorString(
        getErrorType(), getNode().getFullyQualifiedName(), getDep().getFullyQualifiedName());
  }

  public static String combinedErrorString(List<VisibilityError> errors) {
    String errorTest =
        errors.stream().map(error -> error.toString()).collect(Collectors.joining("\n\n"));
    return String.format(
        "%s\n\nMore info at:\nhttps://buck.build/concept/visibility.html", errorTest);
  }

  @VisibleForTesting
  public static String errorString(ErrorType errorType, String node, String dep) {
    return String.format(
        "%s depends on %s, which is not %s. More info at:\nhttps://buck.build/concept/visibility.html",
        node, dep, errorType == VisibilityError.ErrorType.WITHIN_VIEW ? "within view" : "visible");
  }
}
