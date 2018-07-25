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

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.ToolArg;
import com.google.common.collect.ImmutableList;

/** Resolves to the executable command for a build target referencing a {@link BinaryBuildRule}. */
public class ExecutableMacroExpander extends BuildTargetMacroExpander<ExecutableMacro> {
  @Override
  public Class<ExecutableMacro> getInputClass() {
    return ExecutableMacro.class;
  }

  protected Tool getTool(BuildRule rule) throws MacroException {
    if (!(rule instanceof BinaryBuildRule)) {
      throw new MacroException(
          String.format(
              "%s used in executable macro does not correspond to a binary rule",
              rule.getBuildTarget()));
    }
    return ((BinaryBuildRule) rule).getExecutableCommand();
  }

  @Override
  protected ExecutableMacro parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException {
    return ExecutableMacro.of(parseBuildTarget(target, cellNames, input));
  }

  @Override
  protected Arg expand(SourcePathResolver resolver, ExecutableMacro ignored, BuildRule rule)
      throws MacroException {
    return ToolArg.of(getTool(rule));
  }
}
