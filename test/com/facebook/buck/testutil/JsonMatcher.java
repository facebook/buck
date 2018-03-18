/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.HashSet;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;

public class JsonMatcher extends TypeSafeDiagnosingMatcher<String> {

  private String expectedJson;

  public JsonMatcher(String json) {
    this.expectedJson = json;
  }

  @Override
  protected boolean matchesSafely(String actualJson, Description description) {
    try {
      JsonNode expectedObject =
          ObjectMappers.READER.readTree(ObjectMappers.createParser(String.format(expectedJson)));
      JsonNode actualObject =
          ObjectMappers.READER.readTree(ObjectMappers.createParser(String.format(actualJson)));

      if (!matchJsonObjects("/", expectedObject, actualObject, description)) {
        description.appendText(String.format(" in <%s>", actualJson));
        return false;
      }
    } catch (IOException e) {
      description.appendText(
          String.format("could not parse the following into a json object: <%s>", actualJson));
      return false;
    }
    return true;
  }

  /**
   * Static method which tries to match 2 JsonNode objects recursively.
   *
   * @param path: Path to start matching the objects from.
   * @param expected: First JsonNode.
   * @param actual: Second JsonNode.
   * @param description: The Description to be appended to.
   * @return true if the 2 objects match, false otherwise.
   */
  private static boolean matchJsonObjects(
      String path, JsonNode expected, JsonNode actual, Description description) {
    if (expected != null && actual != null && expected.isObject()) {
      if (!actual.isObject()) {
        description.appendText(String.format("the JsonNodeType is not OBJECT at path [%s]", path));
        return false;
      }
      HashSet<String> expectedFields = Sets.newHashSet(expected.fieldNames());
      HashSet<String> actualFields = Sets.newHashSet(actual.fieldNames());

      for (String field : expectedFields) {
        if (!actualFields.contains(field)) {
          description.appendText(String.format("expecting field [%s] at path [%s]", field, path));
          return false;
        }
        if (!matchJsonObjects(
            path + "/" + field, expected.get(field), actual.get(field), description)) {
          return false;
        }
      }
      if (!new HashSet<>().equals(Sets.difference(actualFields, expectedFields))) {
        description.appendText(
            String.format(
                "found unexpected fields %s at path [%s]",
                Sets.difference(actualFields, expectedFields).toString(), path));
        return false;
      }
    }
    if (!expected.equals(actual)) {
      description.appendText(String.format("mismatch at path [%s]", path));
      return false;
    }
    return true;
  }

  @Override
  public void describeTo(Description description) {
    description.appendText(String.format("Json string: <%s>", expectedJson));
  }
}
