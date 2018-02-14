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
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.HasSupplementaryOutputs;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableList;
import java.util.function.Consumer;

/** Handles '$(output ...)' macro which expands to the path of a named supplementary output. */
public class OutputMacroExpander extends AbstractMacroExpanderWithoutPrecomputedWork<OutputMacro> {
  @Override
  public Arg expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      OutputMacro input) {
    return new Arg() {

      @AddToRuleKey private final String name = input.getOutputName();

      @Override
      public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
        // Ideally, we'd support some way to query the `HasSupplementalOutputs` interface via a
        // `SourcePathResolver` so we wouldn't need to capture the `BuildRuleResolver`.
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
            // Note: As this is only used by the declaring rule, it can just use a relative path
            // here as it will necessarily be expanded in the same cell.
            consumer.accept(pathResolver.getRelativePath(output).toString());
            return;
          }
        }
        throw new HumanReadableException(
            String.format(
                "%s: rule does not have an output with the name: %s",
                target, input.getOutputName()));
      }
    };
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
