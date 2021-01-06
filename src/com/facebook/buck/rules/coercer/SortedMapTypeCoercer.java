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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import java.util.Map;

/** Coere to {@link com.google.common.collect.ImmutableSortedMap}. */
public class SortedMapTypeCoercer<KU extends Comparable<KU>, VU, K extends Comparable<K>, V>
    implements TypeCoercer<ImmutableSortedMap<KU, VU>, ImmutableSortedMap<K, V>> {
  private final TypeCoercer<KU, K> keyTypeCoercer;
  private final TypeCoercer<VU, V> valueTypeCoercer;
  private final TypeToken<ImmutableSortedMap<K, V>> typeToken;
  private final TypeToken<ImmutableSortedMap<KU, VU>> typeTokenUnconfigured;

  SortedMapTypeCoercer(TypeCoercer<KU, K> keyTypeCoercer, TypeCoercer<VU, V> valueTypeCoercer) {
    this.keyTypeCoercer = keyTypeCoercer;
    this.valueTypeCoercer = valueTypeCoercer;
    this.typeToken =
        new TypeToken<ImmutableSortedMap<K, V>>() {}.where(
                new TypeParameter<K>() {}, keyTypeCoercer.getOutputType())
            .where(new TypeParameter<V>() {}, valueTypeCoercer.getOutputType());
    this.typeTokenUnconfigured =
        new TypeToken<ImmutableSortedMap<KU, VU>>() {}.where(
                new TypeParameter<KU>() {}, keyTypeCoercer.getUnconfiguredType())
            .where(new TypeParameter<VU>() {}, valueTypeCoercer.getUnconfiguredType());
  }

  @Override
  public TypeToken<ImmutableSortedMap<K, V>> getOutputType() {
    return typeToken;
  }

  @Override
  public TypeToken<ImmutableSortedMap<KU, VU>> getUnconfiguredType() {
    return typeTokenUnconfigured;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return keyTypeCoercer.hasElementClass(types) || valueTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(
      CellNameResolver cellRoots, ImmutableSortedMap<K, V> object, Traversal traversal) {
    traversal.traverse(object);
    for (Map.Entry<K, V> element : object.entrySet()) {
      keyTypeCoercer.traverse(cellRoots, element.getKey(), traversal);
      valueTypeCoercer.traverse(cellRoots, element.getValue(), traversal);
    }
  }

  @Override
  public ImmutableSortedMap<KU, VU> coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Map<?, ?>) {
      ImmutableSortedMap.Builder<KU, VU> builder = ImmutableSortedMap.naturalOrder();
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
        KU key =
            keyTypeCoercer.coerceToUnconfigured(
                cellRoots, filesystem, pathRelativeToProjectRoot, entry.getKey());
        VU value =
            valueTypeCoercer.coerceToUnconfigured(
                cellRoots, filesystem, pathRelativeToProjectRoot, entry.getValue());
        builder.put(key, value);
      }
      return builder.build();
    } else {
      throw CoerceFailedException.simple(object, getOutputType());
    }
  }

  @Override
  public ImmutableSortedMap<K, V> coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      ImmutableSortedMap<KU, VU> object)
      throws CoerceFailedException {
    ImmutableSortedMap.Builder<K, V> builder = ImmutableSortedMap.naturalOrder();

    for (Map.Entry<KU, VU> entry : object.entrySet()) {
      K key =
          keyTypeCoercer.coerce(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              entry.getKey());
      V value =
          valueTypeCoercer.coerce(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              entry.getValue());
      builder.put(key, value);
    }

    return builder.build();
  }
}
