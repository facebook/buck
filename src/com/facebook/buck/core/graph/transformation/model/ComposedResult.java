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
package com.facebook.buck.core.graph.transformation.model;

import com.google.common.collect.ImmutableMap;
import java.util.Iterator;
import org.immutables.value.Value;

/**
 * The {@link ComputeResult} for a composed computation. Each {@link ComposedResult} holds a list of
 * {@link ComputeResult} that may be generated.
 *
 * @param <ResultType> the type of the results held
 */
@Value.Immutable(builder = false, copy = false)
public abstract class ComposedResult<
        KeyType extends ComputeKey<ResultType>, ResultType extends ComputeResult>
    implements ComputeResult, Iterable<ResultType> {

  @Value.Parameter
  public abstract ImmutableMap<KeyType, ResultType> resultMap();

  @Override
  public Iterator<ResultType> iterator() {
    return resultMap().values().iterator();
  }
}
