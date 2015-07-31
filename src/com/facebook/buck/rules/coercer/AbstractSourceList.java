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

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractSourceList {

  public enum Type {
    UNNAMED,
    NAMED,
  }

  @Value.Parameter
  public abstract Type getType();

  @Value.Parameter
  public abstract Optional<ImmutableSortedSet<SourcePath>> getUnnamedSources();

  @Value.Parameter
  public abstract Optional<ImmutableSortedMap<String, SourcePath>> getNamedSources();

  @Value.Check
  protected void check() {
    switch (getType()) {
      case UNNAMED:
        Preconditions.checkArgument(getUnnamedSources().isPresent());
        Preconditions.checkArgument(!getNamedSources().isPresent());
        break;
      case NAMED:
        Preconditions.checkArgument(!getUnnamedSources().isPresent());
        Preconditions.checkArgument(getNamedSources().isPresent());
        break;
    }
  }

  public static SourceList ofUnnamedSources(ImmutableSortedSet<SourcePath> unnamedSources) {
    return SourceList.of(
        Type.UNNAMED,
        Optional.of(unnamedSources),
        Optional.<ImmutableSortedMap<String, SourcePath>>absent());
  }

  public static SourceList ofNamedSources(ImmutableSortedMap<String, SourcePath> namedSources) {
    return SourceList.of(
        Type.NAMED,
        Optional.<ImmutableSortedSet<SourcePath>>absent(),
        Optional.of(namedSources));
  }

}
