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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.util.types.Unit;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.facebook.buck.versions.TargetTranslatable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A group of ordered sources, stored as either a {@link Set} of unnamed {@link SourcePath}s or a
 * {@link java.util.Map} of names to {@link SourcePath}s. Commonly used to represent the input
 * <code>srcs</code> parameter of rules where source "names" may be important (e.g. to control
 * layout of C++ headers).
 */
public abstract class SourceSet implements TargetTranslatable<SourceSet>, AddsToRuleKey {

  private SourceSet() {}

  public static final SourceSet EMPTY = SourceSet.ofUnnamedSources(ImmutableSet.of());

  /** Named. */
  @BuckStyleValue
  abstract static class SourceSetNamed extends SourceSet {
    @AddToRuleKey
    public abstract ImmutableMap<String, SourcePath> getNamedSources();

    @AddToRuleKey
    @Override
    public Type getType() {
      return Type.NAMED;
    }

    @Override
    public boolean isEmpty() {
      return getNamedSources().isEmpty();
    }

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.named(getNamedSources());
    }
  }

  /** Unnamed. */
  @BuckStyleValue
  abstract static class SourceSetUnnamed extends SourceSet {
    @AddToRuleKey
    public abstract ImmutableSet<SourcePath> getUnnamedSources();

    @AddToRuleKey
    @Override
    public Type getType() {
      return Type.UNNAMED;
    }

    @Override
    public boolean isEmpty() {
      return getUnnamedSources().isEmpty();
    }

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.unnamed(getUnnamedSources());
    }
  }

  public enum Type {
    UNNAMED,
    NAMED,
  }

  public abstract Type getType();

  public static SourceSet ofUnnamedSources(ImmutableSet<SourcePath> unnamedSources) {
    return ImmutableSourceSetUnnamed.ofImpl(unnamedSources);
  }

  public static SourceSet ofNamedSources(ImmutableMap<String, SourcePath> namedSources) {
    return ImmutableSourceSetNamed.ofImpl(namedSources);
  }

  public abstract boolean isEmpty();

  /** Callback for {@link #match(Matcher)}. */
  public interface Matcher<R> {
    R named(ImmutableMap<String, SourcePath> named);

    R unnamed(ImmutableSet<SourcePath> unnamed);
  }

  /** Invoke different callback based on this subclass. */
  public abstract <R> R match(Matcher<R> matcher);

  public ImmutableMap<String, SourcePath> toNameMap(
      BuildTarget buildTarget,
      SourcePathResolverAdapter pathResolver,
      String parameterName,
      Predicate<SourcePath> filter,
      Function<SourcePath, SourcePath> transform) {
    return match(
        new Matcher<ImmutableMap<String, SourcePath>>() {
          @Override
          public ImmutableMap<String, SourcePath> named(ImmutableMap<String, SourcePath> named) {
            ImmutableMap.Builder<String, SourcePath> sources = ImmutableMap.builder();
            for (Map.Entry<String, SourcePath> ent : named.entrySet()) {
              if (filter.test(ent.getValue())) {
                sources.put(ent.getKey(), transform.apply(ent.getValue()));
              }
            }
            return sources.build();
          }

          @Override
          public ImmutableMap<String, SourcePath> unnamed(ImmutableSet<SourcePath> unnamed) {
            ImmutableMap.Builder<String, SourcePath> sources = ImmutableMap.builder();
            pathResolver
                .getSourcePathNames(buildTarget, parameterName, unnamed, filter, transform)
                .forEach((name, path) -> sources.put(name, transform.apply(path)));
            return sources.build();
          }
        });
  }

  public ImmutableMap<String, SourcePath> toNameMap(
      BuildTarget buildTarget, SourcePathResolverAdapter pathResolver, String parameterName) {
    return toNameMap(buildTarget, pathResolver, parameterName, x -> true, x -> x);
  }

  public ImmutableList<SourcePath> getPaths() {
    return match(
        new Matcher<ImmutableList<SourcePath>>() {
          @Override
          public ImmutableList<SourcePath> named(ImmutableMap<String, SourcePath> named) {
            return named.values().asList();
          }

          @Override
          public ImmutableList<SourcePath> unnamed(ImmutableSet<SourcePath> unnamed) {
            return unnamed.asList();
          }
        });
  }

  @Override
  public Optional<SourceSet> translateTargets(
      CellNameResolver cellPathResolver, BaseName targetBaseName, TargetNodeTranslator translator) {
    return match(
        new Matcher<Optional<SourceSet>>() {
          @Override
          public Optional<SourceSet> named(ImmutableMap<String, SourcePath> named) {
            return translator
                .translate(cellPathResolver, targetBaseName, named)
                .map(SourceSet::ofNamedSources);
          }

          @Override
          public Optional<SourceSet> unnamed(ImmutableSet<SourcePath> unnamed) {
            return translator
                .translate(cellPathResolver, targetBaseName, unnamed)
                .map(SourceSet::ofUnnamedSources);
          }
        });
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
      element.match(
          new Matcher<Unit>() {
            @Override
            public Unit named(ImmutableMap<String, SourcePath> named) {
              throw new IllegalStateException("Expected unnamed source list");
            }

            @Override
            public Unit unnamed(ImmutableSet<SourcePath> unnamed) {
              unnamedSources.addAll(unnamed);
              return Unit.UNIT;
            }
          });
    }

    return ofUnnamedSources(ImmutableSet.copyOf(unnamedSources));
  }

  private static SourceSet concatNamed(Iterable<SourceSet> elements) {
    ImmutableMap.Builder<String, SourcePath> namedSources = ImmutableMap.builder();

    for (SourceSet element : elements) {
      element.match(
          new Matcher<Unit>() {
            @Override
            public Unit named(ImmutableMap<String, SourcePath> named) {
              namedSources.putAll(named);
              return Unit.UNIT;
            }

            @Override
            public Unit unnamed(ImmutableSet<SourcePath> unnamed) {
              throw new IllegalStateException("Expected named source list");
            }
          });
    }

    return ofNamedSources(namedSources.build());
  }
}
