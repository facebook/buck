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

import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;

/**
 * A group of sources, stored as either a {@link SortedSet} of unnamed {@link SourcePath}s or a
 * {@link java.util.SortedMap} of names to {@link SourcePath}s. Commonly used to represent the input
 * <code>srcs</code> parameter of rules where source "names" may be important (e.g. to control
 * layout of C++ headers).
 */
public abstract class UnconfiguredSourceSortedSet {

  private UnconfiguredSourceSortedSet() {}

  /** Named sources. */
  @BuckStyleValue
  abstract static class UnconfiguredSourceSortedSetNamed extends UnconfiguredSourceSortedSet {
    public abstract ImmutableSortedMap<String, UnconfiguredSourcePath> getNamedSources();

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.named(getNamedSources());
    }

    @Override
    public <R, E extends Exception> R match(MatcherWithException<R, E> matcher) throws E {
      return matcher.named(getNamedSources());
    }
  }

  /** Unnamed sources. */
  @BuckStyleValue
  abstract static class UnconfiguredSourceSortedSetUnnamed extends UnconfiguredSourceSortedSet {
    public abstract ImmutableSortedSet<UnconfiguredSourcePath> getUnnamedSources();

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.unnamed(getUnnamedSources());
    }

    @Override
    public <R, E extends Exception> R match(MatcherWithException<R, E> matcher) throws E {
      return matcher.unnamed(getUnnamedSources());
    }
  }

  public static final UnconfiguredSourceSortedSet EMPTY =
      UnconfiguredSourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of());

  /** Callbacks for {@link #match(Matcher)}. */
  public interface Matcher<R> {
    R named(ImmutableSortedMap<String, UnconfiguredSourcePath> named);

    R unnamed(ImmutableSortedSet<UnconfiguredSourcePath> unnamed);
  }

  /** Callbacks for {@link #match(Matcher)}. */
  public interface MatcherWithException<R, E extends Exception> {
    R named(ImmutableSortedMap<String, UnconfiguredSourcePath> named) throws E;

    R unnamed(ImmutableSortedSet<UnconfiguredSourcePath> unnamed) throws E;
  }

  /** Invoke a different callback depending on this subclass. */
  public abstract <R> R match(Matcher<R> matcher);

  /** Invoke a different callback depending on this subclass. */
  public abstract <R, E extends Exception> R match(MatcherWithException<R, E> matcher) throws E;

  public static UnconfiguredSourceSortedSet ofUnnamedSources(
      ImmutableSortedSet<UnconfiguredSourcePath> unnamedSources) {
    return ImmutableUnconfiguredSourceSortedSetUnnamed.ofImpl(unnamedSources);
  }

  public static UnconfiguredSourceSortedSet ofNamedSources(
      ImmutableSortedMap<String, UnconfiguredSourcePath> namedSources) {
    return ImmutableUnconfiguredSourceSortedSetNamed.ofImpl(namedSources);
  }
}
