/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.io.IOException;

/** Serializer that uses JSON to represent {@link TargetConfiguration} in a text form. */
public class JsonTargetConfigurationSerializer implements TargetConfigurationSerializer {

  private final ObjectWriter objectWriter;
  private final ObjectReader objectReader;

  public JsonTargetConfigurationSerializer() {
    ObjectMapper objectMapper = ObjectMappers.createWithEmptyBeansPermitted();
    objectReader = objectMapper.reader();
    objectWriter = objectMapper.writer();
  }

  @Override
  public String serialize(TargetConfiguration targetConfiguration) {
    if (targetConfiguration instanceof EmptyTargetConfiguration) {
      try {
        return objectWriter.writeValueAsString(targetConfiguration);
      } catch (JsonProcessingException e) {
        throw new HumanReadableException(
            e, "Cannot serialize target configuration %s", targetConfiguration);
      }
    }
    throw new IllegalArgumentException("Serialization not supported for: " + targetConfiguration);
  }

  @Override
  public TargetConfiguration deserialize(String rawValue) {
    JsonNode node;
    try {
      node = objectReader.readTree(rawValue);
    } catch (IOException e) {
      throw new HumanReadableException(e, "Cannot deserialize target configuration %s", rawValue);
    }
    if (node.size() == 0) {
      return EmptyTargetConfiguration.INSTANCE;
    }
    throw new IllegalArgumentException("Deserialization not supported for: " + rawValue);
  }
}
