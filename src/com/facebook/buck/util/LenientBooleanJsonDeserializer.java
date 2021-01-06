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

package com.facebook.buck.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;

/** JSON deserializer which converts boolean, numeric, and string values into booleans. */
public class LenientBooleanJsonDeserializer extends JsonDeserializer<Boolean> {
  @Override
  public Boolean deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
      throws IOException {
    switch (jsonParser.getText().toLowerCase()) {
      case "false":
      case "\"false\"":
      case "0":
        return false;
      case "true":
      case "\"true\"":
      case "1":
        return true;
      default:
        throw new IllegalArgumentException(
            String.format("Did not recognize \"%s\" as a boolean", jsonParser.getText()));
    }
  }
}
