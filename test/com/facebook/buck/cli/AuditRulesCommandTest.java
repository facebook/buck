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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.parser.syntax.SelectorValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.Dict;
import com.google.devtools.build.lib.syntax.Mutability;
import org.junit.Test;

public class AuditRulesCommandTest {

  @Test
  public void testCreateDisplayString() throws Exception {
    assertEquals("None", AuditRulesCommand.createDisplayString(null));
    assertEquals("True", AuditRulesCommand.createDisplayString(true));
    assertEquals("False", AuditRulesCommand.createDisplayString(false));
    assertEquals("42", AuditRulesCommand.createDisplayString(42));
    assertEquals("3.14", AuditRulesCommand.createDisplayString(3.14));
    assertEquals("\"Hello, world!\"", AuditRulesCommand.createDisplayString("Hello, world!"));
    assertEquals("[\n]", AuditRulesCommand.createDisplayString(ImmutableList.<String>of()));
    ImmutableList<String> testList = ImmutableList.of("foo", "bar", "baz");
    assertEquals(
        "[\n  \"foo\",\n  \"bar\",\n  \"baz\",\n]",
        AuditRulesCommand.createDisplayString(testList));
    assertEquals(
        "{\n  \"foo\": 1,\n  \"bar\": 2,\n  \"baz\": 3,\n}",
        AuditRulesCommand.createDisplayString(ImmutableMap.of("foo", 1, "bar", 2, "baz", 3)));
    assertEquals(
        "{\n  \"foo\": [\n    1,\n  ],\n}",
        AuditRulesCommand.createDisplayString(ImmutableMap.of("foo", ImmutableList.of(1))));
    Dict<String, String> testDict = Dict.of(null, "one", "two");
    assertEquals(
        "select({\n  \"one\": \"two\",\n})",
        AuditRulesCommand.createDisplayString(
            ListWithSelects.of(
                ImmutableList.of(SelectorValue.copyOf(testDict, "")), String.class)));
    Dict<String, String> testDict2 = Dict.of(Mutability.create(), "three", "four");
    Dict<String, String> twoEntryDict = Dict.of(Mutability.create());
    twoEntryDict.putAll(testDict, Location.BUILTIN);
    twoEntryDict.putAll(testDict2, Location.BUILTIN);
    assertEquals(
        "select({\n  \"one\": \"two\",\n  \"three\": \"four\",\n})",
        AuditRulesCommand.createDisplayString(
            ListWithSelects.of(
                ImmutableList.of(SelectorValue.copyOf(twoEntryDict, "")), String.class)));
    Dict<String, ImmutableList<String>> testDict3 = Dict.of(null, "foo", testList);
    Dict<String, ImmutableList<String>> result = Dict.of(Mutability.create());
    result.putAll(testDict3, Location.BUILTIN);
    result.putAll(Dict.of(null, "bar", testList), Location.BUILTIN);
    testDict3 = result;
    // ListWithSelects with SelectorValue only
    assertEquals(
        "select({\n  \"foo\": [\n    \"foo\",\n    \"bar\",\n    \"baz\",\n  ],\n  \"bar\": [\n    \"foo\",\n    \"bar\",\n    \"baz\",\n  ],\n})",
        AuditRulesCommand.createDisplayString(
            ListWithSelects.of(
                ImmutableList.of(SelectorValue.copyOf(testDict3, "")), String.class)));
    // ListWithSelects with SelectorValue and other types
    ImmutableList<String> shortList = ImmutableList.of("foo");
    assertEquals(
        "[\n  \"foo\",\n] + select({\n  \"foo\": [\n    \"foo\",\n    \"bar\",\n    \"baz\",\n  ],\n  \"bar\": [\n    \"foo\",\n    \"bar\",\n    \"baz\",\n  ],\n}) + [\n  \"foo\",\n  \"bar\",\n  \"baz\",\n]",
        AuditRulesCommand.createDisplayString(
            ListWithSelects.of(
                ImmutableList.of(shortList, SelectorValue.copyOf(testDict3, ""), testList),
                String.class)));
  }

  @Test(expected = IllegalStateException.class)
  public void testCreateDisplayStringRejectsUnknownType() {
    AuditRulesCommand.createDisplayString(new Object());
  }
}
