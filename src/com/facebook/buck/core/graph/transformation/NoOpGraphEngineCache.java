/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.core.graph.transformation;

import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import java.util.Optional;

/**
 * Graph Engine cache that does not store result, and always returns nothing on get. Can be used for
 * testing or fast transformations that do not benefit from caching intermediate data.
 */
public class NoOpGraphEngineCache<Key extends ComputeKey<Value>, Value extends ComputeResult>
    implements GraphEngineCache<Key, Value> {
  @Override
  public Optional<Value> get(Key key) {
    return Optional.empty();
  }

  @Override
  public void put(Key key, Value value) {}
}
