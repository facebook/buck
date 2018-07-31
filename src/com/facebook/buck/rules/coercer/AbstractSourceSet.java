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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.parser.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.facebook.buck.versions.TargetTranslatable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import org.immutables.value.Value;

/**
 * A group of ordered sources, stored as either a {@link Set} of unnamed {@link SourcePath}s or a
 * {@link java.util.Map} of names to {@link SourcePath}s. Commonly used to represent the input
 * <code>srcs</code> parameter of rules where source "names" may be important (e.g. to control
 * layout of C++ headers).
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractSourceSet implements TargetTranslatable<SourceSet>, AddsToRuleKey {

  public static final SourceSet EMPTY = SourceSet.ofUnnamedSources(ImmutableSet.of());

  public enum Type {
    UNNAMED,
    NAMED,
  }

  @Value.Parameter
  @AddToRuleKey
  public abstract Type getType();

  @Value.Parameter
  @AddToRuleKey
  public abstract Optional<ImmutableSet<SourcePath>> getUnnamedSources();

  @Value.Parameter
  @AddToRuleKey
  public abstract Optional<ImmutableMap<String, SourcePath>> getNamedSources();

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

  public static SourceSet ofUnnamedSources(ImmutableSet<SourcePath> unnamedSources) {
    return SourceSet.of(Type.UNNAMED, Optional.of(unnamedSources), Optional.empty());
  }

  public static SourceSet ofNamedSources(ImmutableMap<String, SourcePath> namedSources) {
    return SourceSet.of(Type.NAMED, Optional.empty(), Optional.of(namedSources));
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
      String parameterName,
      Predicate<SourcePath> filter,
      Function<SourcePath, SourcePath> transform) {

    ImmutableMap.Builder<String, SourcePath> sources = ImmutableMap.builder();
    switch (getType()) {
      case NAMED:
        for (Map.Entry<String, SourcePath> ent : getNamedSources().get().entrySet()) {
          if (filter.test(ent.getValue())) {
            sources.put(ent.getKey(), transform.apply(ent.getValue()));
          }
        }
        break;
      case UNNAMED:
        pathResolver
            .getSourcePathNames(
                buildTarget, parameterName, getUnnamedSources().get(), filter, transform)
            .forEach((name, path) -> sources.put(name, transform.apply(path)));
        break;
    }
    return sources.build();
  }

  public ImmutableMap<String, SourcePath> toNameMap(
      BuildTarget buildTarget, SourcePathResolver pathResolver, String parameterName) {
    return toNameMap(buildTarget, pathResolver, parameterName, x -> true, x -> x);
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

  @Override
  public Optional<SourceSet> translateTargets(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      TargetNodeTranslator translator) {
    Optional<Optional<ImmutableMap<String, SourcePath>>> namedSources =
        translator.translate(cellPathResolver, pattern, getNamedSources());
    Optional<Optional<ImmutableSet<SourcePath>>> unNamedSources =
        translator.translate(cellPathResolver, pattern, getUnnamedSources());
    if (!namedSources.isPresent() && !unNamedSources.isPresent()) {
      return Optional.empty();
    }
    SourceSet.Builder builder = SourceSet.builder();
    builder.setType(getType());
    builder.setNamedSources(namedSources.orElse(getNamedSources()));
    builder.setUnnamedSources(unNamedSources.orElse(getUnnamedSources()));
    return Optional.of(builder.build());
  }

  /** Concatenates elements of the given lists into a single list. */
  public static SourceSet concat(Iterable<SourceSet> elements) {
    Type type = findType(elements);

    if (type == Type.UNNAMED) {
      return concatUnnamed(elements);
    } else {
      return concatNamed(elements);
    }
  }

  private static Type findType(Iterable<SourceSet> elements) {
    if (!elements.iterator().hasNext()) {
      return Type.UNNAMED;
    }
    return elements.iterator().next().getType();
  }

  private static SourceSet concatUnnamed(Iterable<SourceSet> elements) {
    Set<SourcePath> unnamedSources = new TreeSet<>();

    for (SourceSet element : elements) {
      Preconditions.checkState(
          element.getType().equals(Type.UNNAMED),
          "Expected unnamed source list, got: %s",
          element.getType());
      element.getUnnamedSources().ifPresent(unnamedSources::addAll);
    }

    return ofUnnamedSources(ImmutableSet.copyOf(unnamedSources));
  }

  private static SourceSet concatNamed(Iterable<SourceSet> elements) {
    ImmutableMap.Builder<String, SourcePath> namedSources = ImmutableMap.builder();

    for (SourceSet element : elements) {
      Preconditions.checkState(
          element.getType().equals(Type.NAMED),
          "Expected named source list, got: %s",
          element.getType());
      element.getNamedSources().ifPresent(namedSources::putAll);
    }

    return ofNamedSources(namedSources.build());
  }
}
