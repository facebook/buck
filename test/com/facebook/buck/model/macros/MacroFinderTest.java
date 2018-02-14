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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.macros.FunctionMacroReplacer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class MacroFinderTest {

  @Test
  public void findAll() throws MacroException {
    ImmutableList<MacroMatchResult> expectedResults =
        ImmutableList.of(
            MacroMatchResult.builder()
                .setMacroType("macro1")
                .setStartIndex(12)
                .setEndIndex(21)
                .setEscaped(false)
                .build(),
            MacroMatchResult.builder()
                .setMacroType("macro2")
                .addMacroInput("arg")
                .setStartIndex(26)
                .setEndIndex(39)
                .setEscaped(false)
                .build(),
            MacroMatchResult.builder()
                .setMacroType("macro1")
                .addMacroInput("arg", "arg2")
                .setStartIndex(40)
                .setEndIndex(58)
                .setEscaped(false)
                .build());
    ImmutableList<MacroMatchResult> actualResults =
        MacroFinder.findAll(
            ImmutableSet.of("macro1", "macro2"),
            "hello world $(macro1) and $(macro2 arg) $(macro1 arg arg2)");
    assertEquals(expectedResults, actualResults);
  }

  @Test(expected = MacroException.class)
  public void findAllUnexpectedMacro() throws MacroException {
    MacroFinder.findAll(ImmutableSet.of(), "hello world $(macro)");
  }

  @Test
  public void replace() throws MacroException {
    ImmutableMap<String, String> replacements =
        ImmutableMap.of(
            "arg1", "something",
            "arg2", "something else");
    String actual =
        MacroFinder.replace(
            ImmutableMap.of(
                "macro", new FunctionMacroReplacer<>(args -> replacements.get(args.get(0)))),
            "hello $(macro arg1) goodbye $(macro arg2)",
            true,
            new StringMacroCombiner());
    assertEquals("hello something goodbye something else", actual);
  }

  @Test
  public void replaceEscaped() throws MacroException {
    ImmutableMap<String, String> replacements =
        ImmutableMap.of(
            "arg1", "something",
            "arg2", "something else");
    String actual =
        MacroFinder.replace(
            ImmutableMap.of(
                "macro", new FunctionMacroReplacer<>(args -> replacements.get(args.get(0)))),
            "hello \\$(macro arg1) goodbye $(macro arg2)",
            true,
            new StringMacroCombiner());
    assertEquals("hello $(macro arg1) goodbye something else", actual);
  }

  @Test
  public void replacePreserveEscaping() throws MacroException {
    String input = "hello \\$(escaped) goodbye";
    assertThat(
        MacroFinder.replace(ImmutableMap.of(), input, false, new StringMacroCombiner()),
        Matchers.equalTo(input));
  }

  @Test
  public void replaceDollarEdgeCases() throws MacroException {
    ImmutableMap<String, String> replacements =
        ImmutableMap.of(
            "arg1", "something",
            "arg2", "something else",
            "$", "dollar");
    String actual =
        MacroFinder.replace(
            ImmutableMap.of(
                "macro", new FunctionMacroReplacer<>(args -> replacements.get(args.get(0)))),
            "hello $\\$(macro arg1) goodbye $$(macro arg2) $(macro \\$)",
            true,
            new StringMacroCombiner());
    assertEquals("hello $$(macro arg1) goodbye $something else dollar", actual);
  }

  @Test
  public void match() throws MacroException {
    assertThat(
        MacroFinder.match(ImmutableSet.of("macro1"), "nothing to see here"),
        Matchers.equalTo(Optional.empty()));
    assertThat(
        MacroFinder.match(ImmutableSet.of("macro1"), "$(macro1)").get(),
        Matchers.equalTo(
            MacroMatchResult.builder()
                .setMacroType("macro1")
                .setStartIndex(0)
                .setEndIndex(9)
                .setEscaped(false)
                .build()));
    assertThat(
        MacroFinder.match(ImmutableSet.of("macro1"), "$(macro1 arg)"),
        Matchers.equalTo(
            Optional.of(
                MacroMatchResult.builder()
                    .setMacroType("macro1")
                    .addMacroInput("arg")
                    .setStartIndex(0)
                    .setEndIndex(13)
                    .setEscaped(false)
                    .build())));
  }

  @Test
  public void matchWithNestedParens() throws MacroException {
    assertThat(
        MacroFinder.match(ImmutableSet.of("macro1"), "$(macro1 arg_fun(param))"),
        Matchers.equalTo(
            Optional.of(
                MacroMatchResult.builder()
                    .setMacroType("macro1")
                    .addMacroInput("arg_fun(param)")
                    .setStartIndex(0)
                    .setEndIndex(24)
                    .setEscaped(false)
                    .build())));
  }

  @Test
  public void matchWithUnbalancedParensDoesNotThrow() throws MacroException {
    assertThat(
        MacroFinder.match(ImmutableSet.of("macro1"), "$(macro1 ()"),
        Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void matchWithQuotesDoesNotCountParens() throws MacroException {
    assertThat(
        MacroFinder.match(ImmutableSet.of("macro1"), "$(macro1 ')')"),
        Matchers.equalTo(
            Optional.of(
                MacroMatchResult.builder()
                    .setMacroType("macro1")
                    .addMacroInput(")")
                    .setStartIndex(0)
                    .setEndIndex(13)
                    .setEscaped(false)
                    .build())));
    assertThat(
        MacroFinder.match(ImmutableSet.of("macro1"), "$(macro1 \")\")"),
        Matchers.equalTo(
            Optional.of(
                MacroMatchResult.builder()
                    .setMacroType("macro1")
                    .addMacroInput(")")
                    .setStartIndex(0)
                    .setEndIndex(13)
                    .setEscaped(false)
                    .build())));
  }

  @Test(expected = MacroException.class)
  public void matchWithInvalidName() throws MacroException {
    MacroFinder.match(ImmutableSet.of("macro1"), "$(macro2)");
  }
}
