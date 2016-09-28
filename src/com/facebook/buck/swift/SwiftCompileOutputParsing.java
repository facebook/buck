/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.swift;

import com.facebook.buck.log.Logger;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonStreamParser;

import java.io.Reader;

/**
 * For parsing output data from swift incremental build.
 */
class SwiftCompileOutputParsing {

  private static final Logger LOG = Logger.get(SwiftCompileOutputParsing.class);

  static void streamOutputFromReader(Reader reader, SwiftOutputHandler handler) {
    JsonStreamParser streamParser = new JsonStreamParser(reader);
    try {
      while (streamParser.hasNext()) {
        dispatchEventCallback(streamParser.next(), handler);
      }
    } catch (JsonParseException e) {
      LOG.warn(e, "Couldn't parse xctool JSON stream");
    }
  }

  private static void dispatchEventCallback(
      JsonElement element,
      SwiftOutputHandler handler) {
    if (element.isJsonPrimitive()) {
      LOG.info("Next block of json size: %d", element.getAsInt());
      return;
    }

    if (!element.isJsonObject()) {
      LOG.warn("Could not parse JSON object from swift output: %s", element);
      return;
    }

    JsonObject outputBlock = element.getAsJsonObject();
    if (!outputBlock.has("kind")) {
      LOG.warn("Ignore for now: %s", outputBlock);
      return;
    }

    String kind = outputBlock.get("kind").getAsString();

    switch (kind) {
      case "finished":
        int exitStatus = outputBlock.get("exit-status").getAsInt();
        if (exitStatus != 0) {
          String outputError = outputBlock.get("output").getAsString();
          handler.recordError(outputError);
        }
        break;
      default:
        LOG.warn("Ignore kind [%s] for now: %s", kind, outputBlock);
          break;
    }
  }

  interface SwiftOutputHandler {
    void recordError(String error);
  }
}
