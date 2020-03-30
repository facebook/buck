/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.shell;

import com.facebook.buck.android.toolchain.AndroidTools;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.SourceSet;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Optional;

/** Same as a Genrule, but marked as a binary. */
public class GenruleBinary extends Genrule implements BinaryBuildRule {
  protected GenruleBinary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      BuildRuleResolver resolver,
      SourceSet srcs,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe,
      Optional<String> type,
      Optional<String> out,
      Optional<ImmutableMap<String, ImmutableSet<String>>> outs,
      boolean isCacheable,
      Optional<String> environmentExpansionSeparator,
      Optional<AndroidTools> androidTools,
      boolean executeRemotely) {
    super(
        buildTarget,
        projectFilesystem,
        resolver,
        sandboxExecutionStrategy,
        srcs,
        cmd,
        bash,
        cmdExe,
        type,
        out,
        outs,
        false,
        isCacheable,
        environmentExpansionSeparator,
        androidTools,
        executeRemotely);
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    ImmutableSortedSet<SourcePath> outputs = getSourcePathToOutput(outputLabel);
    if (outputs.size() != 1) {
      throw new HumanReadableException(
          "Unexpectedly found %d outputs for %s[%s]",
          outputs.size(), getBuildTarget().getFullyQualifiedName(), outputLabel);
    }
    return new CommandTool.Builder()
        .addArg(SourcePathArg.of(Iterables.getOnlyElement(outputs)))
        .build();
  }
}
