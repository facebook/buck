/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.model;

import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Provides a named flavor abstraction on top of boolean flavors. */
public class FlavorDomain<T> {

  private final String name;
  private final ImmutableMap<Flavor, T> translation;

  public FlavorDomain(String name, ImmutableMap<Flavor, T> translation) {
    this.name = name;
    this.translation = translation;
  }

  public String getName() {
    return name;
  }

  public ImmutableSet<Flavor> getFlavors() {
    return translation.keySet();
  }

  public ImmutableCollection<T> getValues() {
    return translation.values();
  }

  public boolean containsAnyOf(Set<Flavor> flavors) {
    return !Sets.intersection(translation.keySet(), flavors).isEmpty();
  }

  public boolean contains(Flavor flavor) {
    return translation.containsKey(flavor);
  }

  public Optional<Flavor> getFlavor(Set<Flavor> flavors) {
    Sets.SetView<Flavor> match = Sets.intersection(translation.keySet(), flavors);
    if (match.size() > 1) {
      throw new FlavorDomainException(
          String.format("multiple \"%s\" flavors: %s", name, Joiner.on(", ").join(match)));
    }

    return Optional.ofNullable(Iterables.getFirst(match, null));
  }

  public Optional<Flavor> getFlavor(BuildTarget buildTarget) {
    try {
      return getFlavor(buildTarget.getFlavors());
    } catch (FlavorDomainException e) {
      throw new FlavorDomainException(
          String.format("In build target %s: %s", buildTarget, e.getHumanReadableErrorMessage()));
    }
  }

  public Optional<Map.Entry<Flavor, T>> getFlavorAndValue(Set<Flavor> flavors) {
    Optional<Flavor> flavor = getFlavor(flavors);
    if (!flavor.isPresent()) {
      return Optional.empty();
    }

    return Optional.of(
        new AbstractMap.SimpleImmutableEntry<>(flavor.get(), translation.get(flavor.get())));
  }

  public Optional<Map.Entry<Flavor, T>> getFlavorAndValue(BuildTarget buildTarget) {
    try {
      return getFlavorAndValue(buildTarget.getFlavors());
    } catch (FlavorDomainException e) {
      throw new FlavorDomainException(
          String.format("In build target %s: %s", buildTarget, e.getHumanReadableErrorMessage()));
    }
  }

  public Optional<T> getValue(Set<Flavor> flavors) {
    Optional<Flavor> flavor = getFlavor(flavors);
    return flavor.map(translation::get);
  }

  public Optional<T> getValue(BuildTarget buildTarget) {
    try {
      return getValue(buildTarget.getFlavors());
    } catch (FlavorDomainException e) {
      throw new FlavorDomainException(
          String.format("In build target %s: %s", buildTarget, e.getHumanReadableErrorMessage()));
    }
  }

  public T getRequiredValue(BuildTarget buildTarget) {
    Optional<T> value;
    try {
      value = getValue(buildTarget.getFlavors());
    } catch (FlavorDomainException e) {
      throw new FlavorDomainException(
          String.format("In build target %s: %s", buildTarget, e.getHumanReadableErrorMessage()));
    }
    if (!value.isPresent()) {
      throw new HumanReadableException(
          "Build target '%s' did not specify required value for '%s', possible values:\n%s",
          buildTarget, name, Joiner.on(", ").join(getFlavors()));
    }
    return value.get();
  }

  public T getValue(Flavor flavor) {
    T result = translation.get(flavor);
    if (result == null) {
      throw new FlavorDomainException(String.format("\"%s\" has no flavor \"%s\"", name, flavor));
    }
    return result;
  }

  /** Create a FlavorDomain from FlavorConvertible objects. */
  public static <T extends FlavorConvertible> FlavorDomain<T> from(
      String name, Iterable<T> objects) {
    ImmutableMap.Builder<Flavor, T> builder = ImmutableMap.builder();
    for (T value : objects) {
      builder.put(value.getFlavor(), value);
    }
    return new FlavorDomain<>(name, builder.build());
  }

  /** Create a FlavorDomain from array/varargs of FlavorConvertible objects. */
  @SafeVarargs
  public static <T extends FlavorConvertible> FlavorDomain<T> of(String name, T... objects) {
    return from(name, Arrays.asList(objects));
  }

  /** Create a FlavorDomain from FlavorConverbile Enum. */
  public static <E extends Enum<E> & FlavorConvertible> FlavorDomain<E> from(
      String name, Class<E> cls) {
    return of(name, cls.getEnumConstants());
  }
}
