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
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.util.types.Unit;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.facebook.buck.versions.TargetTranslatable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A group of sources, stored as either a {@link SortedSet} of unnamed {@link SourcePath}s or a
 * {@link java.util.SortedMap} of names to {@link SourcePath}s. Commonly used to represent the input
 * <code>srcs</code> parameter of rules where source "names" may be important (e.g. to control
 * layout of C++ headers).
 */
public abstract class SourceSortedSet implements TargetTranslatable<SourceSortedSet> {

  private SourceSortedSet() {}

  /** Named sources. */
  @BuckStyleValue
  abstract static class SourceSortedSetNamed extends SourceSortedSet {
    public abstract ImmutableSortedMap<String, SourcePath> getNamedSources();

    @Override
    public Type getType() {
      return Type.NAMED;
    }

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.named(getNamedSources());
    }

    @Override
    public boolean isEmpty() {
      return getNamedSources().isEmpty();
    }
  }

  /** Unnamed sources. */
  @BuckStyleValue
  abstract static class SourceSortedSetUnnamed extends SourceSortedSet {
    public abstract ImmutableSortedSet<SourcePath> getUnnamedSources();

    @Override
    public Type getType() {
      return Type.UNNAMED;
    }

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.unnamed(getUnnamedSources());
    }

    @Override
    public boolean isEmpty() {
      return getUnnamedSources().isEmpty();
    }
  }

  public static final SourceSortedSet EMPTY =
      SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of());

  public enum Type {
    UNNAMED,
    NAMED,
  }

  public abstract Type getType();

  /** Callbacks for {@link #match(Matcher)}. */
  public interface Matcher<R> {
    R named(ImmutableSortedMap<String, SourcePath> named);

    R unnamed(ImmutableSortedSet<SourcePath> unnamed);
  }

  /** Invoke a different callback depending on this subclass. */
  public abstract <R> R match(Matcher<R> matcher);

  public static SourceSortedSet ofUnnamedSources(ImmutableSortedSet<SourcePath> unnamedSources) {
    return ImmutableSourceSortedSetUnnamed.ofImpl(unnamedSources);
  }

  public static SourceSortedSet ofNamedSources(
      ImmutableSortedMap<String, SourcePath> namedSources) {
    return ImmutableSourceSortedSetNamed.ofImpl(namedSources);
  }

  public abstract boolean isEmpty();

  public ImmutableMap<String, SourcePath> toNameMap(
      BuildTarget buildTarget,
      SourcePathResolverAdapter pathResolver,
      String parameterName,
      Predicate<SourcePath> filter,
      Function<SourcePath, SourcePath> transform) {

    return match(
        new Matcher<ImmutableMap<String, SourcePath>>() {
          @Override
          public ImmutableMap<String, SourcePath> named(
              ImmutableSortedMap<String, SourcePath> named) {
            ImmutableMap.Builder<String, SourcePath> sources = ImmutableMap.builder();
            for (Map.Entry<String, SourcePath> ent : named.entrySet()) {
              if (filter.test(ent.getValue())) {
                sources.put(ent.getKey(), transform.apply(ent.getValue()));
              }
            }
            return sources.build();
          }

          @Override
          public ImmutableMap<String, SourcePath> unnamed(ImmutableSortedSet<SourcePath> unnamed) {
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
          public ImmutableList<SourcePath> named(ImmutableSortedMap<String, SourcePath> named) {
            return ImmutableList.copyOf(named.values());
          }

          @Override
          public ImmutableList<SourcePath> unnamed(ImmutableSortedSet<SourcePath> unnamed) {
            return ImmutableList.copyOf(unnamed);
          }
        });
  }

  @Override
  public Optional<SourceSortedSet> translateTargets(
      CellNameResolver cellPathResolver, BaseName targetBaseName, TargetNodeTranslator translator) {

    return match(
        new Matcher<Optional<SourceSortedSet>>() {
          @Override
          public Optional<SourceSortedSet> named(ImmutableSortedMap<String, SourcePath> named) {
            return translator
                .translate(cellPathResolver, targetBaseName, named)
                .map(SourceSortedSet::ofNamedSources);
          }

          @Override
          public Optional<SourceSortedSet> unnamed(ImmutableSortedSet<SourcePath> unnamed) {
            return translator
                .translate(cellPathResolver, targetBaseName, unnamed)
                .map(SourceSortedSet::ofUnnamedSources);
          }
        });
  }

  /** Concatenates elements of the given lists into a single list. */
  public static SourceSortedSet concat(Iterable<SourceSortedSet> elements) {
    Type type = findType(elements);

    if (type == Type.UNNAMED) {
      return concatUnnamed(elements);
    } else {
      return concatNamed(elements);
    }
  }

  private static Type findType(Iterable<SourceSortedSet> elements) {
    if (!elements.iterator().hasNext()) {
      return Type.UNNAMED;
    }
    return elements.iterator().next().getType();
  }

  private static SourceSortedSet concatUnnamed(Iterable<SourceSortedSet> elements) {
    SortedSet<SourcePath> unnamedSources = new TreeSet<>();

    for (SourceSortedSet element : elements) {
      element.match(
          new Matcher<Unit>() {
            @Override
            public Unit named(ImmutableSortedMap<String, SourcePath> named) {
              throw new IllegalStateException("Expected unnamed source list");
            }

            @Override
            public Unit unnamed(ImmutableSortedSet<SourcePath> unnamed) {
              unnamedSources.addAll(unnamed);
              return Unit.UNIT;
            }
          });
    }

    return ofUnnamedSources(ImmutableSortedSet.copyOf(unnamedSources));
  }

  private static SourceSortedSet concatNamed(Iterable<SourceSortedSet> elements) {
    ImmutableSortedMap.Builder<String, SourcePath> namedSources = ImmutableSortedMap.naturalOrder();

    for (SourceSortedSet element : elements) {
      element.match(
          new Matcher<Unit>() {
            @Override
            public Unit named(ImmutableSortedMap<String, SourcePath> named) {
              namedSources.putAll(named);
              return Unit.UNIT;
            }

            @Override
            public Unit unnamed(ImmutableSortedSet<SourcePath> unnamed) {
              throw new IllegalStateException("Expected named source list");
            }
          });
    }

    return ofNamedSources(namedSources.build());
  }
}
