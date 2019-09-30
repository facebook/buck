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
    // the json should be a map, iterable, a single Arg, or a Number
    Object object = getData();
    Preconditions.checkState(
        object instanceof Map
            || object instanceof Iterable
            || object instanceof Arg
            || object instanceof Number);
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

      jsonGenerator.writeStartObject();
      for (Map.Entry<Arg, CoercedTestRunnerSpec> entry :
          ((Map<Arg, CoercedTestRunnerSpec>) getData()).entrySet()) {
        jsonGenerator.writeFieldName(Arg.stringify(entry.getKey(), sourcePathResolver));
        entry.getValue().serialize(jsonGenerator, sourcePathResolver);
      }
      jsonGenerator.writeEndObject();
    } else if (getData() instanceof Iterable) {
      jsonGenerator.writeStartArray();
      for (CoercedTestRunnerSpec item : (Iterable<CoercedTestRunnerSpec>) getData()) {
        item.serialize(jsonGenerator, sourcePathResolver);
      }
      jsonGenerator.writeEndArray();
    } else if (getData() instanceof Arg) {
      jsonGenerator.writeString(Arg.stringify((Arg) getData(), sourcePathResolver));
    } else if (getData() instanceof Number) {
      jsonGenerator.writeObject(getData());
    } else {
      throw new IllegalStateException("Unexpected data type");
    }
  }
}
