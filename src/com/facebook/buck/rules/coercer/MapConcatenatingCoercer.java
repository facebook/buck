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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

class MapConcatenatingCoercer extends JsonTypeConcatenatingCoercer {

  MapConcatenatingCoercer() {
    super(Map.class);
  }

  @Override
  public Object concat(Iterable<Object> elements) {
    @SuppressWarnings("unchecked")
    Iterable<Set<Entry<Object, Object>>> maps =
        Iterables.transform(elements, map -> ((Map<Object, Object>) map).entrySet());
    return ImmutableMap.copyOf(Iterables.concat(maps));
  }
}
