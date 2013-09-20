/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.plugin.intellij.commands;

import com.facebook.buck.plugin.intellij.BuckTarget;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;

public class TargetsCommand {

  private static final Logger LOG = Logger.getInstance(TargetsCommand.class);

  private TargetsCommand() {}

  public static ImmutableList<BuckTarget> getTargets(BuckRunner buckRunner)  {
    try {
      int exitCode = buckRunner.execute("targets", "--json");
      if (exitCode != 0) {
        throw new RuntimeException(buckRunner.getStderr());
      }

      // Parse output
      ObjectMapper mapper = new ObjectMapper();
      JsonFactory factory = mapper.getJsonFactory();
      JsonParser parser = factory.createJsonParser(buckRunner.getStdout());
      JsonNode jsonNode = mapper.readTree(parser);
      if (!jsonNode.isArray()) {
        throw new IllegalStateException();
      }

      ImmutableList.Builder<BuckTarget> builder = ImmutableList.builder();
      for (JsonNode target : jsonNode) {
        if (!target.isObject()) {
          throw new IllegalStateException();
        }
        String basePath = target.get("buck.base_path").asText();
        String name = target.get("name").asText();
        String type = target.get("type").asText();
        ImmutableList.Builder<String> srcBuilder = ImmutableList.builder();
        JsonNode srcs = target.get("srcs");
        if (srcs != null && srcs.isArray()) {
          for (JsonNode src : srcs) {
            srcBuilder.add(src.asText());
          }
        }
        builder.add(new BuckTarget(type, name, basePath, srcBuilder.build()));
      }
      return builder.build();
    } catch (IOException | RuntimeException e) {
      LOG.error(e);
    }
    return ImmutableList.of();
  }
}
