/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.HasMultipleOutputs;
import com.facebook.buck.core.rules.attr.HasSupplementaryOutputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

/** Expands to the path of a build rules output. */
public class AbstractLocationMacroExpander<T extends BaseLocationMacro>
    extends BuildTargetMacroExpander<T> {

  private final Class<T> clazz;

  public AbstractLocationMacroExpander(Class<T> clazz) {
    this.clazz = clazz;
  }

  @Override
  public Class<T> getInputClass() {
    return clazz;
  }

  @Override
  protected Arg expand(SourcePathResolverAdapter resolver, T macro, BuildRule rule)
      throws MacroException {
    BuildTargetWithOutputs targetWithOutputs = macro.getTargetWithOutputs();

    SourcePath output;
    if (!targetWithOutputs.getOutputLabel().isDefault()) {
      return resolveArgWithBracketSyntax(targetWithOutputs.getOutputLabel(), rule);
    }
    output = rule.getSourcePathToOutput();
    if (output == null) {
      throw new MacroException(
          String.format(
              "%s used in location macro does not produce output", rule.getBuildTarget()));
    }
    return SourcePathArg.of(output);
  }

  private Arg resolveArgWithBracketSyntax(OutputLabel outputLabel, BuildRule rule)
      throws MacroException {
    if (rule instanceof HasSupplementaryOutputs) {
      SourcePath output =
          ((HasSupplementaryOutputs) rule)
              .getSourcePathToSupplementaryOutput(OutputLabel.internals().getLabel(outputLabel));
      if (output == null) {
        throw new MacroException(
            String.format(
                "%s used in location macro does not produce supplementary output %s",
                rule.getBuildTarget(), outputLabel));
      }
      return SourcePathArg.of(output);
    } else if (rule instanceof HasMultipleOutputs) {
      ImmutableSortedSet<SourcePath> outputs =
          ((HasMultipleOutputs) rule).getSourcePathToOutput(outputLabel);
      if (outputs == null || outputs.isEmpty()) {
        throw new MacroException(
            String.format(
                "%s used in location macro does not produce outputs with label [%s]",
                rule.getBuildTarget(), outputLabel));
      }
      try {
        return SourcePathArg.of(Iterables.getOnlyElement(outputs));
      } catch (IllegalArgumentException e) {
        throw new MacroException(
            String.format(
                "%s[%s] produces multiple outputs but location macro accepts only one output",
                rule.getBuildTarget(), outputLabel));
      }
    }
    throw new MacroException(
        String.format(
            "%s used in location macro does not produce supplementary output or output groups",
            rule.getBuildTarget()));
  }
}
