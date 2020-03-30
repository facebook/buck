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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.Version;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class VersionMatchedCollectionTypeCoercer<T>
    implements TypeCoercer<Object, VersionMatchedCollection<T>> {

  TypeCoercer<?, ImmutableMap<BuildTarget, Version>> versionsTypeCoercer;
  TypeCoercer<?, T> valueTypeCoercer;
  private final TypeToken<VersionMatchedCollection<T>> typeToken;

  public VersionMatchedCollectionTypeCoercer(
      TypeCoercer<?, ImmutableMap<BuildTarget, Version>> versionsTypeCoercer,
      TypeCoercer<?, T> valueTypeCoercer) {
    this.versionsTypeCoercer = versionsTypeCoercer;
    this.valueTypeCoercer = valueTypeCoercer;
    this.typeToken =
        new TypeToken<VersionMatchedCollection<T>>() {}.where(
            new TypeParameter<T>() {}, valueTypeCoercer.getOutputType());
  }

  @Override
  public TypeToken<VersionMatchedCollection<T>> getOutputType() {
    return typeToken;
  }

  @Override
  public TypeToken<Object> getUnconfiguredType() {
    return TypeToken.of(Object.class);
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return versionsTypeCoercer.hasElementClass(types) || valueTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(
      CellNameResolver cellRoots, VersionMatchedCollection<T> object, Traversal traversal) {
    for (Pair<ImmutableMap<BuildTarget, Version>, T> pair : object.getValuePairs()) {
      versionsTypeCoercer.traverse(cellRoots, pair.getFirst(), traversal);
      valueTypeCoercer.traverse(cellRoots, pair.getSecond(), traversal);
    }
  }

  @Override
  public Object coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    return object;
  }

  @Override
  public VersionMatchedCollection<T> coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfiguration hostConfiguration,
      Object object)
      throws CoerceFailedException {
    if (!(object instanceof List)) {
      throw CoerceFailedException.simple(
          object, getOutputType(), "input object should be a list of pairs");
    }
    VersionMatchedCollection.Builder<T> builder = VersionMatchedCollection.builder();
    List<?> list = (List<?>) object;
    for (Object element : list) {
      if (!(element instanceof Collection) || ((Collection<?>) element).size() != 2) {
        throw CoerceFailedException.simple(
            object, getOutputType(), "input object should be a list of pairs");
      }
      Iterator<?> pair = ((Collection<?>) element).iterator();
      ImmutableMap<BuildTarget, Version> versionsSelector =
          versionsTypeCoercer.coerceBoth(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              pair.next());
      T value =
          valueTypeCoercer.coerceBoth(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfiguration,
              pair.next());
      builder.add(versionsSelector, value);
    }
    return builder.build();
  }
}
