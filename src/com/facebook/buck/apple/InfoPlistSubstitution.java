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

package com.facebook.buck.apple;

import com.facebook.buck.util.HumanReadableException;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class to substitute Xcode Info.plist variables in the forms:
 *
 * <code>
 * ${FOO}
 * $(FOO)
 * ${FOO:modifier}
 * $(FOO:modifier)
 * </code>
 *
 * with specified string values.
 */
public class InfoPlistSubstitution {

  // Utility class, do not instantiate.
  private InfoPlistSubstitution() { }

  private static final String VARIABLE_GROUP_NAME = "variable";
  private static final String OPEN_PAREN_GROUP_NAME = "openparen";
  private static final String CLOSE_PAREN_GROUP_NAME = "closeparen";
  private static final String MODIFIER_GROUP_NAME = "modifier";

  private static final Pattern PLIST_VARIABLE_PATTERN =
      Pattern.compile(
          "\\$(?<" + OPEN_PAREN_GROUP_NAME + ">[\\{\\(])" +
          "(?<" + VARIABLE_GROUP_NAME + ">[^\\}\\):]+)" +
          "(?::(?<" + MODIFIER_GROUP_NAME + ">[^\\}\\)]+))?" +
          "(?<" + CLOSE_PAREN_GROUP_NAME + ">[\\}\\)])");

  private static final ImmutableMap<String, String> MATCHING_PARENS = ImmutableMap.of(
      "{", "}",
      "(", ")"
  );

  public static String replaceVariablesInString(
      String input,
      Map<String, String> variablesToExpand) {
    Matcher variableMatcher = PLIST_VARIABLE_PATTERN.matcher(input);
    StringBuffer result = new StringBuffer();
    while (variableMatcher.find()) {
      String openParen = variableMatcher.group(OPEN_PAREN_GROUP_NAME);
      String closeParen = variableMatcher.group(CLOSE_PAREN_GROUP_NAME);

      if (!MATCHING_PARENS.get(openParen).equals(closeParen)) {
        // Mismatching parens; don't substitute.
        variableMatcher.appendReplacement(
            result,
            Matcher.quoteReplacement(variableMatcher.group(0)));
        continue;
      }

      String variableName = variableMatcher.group(VARIABLE_GROUP_NAME);
      String expansion = variablesToExpand.get(variableName);
      if (expansion == null) {
        throw new HumanReadableException(
            "Unrecognized plist variable: %s",
            variableMatcher.group(0));
      }

      // TODO(user): Add support for "rfc1034identifier" modifier and sanitize
      // expansion so it's a legal hostname (a-zA-Z0-9, dash, period).

      variableMatcher.appendReplacement(
          result,
          Matcher.quoteReplacement(expansion));
    }
    variableMatcher.appendTail(result);
    return result.toString();
  }

  public static Function<String, String> createVariableExpansionFunction(
      Map<String, String> variablesToExpand) {
    final ImmutableMap<String, String> variablesToExpandCopy = ImmutableMap.copyOf(
        variablesToExpand);
    return new Function<String, String>() {
      @Override
      public String apply(String input) {
        return replaceVariablesInString(input, variablesToExpandCopy);
      }
    };
  }
}
