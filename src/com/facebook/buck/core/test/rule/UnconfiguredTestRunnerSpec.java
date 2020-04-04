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

package com.facebook.buck.core.test.rule;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.macros.UnconfiguredStringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/** Unconfigured graph version of {@link TestRunnerSpec}. */
public abstract class UnconfiguredTestRunnerSpec {

  private UnconfiguredTestRunnerSpec() {}

  /** Map. */
  @BuckStyleValue
  abstract static class UnconfiguredTestRunnerSpecMap extends UnconfiguredTestRunnerSpec {
    public abstract ImmutableMap<UnconfiguredStringWithMacros, UnconfiguredTestRunnerSpec> getMap();

    @Override
    public <R, E extends Exception> R match(Matcher<R, E> matcher) throws E {
      return matcher.map(getMap());
    }
  }

  /** Iterable. */
  @BuckStyleValue
  abstract static class UnconfiguredTestRunnerSpecList extends UnconfiguredTestRunnerSpec {
    public abstract ImmutableList<UnconfiguredTestRunnerSpec> getList();

    @Override
    public <R, E extends Exception> R match(Matcher<R, E> matcher) throws E {
      return matcher.list(getList());
    }
  }

  /** String with macros. */
  @BuckStyleValue
  abstract static class UnconfiguredTestRunnerSpecStringWithMacros
      extends UnconfiguredTestRunnerSpec {
    public abstract UnconfiguredStringWithMacros getStringWithMacros();

    @Override
    public <R, E extends Exception> R match(Matcher<R, E> matcher) throws E {
      return matcher.stringWithMacros(getStringWithMacros());
    }
  }

  /** Number. */
  @BuckStyleValue
  abstract static class UnconfiguredTestRunnerSpecNumber extends UnconfiguredTestRunnerSpec {
    public abstract Number getNumber();

    @Override
    public <R, E extends Exception> R match(Matcher<R, E> matcher) throws E {
      return matcher.number(getNumber());
    }
  }

  /** Boolean. */
  @BuckStyleValue
  abstract static class UnconfiguredTestRunnerSpecBoolean extends UnconfiguredTestRunnerSpec {
    public abstract boolean getBoolean();

    @Override
    public <R, E extends Exception> R match(Matcher<R, E> matcher) throws E {
      return matcher.bool(getBoolean());
    }

    private static class Holder {
      private static final UnconfiguredTestRunnerSpecBoolean TRUE =
          ImmutableUnconfiguredTestRunnerSpecBoolean.ofImpl(true);
      private static final UnconfiguredTestRunnerSpecBoolean FALSE =
          ImmutableUnconfiguredTestRunnerSpecBoolean.ofImpl(false);
    }
  }

  /** Constructor. */
  public static UnconfiguredTestRunnerSpec ofMap(
      ImmutableMap<UnconfiguredStringWithMacros, UnconfiguredTestRunnerSpec> map) {
    return ImmutableUnconfiguredTestRunnerSpecMap.ofImpl(map);
  }

  /** Constructor. */
  public static UnconfiguredTestRunnerSpec ofList(
      ImmutableList<UnconfiguredTestRunnerSpec> iterable) {
    return ImmutableUnconfiguredTestRunnerSpecList.ofImpl(iterable);
  }

  /** Constructor. */
  public static UnconfiguredTestRunnerSpec ofStringWithMacros(
      UnconfiguredStringWithMacros stringWithMacros) {
    return ImmutableUnconfiguredTestRunnerSpecStringWithMacros.ofImpl(stringWithMacros);
  }

  /** Constructor. */
  public static UnconfiguredTestRunnerSpec ofNumber(Number number) {
    return ImmutableUnconfiguredTestRunnerSpecNumber.ofImpl(number);
  }

  /** Constructor. */
  public static UnconfiguredTestRunnerSpec ofBoolean(boolean b) {
    return b
        ? UnconfiguredTestRunnerSpecBoolean.Holder.TRUE
        : UnconfiguredTestRunnerSpecBoolean.Holder.FALSE;
  }

  /** Callback for {@link #match(Matcher)}. */
  public interface Matcher<R, E extends Exception> {
    /** This is map. */
    R map(ImmutableMap<UnconfiguredStringWithMacros, UnconfiguredTestRunnerSpec> map) throws E;

    /** This is list. */
    R list(ImmutableList<UnconfiguredTestRunnerSpec> list) throws E;

    /** This is string with macros. */
    R stringWithMacros(UnconfiguredStringWithMacros stringWithMacros) throws E;

    /** This is number. */
    R number(Number number) throws E;

    /** This is boolean. */
    R bool(boolean b) throws E;
  }

  /** Invoke a different callback based on this subclass. */
  public abstract <R, E extends Exception> R match(Matcher<R, E> matcher) throws E;
}
