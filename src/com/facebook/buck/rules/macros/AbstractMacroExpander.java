/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.rules.macros;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

public abstract class AbstractMacroExpander<T> implements MacroExpander {

  /**
   * @return parse the input arguments into a type that will be used on the interfaces below.
   */
  protected abstract T parse(
      BuildTarget target,
      CellPathResolver cellNames,
      ImmutableList<String> input)
      throws MacroException;

  @Override
  public final String expand(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      ImmutableList<String> input)
      throws MacroException {
    return expandFrom(target, cellNames, resolver, parse(target, cellNames, input));
  }

  public abstract String expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      T input)
      throws MacroException;

  @Override
  public final ImmutableList<BuildRule> extractBuildTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      ImmutableList<String> input) throws MacroException {
    return extractBuildTimeDepsFrom(target, cellNames, resolver, parse(target, cellNames, input));
  }

  @SuppressWarnings("unused")
  public ImmutableList<BuildRule> extractBuildTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      T input)
      throws MacroException {
    return ImmutableList.of();
  }

  @Override
  public final ImmutableList<BuildTarget> extractParseTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      ImmutableList<String> input)
      throws MacroException {
    return extractParseTimeDepsFrom(target, cellNames, parse(target, cellNames, input));
  }

  @SuppressWarnings("unused")
  public ImmutableList<BuildTarget> extractParseTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      T input) {
    return ImmutableList.of();
  }

  @Override
  public final Object extractRuleKeyAppendables(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      ImmutableList<String> input) throws MacroException {
    return extractRuleKeyAppendablesFrom(
        target,
        cellNames,
        resolver,
        parse(target, cellNames, input));
  }

  @SuppressWarnings("unused")
  public Object extractRuleKeyAppendablesFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      T input)
      throws MacroException {
    return Optional.absent();
  }

}
