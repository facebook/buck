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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Set;

/**
 * A group of ordered sources, stored as either a {@link Set} of unnamed {@link SourcePath}s or a
 * {@link Map} of names to {@link SourcePath}s. Commonly used to represent the input <code>srcs
 * </code> parameter of rules where source "names" may be important (e.g. to control layout of C++
 * headers).
 */
public abstract class UnconfiguredSourceSet {

  private UnconfiguredSourceSet() {}

  public static final UnconfiguredSourceSet EMPTY =
      UnconfiguredSourceSet.ofUnnamedSources(ImmutableSet.of());

  /** Named. */
  @BuckStyleValue
  abstract static class UnconfiguredSourceSetNamed extends UnconfiguredSourceSet {
    public abstract ImmutableMap<String, UnconfiguredSourcePath> getNamedSources();

    @Override
    public boolean isEmpty() {
      return getNamedSources().isEmpty();
    }

    @Override
    public <R, E extends Exception> R match(Matcher<R, E> matcher) throws E {
      return matcher.named(getNamedSources());
    }
  }

  /** Unnamed. */
  @BuckStyleValue
  abstract static class UnconfiguredSourceSetUnnamed extends UnconfiguredSourceSet {
    public abstract ImmutableSet<UnconfiguredSourcePath> getUnnamedSources();

    @Override
    public boolean isEmpty() {
      return getUnnamedSources().isEmpty();
    }

    @Override
    public <R, E extends Exception> R match(Matcher<R, E> matcher) throws E {
      return matcher.unnamed(getUnnamedSources());
    }
  }

  public static UnconfiguredSourceSet ofUnnamedSources(
      ImmutableSet<UnconfiguredSourcePath> unnamedSources) {
    return ImmutableUnconfiguredSourceSetUnnamed.ofImpl(unnamedSources);
  }

  public static UnconfiguredSourceSet ofNamedSources(
      ImmutableMap<String, UnconfiguredSourcePath> namedSources) {
    return ImmutableUnconfiguredSourceSetNamed.ofImpl(namedSources);
  }

  public abstract boolean isEmpty();

  /** Callback for {@link #match(Matcher)}. */
  public interface Matcher<R, E extends Exception> {
    R named(ImmutableMap<String, UnconfiguredSourcePath> named) throws E;

    R unnamed(ImmutableSet<UnconfiguredSourcePath> unnamed) throws E;
  }

  /** Invoke different callback based on this subclass. */
  public abstract <R, E extends Exception> R match(Matcher<R, E> matcher) throws E;
}
