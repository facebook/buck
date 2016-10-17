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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import org.immutables.value.Value;

import java.util.Optional;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractSourceList {

  public static final SourceList EMPTY =
      SourceList.ofUnnamedSources(ImmutableSortedSet.of());

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
        Optional.empty());
  }

  public static SourceList ofNamedSources(ImmutableSortedMap<String, SourcePath> namedSources) {
    return SourceList.of(
        Type.NAMED,
        Optional.empty(),
        Optional.of(namedSources));
  }

  public boolean isEmpty() {
    switch (getType()) {
      case UNNAMED:
        return getUnnamedSources().get().isEmpty();
      case NAMED:
        return getNamedSources().get().isEmpty();
      default:
        throw new IllegalStateException("unexpected type: " + getType());
    }
  }

  public ImmutableMap<String, SourcePath> toNameMap(
      BuildTarget buildTarget,
      SourcePathResolver pathResolver,
      String parameterName) {
    ImmutableMap.Builder<String, SourcePath> sources = ImmutableMap.builder();
    switch (getType()) {
      case NAMED:
        sources.putAll(getNamedSources().get());
        break;
      case UNNAMED:
        sources.putAll(
            pathResolver.getSourcePathNames(
                buildTarget,
                parameterName,
                getUnnamedSources().get()));
        break;
    }
    return sources.build();
  }

  public ImmutableList<SourcePath> getPaths() {
    ImmutableList.Builder<SourcePath> sources = ImmutableList.builder();
    switch (getType()) {
      case NAMED:
        sources.addAll(getNamedSources().get().values());
        break;
      case UNNAMED:
        sources.addAll(getUnnamedSources().get());
        break;
    }
    return sources.build();
  }

}
