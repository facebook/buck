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

package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;

/** JSON serializer of {@link BuildTarget} that uses fully qualified name to represent a target. */
public class BuildTargetSimpleSerializer extends StdSerializer<BuildTarget> {

  protected BuildTargetSimpleSerializer(Class<BuildTarget> instanceClass) {
    super(instanceClass);
  }

  @Override
  public void serialize(BuildTarget buildTarget, JsonGenerator gen, SerializerProvider provider)
      throws IOException {
    gen.writeString(buildTarget.getFullyQualifiedName());
  }
}
