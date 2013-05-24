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

import com.fasterxml.jackson.core.JsonParseException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.io.IOException;
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
    Map<String, Object> token = parser.next();
    assertEquals(
        ImmutableMap.of("srcs",
            ImmutableList.of(
                "src/com/facebook/buck/Bar.java",
                "src/com/facebook/buck/Foo.java")),
        token);
  }
}
