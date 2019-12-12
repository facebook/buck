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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPattern;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetPatternParser;
import com.facebook.buck.core.util.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
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

  private static final FocusedTargetMatcher NO_FOCUS =
      new FocusedTargetMatcher(
          null,
          new CellNameResolver() {
            @Override
            public Optional<CanonicalCellName> getNameIfResolvable(Optional<String> localName) {
              throw new AssertionError("unreachable");
            }

            @Override
            public CanonicalCellName getName(Optional<String> localName) {
              throw new AssertionError("unreachable");
            }

            @Override
            public ImmutableMap<Optional<String>, CanonicalCellName> getKnownCells() {
              throw new AssertionError("unreachable");
            }
          });

  final boolean hasEntries;

  // Match targets defined in given package.
  // Example: //foo matches //foo:bar but does not match //foo/bar:baz
  private final Set<CellRelativePath> buildTargetPackages = new HashSet<>();

  // Match targets with a given prefix.
  // Example: //foo matches //foo:bar and //foo/bar:baz
  private final Set<CellRelativePath> buildTargetPrefixes = new HashSet<>();

  // Match targets are tested against regular expressions to see if the regular expression can be
  // found in the match target.
  private final Set<Pattern> regexPatterns = new HashSet<Pattern>();

  private final Set<UnflavoredBuildTarget> matchedTargets = new HashSet<>();
  private final Set<UnflavoredBuildTarget> unMatchedTargets = new HashSet<>();

  /**
   * Returns a matcher configured to match any Build Target Patterns or String Regular Expressions.
   *
   * @param focus Space separated list of build target patterns or regular expressions to focus on.
   */
  public FocusedTargetMatcher(@Nullable String focus, CellNameResolver cellNameResolver) {
    if (focus == null) {
      this.hasEntries = false;
      return;
    }

    this.hasEntries = true;

    List<String> entries = Splitter.on(' ').trimResults().omitEmptyStrings().splitToList(focus);

    for (String entry : entries) {
      BuildTargetPattern buildTargetPattern = parseBuildPattern(entry, cellNameResolver);
      if (buildTargetPattern != null) {
        addBuildTargetPattern(buildTargetPattern);
      } else {
        Pattern regexPattern = parseRegex(entry);
        if (regexPattern != null) {
          this.regexPatterns.add(regexPattern);
        }
      }
    }
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
      matchedTargets.add(buildTarget.getUnflavoredBuildTarget());
    }
  }

  /**
   * Matches the buildTarget against focus entries this matcher was initialized.
   *
   * @param buildTarget The build target to match.
   * @return Whether the buildTarget is focused on.
   */
  boolean matches(BuildTarget buildTarget) {
    return matches(buildTarget.getUnflavoredBuildTarget());
  }

  /**
   * Internal implementation that checks whether the entry is focused on.
   *
   * @param buildTarget Build target entry to match.
   * @return Whether the entry is focused on.
   */
  @VisibleForTesting
  boolean matches(UnflavoredBuildTarget buildTarget) {
    if (!hasEntries) {
      return true;
    }

    String entry = buildTarget.getFullyQualifiedName();

    // Check cached targets which we've previously matched
    // or populated from user input.
    if (matchedTargets.contains(buildTarget)) {
      return true;
    }
    if (unMatchedTargets.contains(buildTarget)) {
      return false;
    }

    // Test whether the entry starts with a build target pattern or if the regex pattern can be
    // found in the entry.
    if (buildTargetPackages.stream().anyMatch(p -> buildTarget.getCellRelativeBasePath().equals(p))
        || buildTargetPrefixes.stream()
            .anyMatch(p -> buildTarget.getCellRelativeBasePath().startsWith(p))
        || regexPatterns.stream().anyMatch(regex -> regex.matcher(entry).find())) {
      matchedTargets.add(buildTarget);
      return true;
    }

    unMatchedTargets.add(buildTarget);
    return false;
  }

  /**
   * Parses a valid BuildTargetPattern. Catches/logs any exceptions parsing and returns null.
   *
   * @param entry Focus entry to parse to a build target pattern.
   * @return A valid build target pattern or null.
   */
  private static @Nullable BuildTargetPattern parseBuildPattern(
      String entry, CellNameResolver cellNameResolver) {
    try {
      BuildTargetPattern pattern = BuildTargetPatternParser.parse(entry, cellNameResolver);
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
   */
  private void addBuildTargetPattern(BuildTargetPattern buildTargetPattern) {
    switch (buildTargetPattern.getKind()) {
      case SINGLE:
        // Fully qualified targets can directly be added to the match list, eg: //foo:bar

        // TODO(nga): BuildTargetPattern.targetName includes flavors,
        //  but we match flavorless targets, thus if there's flavor, it is not match.
        //  We should stop accept flavors when parsing patterns, at least for focus.
        if (buildTargetPattern.getLocalNameAndFlavors().contains("#")) {
          break;
        }

        this.matchedTargets.add(
            UnflavoredBuildTarget.of(
                buildTargetPattern.getCellRelativeBasePath(),
                buildTargetPattern.getLocalNameAndFlavors()));
        break;
      case PACKAGE:
        // Match //foo:
        this.buildTargetPackages.add(buildTargetPattern.getCellRelativeBasePath());
        break;
      case RECURSIVE:
        // Match //foo/...
        this.buildTargetPrefixes.add(buildTargetPattern.getCellRelativeBasePath());
        break;
    }
  }
}
