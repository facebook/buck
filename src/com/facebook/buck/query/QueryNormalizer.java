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

package com.facebook.buck.query;

import com.google.common.collect.Iterables;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Translates raw query to its canonical view, replacing substitutions if needed */
public final class QueryNormalizer {

  /** String used to separate distinct sets when using `%Ss` in a query */
  public static final String SET_SEPARATOR = "--";

  /**
   * String used to specify a substitution location of a set which is specified as a list of
   * newline-separated strings which follow the query itself
   */
  public static final String SET_SUBSTITUTOR = "%Ss";

  /**
   * Format query using list substitution
   *
   * @param pattern Query that contains one or more %Ss
   * @param formatArgs Replacement strings for each %Ss occurrence, if there is just on replacement
   *     then all %sS instance would be replaced with it. If there is another mismatch between
   *     number of %Ss and replacement strings then the error is raised
   */
  public static String normalizePattern(String pattern, List<String> formatArgs)
      throws QueryException {
    int numberOfSetsProvided =
        formatArgs.isEmpty() ? 0 : Iterables.frequency(formatArgs, SET_SEPARATOR) + 1;
    int numberOfSetsRequested = countMatches(pattern, SET_SUBSTITUTOR);

    // If they only provided one list as args, use that for every instance of `%Ss`
    if (numberOfSetsProvided == 1) {
      return pattern.replace(SET_SUBSTITUTOR, getSetRepresentation(formatArgs));
    }

    if (numberOfSetsProvided != numberOfSetsRequested) {
      String message =
          String.format(
              "Incorrect number of sets. Query uses `%s` %d times but %d sets were given",
              SET_SUBSTITUTOR, numberOfSetsRequested, numberOfSetsProvided);
      throw new QueryException(message);
    }

    List<String> unusedFormatArgs = formatArgs;
    String formattedQuery = pattern;
    while (formattedQuery.contains(SET_SUBSTITUTOR)) {
      int nextSeparatorIndex = unusedFormatArgs.indexOf(SET_SEPARATOR);
      List<String> currentSet =
          nextSeparatorIndex == -1
              ? unusedFormatArgs
              : unusedFormatArgs.subList(0, nextSeparatorIndex);
      // +1 so we don't include the separator in the next list
      unusedFormatArgs = unusedFormatArgs.subList(nextSeparatorIndex + 1, unusedFormatArgs.size());
      formattedQuery =
          formattedQuery.replaceFirst(SET_SUBSTITUTOR, getSetRepresentation(currentSet));
    }
    return formattedQuery;
  }

  /**
   * Format query using list substitution If the first line of the query contains %Ss then
   * subsequent lines are treated as replacement strings
   */
  public static String normalize(String query) throws QueryException {
    String[] lines = query.split(System.lineSeparator());

    if (!lines[0].contains(SET_SUBSTITUTOR)) {
      // short-circuit for performance
      return query;
    }

    return normalizePattern(lines[0], Arrays.stream(lines).skip(1).collect(Collectors.toList()));
  }

  private static String getSetRepresentation(List<String> args) {
    return args.stream()
        .map(input -> "'" + input + "'")
        .collect(Collectors.joining(" ", "set(", ")"));
  }

  /** Number of occurrences of a 'needle' in a 'haystack' Implement it here to avoid dependencies */
  private static int countMatches(String haystack, String needle) {
    int num = 0;
    int pos = 0;

    while (pos < haystack.length()) {
      int nextPos = haystack.indexOf(needle, pos);
      if (nextPos < 0) {
        break;
      }
      num++;
      pos = nextPos + needle.length();
    }

    return num;
  }
}
