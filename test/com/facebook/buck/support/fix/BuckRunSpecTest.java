/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.support.fix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Paths;
import org.junit.Test;

public class BuckRunSpecTest {
  @Test
  public void serializesProperly() throws IOException {
    // Handle Windows stringification
    String expectedCwd = "cwd/goes/here";
    if (Platform.detect() == Platform.WINDOWS) {
      expectedCwd = "cwd\\goes\\here";
    }

    BuckRunSpec spec =
        ImmutableBuckRunSpec.of(
            ImmutableList.of("foo", "bar"),
            ImmutableMap.of("FOO", "BAR"),
            Paths.get("cwd/goes/here"),
            true);

    String jsonString = ObjectMappers.WRITER.writeValueAsString(spec);

    JsonNode readData = ObjectMappers.READER.readTree(jsonString);

    assertEquals("foo", readData.get("path").asText());
    assertEquals(2, readData.get("argv").size());
    assertEquals("foo", readData.get("argv").get(0).asText());
    assertEquals("bar", readData.get("argv").get(1).asText());
    assertEquals(1, readData.get("envp").size());
    assertEquals("BAR", readData.get("envp").get("FOO").asText());
    assertEquals(expectedCwd, readData.get("cwd").asText());
    assertTrue(readData.get("is_fix_script").asBoolean());
  }
}
