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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;

/**
 * Expands $(env XYZ) to use the appropriate shell expansion for the current platform. It does not
 * expand the value of the environment variable in place. Rather, the intention is for the variable
 * to be interpreted when a shell command is invoked.
 */
public class EnvironmentVariableMacroExpander
    extends AbstractMacroExpanderWithoutPrecomputedWork<String> {

  private final Platform platform;

  public EnvironmentVariableMacroExpander(Platform platform) {
    this.platform = platform;
  }

  @Override
  public Class<String> getInputClass() {
    return String.class;
  }

  @Override
  protected String parse(
      BuildTarget target, CellPathResolver cellNames, ImmutableList<String> input)
      throws MacroException {
    if (input.size() != 1) {
      throw new MacroException(String.format("expected a single argument: %s", input));
    }
    return input.get(0);
  }

  @Override
  public StringArg expandFrom(
      BuildTarget target, CellPathResolver cellNames, ActionGraphBuilder graphBuilder, String var) {
    if (platform == Platform.WINDOWS) {
      if ("pwd".equalsIgnoreCase(var)) {
        var = "cd";
      }
      return StringArg.of("%" + var + "%");
    } else {
      return StringArg.of("${" + var + "}");
    }
  }
}
