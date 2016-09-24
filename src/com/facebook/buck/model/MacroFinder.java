/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.model;

import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replace and extracts macros from input strings.
 *
 * Examples:
 *   $(exe //foo:bar)
 *   $(location :bar)
 *   $(platform)
 */
public class MacroFinder {

  /**
   * Matches a some macro name wrapped in <tt>$()</tt> followed by an optional argument, unless the
   * <code>$</code> is preceded by a backslash.
   * <p>
   * Given the input: $(exe //foo:bar), capturing groups are
   * <ol>
   *     <li> $(exe //foo:bar)
   *     <li> exe
   *     <li> //foo:bar
   * </ol>
   * <p>
   * If we match against $(platform), the capturing groups are:
   * <ol>
   *     <li> $(platform)
   *     <li> platform
   * </ol>
   **/
  private static final Pattern MACRO_PATTERN = Pattern.compile("\\$\\(([^)\\s]+)(?: ([^)]*))?\\)");

  public Optional<MacroMatchResult> match(ImmutableSet<String> macros, String blob)
      throws MacroException {
    Matcher matcher = MACRO_PATTERN.matcher(blob);
    if (matcher.matches()) {
      if (!macros.contains(matcher.group(1))) {
        throw new MacroException(
            String.format("expanding %s: no such macro \"%s\"", matcher.group(), matcher.group(1)));
      }
      MacroMatchResult.Builder result = MacroMatchResult.builder();
      result.setMacroType(matcher.group(1));
      if (matcher.group(2) != null) {
        result.addAllMacroInput(
            Splitter.on(' ')
                .trimResults()
                .split(matcher.group(2)));
      }
      result.setStartIndex(0);
      result.setEndIndex(blob.length());
      return Optional.of(result.build());
    }
    return Optional.absent();
  }

  public String replace(ImmutableMap<String, MacroReplacer> replacers, String blob)
      throws MacroException {

    StringBuilder expanded = new StringBuilder();

    // Iterate over all macros found in the string, expanding each found macro.
    int lastEnd = 0;
    Matcher matcher = MACRO_PATTERN.matcher(blob);
    while (matcher.find()) {

      // If the match is preceded by a backslash, skip this match but drop the backslash.
      if (matcher.start() > 0 && blob.charAt(matcher.start() - 1) == '\\') {
        expanded.append(blob.substring(lastEnd, matcher.start() - 1));
        expanded.append(blob.substring(matcher.start(), matcher.end()));

      // Otherwise we need to add the expanded value.
      } else {

        // Add everything from the original string since the last match to this one.
        expanded.append(blob.substring(lastEnd, matcher.start()));

        // Call the relevant expander and add the expanded value to the string.
        String name = matcher.group(1);
        MacroReplacer replacer = replacers.get(name);
        if (replacer == null) {
          throw new MacroException(
              String.format("expanding %s: no such macro \"%s\"", matcher.group(), name));
        }

        ImmutableList<String> args =
            ImmutableList.copyOf(
                Splitter.on(' ')
                    .trimResults()
                    .split(Optional.fromNullable(matcher.group(2)).or("")));
        try {
          expanded.append(replacer.replace(args));
        } catch (MacroException e) {
          throw new MacroException(
              String.format("expanding %s: %s", matcher.group(), e.getMessage()),
              e);
        }
      }

      lastEnd = matcher.end();
    }

    // Append the remaining part of the original string after the last match.
    expanded.append(blob.substring(lastEnd, blob.length()));

    return expanded.toString();
  }

  public ImmutableList<MacroMatchResult> findAll(ImmutableSet<String> macros, String blob)
      throws MacroException {

    ImmutableList.Builder<MacroMatchResult> results = ImmutableList.builder();

    // Iterate over all macros found in the string, and return a list of MacroMatchResults.
    Matcher matcher = MACRO_PATTERN.matcher(blob);
    while (matcher.find()) {
      if (matcher.start() > 0 && blob.charAt(matcher.start() - 1) == '\\') {
        continue;
      }
      String name = matcher.group(1);
      if (!macros.contains(name)) {
        throw new MacroException(String.format("no such macro \"%s\"", name));
      }
      MatchResult matchResult = matcher.toMatchResult();
      MacroMatchResult.Builder result = MacroMatchResult.builder();
      result.setMacroType(matcher.group(1));
      if (matcher.group(2) != null) {
        result.addAllMacroInput(
            Splitter.on(' ')
                .trimResults()
                .split(matcher.group(2)));
      }
      result.setStartIndex(matchResult.start());
      result.setEndIndex(matchResult.end());
      results.add(result.build());
    }

    return results.build();
  }

}
