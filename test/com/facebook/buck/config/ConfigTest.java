/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.IOException;

public class ConfigTest {

  @Test
  public void shouldGetBooleanValues() throws IOException {
    assertTrue(
        "a.b is true when 'yes'",
        ConfigBuilder.createFromText("[a]", "  b = yes").getBooleanValue("a", "b", true));
    assertTrue(
        "a.b is true when literally 'true'",
        ConfigBuilder.createFromText("[a]", "  b = true").getBooleanValue("a", "b", true));
    assertTrue(
        "a.b is true when 'YES' (capitalized)",
        ConfigBuilder.createFromText("[a]", "  b = YES").getBooleanValue("a", "b", true));
    assertFalse(
        "a.b is false by default",
        ConfigBuilder.createFromText("[x]", "  y = COWS").getBooleanValue("a", "b", false));
    assertFalse(
        "a.b is true when 'no'",
        ConfigBuilder.createFromText("[a]", "  b = no").getBooleanValue("a", "b", true));
  }

  @Test
  public void testGetFloat() throws IOException {
    assertEquals(
        Optional.of(0.333f),
        ConfigBuilder.createFromText("[a]", "  f = 0.333").getFloat("a", "f")
    );
  }

  @Test
  public void testGetList() throws IOException {
    String[] config = {"[a]", "  b = foo,bar,baz"};
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    ImmutableList<String> expected = builder.add("foo").add("bar").add("baz").build();
    assertEquals(
        expected,
        ConfigBuilder.createFromText(config[0], config[1]).getListWithoutComments("a", "b")
    );
    assertEquals(
        Optional.of(expected),
        ConfigBuilder.createFromText(config[0], config[1]).getOptionalListWithoutComments("a", "b")
    );
  }

  @Test
  public void testGetListWithCustomSplitChar() throws IOException {
    String[] config = {"[a]", "  b = cool;story;bro"};
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    ImmutableList<String> expected = builder.add("cool").add("story").add("bro").build();
    assertEquals(
        expected,
        ConfigBuilder.createFromText(config[0], config[1]).getListWithoutComments("a", "b", ';')
    );
    assertEquals(
        Optional.of(expected),
        ConfigBuilder.createFromText(config[0], config[1])
            .getOptionalListWithoutComments("a", "b", ';')
    );
  }

  @Test(expected = HumanReadableException.class)
  public void testGetMalformedFloat() throws IOException {
    ConfigBuilder.createFromText("[a]", "  f = potato").getFloat("a", "f");
  }

  private enum TestEnum {
    A,
    B
  }

  @Test
  public void getEnum() {
    Config config = ConfigBuilder.createFromText("[section]", "field = A");
    Optional<TestEnum> value = config.getEnum("section", "field", TestEnum.class);
    assertEquals(Optional.of(TestEnum.A), value);
  }

  @Test
  public void getEnumLowerCase() {
    Config config = ConfigBuilder.createFromText("[section]", "field = a");
    Optional<TestEnum> value = config.getEnum("section", "field", TestEnum.class);
    assertEquals(Optional.of(TestEnum.A), value);
  }

  @Test(expected = HumanReadableException.class)
  public void getEnumInvalidValue() {
    Config config = ConfigBuilder.createFromText("[section]", "field = C");
    config.getEnum("section", "field", TestEnum.class);
  }

  @Test
  public void getQuotedValue() throws IOException {
    assertEquals(
        "quoted strings are decoded",
        "foo [bar]\t\"\tbaz\\\n\u001f\udbcc\udc05\u6211\r",
        ConfigBuilder.createFromText(
            "[foo]",
            "bar=\"foo [bar]\\t\\\"\tbaz\\\\\\n\\x1f\\U00103005\\u6211\\r\"")
        .getValue("foo", "bar").get());
  }

  @Test
  public void getListWithQuotedParts() throws IOException {
    assertEquals(
        "lists with quoted parts are decoded",
        ImmutableList.of("foo bar", ",,,", ";", "\n"),
        ConfigBuilder.createFromText(
            "[foo]",
            "bar=\"foo bar\" ,,, ; \"\\n\"")
        .getListWithoutComments("foo", "bar", ' '));
  }

  @Test
  public void invalidEscapeSequence() throws IOException {
    String msg = "";
    try {
      ConfigBuilder.createFromText("[foo]\nbar=\"\\z\"")
        .getValue("foo", "bar");
    } catch (HumanReadableException e) {
      msg = e.getMessage();
    }
    assertEquals(
        ".buckconfig: foo:bar: Invalid escape sequence: \\z",
        msg);
  }

  @Test
  public void invalidHexSequence() throws IOException {
    String msg = "";
    try {
      ConfigBuilder.createFromText("[x]\ny=\"\\x4-\"")
        .getValue("x", "y");
    } catch (HumanReadableException e) {
      msg = e.getMessage();
    }
    assertEquals(
        ".buckconfig: x:y: Invalid hexadecimal digit in sequence: \\x4-",
        msg);
  }

  @Test
  public void missingCloseQuote() throws IOException {
    String msg = "";
    try {
      ConfigBuilder.createFromText("[foo]\nbar=xyz\"asdfasdfasdf\n")
        .getValue("foo", "bar");
    } catch (HumanReadableException e) {
      msg = e.getMessage();
    }
    assertEquals(
        ".buckconfig: foo:bar: Input ends inside quoted string: \"asdfasdfa...",
        msg);
  }

  @Test
  public void shortHexSequence() throws IOException {
    String msg = "";
    try {
      ConfigBuilder.createFromText("[foo]\nbar=\"\\u002")
        .getValue("foo", "bar");
    } catch (HumanReadableException e) {
      msg = e.getMessage();
    }
    assertEquals(
        ".buckconfig: foo:bar: Input ends inside hexadecimal sequence: \\u002",
        msg);
  }
}
