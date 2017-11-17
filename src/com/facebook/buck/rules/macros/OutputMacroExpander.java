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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.HasSupplementaryOutputs;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableList;

/** Handles '$(output ...)' macro which expands to the path of a named supplementary output. */
public class OutputMacroExpander extends AbstractMacroExpanderWithoutPrecomputedWork<OutputMacro> {

  @Override
  public String expandFrom(
      BuildTarget target, CellPathResolver cellNames, BuildRuleResolver resolver, OutputMacro input)
      throws MacroException {
    BuildRule rule =
        resolver
            .getRuleOptional(target)
            .orElseThrow(
                () ->
                    new AssertionError(
                        "Expected build target to resolve to a build rule: " + target));
    if (rule instanceof HasSupplementaryOutputs) {
      SourcePath output =
          ((HasSupplementaryOutputs) rule)
              .getSourcePathToSupplementaryOutput(input.getOutputName());
      if (output != null) {
        SourcePathResolver sourcePathResolver =
            DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
        // Using a relative path here since this path will necessarily be used in the same cell as
        // the rule, since this macro only refers to outputs of the current rule.
        return sourcePathResolver.getRelativePath(output).toString();
      }
    }
    throw new MacroException(
        String.format("Rule does not have an output with the name: %s", input.getOutputName()));
  }

  @Override
  public Class<OutputMacro> getInputClass() {
    return OutputMacro.class;
  }

  @Override
  protected OutputMacro parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> args)
      throws MacroException {
    if (args.size() != 1) {
      throw new MacroException(
          String.format("expected exactly one argument (found %d)", args.size()));
    }
    return OutputMacro.of(args.get(0));
  }
}
