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

package com.facebook.buck.features.apple.common;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Optional;

/**
 * Convenience class for parsing and reading Xcconfig files. At the time, this is not feature
 * complete; right now, this class supports basic patterns into a String: String[] format, but lacks
 * many "advanced" features such as #include statements, variable expansion in values, comments, and
 * conditionals.
 */
public class Xcconfig {
  private final ImmutableMap<String, ImmutableList<String>> parsedContents;

  /**
   * Generates an Xcconfig from the input string.
   *
   * @param input The string to parse.
   * @return The parsed Xcconfig file.
   * @throws ParseException Thrown if unsupported or unknown syntax is encountered.
   */
  public static Xcconfig fromString(String input) throws ParseException {
    ImmutableMap.Builder<String, ImmutableList<String>> contentsBuilder = ImmutableMap.builder();
    for (String line : input.split("\n")) {
      if (line.startsWith("//") || line.startsWith("#include")) {
        continue;
      }

      String[] split = line.split("=");
      if (split.length < 2) {
        throw new ParseException("The input value is not a valid xcconfig format.", 0);
      }

      String key = split[0].trim();
      ImmutableList<String> values =
          Arrays.stream(split[1].split(" "))
              .filter(value -> value.length() > 0)
              .collect(ImmutableList.toImmutableList());
      contentsBuilder.put(key, values);
    }

    return new Xcconfig(contentsBuilder.build());
  }

  private Xcconfig(ImmutableMap<String, ImmutableList<String>> parsedContents) {
    this.parsedContents = parsedContents;
  }

  public boolean containsKey(String key) {
    return parsedContents.containsKey(key);
  }

  public Optional<ImmutableList<String>> getKey(String key) {
    if (containsKey(key)) {
      return Optional.of(parsedContents.get(key));
    }
    return Optional.empty();
  }
}
