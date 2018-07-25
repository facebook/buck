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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * Abstract expander which resolves using a references to another {@link BuildRule}.
 *
 * <p>Matches either a relative or fully-qualified build target wrapped in <tt>$()</tt>, unless the
 * <code>$</code> is preceded by a backslash.
 */
public abstract class BuildTargetMacroExpander<M extends BuildTargetMacro>
    extends AbstractMacroExpanderWithoutPrecomputedWork<M> {

  protected abstract Arg expand(SourcePathResolver resolver, M macro, BuildRule rule)
      throws MacroException;

  protected BuildRule resolve(ActionGraphBuilder graphBuilder, M input) throws MacroException {
    Optional<BuildRule> rule = graphBuilder.getRuleOptional(input.getTarget());
    if (!rule.isPresent()) {
      throw new MacroException(String.format("no rule %s", input.getTarget()));
    }
    return rule.get();
  }

  BuildTarget parseBuildTarget(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException {
    if (input.size() != 1) {
      throw new MacroException(String.format("expected a single argument: %s", input));
    }
    try {
      return BuildTargetParser.INSTANCE.parse(
          input.get(0), BuildTargetPatternParser.forBaseName(target.getBaseName()), cellNames);
    } catch (BuildTargetParseException e) {
      throw new MacroException(e.getMessage(), e);
    }
  }

  @Override
  public Arg expandFrom(
      BuildTarget target, CellPathResolver cellNames, ActionGraphBuilder graphBuilder, M input)
      throws MacroException {
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    return expand(pathResolver, input, resolve(graphBuilder, input));
  }

  @Override
  public void extractParseTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      M input,
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    buildDepsBuilder.add(input.getTarget());
  }
}
