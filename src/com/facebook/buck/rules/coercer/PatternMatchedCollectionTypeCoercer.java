/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.Pair;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

public class PatternMatchedCollectionTypeCoercer<T>
    implements TypeCoercer<PatternMatchedCollection<T>> {

  TypeCoercer<Pattern> patternTypeCoercer;
  TypeCoercer<T> valueTypeCoercer;

  public PatternMatchedCollectionTypeCoercer(
      TypeCoercer<Pattern> patternTypeCoercer,
      TypeCoercer<T> valueTypeCoercer) {
    this.patternTypeCoercer = patternTypeCoercer;
    this.valueTypeCoercer = valueTypeCoercer;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Class<PatternMatchedCollection<T>> getOutputClass() {
    return (Class<PatternMatchedCollection<T>>) (Class<?>) PatternMatchedCollection.class;
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
  public void traverse(PatternMatchedCollection<T> object, Traversal traversal) {
    for (Pair<Pattern, T> value : object.getPatternsAndValues()) {
      traversal.traverse(value.getFirst());
      valueTypeCoercer.traverse(value.getSecond(), traversal);
    }
  }

  @Override
  public Optional<PatternMatchedCollection<T>> getOptionalValue() {
    return Optional.of(PatternMatchedCollection.<T>of());
  }

  @Override
  public PatternMatchedCollection<T> coerce(
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object) throws CoerceFailedException {
    if (!(object instanceof List)) {
      throw CoerceFailedException.simple(
          object,
          getOutputClass(),
          "input object should be a list of pairs");
    }
    PatternMatchedCollection.Builder<T> builder = PatternMatchedCollection.builder();
    List<?> list = (List<?>) object;
    for (Object element : list) {
      if (!(element instanceof Collection) || ((Collection<?>) element).size() != 2) {
        throw CoerceFailedException.simple(
            object,
            getOutputClass(),
            "input object should be a list of pairs");
      }
      Iterator<?> pair = ((Collection<?>) element).iterator();
      Pattern platformSelector = patternTypeCoercer.coerce(
          filesystem,
          pathRelativeToProjectRoot,
          pair.next());
      T value = valueTypeCoercer.coerce(
          filesystem,
          pathRelativeToProjectRoot,
          pair.next());
      builder.add(platformSelector, value);
    }
    return builder.build();
  }
}
