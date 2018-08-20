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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.WriteToFileArg;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

public interface MacroExpander {

  /** Expand the input given for the this macro to an Arg. */
  Arg expand(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      ImmutableList<String> input,
      Object precomputedWork)
      throws MacroException;

  /**
   * Expand the input given for the this macro to some string, which is intended to be written to a
   * file.
   */
  default Arg expandForFile(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      ImmutableList<String> input,
      Object precomputedWork)
      throws MacroException {
    // "prefix" should give a stable name, so that the same delegate with the same input can output
    // the same file. We won't optimise for this case, since it's actually unlikely to happen within
    // a single run, but using a random name would cause 'buck-out' to expand in an uncontrolled
    // manner.
    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putString(getClass().getName(), UTF_8);
    input.forEach(s -> hasher.putString(s, UTF_8));
    return makeExpandToFileArg(
        target,
        hasher.hash().toString(),
        expand(target, cellNames, graphBuilder, input, precomputedWork));
  }

  default Arg makeExpandToFileArg(BuildTarget target, String prefix, Arg delegate) {
    return new WriteToFileArg(target, prefix, delegate);
  }

  /**
   * @return names of additional {@link TargetNode}s which must be followed by the parser to support
   *     this macro when constructing the target graph. To be used by {@link
   *     ImplicitDepsInferringDescription#findDepsForTargetFromConstructorArgs} to extract implicit
   *     dependencies hidden behind macros.
   */
  void extractParseTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      ImmutableList<String> input,
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder)
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
