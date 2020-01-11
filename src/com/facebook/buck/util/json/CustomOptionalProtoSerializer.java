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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.util.Optional;

/**
 * Custom serializer for ProtoBuf classes as jackson is not able to serialize them.
 * https://stackoverflow.com/questions/51588778/convert-a-protobuf-to-json-using-jackson
 */
public class CustomOptionalProtoSerializer extends JsonSerializer<Optional<Message>> {
  @Override
  public void serialize(
      Optional<Message> message, JsonGenerator gen, SerializerProvider serializers)
      throws IOException {
    if (message.isPresent()) {
      try {
        gen.writeString(JsonFormat.printer().print(message.get()));
        // Lets add this so we don't get unnecessary errors trying to print logs
      } catch (IOException e) {
        gen.writeString("Unexpected error trying to serialize proto object");
      }
    } else {
      gen.writeString("No message");
    }
  }
}
