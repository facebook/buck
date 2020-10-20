/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.externalactions.utils;

import com.facebook.buck.externalactions.model.JsonArgs;
import com.facebook.buck.util.json.ObjectMappers;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Utilities for external actions. */
public class ExternalActionsUtils {

  private ExternalActionsUtils() {}

  /** Returns an object of the given class by deserializing JSON at at the given path. */
  public static <T extends JsonArgs> T readJsonArgs(Path jsonFilePath, Class<T> clazz) {
    T args;
    try {
      args = ObjectMappers.readValue(jsonFilePath, clazz);
    } catch (IOException e) {
      throw new IllegalStateException(
          String.format(
              "Failed to read JSON from  %s deserializing %s", jsonFilePath, clazz.getName()),
          e);
    }
    return args;
  }

  /** Returns an object of the given class by deserializing JSON at at the given path. */
  public static <T extends JsonArgs> T readJsonArgs(String jsonFilePath, Class<T> clazz) {
    return readJsonArgs(Paths.get(jsonFilePath), clazz);
  }
}
