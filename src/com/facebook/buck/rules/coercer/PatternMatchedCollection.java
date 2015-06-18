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

import com.facebook.buck.model.Pair;
import com.google.common.collect.ImmutableList;

import java.util.regex.Pattern;

public class PatternMatchedCollection<T> {

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

  public static <T> PatternMatchedCollection<T> of() {
    return new PatternMatchedCollection<>(ImmutableList.<Pair<Pattern, T>>of());
  }

  public static <T> Builder<T> builder() {
    return new Builder<>();
  }

  public static final class Builder<T> {

    private final ImmutableList.Builder<Pair<Pattern, T>> builder = ImmutableList.builder();

    public void add(Pattern platformSelector, T value) {
      builder.add(new Pair<>(platformSelector, value));
    }

    public PatternMatchedCollection<T> build() {
      return new PatternMatchedCollection<>(builder.build());
    }
  }
}
