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
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * Freeform JSON to be used for the test protocol. The JSON is composed of {@link
 * com.facebook.buck.rules.macros.StringWithMacros}.
 *
 * <p>The JSON map keys must be {@link StringWithMacros}, and not other complicated collection
 * structures.
 */
public abstract class TestRunnerSpec {

  private TestRunnerSpec() {}

  /** Map. */
  @BuckStyleValue
  abstract static class TestRunnerSpecMap extends TestRunnerSpec {
    public abstract ImmutableMap<StringWithMacros, TestRunnerSpec> getMap();

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.map(getMap());
    }
  }

  /** Iterable. */
  @BuckStyleValue
  abstract static class TestRunnerSpecList extends TestRunnerSpec {
    public abstract ImmutableList<TestRunnerSpec> getList();

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.list(getList());
    }
  }

  /** String with macros. */
  @BuckStyleValue
  abstract static class TestRunnerSpecStringWithMacros extends TestRunnerSpec {
    public abstract StringWithMacros getStringWithMacros();

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.stringWithMacros(getStringWithMacros());
    }
  }

  /** Number. */
  @BuckStyleValue
  abstract static class TestRunnerSpecNumber extends TestRunnerSpec {
    public abstract Number getNumber();

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.number(getNumber());
    }
  }

  /** Boolean. */
  @BuckStyleValue
  abstract static class TestRunnerSpecBoolean extends TestRunnerSpec {
    public abstract boolean getBoolean();

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.bool(getBoolean());
    }

    private static class Holder {
      private static final TestRunnerSpecBoolean TRUE = ImmutableTestRunnerSpecBoolean.ofImpl(true);
      private static final TestRunnerSpecBoolean FALSE =
          ImmutableTestRunnerSpecBoolean.ofImpl(false);
    }
  }

  /** Constructor. */
  public static TestRunnerSpec ofMap(ImmutableMap<StringWithMacros, TestRunnerSpec> map) {
    return ImmutableTestRunnerSpecMap.ofImpl(map);
  }

  /** Constructor. */
  public static TestRunnerSpec ofList(ImmutableList<TestRunnerSpec> iterable) {
    return ImmutableTestRunnerSpecList.ofImpl(iterable);
  }

  /** Constructor. */
  public static TestRunnerSpec ofStringWithMacros(StringWithMacros stringWithMacros) {
    return ImmutableTestRunnerSpecStringWithMacros.ofImpl(stringWithMacros);
  }

  /** Constructor. */
  public static TestRunnerSpec ofNumber(Number number) {
    return ImmutableTestRunnerSpecNumber.ofImpl(number);
  }

  /** Constructor. */
  public static TestRunnerSpec ofBoolean(boolean b) {
    return b ? TestRunnerSpecBoolean.Holder.TRUE : TestRunnerSpecBoolean.Holder.FALSE;
  }

  /** Callback for {@link #match(Matcher)}. */
  public interface Matcher<R> {
    /** This is map. */
    R map(ImmutableMap<StringWithMacros, TestRunnerSpec> map);

    /** This is list. */
    R list(ImmutableList<TestRunnerSpec> list);

    /** This is string with macros. */
    R stringWithMacros(StringWithMacros stringWithMacros);

    /** This is number. */
    R number(Number number);

    /** This is boolean. */
    R bool(boolean b);
  }

  /** Invoke a different callback based on this subclass. */
  public abstract <R> R match(Matcher<R> matcher);
}
