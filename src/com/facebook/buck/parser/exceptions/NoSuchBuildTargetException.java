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

package com.facebook.buck.parser.exceptions;

import com.facebook.buck.core.model.BuildTarget;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;

/** Thrown when build target definition is missing in corresponding build file */
public class NoSuchBuildTargetException extends BuildTargetException {

  public NoSuchBuildTargetException(BuildTarget target) {
    this(String.format("No such target: '%s'", target));
  }

  private NoSuchBuildTargetException(String message) {
    super(message);
  }

  /**
   * @param buildTarget the failing {@link BuildTarget}
   * @param buckFilePath the path to BUCK file
   * @param suggestions potential target suggestions, will not display if they are null or empty
   */
  public static NoSuchBuildTargetException createForMissingBuildRule(
      BuildTarget buildTarget, Path buckFilePath, ImmutableList<String> suggestions) {
    StringBuilder exceptionMessageBuilder =
        new StringBuilder(
            String.format(
                "The rule %s could not be found.\nPlease check the spelling and whether it exists in %s.",
                buildTarget.getFullyQualifiedName(), buckFilePath));

    if (!suggestions.isEmpty()) {
      String lineSeparator = System.lineSeparator();
      String separator = "    " + lineSeparator;
      exceptionMessageBuilder.append(lineSeparator);
      exceptionMessageBuilder.append("Did you mean:");
      Joiner.on(separator).appendTo(exceptionMessageBuilder, suggestions);
    }
    return new NoSuchBuildTargetException(exceptionMessageBuilder.toString());
  }

  @Override
  public String getHumanReadableErrorMessage() {
    return getMessage();
  }
}
