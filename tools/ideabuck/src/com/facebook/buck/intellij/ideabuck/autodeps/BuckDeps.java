/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.autodeps;

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuckDeps {
  private static final Logger LOG = Logger.getInstance(BuckDeps.class);

  // Patterns to search within a BUCK file for known rule
  private static final Pattern autodepsPattern = Pattern.compile("\\bautodeps\\b\\s*=\\s*True");
  private static final Pattern depsPattern = Pattern.compile("\\bdeps\\b\\s*=\\s*\\[([^\\]]+)\\]");
  private static final Pattern exportedDepsPattern =
      Pattern.compile("\\bexported_deps\\b\\s*=\\s*\\[([^\\]]+)\\]");
  private static final Pattern visibilityPattern =
      Pattern.compile("\\bvisibility\\b\\s*=\\s*\\[([^\\]]+)\\]");

  private BuckDeps() {}

  /**
   * Given the contents of a buckfile and a target name, returns an array of indices into the
   * content [start, end] of a guess where that target def in the given BUCK file.
   */
  @VisibleForTesting
  static int[] findRuleInBuckFileContents(String contents, String target) {
    int nameOffset = contents.indexOf("\"" + target + "\"");
    if (nameOffset == -1) {
      nameOffset = contents.indexOf("'" + target + "'");
    }
    if (nameOffset == -1) {
      return null; // target not found
    }
    // March backwards to figure out where the rule starts
    int targetStart = nameOffset;
    int depth = 1;
    while (depth > 0) {
      targetStart -= 1;
      if (targetStart <= 0) {
        return null; // marched back to beginning of file...this ain't it.
      }
      char ch = contents.charAt(targetStart);
      if (ch == '(') {
        depth -= 1;
      } else if (ch == ')') {
        depth += 1;
      }
    }
    // March forwards to figure out where the rule ends
    int targetEnd = nameOffset + target.length();
    depth = 1;
    while (depth > 0) {
      if (targetEnd >= contents.length()) {
        return null; // marched past end of file...didn't find it.
      }
      char ch = contents.charAt(targetEnd);
      if (ch == '(') {
        depth += 1;
      } else if (ch == ')') {
        depth -= 1;
      }
      targetEnd += 1;
    }
    return new int[] {targetStart, targetEnd};
  }

  /**
   * Given the contents of a BUCK file, try to modify the contents to add the given dependency to
   * the given target.
   */
  @VisibleForTesting
  static String tryToAddDepsToTarget(
      String buckContents, String dependencyString, String targetString) {
    BuckTarget target = BuckTarget.parse(targetString).orElse(null);
    BuckTarget dependency = BuckTarget.parse(dependencyString).orElse(null);
    if (target == null || dependency == null) {
      return buckContents;
    }

    int[] targetOffset = findRuleInBuckFileContents(buckContents, target.getRuleName());
    if (targetOffset == null) {
      LOG.warn("Couldn't find target definition for " + target);
      // TODO(ideabuck):  make this a better parser
      return buckContents;
    }

    String targetDef = buckContents.substring(targetOffset[0], targetOffset[1]);

    if (autodepsPattern.matcher(targetDef).find()) {
      // TODO(ideabuck):  Once 'buck autodeps' stabilizes, should we invoke it here?
      return buckContents;
    }

    BuckTarget relativeDependency = target.relativize(dependency);

    Matcher exportedDepsMatcher = exportedDepsPattern.matcher(targetDef);
    if (exportedDepsMatcher.find()) {
      String exportedDeps = exportedDepsMatcher.group(1);
      if (exportedDeps.contains(relativeDependency.toString())) {
        // If it already appears in the exported_deps section, nothing to do
        return buckContents;
      }
    }

    Matcher depsMatcher = depsPattern.matcher(targetDef);
    if (!depsMatcher.find()) {
      LOG.warn(
          "Couldn't figure out where to add new dependency on "
              + dependency
              + " in definition for "
              + target);
      return buckContents;
    }
    if (depsMatcher.group(1).contains(relativeDependency.toString())) {
      // already have dep in the deps section
      return buckContents;
    }
    int offset = targetOffset[0] + depsMatcher.start(1);
    return buckContents.substring(0, offset)
        + "\n\t\t\""
        + relativeDependency.toString()
        + "\","
        + buckContents.substring(offset);
  }

  @VisibleForTesting
  static String maybeAddVisibilityToTarget(
      String buckContents, String visibilityString, String targetString) {
    BuckTarget target = BuckTarget.parse(targetString).orElse(null);
    BuckTarget visibility = BuckTarget.parse(visibilityString).orElse(null);
    if (target == null || visibility == null) {
      return buckContents;
    }

    if (target.getCellName().equals(visibility.getCellName())
        && target.getCellPath().equals(visibility.getCellPath())) {
      // No need to add visibility for target within same file.
      return buckContents;
    }

    int[] targetOffset = findRuleInBuckFileContents(buckContents, target.getRuleName());
    if (targetOffset == null) {
      LOG.warn("Couldn't find target definition for " + target);
      // TODO(ideabuck):  make this a better parser
      return buckContents;
    }

    String targetDef = buckContents.substring(targetOffset[0], targetOffset[1]);

    Matcher visibilityMatcher = visibilityPattern.matcher(targetDef);
    if (!visibilityMatcher.find()) {
      LOG.warn(
          "Couldn't figure out where to increase visibility for "
              + target
              + " to include "
              + visibility);
      // TODO(ideabuck):  try harder to figure out where to add the visibility
      return buckContents;
    }

    String visibilityList = visibilityMatcher.group(1);
    if (visibilityList.contains("PUBLIC")
        || visibilityList.contains(visibility.toString())
        || visibilityList.contains(target.relativize(visibility).toString())) {
      return buckContents; // already visibile
    }
    int offset = targetOffset[0] + visibilityMatcher.start(1);
    buckContents =
        buckContents.substring(0, offset)
            + "\n\t\t\""
            + target.relativize(visibility).toString()
            + "\","
            + buckContents.substring(offset);
    return buckContents;
  }

  /**
   * In the given {@code buckFile}, modify the given {@code target} to include {@code dependency} in
   * its deps.
   *
   * @param buckFile Home for the given {@code target}.
   * @param target The fully qualified target to be modified.
   * @param dependency The fully qualified dependency required by the target.
   */
  static boolean modifyTargetToAddDependency(
      VirtualFile buckFile, String target, String dependency) {
    try {
      File file = new File(buckFile.getPath());
      String oldContents = FileUtil.loadFile(file);
      String newContents = tryToAddDepsToTarget(oldContents, dependency, target);
      if (oldContents.equals(newContents)) {
        return false;
      }
      LOG.info("Updating " + file.getPath());
      FileUtil.writeToFile(file, newContents);
      buckFile.refresh(false, false);
      return true;
    } catch (IOException e) {
      LOG.error(
          "Failed to update "
              + buckFile.getPath()
              + " target "
              + target
              + " to include "
              + dependency,
          e);
      return false;
    }
  }

  /**
   * In the given {@code buckFile}, modify the given {@code target} to make it visible to the given
   * {@code dependency}.
   *
   * @param buckFile Home for the given {@code target}.
   * @param target The fully qualified target to be modified.
   * @param dependent The fully qualified dependent that needs visibility to the target.
   */
  static void addVisibilityToTargetForUsage(VirtualFile buckFile, String target, String dependent) {
    try {
      File file = new File(buckFile.getPath());
      String oldContents = FileUtil.loadFile(file);
      String newContents = maybeAddVisibilityToTarget(oldContents, dependent, target);
      if (!oldContents.equals(newContents)) {
        LOG.info("Updating " + file.getPath());
        FileUtil.writeToFile(file, newContents);
        buckFile.refresh(false, false);
      }
    } catch (IOException e) {
      LOG.error(
          "Failed to update "
              + buckFile.getPath()
              + " target "
              + target
              + " to be visible to "
              + dependent,
          e);
    }
  }
}
