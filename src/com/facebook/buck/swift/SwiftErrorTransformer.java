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

package com.facebook.buck.swift;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts relative paths in Swift error output to absolute paths.
 *
 * <p>Xcode requires that paths be absolute to make inline error messages work, and to allow
 * clicking on an error message in the navigator to navigate to the line with the error. With
 * relative paths, error messages only appear in the navigator, and clicking on them only outputs an
 * error beep noise.
 */
final class SwiftErrorTransformer {
  private static final String TERM_ESCS = "(?:\\u001B\\[[;\\d]*[mK])*";
  private static final Pattern RELATIVE_PATH_ERROR_PATTERN =
      Pattern.compile(
          "(?<prefix>^"
              + TERM_ESCS
              + ")"
              + "(?<path>[^/][^:]+)"
              + "(?<suffix>(?::\\d+(?::\\d+:)?)?:)");

  private final ProjectFilesystem filesystem;

  SwiftErrorTransformer(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
  }

  String transformLine(String line) {
    Matcher matcher = RELATIVE_PATH_ERROR_PATTERN.matcher(line);
    if (matcher.find()) {
      StringBuilder builder = new StringBuilder();
      String prefix = matcher.group("prefix");
      if (prefix != null) {
        builder.append(prefix);
      }
      builder.append(filesystem.resolve(matcher.group("path")));
      String suffix = matcher.group("suffix");
      if (suffix != null) {
        builder.append(suffix);
      }
      return matcher.replaceAll(Matcher.quoteReplacement(builder.toString()));
    } else {
      return line;
    }
  }
}
