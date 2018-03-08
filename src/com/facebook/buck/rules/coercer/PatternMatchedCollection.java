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

import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Pair;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.facebook.buck.versions.TargetTranslatable;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

public class PatternMatchedCollection<T>
    implements TargetTranslatable<PatternMatchedCollection<T>> {

  private final ImmutableList<Pair<Pattern, T>> values;

  private PatternMatchedCollection(ImmutableList<Pair<Pattern, T>> values) {
    this.values = values;
  }

  public ImmutableList<T> getMatchingValues(String string) {
    ImmutableList.Builder<T> matchingValues = ImmutableList.builder();
    for (Pair<Pattern, T> pair : values) {
      if (pair.getFirst().matcher(string).find()) {
        matchingValues.add(pair.getSecond());
      }
    }
    return matchingValues.build();
  }

  public ImmutableList<Pair<Pattern, T>> getPatternsAndValues() {
    return values;
  }

  public ImmutableList<T> getValues() {
    ImmutableList.Builder<T> vals = ImmutableList.builder();
    for (Pair<Pattern, T> value : values) {
      vals.add(value.getSecond());
    }
    return vals.build();
  }

  @Override
  public Optional<PatternMatchedCollection<T>> translateTargets(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      TargetNodeTranslator translator) {
    Optional<ImmutableList<Pair<Pattern, T>>> translatedValues =
        translator.translate(cellPathResolver, pattern, values);
    return translatedValues.isPresent()
        ? Optional.of(new PatternMatchedCollection<>(translatedValues.get()))
        : Optional.empty();
  }

  public static <T> PatternMatchedCollection<T> of() {
    return new PatternMatchedCollection<>(ImmutableList.of());
  }

  public <V> PatternMatchedCollection<V> map(Function<T, V> func) {
    return new PatternMatchedCollection<>(
        RichStream.from(values)
            .map(p -> new Pair<>(p.getFirst(), func.apply(p.getSecond())))
            .toImmutableList());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PatternMatchedCollection)) {
      return false;
    }

    PatternMatchedCollection<?> that = (PatternMatchedCollection<?>) o;

    return values.equals(that.values);
  }

  @Override
  public int hashCode() {
    return values.hashCode();
  }

  public static <T> Builder<T> builder() {
    return new Builder<>();
  }

  public static final class Builder<T> {

    private final ImmutableList.Builder<Pair<Pattern, T>> builder = ImmutableList.builder();

    public Builder<T> add(Pattern platformSelector, T value) {
      builder.add(new Pair<>(platformSelector, value));
      return this;
    }

    public PatternMatchedCollection<T> build() {
      return new PatternMatchedCollection<>(builder.build());
    }
  }
}
