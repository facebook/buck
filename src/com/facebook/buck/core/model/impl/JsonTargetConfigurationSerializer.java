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
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.function.Function;

/** Serializer that uses JSON to represent {@link TargetConfiguration} in a text form. */
public class JsonTargetConfigurationSerializer implements TargetConfigurationSerializer {

  private final ObjectWriter objectWriter;
  private final ObjectReader objectReader;
  private final Function<String, UnconfiguredBuildTargetView> buildTargetProvider;

  public JsonTargetConfigurationSerializer(
      Function<String, UnconfiguredBuildTargetView> buildTargetProvider) {
    this.buildTargetProvider = buildTargetProvider;
    ObjectMapper objectMapper = ObjectMappers.createWithEmptyBeansPermitted();
    SimpleModule targetConfigurationModule = new SimpleModule();
    targetConfigurationModule.addSerializer(
        UnconfiguredBuildTargetView.class,
        new UnconfiguredBuildTargetSimpleSerializer(UnconfiguredBuildTargetView.class));
    objectMapper.registerModule(targetConfigurationModule);

    objectReader = objectMapper.reader();
    objectWriter = objectMapper.writer();
  }

  @Override
  public String serialize(TargetConfiguration targetConfiguration) {
    try {
      return objectWriter.writeValueAsString(targetConfiguration);
    } catch (JsonProcessingException e) {
      throw new HumanReadableException(
          e, "Cannot serialize target configuration %s", targetConfiguration);
    }
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
    JsonNode targetPlatformNode =
        Preconditions.checkNotNull(
            node.get("targetPlatform"), "Cannot find targetPlatform in %s", rawValue);
    UnconfiguredBuildTargetView platform =
        buildTargetProvider.apply(targetPlatformNode.textValue());
    return ImmutableDefaultTargetConfiguration.of(platform);
  }
}
