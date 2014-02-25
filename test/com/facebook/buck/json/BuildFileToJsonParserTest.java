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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Unit test for {@link BuildFileToJsonParser}.
 */
public class BuildFileToJsonParserTest {

  @Test
  public void testSimpleParse() throws IOException {
    String json =
        "{" +
          "\"srcs\": [\"src/com/facebook/buck/Bar.java\", \"src/com/facebook/buck/Foo.java\"]" +
        "}";

    try (BuildFileToJsonParser parser = new BuildFileToJsonParser(json, false /* isServerMode */)) {
      assertEquals(
          ImmutableList.of(
              ImmutableMap.of("srcs",
                  ImmutableList.of(
                      "src/com/facebook/buck/Bar.java",
                      "src/com/facebook/buck/Foo.java"))),
          parser.nextRules());
    }
  }

  @Test
  public void testParseLong() throws IOException {
    String json = "{\"thing\": 27}";
    List<Map<String, Object>> tokens;
    try (BuildFileToJsonParser parser = new BuildFileToJsonParser(json, false /* isServerMode */)) {
      tokens = parser.nextRules();
    }

    assertEquals(1, tokens.size());
    Map<String, Object> rule = tokens.get(0);
    Object value = rule.get("thing");
    assertEquals(Long.class, value.getClass());
    assertEquals(27L, rule.get("thing"));
    assertNotEquals(27, rule.get("thing"));
  }

  @Test
  public void testServerModeParse() throws IOException {
    String json =
        "[{\"foo\": \"a:1\"}, {\"foo\": \"a:2\"}]\n" +
        "[{\"bar\": \"b:1\"}]";

    try (BuildFileToJsonParser parser = new BuildFileToJsonParser(json, true /* isServerMode */)) {
      assertEquals(
          ImmutableList.of(
              ImmutableMap.of("foo", "a:1"),
              ImmutableMap.of("foo", "a:2")),
          parser.nextRules());

      assertEquals(
          ImmutableList.of(
              ImmutableMap.of("bar", "b:1")),
          parser.nextRules());
    }
  }

  @Test
  public void testParseNestedStructures() throws IOException {
    String json =
         "{" +
             "\"foo\": [\"a\", \"b\"]," +
             "\"bar\": {\"baz\": \"quox\"}" +
         "}";

    try (BuildFileToJsonParser parser = new BuildFileToJsonParser(json, false /* isServerMode */)) {
      assertEquals(
          ImmutableList.of(ImmutableMap.of(
              "foo", ImmutableList.of("a", "b"),
              "bar", ImmutableMap.of("baz", "quox"))),
          parser.nextRules());
    }
  }

  @Test
  public void testToRawTypes() {
    JsonObject ruleJson = new JsonObject();

    ruleJson.addProperty("name", "foo");
    ruleJson.addProperty("export_deps", false);
    ruleJson.addProperty("proguard_config", (String)null);
    ruleJson.addProperty("source", 6);

    JsonArray deps = new JsonArray();
    deps.add(new JsonPrimitive("//foo:bar"));
    deps.add(new JsonPrimitive("//foo:baz"));
    deps.add(new JsonPrimitive("//foo:biz"));
    ruleJson.add("deps", deps);

    // Note that an ImmutableMap does not allow null values, but BuildFileToJsonParser.toRawTypes()
    // can return a Map with null values, so we use an ordinary HashMap for the expected value.
    Map<String, Object> expected = Maps.newHashMap();
    expected.put("name", "foo");
    expected.put("export_deps", false);
    expected.put("proguard_config", null);
    expected.put("source", 6L);
    expected.put("deps", ImmutableList.of("//foo:bar", "//foo:baz", "//foo:biz"));
    assertEquals(expected, BuildFileToJsonParser.toRawTypes(ruleJson));
  }
}
