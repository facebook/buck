/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.features.project.intellij.writer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wrapper around the LinkedHashMap to generate Json data. This map takes care of the responsibility
 * to not write the empty/null fields, also boolean fields are not written unless true.
 */
public class JsonMap {
  private final Map<String, Object> map;

  public JsonMap() {
    this.map = new LinkedHashMap<>();
  }

  /** Puts the key and value after safety checks. */
  public JsonMap put(String key, Object value) {
    if (value == null
        || (value instanceof Boolean && !((Boolean) value))
        || (value instanceof String && ((String) value).isEmpty())
        || (value instanceof Collection<?> && ((Collection<?>) value).isEmpty())) {
      return this;
    }
    this.map.put(key, value);
    return this;
  }

  public Map<String, Object> get() {
    return this.map;
  }
}
