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

package com.facebook.buck.skylark.parser.pojoizer;

import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.parser.syntax.SelectorValue;
import com.facebook.buck.skylark.function.select.SelectorList;
import com.facebook.buck.util.collect.TwoArraysImmutableHashMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;

/** Utility class to convert parser-created objects to equivalent POJO-typed objects */
public class BuildFileManifestPojoizer {

  private BuildFileManifestPojoizer() {}

  private static ImmutableList<Object> convertListToPojo(Collection<?> object)
      throws EvalException {
    ImmutableList.Builder<Object> builder = ImmutableList.builderWithExpectedSize(object.size());

    for (Object obj : object) {
      builder.add(convertToPojo(obj));
    }

    return builder.build();
  }

  private static TwoArraysImmutableHashMap<Object, Object> convertMapToPojo(Map<?, ?> object)
      throws EvalException {
    @SuppressWarnings("unchecked")
    Map<Object, Object> map = (Map<Object, Object>) object;

    TwoArraysImmutableHashMap.Builder<Object, Object> builder =
        TwoArraysImmutableHashMap.builderWithExpectedSize(map.size());

    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = entry.getKey();
      Object value = entry.getValue();
      Object convertedKey = convertToPojo(key);
      Object convertedValue = convertToPojo(value);

      builder.put(convertedKey, convertedValue);
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private static ImmutableSet<Object> convertSetToPojo(Set<?> object) throws EvalException {
    boolean needConversion;
    ImmutableSet.Builder<Object> builder;
    if (object instanceof SortedSet<?>) {
      needConversion = !(object instanceof ImmutableSortedSet<?>);
      builder = new ImmutableSortedSet.Builder<>(((SortedSet<Object>) object).comparator());
    } else {
      needConversion = !(object instanceof ImmutableSet);
      builder = ImmutableSet.builderWithExpectedSize(object.size());
    }

    for (Object obj : object) {
      Object convertedObj = convertToPojo(obj);

      if (!needConversion && convertedObj != obj) {
        needConversion = true;
      }

      builder.add(convertedObj);
    }

    return needConversion ? builder.build() : (ImmutableSet<Object>) object;
  }

  /**
   * Convert Starlark object parameter to an object compatible with buck raw target node value.
   * Return {@code null} for {@link Starlark#NONE}.
   */
  public static Object convertToPojo(Object obj) throws EvalException {
    if (obj instanceof Boolean
        || obj instanceof String
        || obj instanceof Integer
        || obj == Starlark.NONE) {
      return obj;
    } else if (obj instanceof StarlarkInt) {
      return ((StarlarkInt) obj).toInt("convert to pojo");
    } else if (obj instanceof List<?>) {
      return convertListToPojo((List<?>) obj);
    } else if (obj instanceof Map<?, ?>) {
      return convertMapToPojo((Map<?, ?>) obj);
    } else if (obj instanceof Set<?>) {
      return convertSetToPojo((Set<?>) obj);
    } else if (obj instanceof SelectorList) {
      SelectorList skylarkSelectorList = (SelectorList) obj;
      // recursively convert list elements
      ImmutableList<Object> elements = convertListToPojo(skylarkSelectorList.getElements());
      return ListWithSelects.of(elements, convertClassToPojo(skylarkSelectorList.getType()));
    } else if (obj instanceof com.facebook.buck.skylark.function.select.SelectorValue) {
      com.facebook.buck.skylark.function.select.SelectorValue skylarkSelectorValue =
          (com.facebook.buck.skylark.function.select.SelectorValue) obj;
      // recursively convert dictionary elements
      @SuppressWarnings("unchecked")
      TwoArraysImmutableHashMap<String, Object> dictionary =
          (TwoArraysImmutableHashMap<String, Object>)
              (TwoArraysImmutableHashMap<?, Object>)
                  convertMapToPojo(skylarkSelectorValue.getDictionary());
      return SelectorValue.of(
          ImmutableMap.copyOf(dictionary), skylarkSelectorValue.getNoMatchError());
    } else if (obj instanceof Optional<?>) {
      // TODO(nga): how Optional can appear here? It is not a valid Starlark value.
      //   Remove and fix tests UDR tests.
      Optional<?> optional = (Optional<?>) obj;
      return optional.isPresent()
          ? Optional.ofNullable(convertToPojo(optional.get()))
          : Optional.empty();
    } else if (obj == null) {
      throw new IllegalArgumentException("null Starlark value");
    } else {
      throw new EvalException(
          String.format("don't know how to convert %s to POJO", obj.getClass().getName()));
    }
  }

  private static Class<?> convertClassToPojo(Class<?> clazz) {
    if (StarlarkInt.class.isAssignableFrom(clazz)) {
      return Integer.class;
    } else if (StarlarkList.class.isAssignableFrom(clazz)) {
      // we convert all starlark lists to immutable lists
      return ImmutableList.class;
    } else {
      return clazz;
    }
  }
}
