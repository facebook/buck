/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.util.function.Function;

public abstract class MoreFunctions {
  private MoreFunctions() {}

  public static <T> Function<T, String> toJsonFunction() {
    return input -> {
      try {
        return ObjectMappers.WRITER.writeValueAsString(input);
      } catch (JsonProcessingException e) {
        throw new HumanReadableException(e, "Failed to serialize to json: " + input);
      }
    };
  }

  public static <T> Function<String, T> fromJsonFunction(final Class<T> type) {
    return input -> {
      try {
        return ObjectMappers.readValue(input, type);
      } catch (IOException e) {
        throw new HumanReadableException(e, "Failed to read from json: " + input);
      }
    };
  }
}
