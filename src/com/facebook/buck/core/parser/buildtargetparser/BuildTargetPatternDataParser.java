/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.core.parser.buildtargetparser;

import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPatternData.Kind;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Factory that parses a string into {@link BuildTargetPatternData}
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
public class BuildTargetPatternDataParser {

  private BuildTargetPatternDataParser() {}

  /**
   * Parse a string representing build target pattern
   *
   * @param pattern String representing build target pattern, for example "//..."
   * @throws BuildTargetParseException If build target pattern is invalid; at this moment {@link
   *     BuildTargetParseException} is unchecked exception but we still want to declare it with the
   *     hope to make it checked one day; this type of exception would be properly handled as user
   *     error
   */
  public static BuildTargetPatternData parse(String pattern) throws BuildTargetParseException {

    check(
        pattern.length() >= BuildTargetPatternData.ROOT_SYMBOL.length() + 1,
        pattern,
        "pattern too short");

    int root_pos = pattern.indexOf(BuildTargetPatternData.ROOT_SYMBOL);
    check(
        root_pos >= 0,
        pattern,
        "should start with either '%s' or a cell name followed by '%s'",
        BuildTargetPatternData.ROOT_SYMBOL,
        BuildTargetPatternData.ROOT_SYMBOL);

    // if pattern starts with // then cellName would be empty string
    String cellName = pattern.substring(0, root_pos);
    BuildTargetPatternData.Kind kind;
    String targetName = "";
    int endOfPathPos;

    if (pattern.charAt(pattern.length() - 1) == BuildTargetPatternData.TARGET_SYMBOL) {
      kind = Kind.PACKAGE;
      endOfPathPos = pattern.length() - 1;
    } else if (pattern.endsWith(BuildTargetPatternData.RECURSIVE_SYMBOL)) {
      kind = Kind.RECURSIVE;
      endOfPathPos = pattern.length() - BuildTargetPatternData.RECURSIVE_SYMBOL.length() - 1;
      check(
          pattern.charAt(endOfPathPos) == BuildTargetPatternData.PATH_SYMBOL,
          pattern,
          "'%s' should be preceded by a '%s'",
          BuildTargetPatternData.RECURSIVE_SYMBOL,
          BuildTargetPatternData.PATH_SYMBOL);
    } else {
      kind = Kind.SINGLE;
      endOfPathPos = pattern.lastIndexOf(BuildTargetPatternData.TARGET_SYMBOL);
      if (endOfPathPos < 0) {
        // Provided input does not have target delimiter and thus target name.
        // Assume target name to be the same as the last component of the base path (package name).
        endOfPathPos = pattern.length();
        int startOfPackageName = pattern.lastIndexOf(BuildTargetPatternData.PATH_SYMBOL);
        targetName = pattern.substring(startOfPackageName + 1);
      } else if (endOfPathPos < root_pos) {
        check(
            false,
            pattern,
            "cell name should not contain '%s'",
            BuildTargetPatternData.TARGET_SYMBOL);
      } else {
        targetName = pattern.substring(endOfPathPos + 1);
      }
    }

    int startOfPathPos = root_pos + BuildTargetPatternData.ROOT_SYMBOL.length();

    // three slashes will give absolute path - halt it early
    check(pattern.charAt(startOfPathPos) != BuildTargetPatternData.PATH_SYMBOL, pattern, "'///'");

    String path =
        endOfPathPos <= startOfPathPos ? "" : pattern.substring(startOfPathPos, endOfPathPos);

    // following checks can be optimized with single pass
    // also, we might consider unsafe version of parsing function that does not check that
    check(
        path.indexOf(BuildTargetPatternData.ROOT_SYMBOL) < 0,
        pattern,
        "'%s' can appear only once",
        BuildTargetPatternData.ROOT_SYMBOL);
    check(
        path.indexOf(BuildTargetPatternData.RECURSIVE_SYMBOL) < 0,
        pattern,
        "'%s' can appear only once",
        BuildTargetPatternData.RECURSIVE_SYMBOL);
    check(
        path.indexOf(BuildTargetPatternData.TARGET_SYMBOL) < 0,
        pattern,
        "'%s' can appear only once",
        BuildTargetPatternData.TARGET_SYMBOL);
    check(
        path.length() == 0 || path.charAt(path.length() - 1) != BuildTargetPatternData.PATH_SYMBOL,
        pattern,
        "base path should not end with '%s'",
        BuildTargetPatternData.PATH_SYMBOL);

    // This will work on both posix and Windows and always evaluates to relative path
    Path basePath = Paths.get(path);

    return ImmutableBuildTargetPatternData.of(cellName, kind, basePath, targetName);
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
