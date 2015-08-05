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
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
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

  /**
   * Returns a variable expansion for keys which may depend on platform name,
   * trying from most to least specific.  While it doesn't capture all arbitrary
   * wildcard expansions, it should handle everything likely to occur in practice.
   *
   * e.g.<code>VALID_ARCHS[sdk=iphoneos*]</code>
   *
   * @param keyName           The name of the parent key.  e.g. "VALID_ARCHS"
   * @param platformName      The name of the platform.  e.g. "iphoneos"
   * @param variablesToExpand The mapping of variable keys to values.
   * @return Optional containing the string value if found, or absent.
   */
  public static Optional<String> getVariableExpansionForPlatform(
      String keyName,
      String platformName,
      Map<String, String> variablesToExpand) {
    final String[] keysToTry;
    if (platformName.equals("iphoneos") || platformName.equals("iphonesimulator")) {
      keysToTry = new String[] {
          keyName + "[sdk=" + platformName + "]",
          keyName + "[sdk=" + platformName + "*]",
          keyName + "[sdk=iphone*]",
          keyName
      };
    } else {
      keysToTry = new String[] {
          keyName + "[sdk=" + platformName + "]",
          keyName + "[sdk=" + platformName + "*]",
          keyName
      };
    }

    for (String keyToTry : keysToTry) {
      if (variablesToExpand.containsKey(keyToTry)) {
        return Optional.of(
            InfoPlistSubstitution.replaceVariablesInString(
                variablesToExpand.get(keyToTry),
                variablesToExpand));
      }
    }

    return Optional.absent();
  }

  public static String replaceVariablesInString(
      String input,
      Map<String, String> variablesToExpand) {
    return replaceVariablesInString(input, variablesToExpand, ImmutableList.<String>of());
  }

  private static String replaceVariablesInString(
      String input,
      Map<String, String> variablesToExpand,
      List<String> maskedVariables) {
    Matcher variableMatcher = PLIST_VARIABLE_PATTERN.matcher(input);

    StringBuffer result = new StringBuffer();
    while (variableMatcher.find()) {
      String openParen = variableMatcher.group(OPEN_PAREN_GROUP_NAME);
      String closeParen = variableMatcher.group(CLOSE_PAREN_GROUP_NAME);

      String expectedCloseParen = Preconditions.checkNotNull(MATCHING_PARENS.get(openParen));
      if (!expectedCloseParen.equals(closeParen)) {
        // Mismatching parens; don't substitute.
        variableMatcher.appendReplacement(
            result,
            Matcher.quoteReplacement(variableMatcher.group(0)));
        continue;
      }

      String variableName = variableMatcher.group(VARIABLE_GROUP_NAME);
      if (maskedVariables.contains(variableName)) {
        throw new HumanReadableException(
            "Recursive plist variable: %s -> %s",
            Joiner.on(" -> ").join(maskedVariables),
            variableName);
      }

      String expansion = variablesToExpand.get(variableName);
      if (expansion == null) {
        throw new HumanReadableException(
            "Unrecognized plist variable: %s",
            variableMatcher.group(0));
      }

      // Variable replacements are allowed to reference other variables (but be careful to mask
      // so we don't end up in a recursive loop)
      expansion = replaceVariablesInString(
          expansion,
          variablesToExpand,
          new ImmutableList.Builder<String>().addAll(maskedVariables).add(variableName).build());

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
