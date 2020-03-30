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

package com.facebook.buck.core.starlark.rule.names;

import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.syntax.SkylarkImport;
import javax.annotation.Nullable;

/**
 * Helper methods to create, validate, and parse the identifier used by the parser for the {@code
 * buck.type} attribute on targets of user defined rules.
 */
public class UserDefinedRuleNames {
  private UserDefinedRuleNames() {}

  /**
   * Creates an identifier that can be used in {@code buck.type} in the parser
   *
   * @param extensionLabel the label of the extension file that this rule was defined in
   * @param exportedName the name of the rule in the extension file
   * @return An identifier of the form {@code cell//package:extension.bzl:extension_name}
   */
  public static String getIdentifier(Label extensionLabel, String exportedName) {
    return String.format("%s:%s", extensionLabel.getCanonicalForm(), exportedName);
  }

  /**
   * Determines whether the given identifier is for a user defined rule
   *
   * @param identifier the identifier from {@code buck.type}
   * @return Whether this looks like an identifier for a user defined rule
   */
  public static boolean isUserDefinedRuleIdentifier(String identifier) {
    return identifier.contains("//");
  }

  /**
   * Parses the extension label and the rule name from an identifier
   *
   * @param identifier the identifier from {@code buck.type}
   * @return a pair of the label of the extension file that contains this rule, and the rule's name.
   *     If the identifier could not be parsed, {@code null} is returned.
   */
  @Nullable
  public static Pair<Label, String> fromIdentifier(String identifier) {
    int idx = identifier.lastIndexOf(':');
    if (idx == -1 || idx == identifier.length() - 1) {
      return null;
    }
    try {
      return new Pair<>(
          Label.parseAbsolute(identifier.substring(0, idx), ImmutableMap.of()),
          identifier.substring(idx + 1));
    } catch (LabelSyntaxException e) {
      return null;
    }
  }

  /**
   * Convert a 'buck.type' string into a SkylarkImport object
   *
   * @param identifier the result from buck.type
   * @return A {@link SkylarkImport} object if {@code identifier} is parsable as a UDR identifier,
   *     else {@code null}
   */
  @Nullable
  public static SkylarkImport importFromIdentifier(String identifier) {
    int idx = identifier.lastIndexOf(':');
    if (idx == -1 || idx == identifier.length() - 1) {
      return null;
    }
    try {
      return SkylarkImport.create(identifier.substring(0, idx));
    } catch (SkylarkImport.SkylarkImportSyntaxException e) {
      return null;
    }
  }
}
