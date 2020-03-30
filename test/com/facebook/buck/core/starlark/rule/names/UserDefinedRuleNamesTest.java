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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.syntax.SkylarkImport;
import org.junit.Test;

public class UserDefinedRuleNamesTest {
  @Test
  public void returnsCorrectIdentifiers() throws LabelSyntaxException {
    String identifier =
        UserDefinedRuleNames.getIdentifier(
            Label.parseAbsolute("@foo//bar:baz.bzl", ImmutableMap.of()), "some_rule");
    assertEquals("@foo//bar:baz.bzl:some_rule", identifier);

    identifier =
        UserDefinedRuleNames.getIdentifier(
            Label.parseAbsolute("//bar:baz.bzl", ImmutableMap.of()), "some_rule");
    assertEquals("//bar:baz.bzl:some_rule", identifier);
  }

  @Test
  public void determinesWhtherAnIdentifierIsAUserDefinedRule() {
    assertFalse(UserDefinedRuleNames.isUserDefinedRuleIdentifier("genrule"));
    assertTrue(UserDefinedRuleNames.isUserDefinedRuleIdentifier("//bar:baz.bzl:some_rule"));
    assertTrue(UserDefinedRuleNames.isUserDefinedRuleIdentifier("@foo//bar:baz.bzl:some_rule"));
  }

  @Test
  public void returnsNullIfIdentifierCannotBeParsed() {
    assertNull(UserDefinedRuleNames.fromIdentifier("python_library"));
    assertNull(UserDefinedRuleNames.fromIdentifier("//some:invalid.bzl:identifier:"));
    assertNull(UserDefinedRuleNames.fromIdentifier(":invalid.bzl:identifier:"));
  }

  @Test
  public void returnsLabelAndNameIfParseable() throws LabelSyntaxException {
    Pair<Label, String> expectedWithCell =
        new Pair<>(Label.parseAbsolute("@foo//bar:baz.bzl", ImmutableMap.of()), "some_rule");
    Pair<Label, String> expectedWithoutCell =
        new Pair<>(Label.parseAbsolute("//bar:baz.bzl", ImmutableMap.of()), "some_rule");

    assertEquals(
        expectedWithCell, UserDefinedRuleNames.fromIdentifier("@foo//bar:baz.bzl:some_rule"));
    assertEquals(
        expectedWithoutCell, UserDefinedRuleNames.fromIdentifier("//bar:baz.bzl:some_rule"));
  }

  @Test
  public void returnsSkylarkImport() {
    assertNull(UserDefinedRuleNames.importFromIdentifier("cxx_binary"));
    assertNull(UserDefinedRuleNames.importFromIdentifier("//foo:"));
    assertNull(UserDefinedRuleNames.importFromIdentifier("something:invalid"));

    SkylarkImport import1 = UserDefinedRuleNames.importFromIdentifier("//foo:bar.bzl:baz_rule");
    SkylarkImport import2 =
        UserDefinedRuleNames.importFromIdentifier("@cell//foo:bar.bzl:baz_rule");

    assertNotNull(import1);
    assertEquals("//foo:bar.bzl", import1.getImportString());
    assertNotNull(import2);
    assertEquals("@cell//foo:bar.bzl", import2.getImportString());
  }
}
