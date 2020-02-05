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

package com.facebook.buck.core.parser.buildtargetpattern;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern.Kind;
import com.facebook.buck.core.path.ForwardRelativePath;
import java.util.Optional;

/**
 * Factory that parses a string into {@link BuildTargetPattern}
 *
 * <p>Pattern may be one of following formats:
 *
 * <ul>
 *   <li>Single build target: cell//path/package:target or cell//path/package, in which case target
 *       name is assumed to be the same as package name
 *   <li>All build targets from some package: cell//path/package:
 *   <li>All build targets from some package and all packages down folder tree:
 *       cell//path/package/...
 * </ul>
 *
 * <p>Cell component may be omitted in which case pattern will be evaluated in the context of
 * executing or owning cell
 */
public class BuildTargetPatternParser {

  private BuildTargetPatternParser() {}

  /**
   * Parse a string representing build target pattern
   *
   * @param pattern String representing build target pattern, for example "//..."
   * @throws BuildTargetParseException If build target pattern is invalid; at this moment {@link
   *     BuildTargetParseException} is unchecked exception but we still want to declare it with the
   *     hope to make it checked one day; this type of exception would be properly handled as user
   *     error
   */
  public static BuildTargetPattern parse(String pattern, CellNameResolver cellNameResolver)
      throws BuildTargetParseException {

    check(
        pattern.length() >= BuildTargetLanguageConstants.ROOT_SYMBOL.length() + 1,
        pattern,
        "pattern too short");

    int root_pos = pattern.indexOf(BuildTargetLanguageConstants.ROOT_SYMBOL);
    check(
        root_pos >= 0,
        pattern,
        "should start with either '%s' or a cell name followed by '%s'",
        BuildTargetLanguageConstants.ROOT_SYMBOL,
        BuildTargetLanguageConstants.ROOT_SYMBOL);

    // if pattern starts with // then cellName would be empty string
    String cellName = pattern.substring(0, root_pos);
    BuildTargetPattern.Kind kind;
    String targetName = "";
    int endOfPathPos;

    if (pattern.charAt(pattern.length() - 1) == BuildTargetLanguageConstants.TARGET_SYMBOL) {
      kind = Kind.PACKAGE;
      endOfPathPos = pattern.length() - 1;
    } else if (pattern.endsWith(BuildTargetLanguageConstants.RECURSIVE_SYMBOL)) {
      kind = Kind.RECURSIVE;
      endOfPathPos = pattern.length() - BuildTargetLanguageConstants.RECURSIVE_SYMBOL.length() - 1;
      check(
          pattern.charAt(endOfPathPos) == BuildTargetLanguageConstants.PATH_SYMBOL,
          pattern,
          "'%s' should be preceded by a '%s'",
          BuildTargetLanguageConstants.RECURSIVE_SYMBOL,
          BuildTargetLanguageConstants.PATH_SYMBOL);
    } else {
      kind = Kind.SINGLE;
      endOfPathPos = pattern.lastIndexOf(BuildTargetLanguageConstants.TARGET_SYMBOL);
      if (endOfPathPos < 0) {
        // Provided input does not have target delimiter and thus target name.
        // Assume target name to be the same as the last component of the base path (package name).
        endOfPathPos = pattern.length();
        int startOfPackageName = pattern.lastIndexOf(BuildTargetLanguageConstants.PATH_SYMBOL);
        targetName = pattern.substring(startOfPackageName + 1);
      } else if (endOfPathPos < root_pos) {
        check(
            false,
            pattern,
            "cell name should not contain '%s'",
            BuildTargetLanguageConstants.TARGET_SYMBOL);
      } else {
        targetName = pattern.substring(endOfPathPos + 1);
      }
    }

    int startOfPathPos = root_pos + BuildTargetLanguageConstants.ROOT_SYMBOL.length();

    // three slashes will give absolute path - halt it early
    check(
        pattern.charAt(startOfPathPos) != BuildTargetLanguageConstants.PATH_SYMBOL,
        pattern,
        "'///'");

    String path =
        endOfPathPos <= startOfPathPos ? "" : pattern.substring(startOfPathPos, endOfPathPos);

    // following checks can be optimized with single pass
    // also, we might consider unsafe version of parsing function that does not check that
    check(
        path.indexOf(BuildTargetLanguageConstants.ROOT_SYMBOL) < 0,
        pattern,
        "'%s' can appear only once",
        BuildTargetLanguageConstants.ROOT_SYMBOL);
    check(
        path.indexOf(BuildTargetLanguageConstants.RECURSIVE_SYMBOL) < 0,
        pattern,
        "'%s' can appear only once",
        BuildTargetLanguageConstants.RECURSIVE_SYMBOL);
    check(
        path.indexOf(BuildTargetLanguageConstants.TARGET_SYMBOL) < 0,
        pattern,
        "'%s' can appear only once",
        BuildTargetLanguageConstants.TARGET_SYMBOL);
    check(
        path.length() == 0
            || path.charAt(path.length() - 1) != BuildTargetLanguageConstants.PATH_SYMBOL,
        pattern,
        "base path should not end with '%s'",
        BuildTargetLanguageConstants.PATH_SYMBOL);

    ForwardRelativePath basePath = ForwardRelativePath.of(path);

    CanonicalCellName canonicalCellName =
        cellNameResolver.getName(cellName.isEmpty() ? Optional.empty() : Optional.of(cellName));

    return BuildTargetPattern.of(
        CellRelativePath.of(canonicalCellName, basePath), kind, targetName);
  }

  private static void check(boolean condition, String pattern, String message, Object... args)
      throws BuildTargetParseException {
    if (!condition) {
      throw new BuildTargetParseException(
          String.format(
              "Incorrect syntax for build target pattern '%s': %s",
              pattern, String.format(message, args)));
    }
  }
}
