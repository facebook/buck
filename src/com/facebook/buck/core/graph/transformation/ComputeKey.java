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

/**
 * This is an interface for all keys to be used in {@link GraphTransformer}.
 *
 * @param <ResultType> should be the corresponding result type of performing the transformation in
 *     the {@link GraphTransformer}
 */
public interface ComputeKey<ResultType extends ComputeResult> {

  /**
   * @return the class identifier of this key. This is different from {@code getClass()} so that
   *     users can define their own {@link ComputeKey} as an Immutable and still have a consistent
   *     identifier that returns the interface class instead of the immutable implementation class.
   */
  Class<? extends ComputeKey<?>> getKeyClass();
}
