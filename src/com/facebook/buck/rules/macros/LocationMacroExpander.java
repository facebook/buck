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
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.HasSupplementaryOutputs;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/** Expands to the path of a build rules output. */
public class LocationMacroExpander extends BuildTargetMacroExpander<LocationMacro> {

  @Override
  public Class<LocationMacro> getInputClass() {
    return LocationMacro.class;
  }

  @Override
  protected LocationMacro parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException {
    if (input.size() != 1 || input.get(0).isEmpty()) {
      throw new MacroException(String.format("expected a single argument: %s", input));
    }
    LocationMacro.SplitResult parts = LocationMacro.splitSupplementaryOutputPart(input.get(0));
    return LocationMacro.of(
        parseBuildTarget(target, cellNames, ImmutableList.of(parts.target)),
        parts.supplementaryOutput);
  }

  @Override
  protected Arg expand(SourcePathResolver resolver, LocationMacro macro, BuildRule rule)
      throws MacroException {
    Optional<String> supplementaryOutputIdentifier = macro.getSupplementaryOutputIdentifier();

    SourcePath output;
    if (supplementaryOutputIdentifier.isPresent()) {
      if (rule instanceof HasSupplementaryOutputs) {
        output =
            ((HasSupplementaryOutputs) rule)
                .getSourcePathToSupplementaryOutput(supplementaryOutputIdentifier.get());
        if (output == null) {
          throw new MacroException(
              String.format(
                  "%s used in location macro does not produce supplementary output %s",
                  rule.getBuildTarget(), supplementaryOutputIdentifier.get()));
        }
      } else {
        throw new MacroException(
            String.format(
                "%s used in location macro does not produce supplementary output",
                rule.getBuildTarget()));
      }
    } else {
      output = rule.getSourcePathToOutput();
      if (output == null) {
        throw new MacroException(
            String.format(
                "%s used in location macro does not produce output", rule.getBuildTarget()));
      }
    }

    return SourcePathArg.of(output);
  }
}
