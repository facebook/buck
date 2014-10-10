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
import com.facebook.buck.parser.BuildTargetParseException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.ParseContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * Abstract expander which resolves using a references to another {@link BuildRule}.
 * <p>
 * Matches either a relative or fully-qualified build target wrapped in <tt>$()</tt>, unless the
 * <code>$</code> is preceded by a backslash.
 */
public abstract class BuildTargetMacroExpander implements MacroExpander {

  protected final BuildTargetParser parser;

  public BuildTargetMacroExpander(BuildTargetParser parser) {
    this.parser = Preconditions.checkNotNull(parser);
  }

  protected abstract String expand(ProjectFilesystem filesystem, BuildRule rule)
      throws MacroException;

  @Override
  public String expand(
      BuildTarget target,
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      String input) throws MacroException {

    BuildTarget other;
    try {
      other = parser.parse(input, ParseContext.forBaseName(target.getBaseName()));
    } catch (BuildTargetParseException e) {
      throw new MacroException(e.getMessage(), e);
    }
    Optional<BuildRule> rule = resolver.getRuleOptional(other);
    if (!rule.isPresent()) {
      throw new MacroException(String.format("no rule %s", other));
    }
    return expand(filesystem, rule.get());
  }

  @Override
  public ImmutableList<BuildTarget> extractTargets(
      BuildTarget target,
      String input) {
    return ImmutableList.of(parser.parse(input, ParseContext.forBaseName(target.getBaseName())));
  }

}
