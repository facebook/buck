/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser.api;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.function.Function;
import org.immutables.value.Value;

/** Utility class to convert parser-created objects to equivalent POJO-typed objects */
public class BuildFileManifestPojoizer {

  /**
   * Container to hold transformation function for classes assignable to a provided class. The
   * result of the transformation is supposed to be a POJO class accepted by {@link
   * BuildFileManifest} protocol.
   */
  @BuckStyleImmutable
  @Value.Immutable(builder = false, copy = false)
  public abstract static class AbstractPojoTransformer {

    /**
     * Return a type that transformer is able to transform from; the real object should be
     * assignable to this class, so base classes and interfaces can be used too
     */
    @Value.Parameter
    public abstract Class<?> getClazz();

    /** Function that transforms a parser object to POJO object */
    @Value.Parameter
    public abstract Function<Object, Object> getTransformFunction();
  }

  private BuildFileManifestPojoizer() {

    // default converter - just return the value itself, possibly checking it conforms to
    // POJO
    transformers.add(PojoTransformer.of(Object.class, this::convertObjectToPojo));

    // all other transformers; the order is important here
    // the least concrete object should go first, the most concrete one does go last
    transformers.add(PojoTransformer.of(Iterable.class, this::convertIterableToPojo));
    transformers.add(PojoTransformer.of(Set.class, this::convertSetToPojo));
    transformers.add(PojoTransformer.of(Map.class, this::convertMapToPojo));
  }

  /** Create an instance of {@link BuildFileManifestPojoizer} */
  public static BuildFileManifestPojoizer of() {
    return new BuildFileManifestPojoizer();
  }

  /**
   * Add a custom transformer to {@link BuildFileManifestPojoizer}. If a type specified in this
   * transfomer matches the type of parser object, the transformation function is invoked. It is
   * expected for transformation function to return POJO-type accepted by {@code BuildFileManifest}.
   *
   * <p>This utility exists mainly to decouple some specific types known to some parser only, from
   * generic parser API
   *
   * <p>This method effectively makes {@link BuildFileManifestPojoizer} mutable. This is only
   * because the implementation of the transformer may want to recursively call {@link
   * BuildFileManifestPojoizer#convertToPojo} so {@link BuildFileManifestPojoizer} should be
   * constructed beforehand.
   */
  public BuildFileManifestPojoizer addPojoTransformer(
      BuildFileManifestPojoizer.AbstractPojoTransformer customTransformer) {
    transformers.add(customTransformer);
    return this;
  }

  private List<AbstractPojoTransformer> transformers = new ArrayList<>(8);

  /**
   * Recursively convert parser-created objects into equivalent POJO objects. All collections are
   * replaced with Immutable collections. So, by contract, only the following collections are
   * allowed to be in converted objects: ImmutableList, ImmutableSet, ImmutableSortedSet,
   * ImmutableMap, ImmutableSortedMap. Any of concrete implementation of those abstract immutable
   * collections is allowed.
   *
   * <p>Other allowed types are primitive Java types, Optional, and classes from
   * com.facebook.buck.parser.api package.
   *
   * <p>This function is allowed to be called from transformer function for full recursion
   *
   * <p>Examples: SkylarkDict is replaced with ImmutableMap, SkylarkList is replaced with
   * ImmutableList recursively
   */
  public Object convertToPojo(Object parserObj) {
    // if none of user transformers matched, apply default transformations
    for (int i = transformers.size() - 1; i >= 0; i--) {
      BuildFileManifestPojoizer.AbstractPojoTransformer transformer = transformers.get(i);
      if (transformer.getClazz().isInstance(parserObj)) {
        return transformer.getTransformFunction().apply(parserObj);
      }
    }

    throw new IllegalArgumentException(
        this.getClass().toString()
            + " is unable to cast an object of type "
            + parserObj.getClass().toString());
  }

  @SuppressWarnings("unchecked")
  private ImmutableList<Object> convertIterableToPojo(Object object) {

    Iterable<Object> iterable = (Iterable<Object>) object;

    ImmutableList.Builder<Object> builder =
        iterable instanceof Collection
            ? ImmutableList.builderWithExpectedSize(((Collection<Object>) iterable).size())
            : ImmutableList.builder();

    boolean needConversion = !(iterable instanceof ImmutableList);
    for (Object obj : iterable) {
      Object convertedObj = convertToPojo(obj);

      if (!needConversion && convertedObj != obj) {
        needConversion = true;
      }

      builder.add(convertedObj);
    }

    return needConversion ? builder.build() : (ImmutableList<Object>) iterable;
  }

  @SuppressWarnings("unchecked")
  private ImmutableSet<Object> convertSetToPojo(Object object) {
    Set<Object> set = (Set<Object>) object;

    boolean needConversion;
    ImmutableSet.Builder<Object> builder;
    if (set instanceof SortedSet) {
      needConversion = !(set instanceof ImmutableSortedSet);
      builder = new ImmutableSortedSet.Builder<>(((SortedSet<Object>) set).comparator());
    } else {
      needConversion = !(set instanceof ImmutableSet);
      builder = ImmutableSet.builderWithExpectedSize(set.size());
    }

    for (Object obj : set) {
      Object convertedObj = convertToPojo(obj);

      if (!needConversion && convertedObj != obj) {
        needConversion = true;
      }

      builder.add(convertedObj);
    }

    return needConversion ? builder.build() : (ImmutableSet<Object>) set;
  }

  @SuppressWarnings("unchecked")
  private ImmutableMap<Object, Object> convertMapToPojo(Object object) {
    Map<Object, Object> map = (Map<Object, Object>) object;

    boolean needConversion;
    ImmutableMap.Builder<Object, Object> builder;
    if (map instanceof SortedMap) {
      needConversion = !(map instanceof ImmutableSortedMap);
      builder = new ImmutableSortedMap.Builder<>(((SortedMap<Object, Object>) map).comparator());
    } else {
      needConversion = !(map instanceof ImmutableMap);
      builder = ImmutableMap.builderWithExpectedSize(map.size());
    }

    for (Map.Entry<?, ?> entry : map.entrySet()) {
      Object key = entry.getKey();
      Object value = entry.getValue();
      Object convertedKey = convertToPojo(key);
      Object convertedValue = convertToPojo(value);

      if (!needConversion && (convertedKey != key || convertedValue != value)) {
        needConversion = true;
      }

      builder.put(convertedKey, convertedValue);
    }
    return needConversion ? builder.build() : (ImmutableMap<Object, Object>) map;
  }

  private Object convertObjectToPojo(Object obj) {
    // Should we do POJO sanity check?
    return obj;
  }
}
