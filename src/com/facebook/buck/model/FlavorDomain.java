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

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.util.AbstractMap;
import java.util.Map;

/**
 * Provides a named flavor abstraction on top of boolean flavors.
 */
public class FlavorDomain<T> {

  private final String name;
  private final ImmutableMap<Flavor, T> translation;

  public FlavorDomain(String name, ImmutableMap<Flavor, T> translation) {
    this.name = Preconditions.checkNotNull(name);
    this.translation = Preconditions.checkNotNull(translation);
  }

  public String getName() {
    return name;
  }

  public boolean containsAnyOf(ImmutableSet<Flavor> flavors) {
    return !Sets.intersection(translation.keySet(), flavors).isEmpty();
  }

  public Optional<Flavor> getFlavor(ImmutableSet<Flavor> flavors) throws FlavorDomainException {
    Sets.SetView<Flavor> match = Sets.intersection(translation.keySet(), flavors);
    if (match.size() > 1) {
      throw new FlavorDomainException(
          String.format(
              "multiple \"%s\" flavors: %s",
              name,
              Joiner.on(", ").join(match)));
    }

    return Optional.fromNullable(Iterables.getFirst(match, null));
  }

  public Optional<Map.Entry<Flavor, T>> getFlavorAndValue(ImmutableSet<Flavor> flavors)
      throws FlavorDomainException {

    Optional<Flavor> flavor = getFlavor(flavors);

    if (!flavor.isPresent()) {
      return Optional.absent();
    }

    return Optional.<Map.Entry<Flavor, T>>of(
        new AbstractMap.SimpleImmutableEntry<>(
            flavor.get(),
            translation.get(flavor.get())));
  }

  public T getValue(Flavor flavor) throws FlavorDomainException {
    T result = translation.get(flavor);
    if (result == null) {
      throw new FlavorDomainException(
          String.format(
              "\"%s\" has no flavor \"%s\"",
              name,
              flavor));
    }
    return result;
  }

}
