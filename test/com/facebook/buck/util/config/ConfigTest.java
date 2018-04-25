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

package com.facebook.buck.util.config;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ConfigTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void shouldGetBooleanValues() {
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
  public void testGetFloat() {
    assertEquals(
        Optional.of(0.333f), ConfigBuilder.createFromText("[a]", "  f = 0.333").getFloat("a", "f"));
  }

  @Test
  public void testGetList() {
    String[] config = {"[a]", "  b = foo,bar,baz"};
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    ImmutableList<String> expected = builder.add("foo").add("bar").add("baz").build();
    assertEquals(
        expected,
        ConfigBuilder.createFromText(config[0], config[1]).getListWithoutComments("a", "b"));
    assertEquals(
        Optional.of(expected),
        ConfigBuilder.createFromText(config[0], config[1])
            .getOptionalListWithoutComments("a", "b"));
  }

  @Test
  public void testGetListWithCustomSplitChar() {
    String[] config = {"[a]", "  b = cool;story;bro"};
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    ImmutableList<String> expected = builder.add("cool").add("story").add("bro").build();
    assertEquals(
        expected,
        ConfigBuilder.createFromText(config[0], config[1]).getListWithoutComments("a", "b", ';'));
    assertEquals(
        Optional.of(expected),
        ConfigBuilder.createFromText(config[0], config[1])
            .getOptionalListWithoutComments("a", "b", ';'));
  }

  @Test
  public void testGetEmptyList() {
    String[] config = {"[a]", "b ="};
    assertThat(
        ConfigBuilder.createFromText(config[0], config[1]).getOptionalListWithoutComments("a", "b"),
        is(equalTo(Optional.of(ImmutableList.of()))));
  }

  @Test
  public void testGetEmptyListWithComment() {
    String[] config = {"[a]", "b = ; comment"};
    assertThat(
        ConfigBuilder.createFromText(config[0], config[1]).getOptionalListWithoutComments("a", "b"),
        is(equalTo(Optional.of(ImmutableList.of()))));
  }

  @Test
  public void testGetUnspecifiedList() {
    String[] config = {"[a]", "c ="};
    assertThat(
        ConfigBuilder.createFromText(config[0], config[1]).getOptionalListWithoutComments("a", "b"),
        is(equalTo(Optional.empty())));
  }

  @Test(expected = HumanReadableException.class)
  public void testGetMalformedFloat() {
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
  public void getQuotedValue() {
    assertEquals(
        "quoted strings are decoded",
        "foo [bar]\t\"\tbaz\\\n\u001f\udbcc\udc05\u6211\r",
        ConfigBuilder.createFromText(
                "[foo]", "bar=\"foo [bar]\\t\\\"\tbaz\\\\\\n\\x1f\\U00103005\\u6211\\r\"")
            .getValue("foo", "bar")
            .get());
  }

  @Test
  public void getListWithQuotedParts() {
    assertEquals(
        "lists with quoted parts are decoded",
        ImmutableList.of("foo bar", ",,,", ";", "\n"),
        ConfigBuilder.createFromText("[foo]", "bar=\"foo bar\" ,,, ; \"\\n\"")
            .getListWithoutComments("foo", "bar", ' '));
  }

  @Test
  public void invalidEscapeSequence() {
    String msg = "";
    try {
      ConfigBuilder.createFromText("[foo]\nbar=\"\\z\"").getValue("foo", "bar");
    } catch (HumanReadableException e) {
      msg = e.getMessage();
    }
    assertEquals(".buckconfig: foo:bar: Invalid escape sequence: \\z", msg);
  }

  @Test
  public void invalidHexSequence() {
    String msg = "";
    try {
      ConfigBuilder.createFromText("[x]\ny=\"\\x4-\"").getValue("x", "y");
    } catch (HumanReadableException e) {
      msg = e.getMessage();
    }
    assertEquals(".buckconfig: x:y: Invalid hexadecimal digit in sequence: \\x4-", msg);
  }

  @Test
  public void missingCloseQuote() {
    String msg = "";
    try {
      ConfigBuilder.createFromText("[foo]\nbar=xyz\"asdfasdfasdf\n").getValue("foo", "bar");
    } catch (HumanReadableException e) {
      msg = e.getMessage();
    }
    assertEquals(".buckconfig: foo:bar: Input ends inside quoted string: \"asdfasdfa...", msg);
  }

  @Test
  public void shortHexSequence() {
    String msg = "";
    try {
      ConfigBuilder.createFromText("[foo]\nbar=\"\\u002").getValue("foo", "bar");
    } catch (HumanReadableException e) {
      msg = e.getMessage();
    }
    assertEquals(".buckconfig: foo:bar: Input ends inside hexadecimal sequence: \\u002", msg);
  }

  @Test
  public void configReference() {
    Config config =
        new Config(
            RawConfig.builder()
                .put("section", "field1", "hello $(config section.field2) world")
                .put("section", "field2", "goodbye")
                .build());
    assertThat(
        config.get("section", "field1"), Matchers.equalTo(Optional.of("hello goodbye world")));
  }

  @Test
  public void configReferenceAtStart() {
    Config config =
        new Config(
            RawConfig.builder()
                .put("section", "field1", "$(config section.field2) world")
                .put("section", "field2", "goodbye")
                .build());
    assertThat(config.get("section", "field1"), Matchers.equalTo(Optional.of("goodbye world")));
  }

  @Test
  public void escapedReference() {
    Config config =
        new Config(
            RawConfig.builder()
                .put("section", "field1", "hello \\$(config section.field2) world")
                .put("section", "field2", "goodbye")
                .build());
    assertThat(
        config.get("section", "field1"),
        Matchers.equalTo(Optional.of("hello $(config section.field2) world")));
  }

  @Test
  public void recursiveConfigReference() {
    Config config =
        new Config(
            RawConfig.builder()
                .put("section", "field1", "hello $(config section.field2) world")
                .put("section", "field2", "hello $(config section.field3) world")
                .put("section", "field3", "goodbye")
                .build());
    assertThat(
        config.get("section", "field1"),
        Matchers.equalTo(Optional.of("hello hello goodbye world world")));
  }

  @Test
  public void cyclicalConfigReference() {
    Config config =
        new Config(
            RawConfig.builder()
                .put("section", "field1", "$(config section.field2)")
                .put("section", "field2", "$(config section.field3)")
                .put("section", "field3", "$(config section.field1)")
                .build());
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        "section.field1 -> section.field2 -> section.field3 -> section.field1");
    config.get("section", "field1");
  }

  @Test
  public void locationMacroIsPreserved() {
    Config config =
        new Config(
            RawConfig.builder().put("section", "field", "hello $(location input) world").build());
    assertThat(
        config.get("section", "field"),
        Matchers.equalTo(Optional.of("hello $(location input) world")));
  }

  @Test
  public void equalsIgnoringIgnoresValueOfSingleField() {
    assertThat(
        new Config(RawConfig.builder().put("section", "field", "valueLeft").build())
            .equalsIgnoring(
                new Config(RawConfig.builder().put("section", "field", "valueRight").build()),
                ImmutableMap.of("section", ImmutableSet.of("field"))),
        is(true));

    assertThat(
        new Config(
                RawConfig.builder()
                    .put("section", "field", "valueLeft")
                    .put("section", "field_b", "value")
                    .build())
            .equalsIgnoring(
                new Config(
                    RawConfig.builder()
                        .put("section", "field", "valueRight")
                        .put("section", "field_b", "value")
                        .build()),
                ImmutableMap.of("section", ImmutableSet.of("field"))),
        is(true));
  }

  @Test
  public void equalsIgnoringIgnoresPresenceOfIgnoredField() {
    assertThat(
        new Config(RawConfig.builder().put("section", "field", "value").build())
            .equalsIgnoring(
                new Config(RawConfig.builder().build()),
                ImmutableMap.of("section", ImmutableSet.of("field"))),
        is(true));

    assertThat(
        new Config(
                RawConfig.builder()
                    .put("section", "field", "value")
                    .put("section", "field_b", "value")
                    .build())
            .equalsIgnoring(
                new Config(RawConfig.builder().put("section", "field_b", "value").build()),
                ImmutableMap.of("section", ImmutableSet.of("field"))),
        is(true));

    assertThat(
        new Config(
                RawConfig.builder()
                    .put("section", "field", "value")
                    .put("section_b", "field_b", "value")
                    .build())
            .equalsIgnoring(
                new Config(RawConfig.builder().put("section_b", "field_b", "value").build()),
                ImmutableMap.of("section", ImmutableSet.of("field"))),
        is(true));
  }
}
