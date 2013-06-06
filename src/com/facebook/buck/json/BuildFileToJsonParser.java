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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.List;
import java.util.Map;

/**
 * This is a special JSON parser that is customized to consume the JSON output of buck.py. In
 * particular, it expects one JSON object per line. Object values may be one of: null, a string, or
 * an array of strings. This means that no sort of nested arrays or objects are allowed in the
 * output as Parser is implemented today. This simplification makes it easier to leverage Jackson's
 * streaming JSON API.
 */
public class BuildFileToJsonParser {

  private final JsonParser parser;

  public BuildFileToJsonParser(String json) throws JsonParseException, IOException {
    JsonFactory jsonFactory = new JsonFactory();
    this.parser = jsonFactory.createJsonParser(json);
  }

  public BuildFileToJsonParser(InputStream json) throws JsonParseException, IOException {
    JsonFactory jsonFactory = new JsonFactory();
    this.parser = jsonFactory.createJsonParser(json);
  }

  public BuildFileToJsonParser(Reader json) throws JsonParseException, IOException {
    JsonFactory jsonFactory = new JsonFactory();
    this.parser = jsonFactory.createJsonParser(json);
  }

  public Map<String, Object> next() throws JsonParseException, IOException {
    String currentFieldName = null;
    List<String> currentArray = null;
    Map<String, Object> currentObject = null;

    while (true) {
      JsonToken token = parser.nextToken();
      if (token == null) {
        return null;
      }

      switch (token) {
      case START_OBJECT:
        currentObject = Maps.newHashMap();
        break;

      case END_OBJECT:
        Map<String, Object> out = currentObject;
        currentObject = null;
        return out;

      case START_ARRAY:
        currentArray = Lists.newArrayList();
        break;

      case END_ARRAY:
        currentObject.put(currentFieldName, currentArray);
        currentArray = null;
        currentFieldName = null;
        break;

      case FIELD_NAME:
        currentFieldName = parser.getText();
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
