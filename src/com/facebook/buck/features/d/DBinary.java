/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.d;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import java.util.stream.Stream;

/** BinaryBuildRule implementation for D binaries. */
public class DBinary extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements BinaryBuildRule, HasRuntimeDeps {

  private final Tool executable;
  private final SourcePath output;

  public DBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Tool executable,
      SourcePath output) {
    super(buildTarget, projectFilesystem, params);
    this.executable = executable;
    this.output = output;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return ImmutableList.of();
  }

  @Override
  public Tool getExecutableCommand() {
    return executable;
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ForwardingBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    // Return the actual executable as a runtime dependency.
    // Without this, the file is not written when we get a cache hit.
    return BuildableSupport.getDeps(executable, ruleFinder).map(BuildRule::getBuildTarget);
  }
}
