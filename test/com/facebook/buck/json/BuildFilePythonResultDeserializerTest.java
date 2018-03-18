/*
 * Copyright 2017-present Facebook, Inc.
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

/** Tests for {@link BuildFilePythonResultDeserializer}. */
public final class BuildFilePythonResultDeserializerTest {
  @Test
  public void emptyParse() throws IOException {
    BuildFilePythonResult result = ObjectMappers.readValue("{}", BuildFilePythonResult.class);
    assertThat(
        result,
        is(BuildFilePythonResult.of(ImmutableList.of(), ImmutableList.of(), Optional.empty())));
  }

  @Test
  public void basicParseWithNull() throws IOException {
    BuildFilePythonResult result =
        ObjectMappers.readValue(
            "{\"values\":[{\"buck.foo\":null,\"buck.bar\":[1,2,3]}]}", BuildFilePythonResult.class);
    // Can't use ImmutableMap since we have a null
    Map<String, Object> expectedValues = new LinkedHashMap<>();
    expectedValues.put("buck.foo", null);

    // Note the L -- equality test will fail if these are Integer
    expectedValues.put("buck.bar", ImmutableList.of(1L, 2L, 3L));
    assertThat(
        result,
        is(
            BuildFilePythonResult.of(
                ImmutableList.of(expectedValues), ImmutableList.of(), Optional.empty())));
  }

  @Test
  public void resultWithDiagnostics() throws IOException {
    BuildFilePythonResult result =
        ObjectMappers.readValue(
            "{\"values\":[],"
                + "\"diagnostics\":[{\"message\":\"Oops\",\"level\":\"fatal\","
                + "\"source\":\"parse\",\"exception\":{\"type\":\"SyntaxError\","
                + "\"value\":\"Syntax error over there\",\"traceback\":\"(omitted)\","
                + "\"filename\":\"foo.py\",\"lineno\":12345,\"offset\":45678,\"text\":"
                + "\"this is a syntax error\"}}]}",
            BuildFilePythonResult.class);
    assertThat(
        result,
        is(
            BuildFilePythonResult.of(
                ImmutableList.of(),
                ImmutableList.of(
                    ImmutableMap.of(
                        "message",
                        "Oops",
                        "level",
                        "fatal",
                        "source",
                        "parse",
                        "exception",
                        ImmutableMap.builder()
                            .put("type", "SyntaxError")
                            .put("value", "Syntax error over there")
                            .put("traceback", "(omitted)")
                            .put("filename", "foo.py")
                            .put("lineno", 12345L)
                            .put("offset", 45678L)
                            .put("text", "this is a syntax error")
                            .build())),
                Optional.empty())));
  }

  @Test
  public void resultWithProfile() throws IOException {
    BuildFilePythonResult result =
        ObjectMappers.readValue(
            "{\"values\":[],\"profile\":\"this is a profile\"}", BuildFilePythonResult.class);
    assertThat(
        result,
        is(
            BuildFilePythonResult.of(
                ImmutableList.of(), ImmutableList.of(), Optional.of("this is a profile"))));
  }
}
