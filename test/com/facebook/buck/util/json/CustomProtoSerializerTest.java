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

package com.facebook.buck.util.json;

import static org.junit.Assert.assertEquals;

import build.bazel.remote.execution.v2.ExecutedActionMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.IOException;
import java.util.Optional;
import org.junit.Test;

public class CustomProtoSerializerTest {
  static class ClassWithProto {
    @JsonSerialize(using = CustomOptionalProtoSerializer.class)
    public Optional<ExecutedActionMetadata> executedActionMetadata;
  }

  @Test
  public void testSerialization() throws IOException {
    ExecutedActionMetadata executedActionMetadata =
        ExecutedActionMetadata.newBuilder().setWorker("abc").build();
    ClassWithProto classWithProto = new ClassWithProto();
    classWithProto.executedActionMetadata = Optional.of(executedActionMetadata);
    assertEquals(
        "{\"executedActionMetadata\":\"{\\n  \\\"worker\\\": \\\"abc\\\"\\n}\"}",
        new ObjectMapper().writeValueAsString(classWithProto));
  }

  @Test
  public void testEmpty() throws IOException {
    ClassWithProto classWithProto = new ClassWithProto();
    classWithProto.executedActionMetadata = Optional.empty();
    assertEquals(
        "{\"executedActionMetadata\":\"No message\"}",
        new ObjectMapper().writeValueAsString(classWithProto));
  }
}
