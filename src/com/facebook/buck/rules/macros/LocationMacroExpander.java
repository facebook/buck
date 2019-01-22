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

import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.HasSupplementaryOutputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import java.util.Optional;

/** Expands to the path of a build rules output. */
public class LocationMacroExpander extends BuildTargetMacroExpander<LocationMacro> {

  @Override
  public Class<LocationMacro> getInputClass() {
    return LocationMacro.class;
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
