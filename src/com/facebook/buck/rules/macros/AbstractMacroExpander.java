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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

public abstract class AbstractMacroExpander<T, P> implements MacroExpander {

  /** @return the class for the parsed macro input type. */
  public abstract Class<T> getInputClass();

  /** @return the class for the precomputed work type */
  public abstract Class<P> getPrecomputedWorkClass();

  /** @return parse the input arguments into a type that will be used on the interfaces below. */
  protected abstract T parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException;

  @Override
  public final void extractParseTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      ImmutableList<String> input,
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder)
      throws MacroException {
    extractParseTimeDepsFrom(
        target,
        cellNames,
        parse(target, cellNames, input),
        buildDepsBuilder,
        targetGraphOnlyDepsBuilder);
  }

  @SuppressWarnings("unused")
  public void extractParseTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      T input,
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {}

  /** @return the precomputed work that can be re-used between invocations */
  @Override
  public final P precomputeWork(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      ImmutableList<String> input)
      throws MacroException {
    return precomputeWorkFrom(target, cellNames, graphBuilder, parse(target, cellNames, input));
  }

  /** @return the precomputed work that can be re-used between invocations */
  public abstract P precomputeWorkFrom(
      BuildTarget target, CellPathResolver cellNames, ActionGraphBuilder graphBuilder, T input)
      throws MacroException;

  @Override
  public final Arg expand(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      ImmutableList<String> input,
      Object precomputedWork)
      throws MacroException {
    return expandFrom(
        target,
        cellNames,
        graphBuilder,
        parse(target, cellNames, input),
        getPrecomputedWorkClass().cast(precomputedWork));
  }

  public abstract Arg expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      T input,
      P precomputedWork)
      throws MacroException;

  @Override
  public final Object extractRuleKeyAppendables(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      ImmutableList<String> input,
      Object precomputedWork)
      throws MacroException {
    return expand(target, cellNames, graphBuilder, input, precomputedWork);
  }
}
