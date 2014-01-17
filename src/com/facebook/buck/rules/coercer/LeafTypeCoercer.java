/*
 * Copyright 2013-present Facebook, Inc.
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

import javax.annotation.Nullable;

/**
 * Superclass of coercers for non-collection/map types.
 */
public abstract class LeafTypeCoercer<T> implements TypeCoercer<T> {
  public Class<T> getLeafClass() {
    return getOutputClass();
  }

  @Nullable
  @Override
  public Class<?> getKeyClass() {
    return null;
  }

  @Override
  public void traverse(Object object, Traversal traversal) {
    traversal.traverse(object);
  }
}
