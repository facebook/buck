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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;

import java.util.Optional;

/**
 * Abstract expander which resolves using a references to another {@link BuildRule}.
 * <p>
 * Matches either a relative or fully-qualified build target wrapped in <tt>$()</tt>, unless the
 * <code>$</code> is preceded by a backslash.
 */
public abstract class BuildTargetsMacroExpander
    extends AbstractMacroExpander<ImmutableList<BuildTarget>> {

  protected abstract String expand(
      BuildRuleResolver resolver,
      ImmutableList<BuildRule> rule)
      throws MacroException;

  @Override
  protected ImmutableList<BuildTarget> parse(
      BuildTarget target,
      CellPathResolver cellNames,
      ImmutableList<String> input) {
    ImmutableList.Builder<BuildTarget> targets = ImmutableList.builder();
    for (String str : input) {
      targets.add(
          BuildTargetParser.INSTANCE.parse(
              str,
              BuildTargetPatternParser.forBaseName(target.getBaseName()),
              cellNames));
    }
    return targets.build();
  }

  protected ImmutableList<BuildRule> resolve(
      BuildRuleResolver resolver,
      ImmutableList<BuildTarget> input)
      throws MacroException {
    ImmutableList.Builder<BuildRule> rules = ImmutableList.builder();
    for (BuildTarget ruleTarget : input) {
      Optional<BuildRule> rule = resolver.getRuleOptional(ruleTarget);
      if (!rule.isPresent()) {
        throw new MacroException(String.format("no rule %s", ruleTarget));
      }
      rules.add(rule.get());
    }
    return rules.build();
  }

  @Override
  public String expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      ImmutableList<BuildTarget> input)
      throws MacroException {
    return expand(resolver, resolve(resolver, input));
  }

  protected ImmutableList<BuildRule> extractBuildTimeDeps(
      @SuppressWarnings("unused") BuildRuleResolver resolver,
      ImmutableList<BuildRule> rules)
      throws MacroException {
    return rules;
  }

  @Override
  public ImmutableList<BuildRule> extractBuildTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      ImmutableList<BuildTarget> input)
      throws MacroException {
    return extractBuildTimeDeps(resolver, resolve(resolver, input));
  }

  @Override
  public ImmutableList<BuildTarget> extractParseTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      ImmutableList<BuildTarget> input) {
    return input;
  }

  @Override
  public Object extractRuleKeyAppendablesFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      ImmutableList<BuildTarget> input)
      throws MacroException {
    return FluentIterable.from(input)
        .transform(
            (Function<BuildTarget, SourcePath>) BuildTargetSourcePath::new)
        .toSortedSet(Ordering.natural());
  }

}
