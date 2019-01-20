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

package com.facebook.buck.doctor;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Helper methods for handling input from the user. */
public class UserInput {
  private static final Pattern RANGE_OR_NUMBER =
      Pattern.compile("((?<rangelo>\\d+)\\s*\\-\\s*(?<rangehi>\\d+))|(?<num>\\d+)");
  private static final Pattern NUMBER = Pattern.compile("(?<num>\\d+)");

  private final PrintStream output;
  private final BufferedReader reader;

  public UserInput(PrintStream output, BufferedReader inputReader) {
    this.output = output;
    this.reader = inputReader;
  }

  public String ask(String question) throws IOException {
    output.println();
    output.println(question);
    output.flush();

    return reader.readLine();
  }

  public boolean confirm(String question) throws IOException {
    ImmutableMap<String, Boolean> supportedResponses =
        ImmutableMap.of(
            "", true,
            "y", true,
            "yes", true,
            "no", false,
            "n", false);
    for (; ; ) {
      output.println();
      output.println(question + " (Y/n)?");
      output.flush();

      String line = reader.readLine();
      // Stdin was closed.
      if (line == null) {
        return false;
      }
      String response = line.trim().toLowerCase();
      if (supportedResponses.containsKey(response)) {
        return supportedResponses.get(response);
      } else {
        output.println("Please answer 'y' or 'n'.");
      }
    }
  }

  public static Integer parseOne(String input) {
    Matcher matcher = NUMBER.matcher(input);
    Preconditions.checkArgument(matcher.matches(), "Malformed entry %s.", input);
    return Integer.parseInt(input);
  }

  public static ImmutableSet<Integer> parseRange(String input) {
    ImmutableSet.Builder<Integer> result = ImmutableSet.builder();
    String[] elements = input.split("[, ]+");
    for (String element : elements) {
      Matcher matcher = RANGE_OR_NUMBER.matcher(element);
      Preconditions.checkArgument(matcher.matches(), "Malformed entry %s.", element);
      String numberString = matcher.group("num");
      String rangeLowString = matcher.group("rangelo");
      String rangeHighString = matcher.group("rangehi");
      Preconditions.checkArgument(
          numberString != null || rangeHighString != null, "Malformed entry %s.", element);

      if (numberString != null) {
        result.add(Integer.parseInt(numberString));
      } else {
        int rangeLow = Integer.parseInt(rangeLowString);
        int rangeHigh = Integer.parseInt(rangeHighString);

        for (int i = Math.min(rangeLow, rangeHigh); i <= Math.max(rangeLow, rangeHigh); ++i) {
          result.add(i);
        }
      }
    }
    return result.build();
  }

  public <T> Optional<T> selectOne(
      String prompt, List<T> entries, Function<T, String> entryFormatter) throws IOException {
    Preconditions.checkArgument(entries.size() > 0);
    output.println();
    output.println(prompt);
    output.println();
    for (int i = 0; i < entries.size(); i++) {
      output.printf("[%d]. %s.\n", i, entryFormatter.apply(entries.get(i)));
    }

    try {
      String response = ask("(input individual number, for example 1 or 2)");
      int index = response.trim().isEmpty() ? 0 : parseOne(response);
      Preconditions.checkArgument(
          index >= 0 && index < entries.size(), "Index %s out of bounds.", index);
      return Optional.of(entries.get(index));
    } catch (IllegalArgumentException e) {
      output.printf("Illegal choice: %s\n", e.getMessage());
      return Optional.empty();
    }
  }

  public <T> ImmutableSet<T> selectRange(
      String prompt, List<T> entries, Function<T, String> entryFormatter) throws IOException {
    Preconditions.checkArgument(entries.size() > 0);

    output.println();
    output.println(prompt);
    output.println();
    for (int i = 0; i < entries.size(); i++) {
      output.printf("[%d]. %s.\n", i, entryFormatter.apply(entries.get(i)));
    }

    ImmutableSet<Integer> buildIndexes;
    for (; ; ) {
      try {
        String response = ask("(input individual numbers or ranges, for example 1,2,3-9)");
        if (response.trim().isEmpty()) {
          buildIndexes = ImmutableSet.of(0);
        } else {
          buildIndexes = parseRange(response);
        }
        for (Integer index : buildIndexes) {
          Preconditions.checkArgument(
              index >= 0 && index < entries.size(), "Index %s out of bounds.", index);
        }
        break;
      } catch (IllegalArgumentException e) {
        output.printf("Illegal range %s.\n", e.getMessage());
      }
    }
    ImmutableSet.Builder<T> result = ImmutableSet.builder();
    for (Integer index : buildIndexes) {
      result.add(entries.get(index));
    }
    return result.build();
  }
}
