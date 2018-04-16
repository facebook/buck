/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.modern;

import com.google.common.base.Preconditions;
import javax.annotation.Nullable;

/**
 * ValueTypeInfo&lt;T&gt; provides methods to extract deps, outputs, rulekeys from values of type T.
 */
@SuppressWarnings("unused")
public interface ValueTypeInfo<T> {
  <E extends Exception> void visit(T value, ValueVisitor<E> visitor) throws E;

  @Nullable
  <E extends Exception> T create(ValueCreator<E> creator) throws E;

  default <E extends Exception> T createNotNull(ValueCreator<E> creator) throws E {
    return Preconditions.checkNotNull(create(creator));
  }
}
