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

package com.facebook.buck.json;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.core.JsonParseException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Unit test for {@link BuildFileToJsonParser}.
 */
public class BuildFileToJsonParserTest {

  @Test
  public void testSimpleParse() throws JsonParseException, IOException {
    String json =
        "{" +
          "\"srcs\": [\"src/com/facebook/buck/Bar.java\", \"src/com/facebook/buck/Foo.java\"]" +
    		"}";
    BuildFileToJsonParser parser = new BuildFileToJsonParser(json);
    List<Map<String, Object>> tokens = parser.nextRules();
    assertEquals(
        ImmutableList.of(
            ImmutableMap.of("srcs",
                ImmutableList.of(
                    "src/com/facebook/buck/Bar.java",
                    "src/com/facebook/buck/Foo.java"))),
        tokens);
  }

  @Test
  public void testParseLong() throws IOException {
    String json = "{\"thing\": 27}";
    BuildFileToJsonParser parser = new BuildFileToJsonParser(json);
    List<Map<String, Object>> rules = parser.nextRules();

    assertEquals(1, rules.size());
    Map<String, Object> rule = rules.get(0);
    Object value = rule.get("thing");
    assertTrue(value instanceof Long);
    assertEquals(27L, rule.get("thing"));
    assertNotEquals(27, rule.get("thing"));
  }

  @Test
  public void testServerModeParse() throws JsonParseException, IOException {
    String json =
        "[{\"foo\": \"a:1\"}, {\"foo\": \"a:2\"}]\n" +
        "[{\"bar\": \"b:1\"}]";
    BuildFileToJsonParser parser = new BuildFileToJsonParser(json);

    List<Map<String, Object>> a = parser.nextRules();
    assertEquals(
        ImmutableList.of(
            ImmutableMap.of("foo", "a:1"),
            ImmutableMap.of("foo", "a:2")),
        a);

    List<Map<String, Object>> b = parser.nextRules();
    assertEquals(
        ImmutableList.of(
            ImmutableMap.of("bar", "b:1")),
        b);
  }
}
