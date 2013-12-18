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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.Map;

/**
 * This is a special JSON parser that is customized to consume the JSON output of buck.py.
 * Object values may be one of: null, a string, or an array of strings. This means that no
 * sort of nested arrays or objects are allowed in the output as Parser is implemented
 * today. This simplification makes it easier to leverage Jackson's streaming JSON API.
 */
public class BuildFileToJsonParser implements AutoCloseable {

  private final Gson gson;
  private final JsonReader reader;

  /**
   * The parser below uses these objects for stateful purposes with the ultimate goal
   * of populating the parsed rules into `currentObjects`.
   *
   * The parser is expecting two different styles of output:
   *   1. Server mode: [{"key": "value"}, {"key": "value"}, ...]
   *   2. Regular mode: {"key": "value"}, {"key": "value"}, ...
   *
   * Server mode output is a necessary short-term step to keep logic in the main Parser
   * consistent (expecting to be able to correlate a set of rules with the specific BUCK file
   * that generated them).  This requirement creates an unnecessary performance weakness
   * in this design where we cannot parallelize buck.py's parsing of BUCK files with buck's
   * processing of the result into a DAG.  Once this can be addressed, server mode should be
   * eliminated.
   */
  private final boolean isServerMode;

  /**
   * @param jsonReader That contains the JSON data.
   */
  public BuildFileToJsonParser(Reader jsonReader, boolean isServerMode) {
    this.gson = new Gson();
    this.reader = new JsonReader(jsonReader);
    this.isServerMode = isServerMode;

    // This is used to read one line at a time.
    reader.setLenient(true);
  }

  @VisibleForTesting
  public BuildFileToJsonParser(String json, boolean isServerMode) {
    this(new StringReader(json), isServerMode);
  }

  /**
   * Access the next set of rules from the build file processor.  Note that for non-server
   * invocations, this will collect all of the rules into one enormous list.
   *
   * @return The parsed JSON, represented as Java collections. Ideally, we would use Gson's object
   *     model directly to avoid the overhead of converting between object models. That would
   *     require updating all code that depends on this method, which may be a lot of work. Also,
   *     bear in mind that using the Java collections decouples clients of this method from the JSON
   *     parser that we use.
   */
  @SuppressWarnings("unchecked")
  List<Map<String, Object>> nextRules() throws IOException {
    List<Map<String, Object>> items = Lists.newArrayList();

    if (isServerMode) {
      reader.beginArray();

      while (reader.hasNext()) {
        JsonObject json = gson.fromJson(reader, JsonObject.class);
        items.add((Map<String, Object>) toRawTypes(json));
      }

      reader.endArray();
    } else {
      while (reader.peek() != JsonToken.END_DOCUMENT) {
        JsonObject json = gson.fromJson(reader, JsonObject.class);
        items.add((Map<String, Object>) toRawTypes(json));
      }
    }

    return items;
  }

  /**
   * @return One of: String, Boolean, Long, Number, List<Object>, Map<String, Object>.
   */
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

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
