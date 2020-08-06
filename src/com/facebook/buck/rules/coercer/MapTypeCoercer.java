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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/** Coerce to {@link com.google.common.collect.ImmutableMap}. */
public class MapTypeCoercer<KU, VU, K, V>
    implements TypeCoercer<ImmutableMap<KU, VU>, ImmutableMap<K, V>> {
  private final TypeCoercer<KU, K> keyTypeCoercer;
  private final TypeCoercer<VU, V> valueTypeCoercer;
  private final TypeToken<ImmutableMap<K, V>> typeToken;
  private final TypeToken<ImmutableMap<KU, VU>> typeTokenUnconfigured;

  public MapTypeCoercer(TypeCoercer<KU, K> keyTypeCoercer, TypeCoercer<VU, V> valueTypeCoercer) {
    this.keyTypeCoercer = keyTypeCoercer;
    this.valueTypeCoercer = valueTypeCoercer;
    this.typeToken =
        new TypeToken<ImmutableMap<K, V>>() {}.where(
                new TypeParameter<K>() {}, keyTypeCoercer.getOutputType())
            .where(new TypeParameter<V>() {}, valueTypeCoercer.getOutputType());
    this.typeTokenUnconfigured =
        new TypeToken<ImmutableMap<KU, VU>>() {}.where(
                new TypeParameter<KU>() {}, keyTypeCoercer.getUnconfiguredType())
            .where(new TypeParameter<VU>() {}, valueTypeCoercer.getUnconfiguredType());
  }

  @Override
  public TypeToken<ImmutableMap<K, V>> getOutputType() {
    return typeToken;
  }

  @Override
  public TypeToken<ImmutableMap<KU, VU>> getUnconfiguredType() {
    return typeTokenUnconfigured;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return keyTypeCoercer.hasElementClass(types) || valueTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellNameResolver cellRoots, ImmutableMap<K, V> object, Traversal traversal) {
    traversal.traverse(object);
    for (Map.Entry<K, V> element : object.entrySet()) {
      keyTypeCoercer.traverse(cellRoots, element.getKey(), traversal);
      valueTypeCoercer.traverse(cellRoots, element.getValue(), traversal);
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public ImmutableMap<KU, VU> coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Map) {
      boolean identity = true;

      ImmutableMap.Builder<KU, VU> builder = ImmutableMap.builder();

      for (Map.Entry<?, ?> entry : ((Map<?, ?>) object).entrySet()) {
        KU key =
            keyTypeCoercer.coerceToUnconfigured(
                cellRoots, filesystem, pathRelativeToProjectRoot, entry.getKey());
        VU value =
            valueTypeCoercer.coerceToUnconfigured(
                cellRoots, filesystem, pathRelativeToProjectRoot, entry.getValue());
        builder.put(key, value);

        identity &= key == entry.getKey() && value == entry.getValue();
      }

      if (identity && object instanceof ImmutableMap<?, ?>) {
        return (ImmutableMap<KU, VU>) object;
      }

      return builder.build();
    } else {
      throw CoerceFailedException.simple(object, getOutputType());
    }
  }

  @Override
  public boolean unconfiguredToConfiguredCoercionIsIdentity() {
    return keyTypeCoercer.unconfiguredToConfiguredCoercionIsIdentity()
        && valueTypeCoercer.unconfiguredToConfiguredCoercionIsIdentity();
  }

  @SuppressWarnings("unchecked")
  @Override
  public ImmutableMap<K, V> coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      ImmutableMap<KU, VU> object)
      throws CoerceFailedException {
    if (unconfiguredToConfiguredCoercionIsIdentity()) {
      return (ImmutableMap<K, V>) object;
    }

    boolean identity = true;

    ImmutableMap.Builder<K, V> builder = ImmutableMap.builder();

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

      identity &= key == entry.getKey() && value == entry.getValue();
    }

    if (identity) {
      return (ImmutableMap<K, V>) object;
    }

    return builder.build();
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
