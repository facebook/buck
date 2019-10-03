/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.test.rule;

import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.Map;
import org.immutables.value.Value;

/**
 * The freeform JSON test protocol specification. This has the {@link
 * com.facebook.buck.rules.macros.StringWithMacros} from {@link TestRunnerSpec} resolved to {@link
 * Arg}s.
 */
@Value.Immutable(builder = false, copy = false)
public abstract class CoercedTestRunnerSpec {

  @Value.Parameter
  protected abstract Object getData();

  @Value.Check
  protected void check() {
    // the json should be a map, iterable, a single Arg, a Number, or a Boolean
    Object object = getData();
    Preconditions.checkState(
        object instanceof Map
            || object instanceof Iterable
            || object instanceof Arg
            || object instanceof Number
            || object instanceof Boolean);
  }

  /**
   * Serializes the underlying freeform JSON of {@link Arg}s.
   *
   * @param jsonGenerator the json writer
   * @param sourcePathResolver the rule resolver for resolving {@link Arg}s
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  public void serialize(JsonGenerator jsonGenerator, SourcePathResolver sourcePathResolver)
      throws IOException {
    if (getData() instanceof Map) {
      writeMap(jsonGenerator, (Map<Arg, CoercedTestRunnerSpec>) getData(), sourcePathResolver);
    } else if (getData() instanceof Iterable) {
      writeArray(jsonGenerator, (Iterable<CoercedTestRunnerSpec>) getData(), sourcePathResolver);
    } else if (getData() instanceof Arg) {
      writeArg(jsonGenerator, Arg.stringify((Arg) getData(), sourcePathResolver));
    } else if (getData() instanceof Number || getData() instanceof Boolean) {
      writeObject(jsonGenerator, getData());
    } else {
      throw new IllegalStateException("Unexpected data type");
    }
  }

  private void writeMap(
      JsonGenerator jsonGenerator,
      Map<Arg, CoercedTestRunnerSpec> data,
      SourcePathResolver sourcePathResolver)
      throws IOException {
    jsonGenerator.writeStartObject();
    for (Map.Entry<Arg, CoercedTestRunnerSpec> entry : data.entrySet()) {
      jsonGenerator.writeFieldName(Arg.stringify(entry.getKey(), sourcePathResolver));
      entry.getValue().serialize(jsonGenerator, sourcePathResolver);
    }
    jsonGenerator.writeEndObject();
  }

  private void writeArray(
      JsonGenerator jsonGenerator,
      Iterable<CoercedTestRunnerSpec> data,
      SourcePathResolver sourcePathResolver)
      throws IOException {
    jsonGenerator.writeStartArray();
    for (CoercedTestRunnerSpec item : data) {
      item.serialize(jsonGenerator, sourcePathResolver);
    }
    jsonGenerator.writeEndArray();
  }

  private void writeArg(JsonGenerator jsonGenerator, String data) throws IOException {
    jsonGenerator.writeString(data);
  }

  private void writeObject(JsonGenerator jsonGenerator, Object data) throws IOException {
    jsonGenerator.writeObject(data);
  }
}
