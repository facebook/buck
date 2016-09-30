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
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

/**
 * Abstract expander which resolves using a references to another {@link BuildRule}.
 * <p>
 * Matches either a relative or fully-qualified build target wrapped in <tt>$()</tt>, unless the
 * <code>$</code> is preceded by a backslash.
 */
public abstract class BuildTargetMacroExpander extends AbstractMacroExpander<BuildTarget> {

  protected abstract String expand(
      SourcePathResolver resolver,
      BuildRule rule)
      throws MacroException;

  protected BuildRule resolve(BuildRuleResolver resolver, BuildTarget input)
      throws MacroException {
    Optional<BuildRule> rule = resolver.getRuleOptional(input);
    if (!rule.isPresent()) {
      throw new MacroException(String.format("no rule %s", input));
    }
    return rule.get();
  }

  @Override
  protected BuildTarget parse(
      BuildTarget target,
      CellPathResolver cellNames,
      ImmutableList<String> input)
      throws MacroException {
    if (input.size() != 1) {
      throw new MacroException(
          String.format(
              "expected a single argument: %s",
              input));
    }
    try {
      return BuildTargetParser.INSTANCE.parse(
          input.get(0),
          BuildTargetPatternParser.forBaseName(target.getBaseName()),
          cellNames);
    } catch (BuildTargetParseException e) {
      throw new MacroException(e.getMessage(), e);
    }
  }

  @Override
  public String expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      BuildTarget input)
      throws MacroException {
    return expand(new SourcePathResolver(resolver), resolve(resolver, input));
  }

  protected ImmutableList<BuildRule> extractBuildTimeDeps(
      @SuppressWarnings("unused") BuildRuleResolver resolver,
      BuildRule rule)
      throws MacroException {
    return ImmutableList.of(rule);
  }

  @Override
  public ImmutableList<BuildRule> extractBuildTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      BuildTarget input)
      throws MacroException {
    return extractBuildTimeDeps(resolver, resolve(resolver, input));
  }

  @Override
  public ImmutableList<BuildTarget> extractParseTimeDepsFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildTarget input)
      throws MacroException {
    return ImmutableList.of(input);
  }

  @Override
  public Object extractRuleKeyAppendablesFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      BuildTarget input)
      throws MacroException {
    return new BuildTargetSourcePath(resolve(resolver, input).getBuildTarget());
  }

}
