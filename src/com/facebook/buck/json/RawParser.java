/*
 * Copyright 2014-present Facebook, Inc.
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.Reader;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * A simple JSON parser that can parse a JSON map to a raw {@code Map<String, Object>} Java object.
 */
public class RawParser {

  /** Utility class: do not instantiate. */
  private RawParser() {}

  /**
   * Consumes the next JSON map from the reader, parses it as a Java {@link Map} to return, and then
   * closes the reader.
   * <p>
   * <strong>Warning:</strong> This method closes the reader.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseFromReader(Reader reader) throws IOException {
    JsonReader jsonReader = new JsonReader(reader);
    try {
      JsonObject json = new Gson().fromJson(reader, JsonObject.class);
      Map<String, Object> map = (Map<String, Object>) toRawTypes(json);
      return Preconditions.checkNotNull(map);
    } finally {
      jsonReader.close();
    }
  }

  /**
   * @return One of: String, Boolean, Long, Number, List<Object>, Map<String, Object>.
   */
  @Nullable
  @VisibleForTesting
  static Object toRawTypes(JsonElement json) {
    // Cases are ordered from most common to least common.
    if (json.isJsonPrimitive()) {
      JsonPrimitive primitive = json.getAsJsonPrimitive();
      if (primitive.isString()) {
        return primitive.getAsString();
      } else if (primitive.isBoolean()) {
        return primitive.getAsBoolean();
      } else if (primitive.isNumber()) {
        Number number = primitive.getAsNumber();
        // Number is likely an instance of class com.google.gson.internal.LazilyParsedNumber.
        if (number.longValue() == number.doubleValue()) {
          return number.longValue();
        } else {
          return number;
        }
      } else {
        throw new IllegalStateException("Unknown primitive type: " + primitive);
      }
    } else if (json.isJsonArray()) {
      JsonArray array = json.getAsJsonArray();
      List<Object> out = Lists.newArrayListWithCapacity(array.size());
      for (JsonElement item : array) {
        out.add(toRawTypes(item));
      }
      return out;
    } else if (json.isJsonObject()) {
      Map<String, Object> out = Maps.newHashMap();
      for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
        // On a large project, without invoking intern(), we have seen `buck targets` OOM. When this
        // happened, according to the .hprof file generated using -XX:+HeapDumpOnOutOfMemoryError,
        // 39.6% of the memory was spent on char[] objects while 14.5% was spent on Strings.
        // (Another 10.5% was spent on java.util.HashMap$Entry.) Introducing intern() stopped the
        // OOM from happening.
        out.put(entry.getKey().intern(), toRawTypes(entry.getValue()));
      }
      return out;
    } else if (json.isJsonNull()) {
      return null;
    } else {
      throw new IllegalStateException("Unknown type: " + json);
    }
  }

}
