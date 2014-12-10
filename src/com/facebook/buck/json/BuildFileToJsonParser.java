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
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

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

  /**
   * The parser below uses these objects for stateful purposes with the ultimate goal
   * of populating the parsed rules into `currentObjects`.
   *
   * The parser is expecting output in the form:
   *   [{"key": "value"}, {"key": "value"}, ...]
   *
   * This is a necessary short-term step to keep logic in the main Parser consistent (expecting to
   * be able to correlate a set of rules with the specific BUCK file that generated them). This
   * requirement creates an unnecessary performance weakness in this design where we cannot
   * parallelize buck.py's parsing of BUCK files with buck's processing of the result into a DAG.
   */
  private final Gson gson;
  private final JsonReader reader;

  /**
   * @param jsonReader That contains the JSON data.
   */
  public BuildFileToJsonParser(Reader jsonReader) {
    this.gson = new Gson();
    this.reader = new JsonReader(jsonReader);

    // This is used to read one line at a time.
    reader.setLenient(true);
  }

  @VisibleForTesting
  public BuildFileToJsonParser(String json) {
    this(new StringReader(json));
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
    try {
      List<Map<String, Object>> items = Lists.newArrayList();
      reader.beginArray();

      while (reader.hasNext()) {
        JsonObject json = gson.fromJson(reader, JsonObject.class);
        items.add((Map<String, Object>) RawParser.toRawTypes(json));
      }

      reader.endArray();
      return items;
    } catch (IllegalStateException e) {
      throw new IOException(e); // Rethrow Gson exceptions as IO (non-runtime) exceptions.
    }
  }

  @Override
  public void close() throws IOException {
    reader.close();
  }
}
