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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.attr.HasSupplementaryOutputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import java.util.function.Consumer;

/** Handles '$(output ...)' macro which expands to the path of a named supplementary output. */
public class OutputMacroExpander extends AbstractMacroExpanderWithoutPrecomputedWork<OutputMacro> {
  @Override
  public Arg expandFrom(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      OutputMacro input) {
    return new OutputArg(input, graphBuilder, target);
  }

  @Override
  public Class<OutputMacro> getInputClass() {
    return OutputMacro.class;
  }

  private static class OutputArg implements Arg {
    @AddToRuleKey private final String name;

    private final BuildRuleResolver resolver;
    private final BuildTarget target;

    public OutputArg(OutputMacro input, BuildRuleResolver resolver, BuildTarget target) {
      this.resolver = resolver;
      this.target = target;
      this.name = input.getOutputName();
    }

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
            ((HasSupplementaryOutputs) rule).getSourcePathToSupplementaryOutput(name);
        if (output != null) {
          // Note: As this is only used by the declaring rule, it can just use a relative path
          // here as it will necessarily be expanded in the same cell.
          consumer.accept(pathResolver.getRelativePath(output).toString());
          return;
        }
      }
      throw new HumanReadableException(
          String.format("%s: rule does not have an output with the name: %s", target, name));
    }
  }
}
