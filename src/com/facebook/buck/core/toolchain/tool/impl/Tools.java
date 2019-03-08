/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.toolchain.tool.impl;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.google.common.base.Verify;

/** Utilities for using or creating {@link Tool} instances. */
public class Tools {

  /** Resolves a tool from as an executable from a build target output or as a single fixed file. */
  public static Tool resolveTool(SourcePath sourcePath, BuildRuleResolver resolver) {
    if (sourcePath instanceof PathSourcePath) {
      return new HashedFileTool(sourcePath);
    }
    Verify.verify(sourcePath instanceof BuildTargetSourcePath);
    BuildRule rule = resolver.getRule(((BuildTargetSourcePath) sourcePath).getTarget());
    Verify.verify(rule instanceof BinaryBuildRule);
    return ((BinaryBuildRule) rule).getExecutableCommand();
  }
}
