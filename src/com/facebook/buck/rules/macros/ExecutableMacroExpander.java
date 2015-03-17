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
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;

/**
 * Resolves to the executable command for a build target referencing a {@link BinaryBuildRule}.
 */
public class ExecutableMacroExpander extends BuildTargetMacroExpander {

  public ExecutableMacroExpander(BuildTargetParser parser) {
    super(parser);
  }

  @Override
  public String expand(ProjectFilesystem filesystem, BuildRule rule) throws MacroException {
    if (!(rule instanceof BinaryBuildRule)) {
      throw new MacroException(
          String.format(
              "%s used in executable macro does not correspond to a binary rule",
              rule.getBuildTarget()));
    }
    BinaryBuildRule binary = (BinaryBuildRule) rule;
    return Joiner.on(' ').join(
        Iterables.transform(
            binary.getExecutableCommand(filesystem),
            Escaper.SHELL_ESCAPER));
  }

}
