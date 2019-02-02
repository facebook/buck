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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

public class MapTypeCoercer<K, V> implements TypeCoercer<ImmutableMap<K, V>> {
  private final TypeCoercer<K> keyTypeCoercer;
  private final TypeCoercer<V> valueTypeCoercer;

  MapTypeCoercer(TypeCoercer<K> keyTypeCoercer, TypeCoercer<V> valueTypeCoercer) {
    this.keyTypeCoercer = keyTypeCoercer;
    this.valueTypeCoercer = valueTypeCoercer;
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
  public void traverse(CellPathResolver cellRoots, ImmutableMap<K, V> object, Traversal traversal) {
    traversal.traverse(object);
    for (Map.Entry<K, V> element : object.entrySet()) {
      keyTypeCoercer.traverse(cellRoots, element.getKey(), traversal);
      valueTypeCoercer.traverse(cellRoots, element.getValue(), traversal);
    }
  }

  @Override
  public ImmutableMap<K, V> coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Map) {
      ImmutableMap.Builder<K, V> builder = ImmutableMap.builder();

      for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
        K key =
            keyTypeCoercer.coerce(
                cellRoots,
                filesystem,
                pathRelativeToProjectRoot,
                targetConfiguration,
                entry.getKey());
        V value =
            valueTypeCoercer.coerce(
                cellRoots,
                filesystem,
                pathRelativeToProjectRoot,
                targetConfiguration,
                entry.getValue());
        builder.put(key, value);
      }

      return builder.build();
    } else {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
  }

  @Nullable
  @Override
  public ImmutableMap<K, V> concat(Iterable<ImmutableMap<K, V>> elements) {
    LinkedHashMap<K, V> result = Maps.newLinkedHashMap();
    for (ImmutableMap<K, V> map : elements) {
      for (Map.Entry<K, V> entry : map.entrySet()) {
        V previousObject = result.putIfAbsent(entry.getKey(), entry.getValue());
        if (previousObject != null) {
          throw new HumanReadableException(
              "Duplicate key found when trying to concatenate a map attribute: %s", entry.getKey());
        }
      }
    }

    return ImmutableMap.copyOf(result);
  }
}
