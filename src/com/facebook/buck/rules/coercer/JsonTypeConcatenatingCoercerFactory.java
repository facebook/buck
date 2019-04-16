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
package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.select.impl.SelectorListFactory;
import java.util.List;
import java.util.Map;

/**
 * Creates a coercer that provides concatenation for a given type.
 *
 * <p>Note that this factory supports coercers that are compatible with JSON types only. The primary
 * purpose of these coercers is to help with concatenating configurable attributes represented in
 * JSON format.
 *
 * <p>Coercers provided by this factory are intended to be used together with {@link
 * SelectorListFactory} to resolve configurable attributes that contain values of JSON-compatible
 * types.
 */
public class JsonTypeConcatenatingCoercerFactory {

  /**
   * @return {@link JsonTypeConcatenatingCoercer} for a given type
   * @throws IllegalArgumentException if the given type doesn't support concatenation
   */
  public static JsonTypeConcatenatingCoercer createForType(Class<?> type) {
    if (List.class.isAssignableFrom(type)) {
      return new ListConcatenatingCoercer();
    }
    if (Map.class.isAssignableFrom(type)) {
      return new MapConcatenatingCoercer();
    }
    if (String.class.equals(type)) {
      return new StringConcatenatingCoercer();
    }
    if (Integer.class.equals(type)) {
      return new IntConcatenatingCoercer();
    }
    return new SingleElementJsonTypeConcatenatingCoercer(type);
  }
}
