/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;

public interface MacroExpander {

  /** Expand the input given for the this macro to an Arg. */
  Arg expand(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      ImmutableList<String> input,
      Object precomputedWork)
      throws MacroException;

  /** @return something that should be added to the rule key of the rule that expands this macro. */
  Object extractRuleKeyAppendables(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      ImmutableList<String> input,
      Object precomputedWork)
      throws MacroException;

  /** @return cache-able work that can be re-used for the various extract/expand functions */
  Object precomputeWork(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      ImmutableList<String> input)
      throws MacroException;
}
