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

package com.facebook.buck.core.parser.buildtargetparser;

import com.facebook.buck.core.exceptions.BuildTargetParseException;

/** Utility to work with Buck base name (e. g. {@code //foo/bar}) */
public class BaseNameParser {

  private BaseNameParser() {}

  /** Check base name is valid (e. g. stars with two slashes, does not contain dot-dot etc. */
  public static void checkBaseName(String baseName, String buildTargetNameForDiagnostics) {
    if (baseName.equals(BuildTargetParser.BUILD_RULE_PREFIX)) {
      return;
    }
    if (!baseName.startsWith(BuildTargetParser.BUILD_RULE_PREFIX)) {
      throw new BuildTargetParseException(
          String.format(
              "Path in %s must start with %s",
              buildTargetNameForDiagnostics, BuildTargetParser.BUILD_RULE_PREFIX));
    }
    if (baseName.endsWith("/")) {
      throw new BuildTargetParseException(
          String.format(
              "Non-empty target path %s must not end with slash", buildTargetNameForDiagnostics));
    }
    int baseNamePathStart = BuildTargetParser.BUILD_RULE_PREFIX.length();
    if (baseName.charAt(baseNamePathStart) == '/') {
      throw new BuildTargetParseException(
          String.format(
              "Build target path should start with an optional cell name, then // and then a "
                  + "relative directory name, not an absolute directory path (found %s)",
              buildTargetNameForDiagnostics));
    }
    // instead of splitting the path by / and allocating lots of unnecessary garbage, use 2 indices
    // to track the [start, end) indices of the package part
    int start = baseNamePathStart;
    while (start < baseName.length()) {
      int end = baseName.indexOf('/', start);
      if (end == -1) end = baseName.length();
      int len = end - start;
      if (len == 0) {
        throw new BuildTargetParseException(
            String.format(
                "Build target path cannot contain // other than at the start "
                    + "(or after a cell name) (found %s)",
                buildTargetNameForDiagnostics));
      }
      if (len == 1 && baseName.charAt(start) == '.') {
        throw new BuildTargetParseException(
            String.format(
                "Build target path cannot contain . (found %s)", buildTargetNameForDiagnostics));
      }
      if (len == 2 && baseName.charAt(start) == '.' && baseName.charAt(start + 1) == '.') {
        throw new BuildTargetParseException(
            String.format(
                "Build target path cannot contain .. (found %s)", buildTargetNameForDiagnostics));
      }
      start = end + 1;
    }
  }
}
