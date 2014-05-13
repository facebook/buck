/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavacErrorParser {

  private static ImmutableList<Pattern> onePartPatterns = ImmutableList.of(
      Pattern.compile(
          "error: cannot access (?<symbol>\\S+)"),
      Pattern.compile(
          "error: package \\S+ does not exist\nimport (?<symbol>\\S+);"),
      Pattern.compile(
          "error: package \\S+ does not exist\nimport static (?<symbol>\\S+)\\.[^.]+;"));

  private static ImmutableList<Pattern> twoPartPatterns = ImmutableList.of(
      Pattern.compile(
          "\\s*symbol:\\s+class (?<class>\\S+)\n\\s*location:\\s+package (?<package>\\S+)"));

  private JavacErrorParser() { }

  public static Optional<String> getMissingSymbolFromCompilerError(String error) {
    for (Pattern pattern: onePartPatterns) {
      Matcher matcher = pattern.matcher(error);
      if (matcher.find()) {
        return Optional.of(matcher.group("symbol"));
      }
    }

    for (Pattern pattern: twoPartPatterns) {
      Matcher matcher = pattern.matcher(error);
      if (matcher.find()) {
        return Optional.of(matcher.group("package") + "." + matcher.group("class"));
      }
    }

    return Optional.absent();
  }
}
