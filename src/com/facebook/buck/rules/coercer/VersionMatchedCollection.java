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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.facebook.buck.versions.TargetTranslatable;
import com.facebook.buck.versions.Version;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;

public class VersionMatchedCollection<T>
    implements TargetTranslatable<VersionMatchedCollection<T>> {

  private final ImmutableList<Pair<ImmutableMap<BuildTarget, Version>, T>> values;

  public VersionMatchedCollection(
      ImmutableList<Pair<ImmutableMap<BuildTarget, Version>, T>> values) {
    this.values = values;
  }

  private boolean matches(
      ImmutableMap<BuildTarget, Version> universe, ImmutableMap<BuildTarget, Version> versions) {
    for (Map.Entry<BuildTarget, Version> ent : versions.entrySet()) {
      Version existing = universe.get(ent.getKey());
      if (existing != null && !existing.equals(ent.getValue())) {
        return false;
      }
    }
    return true;
  }

  public ImmutableList<T> getMatchingValues(ImmutableMap<BuildTarget, Version> selected) {
    ImmutableList.Builder<T> matchingValues = ImmutableList.builder();
    for (Pair<ImmutableMap<BuildTarget, Version>, T> pair : values) {
      if (matches(selected, pair.getFirst())) {
        matchingValues.add(pair.getSecond());
      }
    }
    return matchingValues.build();
  }

  /** @return the only item that matches the given version map, or throw. */
  public T getOnlyMatchingValue(String source, ImmutableMap<BuildTarget, Version> selected) {
    ImmutableList<T> matching = getMatchingValues(selected);
    if (matching.size() != 1) {
      throw new HumanReadableException(
          "%s: expected at most a single match for %s, but found %s (from %s). "
              + "Please add an entry which matches.",
          source, selected, matching, values);
    }
    return matching.get(0);
  }

  public ImmutableList<T> getValues() {
    ImmutableList.Builder<T> vals = ImmutableList.builder();
    for (Pair<?, T> value : values) {
      vals.add(value.getSecond());
    }
    return vals.build();
  }

  public ImmutableList<Pair<ImmutableMap<BuildTarget, Version>, T>> getValuePairs() {
    return values;
  }

  public static <T> VersionMatchedCollection<T> of() {
    return new VersionMatchedCollection<>(ImmutableList.of());
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(VersionMatchedCollection.class)
        .add("values", values)
        .toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VersionMatchedCollection)) {
      return false;
    }

    VersionMatchedCollection<?> that = (VersionMatchedCollection<?>) o;

    return values.equals(that.values);
  }

  @Override
  public int hashCode() {
    return values.hashCode();
  }

  public static <T> Builder<T> builder() {
    return new Builder<>();
  }

  @Override
  public Optional<VersionMatchedCollection<T>> translateTargets(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      TargetNodeTranslator translator) {
    boolean translated = false;
    ImmutableList.Builder<Pair<ImmutableMap<BuildTarget, Version>, T>> newValues =
        ImmutableList.builder();
    for (Pair<ImmutableMap<BuildTarget, Version>, T> p : values) {
      Optional<T> newVal = translator.translate(cellPathResolver, pattern, p.getSecond());
      if (newVal.isPresent()) {
        newValues.add(new Pair<>(p.getFirst(), newVal.get()));
        translated = true;
      }
    }
    return translated
        ? Optional.of(new VersionMatchedCollection<>(newValues.build()))
        : Optional.empty();
  }

  public static final class Builder<T> {

    private final ImmutableList.Builder<Pair<ImmutableMap<BuildTarget, Version>, T>> builder =
        ImmutableList.builder();

    public Builder<T> add(ImmutableMap<BuildTarget, Version> versions, T value) {
      builder.add(new Pair<>(versions, value));
      return this;
    }

    public VersionMatchedCollection<T> build() {
      return new VersionMatchedCollection<>(builder.build());
    }
  }
}
