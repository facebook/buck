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
package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetLanguageConstants;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPatternParser;
import com.facebook.buck.core.parser.buildtargetpattern.ImmutableBuildTargetPattern;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Matcher class that matches build targets against specific focused target entries. The focus
 * entries may be either a Build Target Pattern
 * (https://buck.build/concept/build_target_pattern.html) or a regular expression. Preference is
 * given to Build Target Patterns.
 */
public class FocusedTargetMatcher {
  private static final Logger LOG = Logger.get(FocusedTargetMatcher.class);

  private static final FocusedTargetMatcher NO_FOCUS = new FocusedTargetMatcher(null, "");

  final boolean hasEntries;

  // Match targets are tested against build target patterns to see if the match target begins with
  // the build target pattern.
  private final Set<String> buildTargetPatterns = new HashSet<String>();

  // Match targets are tested against regular expressions to see if the regular expression can be
  // found in the match target.
  private final Set<Pattern> regexPatterns = new HashSet<Pattern>();

  private final Set<String> matchedTargets = new HashSet<String>();
  private final Set<String> unMatchedTargets = new HashSet<String>();

  /**
   * Returns a matcher configured to match any Build Target Patterns or String Regular Expressions.
   *
   * @param focus Space separated list of build target patterns or regular expressions to focus on.
   * @param cellName The name of the current cell.
   */
  public FocusedTargetMatcher(@Nullable String focus, String cellName) {
    if (focus == null) {
      this.hasEntries = false;
      return;
    }

    this.hasEntries = true;

    List<String> entries = Splitter.on(' ').trimResults().omitEmptyStrings().splitToList(focus);

    for (String entry : entries) {
      BuildTargetPattern buildTargetPattern = parseBuildPattern(entry);
      if (buildTargetPattern != null) {
        addBuildTargetPattern(buildTargetPattern, cellName);
      } else {
        Pattern regexPattern = parseRegex(entry);
        if (regexPattern != null) {
          this.regexPatterns.add(regexPattern);
        }
      }
    }
  }

  /**
   * Returns a matcher configured to match any Build Target Patterns or String Regular Expressions.
   *
   * @param focus Space separated list of build target patterns or regular expressions to focus on.
   */
  public FocusedTargetMatcher(@Nullable String focus) {
    this(focus, "");
  }

  /** @return A matcher configured to match all targets. */
  public static FocusedTargetMatcher noFocus() {
    return NO_FOCUS;
  }

  /**
   * Add a build target to always match.
   *
   * @param buildTarget The build target to match.
   */
  void addTarget(BuildTarget buildTarget) {
    if (hasEntries) {
      matchedTargets.add(buildTarget.getUnflavoredBuildTarget().getFullyQualifiedName());
    }
  }

  /**
   * Matches the buildTarget against focus entries this matcher was initialized.
   *
   * @param buildTarget The build target to match.
   * @return Whether the buildTarget is focused on.
   */
  boolean matches(BuildTarget buildTarget) {
    return matches(buildTarget.getUnflavoredBuildTarget().getFullyQualifiedName());
  }

  /**
   * Internal implementation that checks whether the entry is focused on.
   *
   * @param entry Build target entry to match.
   * @return Whether the entry is focused on.
   */
  @VisibleForTesting
  boolean matches(String entry) {
    if (!hasEntries) {
      return true;
    }

    // Check cached targets which we've previously matched.
    if (matchedTargets.contains(entry)) {
      return true;
    }
    if (unMatchedTargets.contains(entry)) {
      return false;
    }

    // Test whether the entry starts with a build target pattern or if the regex pattern can be
    // found in the entry.
    if (buildTargetPatterns.stream().anyMatch(pattern -> entry.startsWith(pattern))
        || regexPatterns.stream().anyMatch(regex -> regex.matcher(entry).find())) {
      matchedTargets.add(entry);
      return true;
    }

    unMatchedTargets.add(entry);
    return false;
  }

  /**
   * Parses a valid BuildTargetPattern. Catches/logs any exceptions parsing and returns null.
   *
   * @param entry Focus entry to parse to a build target pattern.
   * @return A valid build target pattern or null.
   */
  private static @Nullable BuildTargetPattern parseBuildPattern(String entry) {
    try {
      BuildTargetPattern pattern = BuildTargetPatternParser.parse(entry);
      return pattern;
    } catch (Exception e) {
      LOG.debug("Could not parse '%s' as a build target pattern: %s", entry, e);
    }
    return null;
  }

  /**
   * Parses a valid regular expression Pattern. Catches/logs any exceptions parsing and returns
   * null.
   *
   * @param entry Focus entry to parse to a regular expression.
   * @return A valid regular expression pattern or null.
   */
  private static @Nullable Pattern parseRegex(String entry) {
    try {
      Pattern pattern = Pattern.compile(entry);
      return pattern;
    } catch (Exception e) {
      LOG.debug("Skipping focus entry '%s' which failed to parse as valid regex: %s", entry, e);
    }
    return null;
  }

  /**
   * Add the build target pattern to the focus patterns set.
   *
   * @param buildTargetPattern The build target pattern to add.
   * @param cellName The name of the current cell.
   */
  private void addBuildTargetPattern(BuildTargetPattern buildTargetPattern, String cellName) {
    addBuildTargetPatternBasedOnKind(buildTargetPattern);

    // If the cell is the same, we need to support build target patterns without the cell.
    if (buildTargetPattern.getCell().equals(cellName)) {
      addBuildTargetPatternBasedOnKind(
          ImmutableBuildTargetPattern.of(
              "",
              buildTargetPattern.getKind(),
              buildTargetPattern.getBasePath(),
              buildTargetPattern.getTargetName()));
    }
  }

  /**
   * Add the build target pattern based on the kind of pattern it is.
   *
   * @param buildTargetPattern The build target pattern to add.
   */
  private void addBuildTargetPatternBasedOnKind(BuildTargetPattern buildTargetPattern) {
    switch (buildTargetPattern.getKind()) {
      case SINGLE:
        // Fully qualified targets can directly be added to the match list, eg: //foo:bar
        this.matchedTargets.add(buildTargetPattern.toString());
        break;
      case PACKAGE:
        // Match //foo:
        this.buildTargetPatterns.add(buildTargetPattern.toString());
        break;
      case RECURSIVE:
        // Match //foo: and //foo/ but specifically not //foobar when the pattern is //foo/...
        String base =
            buildTargetPattern.getCell()
                + BuildTargetLanguageConstants.ROOT_SYMBOL
                + PathFormatter.pathWithUnixSeparators(buildTargetPattern.getBasePath());
        this.buildTargetPatterns.add(base + BuildTargetLanguageConstants.TARGET_SYMBOL);
        this.buildTargetPatterns.add(base + BuildTargetLanguageConstants.PATH_SYMBOL);
    }
  }
}
