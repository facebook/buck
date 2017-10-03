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

package com.facebook.buck.haskell;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BinaryWrapperRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.ForwardingBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableSet;

public class HaskellBinary extends BinaryWrapperRule {

  private final ImmutableSet<BuildRule> deps;
  private final Tool binary;
  private final SourcePath output;

  public HaskellBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      ImmutableSet<BuildRule> deps,
      Tool binary,
      SourcePath output) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.deps = deps;
    this.binary = binary;
    this.output = output;
  }

  @Override
  public Tool getExecutableCommand() {
    return binary;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  public ImmutableSet<BuildRule> getBinaryDeps() {
    return deps;
  }
}
