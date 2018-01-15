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

package com.facebook.buck.model.macros;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

/**
 * Replace and extracts macros from input strings.
 *
 * <p>Examples: $(exe //foo:bar) $(location :bar) $(platform)
 */
public final class MacroFinder {

  private MacroFinder() {} // Static utility.

  public static Optional<MacroMatchResult> match(ImmutableSet<String> macros, String blob)
      throws MacroException {

    MacroFinderAutomaton macroFinder = new MacroFinderAutomaton(blob);
    if (!macroFinder.hasNext()) {
      return Optional.empty();
    }
    MacroMatchResult result = macroFinder.next();
    if (!result.isEscaped()
        && result.getStartIndex() == 0
        && result.getEndIndex() == blob.length()
        && !macros.contains(result.getMacroType())) {
      throw new MacroException(
          String.format("expanding %s: no such macro \"%s\"", blob, result.getMacroType()));
    }
    return Optional.of(result);
  }

  /**
   * Expand macros embedded in a string.
   *
   * @param replacers a map of macro names to {@link MacroReplacer} objects used to expand them.
   * @param blob the input string containing macros to be expanded
   * @param resolveEscaping whether to drop characters used to escape literal uses of `$(...)`
   * @return a copy of the input string with all macros expanded
   */
  public static <T> T replace(
      ImmutableMap<String, MacroReplacer<T>> replacers,
      String blob,
      boolean resolveEscaping,
      MacroCombiner<T> combiner)
      throws MacroException {
    // Iterate over all macros found in the string, expanding each found macro.
    int lastEnd = 0;
    MacroFinderAutomaton matcher = new MacroFinderAutomaton(blob);
    while (matcher.hasNext()) {
      MacroMatchResult matchResult = matcher.next();
      // Add everything from the original string since the last match to this one.
      combiner.addString(blob.substring(lastEnd, matchResult.getStartIndex()));

      // If the macro is escaped, add the macro text (but omit the escaping backslash)
      if (matchResult.isEscaped()) {
        combiner.addString(
            blob.substring(
                matchResult.getStartIndex() + (resolveEscaping ? 1 : 0),
                matchResult.getEndIndex()));
      } else {
        // Call the relevant expander and add the expanded value to the string.
        MacroReplacer<T> replacer = replacers.get(matchResult.getMacroType());
        if (replacer == null) {
          throw new MacroException(
              String.format(
                  "expanding %s: no such macro \"%s\"",
                  blob.substring(matchResult.getStartIndex(), matchResult.getEndIndex()),
                  matchResult.getMacroType()));
        }
        try {
          combiner.add(replacer.replace(matchResult));
        } catch (MacroException e) {
          throw new MacroException(
              String.format(
                  "expanding %s: %s",
                  blob.substring(matchResult.getStartIndex(), matchResult.getEndIndex()),
                  e.getMessage()),
              e);
        }
      }
      lastEnd = matchResult.getEndIndex();
    }
    // Append the remaining part of the original string after the last match.
    combiner.addString(blob.substring(lastEnd, blob.length()));
    return combiner.build();
  }

  public static ImmutableList<MacroMatchResult> findAll(ImmutableSet<String> macros, String blob)
      throws MacroException {

    ImmutableList.Builder<MacroMatchResult> results = ImmutableList.builder();
    MacroFinderAutomaton matcher = new MacroFinderAutomaton(blob);
    while (matcher.hasNext()) {
      MacroMatchResult matchResult = matcher.next();
      if (matchResult.isEscaped()) {
        continue;
      }
      if (!macros.contains(matchResult.getMacroType())) {
        throw new MacroException(String.format("no such macro \"%s\"", matchResult.getMacroType()));
      }
      results.add(matchResult);
    }

    return results.build();
  }
}
