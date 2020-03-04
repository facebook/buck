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

import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.datatype.guava.deser.GuavaMapDeserializer;
import java.io.IOException;

/** Jackson serializer for {@link com.facebook.buck.util.collect.TwoArraysImmutableHashMap}. */
class TwoArraysImmutableHashMapModule extends Module {

  @Override
  public String getModuleName() {
    return TwoArraysImmutableHashMap.class.getName();
  }

  @Override
  public Version version() {
    return Version.unknownVersion();
  }

  @Override
  public void setupModule(SetupContext context) {
    context.addDeserializers(
        new Deserializers.Base() {
          @Override
          public JsonDeserializer<?> findMapDeserializer(
              MapType type,
              DeserializationConfig config,
              BeanDescription beanDesc,
              KeyDeserializer keyDeserializer,
              TypeDeserializer elementTypeDeserializer,
              JsonDeserializer<?> elementDeserializer)
              throws JsonMappingException {
            if (type.getRawClass() == TwoArraysImmutableHashMap.class) {
              return new TwoArraysImmutableHashMapDeserializer(
                  type, keyDeserializer, elementTypeDeserializer, elementDeserializer);
            }

            return null;
          }
        });
  }

  private static class TwoArraysImmutableHashMapDeserializer extends GuavaMapDeserializer<Object> {

    public TwoArraysImmutableHashMapDeserializer(
        MapType type,
        KeyDeserializer keyDeserializer,
        TypeDeserializer elementTypeDeserializer,
        JsonDeserializer<?> elementDeserializer) {
      super(type, keyDeserializer, elementTypeDeserializer, elementDeserializer);
    }

    @Override
    public GuavaMapDeserializer<Object> withResolved(
        KeyDeserializer keyDeser, TypeDeserializer typeDeser, JsonDeserializer<?> valueDeser) {
      return new TwoArraysImmutableHashMapDeserializer(_mapType, keyDeser, typeDeser, valueDeser);
    }

    @Override
    protected Object _deserializeEntries(JsonParser p, DeserializationContext ctxt)
        throws IOException {
      final KeyDeserializer keyDes = _keyDeserializer;
      final JsonDeserializer<?> valueDes = _valueDeserializer;
      final TypeDeserializer typeDeser = _typeDeserializerForValue;

      TwoArraysImmutableHashMap.Builder<Object, Object> builder =
          TwoArraysImmutableHashMap.builder();
      for (; p.getCurrentToken() == JsonToken.FIELD_NAME; p.nextToken()) {
        // Must point to field name now
        String fieldName = p.getCurrentName();
        Object key = (keyDes == null) ? fieldName : keyDes.deserializeKey(fieldName, ctxt);
        p.nextToken();
        Object value;
        if (typeDeser == null) {
          value = valueDes.deserialize(p, ctxt);
        } else {
          value = valueDes.deserializeWithType(p, ctxt, typeDeser);
        }
        builder.put(key, value);
      }

      return builder.build();
    }
  }
}
