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

import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter; // NOPMD not in core Buck
import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuckDeps {
  private static final Logger LOG = Logger.getInstance(BuckDeps.class);

  // Patterns to search within a BUCK file for known rule
  private static final Pattern autodepsPattern = Pattern.compile("autodeps\\s*=\\s*True");
  private static final Pattern depsPattern = Pattern.compile("deps\\s*=\\s*\\[([^\\]]+)\\]");
  private static final Pattern exportedDepsPattern =
      Pattern.compile("exported_deps\\s*=\\s*\\[([^\\]]+)\\]");
  private static final Pattern visibilityPattern =
      Pattern.compile("visibility\\s*=\\s*\\[([^\\]]+)\\]");

  private BuckDeps() {}

  public static void addDeps(
      Project project,
      String importBuckPath,
      String currentBuckPath,
      String importTarget,
      String currentTarget) {
    String buckPath = project.getBaseDir().getPath() + "/" + currentBuckPath + "/BUCK";
    String contents = readBuckFile(buckPath);
    String dependency = "//" + importBuckPath + ":" + importTarget;
    String newContents = maybeAddDepToTarget(contents, dependency, currentTarget);
    if (!contents.equals(newContents)) {
      writeBuckFile(buckPath, newContents);
    }
  }

  public static void addVisibility(
      Project project,
      String importBuckPath,
      String currentBuckPath,
      String importTarget,
      String currentTarget) {
    String buckPath = project.getBaseDir().getPath() + "/" + importBuckPath + "/BUCK";
    String contents = readBuckFile(buckPath);
    String visibility = "//" + currentBuckPath + ":" + currentTarget;
    String newContents = maybeAddVisibilityToTarget(contents, visibility, importTarget);
    if (!contents.equals(newContents)) {
      writeBuckFile(buckPath, newContents);
    }
  }

  /**
   * Given the contents of a buckfile and a target name, returns an array of indices into the
   * content [start, end] of a guess where that target def in the given BUCK file.
   */
  @VisibleForTesting
  static int[] findTargetInBuckFileContents(String contents, String target) {
    int nameOffset = contents.indexOf("'" + target + "'");
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
  static String maybeAddDepToTarget(String buckContents, String dependency, String target) {
    int[] targetOffset = findTargetInBuckFileContents(buckContents, target);
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

    Matcher exportedDepsMatcher = exportedDepsPattern.matcher(targetDef);
    if (exportedDepsMatcher.find()) {
      String exportedDeps = exportedDepsMatcher.group(1);
      if (exportedDeps.contains(dependency)) {
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
    if (depsMatcher.group(1).contains(dependency)) {
      // already have dep in the deps section
      return buckContents;
    }
    int offset = targetOffset[0] + depsMatcher.start(1);
    return buckContents.substring(0, offset)
        + "\n\t\t'"
        + dependency
        + "',"
        + buckContents.substring(offset);
  }

  @VisibleForTesting
  static String maybeAddVisibilityToTarget(String buckContents, String visibility, String target) {

    int[] targetOffset = findTargetInBuckFileContents(buckContents, target);
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

    if (visibilityMatcher.group(1).contains(visibility)) {
      return buckContents; // already visibile to this caller
    }
    int offset = targetOffset[0] + visibilityMatcher.start(1);
    buckContents =
        buckContents.substring(0, offset)
            + "\n\t\t'"
            + visibility
            + "',"
            + buckContents.substring(offset);
    return buckContents;
  }

  private static String readBuckFile(String buckFilePath) {
    String str = "";
    File file = new File(buckFilePath);
    try (FileInputStream fis = new FileInputStream(file)) {

      byte[] data = new byte[(int) file.length()];
      fis.read(data);
      fis.close();
      str = new String(data, "UTF-8");
    } catch (FileNotFoundException e) {
      LOG.error(e.toString());
    } catch (UnsupportedEncodingException e) {
      LOG.error(e.toString());
    } catch (IOException e) {
      LOG.error(e.toString());
    }
    return str;
  }

  private static void writeBuckFile(String buckFilePath, String text) {

    try (PrintWriter out = new PrintWriter(buckFilePath)) { // NOPMD not in core Buck
      out.write(text);
    } catch (FileNotFoundException e) {
      LOG.error(e.toString());
    } catch (IOException e) {
      LOG.error(e.toString());
    }
  }
}
