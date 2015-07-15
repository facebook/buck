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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

/**
 * Abstract expander which resolves using a references to another {@link BuildRule}.
 * <p>
 * Matches either a relative or fully-qualified build target wrapped in <tt>$()</tt>, unless the
 * <code>$</code> is preceded by a backslash.
 */
public abstract class BuildTargetMacroExpander implements MacroExpander {

  protected abstract String expand(
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      BuildRule rule)
      throws MacroException;

  protected BuildRule resolve(
      BuildTarget target,
      BuildRuleResolver resolver,
      String input)
      throws MacroException {

    BuildTarget other;
    try {
      other = BuildTargetParser.INSTANCE.parse(
          input,
          BuildTargetPatternParser.forBaseName(target.getBaseName()));
    } catch (BuildTargetParseException e) {
      throw new MacroException(e.getMessage(), e);
    }
    Optional<BuildRule> rule = resolver.getRuleOptional(other);
    if (!rule.isPresent()) {
      throw new MacroException(String.format("no rule %s", other));
    }
    return rule.get();
  }

  @Override
  public String expand(
      BuildTarget target,
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      String input)
      throws MacroException {
    return expand(new SourcePathResolver(resolver), filesystem, resolve(target, resolver, input));
  }

  protected ImmutableList<BuildRule> extractAdditionalBuildTimeDeps(
      @SuppressWarnings("unused") BuildRuleResolver resolver,
      BuildRule rule)
      throws MacroException {
    return ImmutableList.of(rule);
  }

  @Override
  public ImmutableList<BuildRule> extractAdditionalBuildTimeDeps(
      BuildTarget target,
      BuildRuleResolver resolver,
      String input)
      throws MacroException {
    return extractAdditionalBuildTimeDeps(resolver, resolve(target, resolver, input));
  }

  @Override
  public ImmutableList<BuildTarget> extractParseTimeDeps(
      BuildTarget target,
      String input) {
    return ImmutableList.of(
        BuildTargetParser.INSTANCE.parse(
            input,
            BuildTargetPatternParser.forBaseName(target.getBaseName())));
  }

}
