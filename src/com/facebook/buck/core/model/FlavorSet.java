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

package com.facebook.buck.core.model;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Set of {@link com.facebook.buck.core.model.Flavor}s. */
public class FlavorSet implements Comparable<FlavorSet> {
  public static final FlavorSet NO_FLAVORS = new FlavorSet(ImmutableSortedSet.of());

  /** Flavors passed to this object should be sorted using this ordering */
  public static final Ordering<Flavor> FLAVOR_ORDERING = Ordering.natural();

  static final Ordering<Iterable<Flavor>> LEXICOGRAPHICAL_ORDERING =
      Ordering.<Flavor>natural().lexicographical();

  private final ImmutableSortedSet<Flavor> flavors;

  private FlavorSet(ImmutableSortedSet<Flavor> flavors) {
    this.flavors = flavors;
  }

  public static FlavorSet of(Flavor... flavors) {
    return copyOf(ImmutableSortedSet.copyOf(flavors));
  }

  @Override
  public boolean equals(Object that) {
    if (this == that) {
      return true;
    }
    if (that == null || that.getClass() != this.getClass()) {
      return false;
    }
    return flavors.equals(((FlavorSet) that).flavors);
  }

  @Override
  public int hashCode() {
    return flavors.hashCode();
  }

  @Override
  public int compareTo(FlavorSet o) {
    return LEXICOGRAPHICAL_ORDERING.compare(this.flavors, o.flavors);
  }

  public ImmutableSortedSet<Flavor> getSet() {
    return flavors;
  }

  public boolean isEmpty() {
    return flavors.isEmpty();
  }

  @Override
  public String toString() {
    return toPostfixString();
  }

  /** Just comma separated. */
  public String toCommaSeparatedString() {
    return flavors.stream().map(Flavor::getName).collect(Collectors.joining(","));
  }

  /** Flavors list as string in the end of build target. */
  public String toPostfixString() {
    if (flavors.isEmpty()) {
      return "";
    }
    return "#" + toCommaSeparatedString();
  }

  /** Constructor. */
  public static FlavorSet copyOf(Iterable<? extends Flavor> flavors) {
    return copyOf(ImmutableSortedSet.copyOf(FLAVOR_ORDERING, flavors));
  }

  /** Constructor. */
  public static FlavorSet copyOf(ImmutableSortedSet<Flavor> flavors) {
    if (flavors.isEmpty()) {
      return NO_FLAVORS;
    } else {
      return new FlavorSet(ImmutableSortedSet.copyOf(FLAVOR_ORDERING, flavors));
    }
  }

  /** Return a flavor set with flavors in first or second given set. */
  public static FlavorSet union(FlavorSet a, FlavorSet b) {
    if (a.isEmpty()) {
      return b;
    } else if (b.isEmpty()) {
      return a;
    } else {
      return new FlavorSet(
          Stream.concat(a.flavors.stream(), b.flavors.stream())
              .collect(ImmutableSortedSet.toImmutableSortedSet(FLAVOR_ORDERING)));
    }
  }

  /** Stream collector. */
  public static Collector<Flavor, ?, FlavorSet> toFlavorSet() {
    return Collectors.collectingAndThen(
        ImmutableSortedSet.toImmutableSortedSet(FLAVOR_ORDERING), FlavorSet::copyOf);
  }

  /** Flavors in this set, but not in that set. */
  public FlavorSet without(Set<Flavor> flavors) {
    if (this.isEmpty() || flavors.isEmpty()) {
      return this;
    }
    return copyOf(Sets.difference(this.flavors, flavors));
  }

  /** Create a flavor set with union of this and that flavors. */
  public FlavorSet withAdded(Set<Flavor> flavors) {
    if (flavors.isEmpty()) {
      return this;
    }
    return copyOf(Sets.union(this.flavors, flavors));
  }

  /** Does this set contain given flavor? */
  public boolean contains(Flavor flavor) {
    return flavors.contains(flavor);
  }

  /** Does this set contain all given flavors? */
  public boolean containsAll(FlavorSet flavors) {
    return this.flavors.containsAll(flavors.getSet());
  }

  /** Does this set contain all given flavors? */
  public boolean containsAll(Collection<Flavor> flavors) {
    return this.flavors.containsAll(flavors);
  }
}
