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

package com.facebook.buck.externalactions.utils;

import static org.hamcrest.MatcherAssert.assertThat;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.externalactions.model.JsonArgs;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.io.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.hamcrest.junit.ExpectedException;
import org.junit.Rule;
import org.junit.Test;

public class ExternalActionsUtilsTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();
  @Rule public ExpectedException expectedThrownException = ExpectedException.none();

  @Test
  public void canReadJsonArgs() throws Exception {
    Path jsonFile = temporaryFolder.newFile().getPath();
    FakeJsonArgs args = FakeJsonArgs.of("test_value");
    String json = ObjectMappers.WRITER.writeValueAsString(args);
    Files.asCharSink(jsonFile.toFile(), StandardCharsets.UTF_8).write(json);

    // Test the path overload of readJsonArgs
    FakeJsonArgs actual = ExternalActionsUtils.readJsonArgs(jsonFile, FakeJsonArgs.class);
    assertThat(actual.myAttr(), Matchers.equalTo("test_value"));

    // Test the string overload of readJsonArgs
    actual = ExternalActionsUtils.readJsonArgs(jsonFile.toString(), FakeJsonArgs.class);
    assertThat(actual.myAttr(), Matchers.equalTo("test_value"));
  }

  @Test
  public void FailureToReadJsonGivesCorrectErrorMessage() throws Exception {
    expectedThrownException.expect(IllegalStateException.class);
    expectedThrownException.expectMessage("Failed to read JSON from ");

    Path jsonFile =
        java.nio.file.Files.createTempFile(temporaryFolder.getRoot().getPath(), "prefix", "suffix");

    ExternalActionsUtils.readJsonArgs(jsonFile, FakeJsonArgs.class);
  }

  @BuckStyleValue
  abstract static class FakeJsonArgs implements JsonArgs {

    private static FakeJsonArgs of(String attr) {
      return ImmutableFakeJsonArgs.ofImpl(attr);
    }

    @JsonCreator
    private static FakeJsonArgs fromJson(@JsonProperty("attr") String attr) {
      return FakeJsonArgs.of(attr);
    }

    @JsonProperty("attr")
    protected abstract String myAttr();
  }
}
