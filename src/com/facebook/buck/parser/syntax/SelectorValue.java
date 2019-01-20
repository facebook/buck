/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser.syntax;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableMap;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/** The value of a select statement. Buck API equivalent of Bazel's Skylark SelectorValue */
@Value.Immutable(builder = false, copy = false)
@JsonDeserialize
public abstract class SelectorValue {

  /** Return a map with select choices and appropriate values */
  @Value.Parameter
  @JsonProperty("dictionary")
  public abstract ImmutableMap<String, Object> getDictionary();

  /** Provide an error message to show if select choices are not matched */
  @Value.Parameter
  @JsonProperty("noMatchError")
  public abstract String getNoMatchError();

  @Override
  public String toString() {
    return getDictionary()
        .entrySet()
        .stream()
        .map(e -> "\"" + e.getKey() + "\": \"" + e.getValue() + "\"")
        .collect(Collectors.joining(", ", "select({", "})"));
  }
}
