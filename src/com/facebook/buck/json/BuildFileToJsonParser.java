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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.List;
import java.util.Map;

/**
 * This is a special JSON parser that is customized to consume the JSON output of buck.py.
 * Object values may be one of: null, a string, or an array of strings. This means that no
 * sort of nested arrays or objects are allowed in the output as Parser is implemented
 * today. This simplification makes it easier to leverage Jackson's streaming JSON API.
 */
public class BuildFileToJsonParser {

  private final JsonParser parser;

  public BuildFileToJsonParser(String json) throws IOException {
    JsonFactory jsonFactory = new JsonFactory();
    this.parser = jsonFactory.createJsonParser(json);
  }

  public BuildFileToJsonParser(InputStream json) throws IOException {
    JsonFactory jsonFactory = new JsonFactory();
    this.parser = jsonFactory.createJsonParser(json);
  }

  public BuildFileToJsonParser(Reader json) throws IOException {
    JsonFactory jsonFactory = new JsonFactory();
    this.parser = jsonFactory.createJsonParser(json);
  }

  /**
   * Access the next set of rules from the build file processor.  Note that for non-server
   * invocations, this will collect all of the rules into one enormous list.
   *
   * @return List of rules expressed as a <em>very</em> simple mapping of JSON field names
   *     to Java primitives.
   */
  public List<Map<String, Object>> nextRules() throws IOException {
    // The parser below uses these objects for stateful purposes with the ultimate goal
    // of populating the parsed rules into `currentObjects`.
    //
    // The parser is expecting two different styles of output:
    //   1. Server mode: [{"key": "value"}, {"key": "value"}, ...]
    //   2. Regular mode: {"key": "value"}, {"key": "value"}, ...
    //
    // Server mode output is a necessary short-term step to keep logic in the main Parser
    // consistent (expecting to be able to correlate a set of rules with the specific BUCK file
    // that generated them).  This requirement creates an unnecessary performance weakness
    // in this design where we cannot parallelize buck.py's parsing of BUCK files with buck's
    // processing of the result into a DAG.  Once this can be addressed, server mode should be
    // eliminated.
    List<Map<String, Object>> currentObjects = null;
    String currentFieldName = null;
    List<String> currentArray = null;
    Map<String, Object> currentObject = null;

    while (true) {
      JsonToken token = parser.nextToken();
      if (token == null) {
        if (currentObject != null) {
          throw new EOFException("unexpected end-of-stream");
        } else if (currentObjects == null) {
          // This happens when buck.py failed to produce any output for this build rule (python
          // parse error or raised exception, I bet).
          throw new EOFException("missing build rules");
        } else {
          return currentObjects;
        }
      }

      switch (token) {
      case START_OBJECT:
        // The syntax differs very slightly between server mode and not.  If we encounter
        // an object not inside of an array, we aren't in server mode and so we can just read
        // all the objects until exhaustion.
        if (currentObjects == null) {
          currentObjects = Lists.newArrayList();
        }
        currentObject = Maps.newHashMap();
        break;

      case END_OBJECT:
        currentObjects.add(currentObject);
        currentObject = null;
        break;

      case START_ARRAY:
        // Heuristic to detect whether we are in the above server mode or regular mode.  If we
        // encounter START_ARRAY before START_OBJECT, it must be server mode so we should build
        // `currentObjects` now.  Otherwise, this is the start of a new sub-array within
        // an object.
        if (currentObjects == null) {
          currentObjects = Lists.newArrayList();
        } else {
          currentArray = Lists.newArrayList();
        }
        break;

      case END_ARRAY:
        if (currentArray != null) {
          currentObject.put(currentFieldName, currentArray);
          currentArray = null;
          currentFieldName = null;
          break;
        } else {
          // Must be in server mode and we finished parsing a single BUCK file.
          return currentObjects;
        }

      case FIELD_NAME:
        currentFieldName = parser.getText().intern();
        break;

      case VALUE_STRING:
        if (currentArray == null) {
          currentObject.put(currentFieldName, parser.getText());
          currentFieldName = null;
        } else {
          currentArray.add(parser.getText());
        }
        break;

      case VALUE_TRUE:
      case VALUE_FALSE:
        Preconditions.checkState(currentArray == null, "Unexpected boolean in JSON array");
        currentObject.put(currentFieldName, token == JsonToken.VALUE_TRUE);
        currentFieldName = null;
        break;

      case VALUE_NUMBER_INT:
        Preconditions.checkState(currentArray == null, "Unexpected int in JSON array");
        currentObject.put(currentFieldName, parser.getLongValue());
        currentFieldName = null;
        break;

      case VALUE_NULL:
        if (currentArray == null) {
          currentObject.put(currentFieldName, null);
          currentFieldName = null;
        } else {
          currentArray.add(null);
        }
        break;

      default:
        throw new JsonParseException("Unexpected token: " + token, parser.getCurrentLocation());
      }
    }
  }
}
