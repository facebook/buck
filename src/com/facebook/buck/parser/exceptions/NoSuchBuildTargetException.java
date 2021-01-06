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

package com.facebook.buck.parser.exceptions;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

/** Thrown when build target definition is missing in corresponding build file */
public class NoSuchBuildTargetException extends BuildTargetException {

  private static int MAX_SIMILAR_TARGETS_TO_DISPLAY = 15;

  public NoSuchBuildTargetException(BuildTarget target) {
    this(String.format("No such target: '%s'", target));
  }

  public NoSuchBuildTargetException(UnconfiguredBuildTarget target) {
    this(String.format("No such target: '%s'", target));
  }

  private NoSuchBuildTargetException(String message) {
    super(message);
  }

  /**
   * @param buildTarget the failing {@link BuildTarget}
   * @param similarTargets targets that are similar to {@code buildTarget} to suggest to users
   * @param totalTargets the total number of targets that were found in the build file
   * @param fileSizeFetcher a function that takes a path and returns its filesize, or {@link
   *     Optional#empty()} if the file couldn't be read
   * @param buildFilePath the absolute path to the build file
   */
  public static NoSuchBuildTargetException createForMissingBuildRule(
      UnconfiguredBuildTarget buildTarget,
      ImmutableList<UnconfiguredBuildTarget> similarTargets,
      int totalTargets,
      Function<Path, OptionalLong> fileSizeFetcher,
      Path buildFilePath) {

    StringBuilder builder =
        new StringBuilder(
            String.format(
                "The rule %s could not be found.\nPlease check the spelling and whether it is one of the %s targets in %s.",
                buildTarget.getFullyQualifiedName(), totalTargets, buildFilePath));

    OptionalLong optionalBytes = fileSizeFetcher.apply(buildFilePath);
    if (optionalBytes.isPresent()) {
      builder.append(String.format(" (%d bytes)", optionalBytes.getAsLong()));
    } else {
      builder.append(" (unknown size)");
    }

    if (!similarTargets.isEmpty()) {
      int displayed = Math.min(MAX_SIMILAR_TARGETS_TO_DISPLAY, similarTargets.size());
      builder.append(String.format("\n%s similar targets in %s are:\n", displayed, buildFilePath));
      int i = 0;
      for (UnconfiguredBuildTarget target : similarTargets) {
        if (i >= MAX_SIMILAR_TARGETS_TO_DISPLAY) {
          break;
        }
        i++;
        builder.append("  ");
        builder.append(target.getFullyQualifiedName());
        builder.append(System.lineSeparator());
      }
    }
    return new NoSuchBuildTargetException(builder.toString());
  }

  @Override
  public String getHumanReadableErrorMessage() {
    return getMessage();
  }
}
