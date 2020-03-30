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

package com.facebook.buck.core.graph.transformation.composition;

import com.facebook.buck.core.graph.transformation.model.ComputeKey;
import com.facebook.buck.core.graph.transformation.model.ComputeResult;
import com.google.common.collect.ImmutableSet;

/** A specific {@link Composer} that has output key restrictions */
@FunctionalInterface
public interface KeyComposer<
        Key1 extends ComputeKey<Result1>, Result1 extends ComputeResult, Key2 extends ComputeKey<?>>
    extends Composer<Key1, Result1> {
  @Override
  ImmutableSet<Key2> transitionWith(Key1 key, Result1 result) throws Exception;
}
