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

package com.facebook.buck.core.artifact;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import java.nio.file.Path;
import org.apache.commons.lang.StringEscapeUtils;

/** Represents a failure to declare an {@link Artifact} */
public class ArtifactDeclarationException extends HumanReadableException {
  /** The reason that an artifact could not be declared */
  enum Reason {
    EMPTY_PATH("Path '%s' in target '%s' was empty"),
    ABSOLUTE_PATH("Path '%s' in target '%s' was absolute, but must be relative"),
    PATH_TRAVERSAL(
        "Path '%s' in target '%s' attempted to traverse upwards in the filesystem. This is not permitted."),
    INVALID_PATH("Path '%s' in target '%s' is not valid");

    private final String message;

    Reason(String message) {
      this.message = message;
    }

    String getMessage(String path, BuildTarget buildTarget) {
      return String.format(message, path, buildTarget);
    }
  }

  public ArtifactDeclarationException(Reason reason, BuildTarget buildTarget, Path path) {
    super(reason.getMessage(path.toString(), buildTarget));
  }

  public ArtifactDeclarationException(Reason reason, BuildTarget buildTarget, String path) {
    // Escape the name so that if there are unprintable characters, whitespace, etc, it will
    // be easy to see that
    super(reason.getMessage(StringEscapeUtils.escapeJava(path), buildTarget));
  }
}
