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
import com.google.common.collect.ImmutableMap;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isEmptyString;

import static org.junit.Assert.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 * Unit tests for {@link InfoPlistSubstitution}.
 */
public class InfoPlistSubstitutionTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void emptyStringReplacementIsEmpty() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "",
            ImmutableMap.<String, String>of()),
        isEmptyString());
  }

  @Test
  public void emptyMapLeavesStringAsIs() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello world",
            ImmutableMap.<String, String>of()),
        equalTo("Hello world"));
  }

  @Test
  public void curlyBracesAreSubstituted() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello ${FOO} world",
            ImmutableMap.of("FOO", "cruel")),
        equalTo("Hello cruel world"));
  }

  @Test
  public void parensAreSubstituted() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello $(FOO) world",
            ImmutableMap.of("FOO", "cruel")),
        equalTo("Hello cruel world"));
  }

  @Test
  public void unknownModifiersAreIgnored() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello $(FOO:bar) world",
            ImmutableMap.of("FOO", "cruel")),
        equalTo("Hello cruel world"));
  }

  @Test
  public void multipleMatchesAreReplaced() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello $(FOO) $(BAR) world",
            ImmutableMap.of(
                "FOO", "cruel",
                "BAR", "mean")),
        equalTo("Hello cruel mean world"));
  }

  @Test
  public void unrecognizedVariableThrows() {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("Unrecognized plist variable: ${XYZZY:blurgh}");
    InfoPlistSubstitution.replaceVariablesInString(
        "Hello ${XYZZY:blurgh} world",
        ImmutableMap.<String, String>of());
  }

  @Test
  public void mismatchedParenIgnored() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello $(FOO} world",
            ImmutableMap.<String, String>of()),
        equalTo("Hello $(FOO} world"));
  }

  @Test
  public void mismatchedBraceIgnored() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello ${FOO) world",
            ImmutableMap.<String, String>of()),
        equalTo("Hello ${FOO) world"));
  }

  @Test
  public void replacementWithMatcherAppendReplacementSpecialChars() {
    assertThat(
        InfoPlistSubstitution.replaceVariablesInString(
            "Hello ${FOO} world",
            ImmutableMap.of(
                "FOO", "${BAZ}")),
        equalTo("Hello ${BAZ} world"));
  }
}
