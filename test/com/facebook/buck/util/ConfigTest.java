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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Iterator;
import java.util.Map;

public class ConfigTest {

  @Test
  public void testSortOrder() throws IOException {
    Reader reader = new StringReader(
        Joiner.on('\n').join(
            "[alias]",
            "one =   //foo:one",
            "two =   //foo:two",
            "three = //foo:three",
            "four  = //foo:four"));
    ImmutableMap<String, ImmutableMap<String, String>> sectionsToEntries =
        new Config(Inis.read(reader)).getSectionToEntries();
    Map<String, String> aliases = sectionsToEntries.get("alias");

    // Verify that entries are sorted in the order that they appear in the file, rather than in
    // alphabetical order, or some sort of hashed-key order.
    Iterator<Map.Entry<String, String>> entries = aliases.entrySet().iterator();

    Map.Entry<String, String> first = entries.next();
    assertEquals("one", first.getKey());

    Map.Entry<String, String> second = entries.next();
    assertEquals("two", second.getKey());

    Map.Entry<String, String> third = entries.next();
    assertEquals("three", third.getKey());

    Map.Entry<String, String> fourth = entries.next();
    assertEquals("four", fourth.getKey());

    assertFalse(entries.hasNext());
  }

  @Test
  public void shouldGetBooleanValues() throws IOException {
    assertTrue(
        "a.b is true when 'yes'",
        createFromText("[a]", "  b = yes").getBooleanValue("a", "b", true));
    assertTrue(
        "a.b is true when literally 'true'",
        createFromText("[a]", "  b = true").getBooleanValue("a", "b", true));
    assertTrue(
        "a.b is true when 'YES' (capitalized)",
        createFromText("[a]", "  b = YES").getBooleanValue("a", "b", true));
    assertFalse(
        "a.b is false by default",
        createFromText("[x]", "  y = COWS").getBooleanValue("a", "b", false));
    assertFalse(
        "a.b is true when 'no'",
        createFromText("[a]", "  b = no").getBooleanValue("a", "b", true));
  }

  @Test
  public void testOverride() throws IOException {
    Reader readerA = new StringReader(Joiner.on('\n').join(
        "[cache]",
        "    mode = dir,cassandra"));
    Reader readerB = new StringReader(Joiner.on('\n').join(
        "[cache]",
        "    mode ="));
    // Verify that no exception is thrown when a definition is overridden.
    new Config(Inis.read(readerA), Inis.read(readerB));
  }

  private static enum TestEnum {
    A,
    B
  }

  @Test
  public void getEnum() {
    Config config = new Config(
        ImmutableMap.of(
            "section",
            ImmutableMap.of("field", "A")));
    Optional<TestEnum> value = config.getEnum("section", "field", TestEnum.class);
    assertEquals(Optional.of(TestEnum.A), value);
  }

  @Test
  public void getEnumLowerCase() {
    Config config = new Config(
        ImmutableMap.of("section",
            ImmutableMap.of("field", "a")));
    Optional<TestEnum> value = config.getEnum("section", "field", TestEnum.class);
    assertEquals(Optional.of(TestEnum.A), value);
  }

  @Test(expected = HumanReadableException.class)
  public void getEnumInvalidValue() {
    Config config = new Config(
        ImmutableMap.of(
            "section",
            ImmutableMap.of("field", "C")));
    config.getEnum("section", "field", TestEnum.class);
  }

  private Config createFromText(String... lines) throws IOException {
    StringReader reader = new StringReader(Joiner.on('\n').join(lines));
    return new Config(Inis.read(reader));
  }


}
