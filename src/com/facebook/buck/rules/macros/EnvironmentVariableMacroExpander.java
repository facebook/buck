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
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;

/**
 * Expands $(env XYZ) to use the appropriate shell expansion for the current platform. It does not
 * expand the value of the environment variable in place. Rather, the intention is for the variable
 * to be interpreted when a shell command is invoked.
 */
public class EnvironmentVariableMacroExpander implements MacroExpander {

  private final Platform platform;

  public EnvironmentVariableMacroExpander(Platform platform) {
    this.platform = platform;
  }

  @Override
  public String expand(
      BuildTarget target,
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      String input) throws MacroException {
    if (platform == Platform.WINDOWS) {
      if ("pwd".equalsIgnoreCase(input)) {
        input = "cd";
      }
      return "%" + input + "%";
    } else {
      return "${" + input + "}";
    }
  }

  @Override
  public ImmutableList<BuildTarget> extractTargets(
      BuildTarget target, String input) throws MacroException {
    return ImmutableList.of();
  }
}
