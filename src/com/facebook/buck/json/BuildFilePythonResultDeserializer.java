/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.util.ImmutableMapWithNullValues;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * JSON deserializer specialized to parse the output of {@code buck.py} into {@link
 * BuildFilePythonResult}.
 *
 * <p>Uses Guava {@link ImmutableMap} and {@link ImmutableList} to reduce memory pressure, along
 * with {@link ImmutableMapWithNullValues} to allow {@code null} values in the maps.
 */
final class BuildFilePythonResultDeserializer extends StdDeserializer<BuildFilePythonResult> {
  private static final Interner<String> STRING_INTERNER = Interners.newWeakInterner();

  public BuildFilePythonResultDeserializer() {
    super(BuildFilePythonResult.class);
  }

  @Override
  public BuildFilePythonResult deserialize(JsonParser jp, DeserializationContext ctxt)
      throws IOException, JsonParseException {
    if (jp.getCurrentToken() != JsonToken.START_OBJECT) {
      throw new JsonParseException(jp, "Missing expected START_OBJECT");
    }
    ImmutableList<Map<String, Object>> values = ImmutableList.of();
    ImmutableList<Map<String, Object>> diagnostics = ImmutableList.of();
    Optional<String> profile = Optional.empty();
    String fieldName;
    while ((fieldName = jp.nextFieldName()) != null) {
      switch (fieldName) {
        case "values":
          values = deserializeObjectList(jp);
          break;
        case "diagnostics":
          diagnostics = deserializeObjectList(jp);
          break;
        case "profile":
          profile = Optional.of(jp.nextTextValue());
          break;
        default:
          throw new JsonParseException(jp, "Unexpected field name: " + fieldName);
      }
    }
    if (jp.getCurrentToken() != JsonToken.END_OBJECT) {
      throw new JsonParseException(jp, "Missing expected END_OBJECT");
    }
    return BuildFilePythonResult.of(values, diagnostics, profile);
  }

  private static ImmutableList<Map<String, Object>> deserializeObjectList(JsonParser jp)
      throws IOException, JsonParseException {
    JsonToken token = jp.nextToken();
    if (token != JsonToken.START_ARRAY) {
      throw new JsonParseException(jp, "Missing expected START_ARRAY, got: " + token);
    }
    ImmutableList.Builder<Map<String, Object>> result = ImmutableList.builder();
    while ((token = jp.nextToken()) == JsonToken.START_OBJECT) {
      result.add(deserializeObject(jp));
    }
    if (token != JsonToken.END_ARRAY) {
      throw new JsonParseException(jp, "Missing expected END_ARRAY");
    }
    return result.build();
  }

  private static Map<String, Object> deserializeObject(JsonParser jp)
      throws IOException, JsonParseException {
    ImmutableMapWithNullValues.Builder<String, Object> builder =
        ImmutableMapWithNullValues.Builder.insertionOrder();
    String fieldName;
    while ((fieldName = jp.nextFieldName()) != null) {
      builder.put(STRING_INTERNER.intern(fieldName), deserializeRecursive(jp, jp.nextToken()));
    }
    if (jp.getCurrentToken() != JsonToken.END_OBJECT) {
      throw new JsonParseException(jp, "Missing expected END_OBJECT");
    }
    return builder.build();
  }

  private static List<Object> deserializeList(JsonParser jp)
      throws IOException, JsonParseException {
    ImmutableList.Builder<Object> builder = ImmutableList.builder();
    JsonToken token;
    while ((token = jp.nextToken()) != JsonToken.END_ARRAY) {
      builder.add(deserializeRecursive(jp, token));
    }
    if (token != JsonToken.END_ARRAY) {
      throw new JsonParseException(jp, "Missing expected END_ARRAY");
    }
    return builder.build();
  }

  @Nullable
  private static Object deserializeRecursive(JsonParser jp, JsonToken token)
      throws IOException, JsonParseException {
    switch (token) {
      case START_OBJECT:
        return deserializeObject(jp);
      case START_ARRAY:
        return deserializeList(jp);
      case VALUE_TRUE:
        return true;
      case VALUE_FALSE:
        return false;
      case VALUE_NULL:
        return null;
      case VALUE_NUMBER_FLOAT:
        return jp.getDoubleValue();
      case VALUE_NUMBER_INT:
        return jp.getLongValue();
      case VALUE_STRING:
        return STRING_INTERNER.intern(jp.getText());
        // $CASES-OMITTED$
      default:
        throw new JsonParseException(jp, "Unexpected token: " + token.toString());
    }
  }
}
