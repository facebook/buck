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

package com.facebook.buck.parser.syntax;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableList;
import java.util.stream.Collectors;

/** An attribute that holds the concatenation of native values with {@link SelectorValue} */
@BuckStyleValue
@JsonDeserialize
public abstract class ListWithSelects {
  /** Ordered list of elements in expression, can be native type or {@link SelectorValue} */
  @JsonProperty("elements")
  public abstract ImmutableList<Object> getElements();

  /** Type of the data in selector values */
  @JsonProperty("type")
  public abstract Class<?> getType();

  @Override
  public String toString() {
    return getElements().stream().map(p -> p.toString()).collect(Collectors.joining(" + "));
  }

  public static ListWithSelects of(ImmutableList<Object> elements, Class<?> type) {
    return ImmutableListWithSelects.of(elements, type);
  }
}
