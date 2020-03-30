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

package com.facebook.buck.core.test.rule;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import java.io.IOException;

/** The JSON serializable object for test protocol */
@BuckStyleValue
public abstract class ExternalRunnerTestProtocol implements ExternalTestSpec {

  /** @return the build target of this rule. */
  protected abstract BuildTarget getTarget();

  /**
   * @return the test protocol specs defined in the BUCK file. This is a free form dictionary that
   *     will be serialized and passed to the test runners.
   */
  protected abstract CoercedTestRunnerSpec getSpecs();

  protected abstract SourcePathResolverAdapter getSourcePathResolver();

  @Override
  public void serialize(JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
      throws IOException {
    jsonGenerator.writeStartObject();
    jsonGenerator.writeStringField("target", getTarget().toString());
    jsonGenerator.writeFieldName("specs");
    getSpecs().serialize(jsonGenerator, getSourcePathResolver());
    jsonGenerator.writeEndObject();
  }

  @Override
  public void serializeWithType(
      JsonGenerator jsonGenerator,
      SerializerProvider serializerProvider,
      TypeSerializer typeSerializer)
      throws IOException {
    serialize(jsonGenerator, serializerProvider);
  }
}
