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
package com.facebook.buck.core.toolchain.toolprovider.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;

/** Utilities for using or creating {@link ToolProvider} instances. */
public class ToolProviders {
  /**
   * Creates a {@link ToolProvider} from a {@link SourcePath}. A path reference will just become a
   * {@link HashedFileTool} referencing that path, and a build target reference will require that it
   * references a {@link BinaryBuildRule} and use that's executable command.
   */
  public static ToolProvider getToolProvider(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return new ConstantToolProvider(new HashedFileTool(sourcePath));
    }

    Preconditions.checkArgument(
        sourcePath instanceof BuildTargetSourcePath,
        "Expected BuildTargetSourcePath, got %s (%s).",
        sourcePath.getClass().getName(),
        sourcePath);
    BuildTarget target = ((BuildTargetSourcePath) sourcePath).getTarget();
    return new ToolProvider() {
      @Override
      public Tool resolve(BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
        BuildRule rule = resolver.getRule(target);
        Verify.verify(rule instanceof BinaryBuildRule);
        return ((BinaryBuildRule) rule).getExecutableCommand();
      }

      @Override
      public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration targetConfiguration) {
        return ImmutableList.of(target);
      }
    };
  }
}
