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

package com.facebook.buck.cli;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.util.Map;

public class ConfigOverrideBuilder {

  private final Map<String, ImmutableMap.Builder<String, String>> entries =
      Maps.newLinkedHashMap();

  public ConfigOverrideBuilder put(String section, String field, String value) {
    if (!entries.containsKey(section)) {
      entries.put(section, ImmutableMap.<String, String>builder());
    }
    entries.get(section).put(field, value);
    return this;
  }

  public ImmutableMap<String, ImmutableMap<String, String>> build() {
    ImmutableMap.Builder<String, ImmutableMap<String, String>> builder = ImmutableMap.builder();
    for (Map.Entry<String, ImmutableMap.Builder<String, String>> entry : entries.entrySet()) {
      builder.put(entry.getKey(), entry.getValue().build());
    }
    return builder.build();
  }

}
