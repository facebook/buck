/*
 * Copyright 2017-present Facebook, Inc.
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
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

/** A macro expander with no inputs or precomputed work */
public abstract class SimpleMacroExpander<M extends Macro>
    extends AbstractMacroExpanderWithoutPrecomputedWork<M> {

  @Override
  @Nullable
  protected final M parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input) {
    return null;
  }

  @Override
  public final Arg expandFrom(
      BuildTarget target, CellPathResolver cellNames, BuildRuleResolver resolver, M input) {
    return expandFrom(target, cellNames, resolver);
  }

  public abstract Arg expandFrom(
      BuildTarget target, CellPathResolver cellNames, BuildRuleResolver resolver);

  @Override
  public final void extractParseTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      M input,
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    extractParseTimeDepsFrom(target, cellNames, buildDepsBuilder, targetGraphOnlyDepsBuilder);
  }

  @SuppressWarnings("unused")
  public void extractParseTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {}
}
