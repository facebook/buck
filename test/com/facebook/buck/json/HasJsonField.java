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

package com.facebook.buck.json;

import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;

/** Matches an {@link JsonNode} which has the specified field. */
public class HasJsonField extends BaseMatcher<JsonNode> {
  private String fieldName;
  private Matcher<? super JsonNode> valueMatcher;

  public HasJsonField(String fieldName, Matcher<? super JsonNode> valueMatcher) {
    this.fieldName = fieldName;
    this.valueMatcher = valueMatcher;
  }

  @Override
  public boolean matches(Object o) {
    if (o instanceof JsonNode) {
      JsonNode node = (JsonNode) o;
      if (!node.has(fieldName)) {
        return false;
      }
      return valueMatcher.matches(node.get(fieldName));
    }
    return false;
  }

  @Override
  public void describeTo(Description description) {
    description.appendText("JSON object with field [" + fieldName + "] ");
    description.appendDescriptionOf(valueMatcher);
    description.appendText("\n");
  }

  @Override
  public void describeMismatch(Object item, Description description) {
    if (item instanceof JsonNode) {
      JsonNode node = (JsonNode) item;
      try {
        description
            .appendText("was ")
            .appendText(ObjectMappers.WRITER.withDefaultPrettyPrinter().writeValueAsString(node));
      } catch (IOException e) {
        super.describeMismatch(item, description);
      }
    } else {
      super.describeMismatch(item, description);
    }
  }
}
