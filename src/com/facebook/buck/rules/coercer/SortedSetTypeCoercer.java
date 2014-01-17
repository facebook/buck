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

import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class SortedSetTypeCoercer<T extends Comparable<T>>
    extends CollectionTypeCoercer<ImmutableSortedSet<T>, T> {
  SortedSetTypeCoercer(TypeCoercer<T> elementTypeCoercer) {
    super(elementTypeCoercer);
  }

  @SuppressWarnings("unchecked")
  @Override
  public Class<ImmutableSortedSet<T>> getOutputClass() {
    return (Class<ImmutableSortedSet<T>>) (Class<?>) ImmutableSortedSet.class;
  }

  @Override
  public ImmutableSortedSet<T> coerce(
      BuildRuleResolver buildRuleResolver, Path pathRelativeToProjectRoot, Object object)
      throws CoerceFailedException {
    ImmutableSortedSet.Builder<T> builder = ImmutableSortedSet.naturalOrder();
    fill(buildRuleResolver, pathRelativeToProjectRoot, builder, object);
    return builder.build();
  }
}
