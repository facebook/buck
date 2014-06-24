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
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.Map;

public class MapTypeCoercer<K, V> implements TypeCoercer<ImmutableMap<K, V>> {
  private final TypeCoercer<K> keyTypeCoercer;
  private final TypeCoercer<V> valueTypeCoercer;

  MapTypeCoercer(TypeCoercer<K> keyTypeCoercer, TypeCoercer<V> valueTypeCoercer) {
    this.keyTypeCoercer = Preconditions.checkNotNull(keyTypeCoercer);
    this.valueTypeCoercer = Preconditions.checkNotNull(valueTypeCoercer);
  }

  @SuppressWarnings("unchecked")
  @Override
  public Class<ImmutableMap<K, V>> getOutputClass() {
    return (Class<ImmutableMap<K, V>>) (Class<?>) ImmutableMap.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return keyTypeCoercer.hasElementClass(types) || valueTypeCoercer.hasElementClass(types);
  }

  @Override
  public Optional<ImmutableMap<K, V>> getOptionalValue() {
    return Optional.of(ImmutableMap.<K, V>of());
  }

  @Override
  public boolean traverse(Object object, Traversal traversal) {
    if (object instanceof Map) {
      traversal.traverse(object);
      for (Map.Entry<?, ?> element : ((Map<?, ?>) object).entrySet()) {
        keyTypeCoercer.traverse(element.getKey(), traversal);
        valueTypeCoercer.traverse(element.getValue(), traversal);
      }
      return true;
    } else {
      return false;
    }
  }

  @Override
  public ImmutableMap<K, V> coerce(
      BuildRuleResolver buildRuleResolver,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Map) {
      ImmutableMap.Builder<K, V> builder = ImmutableMap.builder();

      for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
        try {
          K key = keyTypeCoercer.coerce(
              buildRuleResolver, filesystem, pathRelativeToProjectRoot, entry.getKey());
          V value = valueTypeCoercer.coerce(
              buildRuleResolver, filesystem, pathRelativeToProjectRoot, entry.getValue());
          builder.put(key, value);
        } catch (CoerceFailedException e) {
          CoerceFailedException wrappedException =
              CoerceFailedException.simple(object, getOutputClass());
          wrappedException.initCause(e);
          throw wrappedException;
        }
      }

      return builder.build();
    } else {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
  }
}
