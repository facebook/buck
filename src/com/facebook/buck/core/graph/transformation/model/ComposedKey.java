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

import com.google.common.base.Preconditions;
import org.immutables.value.Value;

/**
 * The {@link ComputeKey} for a composed computation
 *
 * @param <KeyType1> the origin key type
 * @param <ResultType2> the target result type
 */
@Value.Immutable(builder = false, copy = false, prehash = true)
public abstract class ComposedKey<KeyType1 extends ComputeKey<?>, ResultType2 extends ComputeResult>
    implements ComputeKey<ComposedResult<ComputeKey<ResultType2>, ResultType2>> {

  @Value.Parameter
  public abstract KeyType1 getOriginKey();

  @Value.Parameter
  public abstract Class<ResultType2> getTargetResultClass();

  @Value.Derived
  @Override
  public ComputationIdentifier<ComposedResult<ComputeKey<ResultType2>, ResultType2>>
      getIdentifier() {
    return ComposedComputationIdentifier.of(getOriginKey().getIdentifier(), getTargetResultClass());
  }

  @Value.Check
  protected void check() {
    Preconditions.checkState(!(getOriginKey() instanceof ComposedKey));
  }
}
