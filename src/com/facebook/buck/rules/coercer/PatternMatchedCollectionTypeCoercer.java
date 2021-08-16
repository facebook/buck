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
import com.facebook.buck.core.filesystems.ForwardRelPath;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Pair;
import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Type coercer for {@code PatternMatchedCollection} */
public class PatternMatchedCollectionTypeCoercer<U, T>
    implements TypeCoercer<PatternMatchedCollection<U>, PatternMatchedCollection<T>> {

  TypeCoercer<Pattern, Pattern> patternTypeCoercer;
  TypeCoercer<U, T> valueTypeCoercer;
  private final TypeToken<PatternMatchedCollection<U>> unconfiguredTypeToken;
  private final TypeToken<PatternMatchedCollection<T>> typeToken;

  public PatternMatchedCollectionTypeCoercer(
      TypeCoercer<Pattern, Pattern> patternTypeCoercer, TypeCoercer<U, T> valueTypeCoercer) {
    this.patternTypeCoercer = patternTypeCoercer;
    this.valueTypeCoercer = valueTypeCoercer;
    this.unconfiguredTypeToken =
        new TypeToken<PatternMatchedCollection<U>>() {}.where(
            new TypeParameter<U>() {}, valueTypeCoercer.getUnconfiguredType());
    this.typeToken =
        new TypeToken<PatternMatchedCollection<T>>() {}.where(
            new TypeParameter<T>() {}, valueTypeCoercer.getOutputType());
  }

  @Override
  public SkylarkSpec getSkylarkSpec() {
    return new SkylarkSpec() {
      @Override
      public String spec() {
        return String.format(
            "attr.list(attr.tuple(attr.regex(), %s))", valueTypeCoercer.getSkylarkSpec().spec());
      }

      @Override
      public String topLevelSpec() {
        return String.format(
            "attr.list(attr.tuple(attr.regex(), %s), default=[])",
            valueTypeCoercer.getSkylarkSpec().spec());
      }

      @Override
      public List<Class<? extends Enum<?>>> enums() {
        return valueTypeCoercer.getSkylarkSpec().enums();
      }
    };
  }

  @Override
  public TypeToken<PatternMatchedCollection<T>> getOutputType() {
    return typeToken;
  }

  @Override
  public TypeToken<PatternMatchedCollection<U>> getUnconfiguredType() {
    return unconfiguredTypeToken;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    for (Class<?> type : types) {
      if (type.equals(String.class)) {
        return true;
      }
    }
    return valueTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverseUnconfigured(
      CellNameResolver cellRoots, PatternMatchedCollection<U> object, Traversal traversal) {
    for (Pair<Pattern, U> value : object.getPatternsAndValues()) {
      traversal.traverse(value.getFirst());
      valueTypeCoercer.traverseUnconfigured(cellRoots, value.getSecond(), traversal);
    }
  }

  @Override
  public void traverse(
      CellNameResolver cellRoots, PatternMatchedCollection<T> object, Traversal traversal) {
    for (Pair<Pattern, T> value : object.getPatternsAndValues()) {
      traversal.traverse(value.getFirst());
      valueTypeCoercer.traverse(cellRoots, value.getSecond(), traversal);
    }
  }

  @Override
  public PatternMatchedCollection<U> coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (!(object instanceof List)) {
      throw CoerceFailedException.simple(
          object, getOutputType(), "input object should be a list of pairs");
    }
    PatternMatchedCollection.Builder<U> builder = PatternMatchedCollection.builder();
    List<?> list = (List<?>) object;
    for (Object element : list) {
      if (!(element instanceof Collection) || ((Collection<?>) element).size() != 2) {
        throw CoerceFailedException.simple(
            object, getOutputType(), "input object should be a list of pairs");
      }
      Iterator<?> pair = ((Collection<?>) element).iterator();
      Pattern platformSelector =
          patternTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, pair.next());
      U value =
          valueTypeCoercer.coerceToUnconfigured(
              cellRoots, filesystem, pathRelativeToProjectRoot, pair.next());
      builder.add(platformSelector, value);
    }
    return builder.build();
  }

  @Override
  public PatternMatchedCollection<T> coerce(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelPath pathRelativeToProjectRoot,
      TargetConfiguration targetConfiguration,
      TargetConfigurationResolver hostConfigurationResolver,
      PatternMatchedCollection<U> object)
      throws CoerceFailedException {
    PatternMatchedCollection.Builder<T> builder = PatternMatchedCollection.builder();
    for (Pair<Pattern, U> pair : object.getPatternsAndValues()) {
      Pattern platformSelector =
          patternTypeCoercer.coerce(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfigurationResolver,
              pair.getFirst());
      T value =
          valueTypeCoercer.coerce(
              cellRoots,
              filesystem,
              pathRelativeToProjectRoot,
              targetConfiguration,
              hostConfigurationResolver,
              pair.getSecond());
      builder.add(platformSelector, value);
    }
    return builder.build();
  }

  @Nullable
  @Override
  public PatternMatchedCollection<T> concat(Iterable<PatternMatchedCollection<T>> elements) {
    return PatternMatchedCollection.concat(elements);
  }
}
