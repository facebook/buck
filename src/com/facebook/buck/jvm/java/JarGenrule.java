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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.SourceSet;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.shell.BaseGenrule;
import com.facebook.buck.shell.GenruleBuildable;
import java.util.Optional;

/**
 * A rule similar to {@link com.facebook.buck.shell.Genrule} except specialized to produce a jar.
 *
 * <p>The produced jar behaves similarly to a jar produced by java_binary, which means it can be
 * executed by {@code buck run} or using the {@code $(exe )} macro.
 */
public final class JarGenrule extends BaseGenrule<GenruleBuildable> implements BinaryBuildRule {
  // Only used by getExecutableCommand, does not need to contribute to the rule key.
  private final Tool javaRuntimeLauncher;

  protected JarGenrule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      BuildRuleResolver resolver,
      SourceSet srcs,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe,
      Optional<String> type,
      String out,
      boolean isCacheable,
      Optional<String> environmentExpansionSeparator,
      Tool javaRuntimeLauncher) {
    super(
        buildTarget,
        projectFilesystem,
        resolver,
        new GenruleBuildable(
            buildTarget,
            projectFilesystem,
            sandboxExecutionStrategy,
            srcs,
            cmd,
            bash,
            cmdExe,
            type,
            Optional.of(out + ".jar"),
            Optional.empty(),
            false,
            isCacheable,
            environmentExpansionSeparator.orElse(" "),
            Optional.empty(),
            Optional.empty(),
            false));
    this.javaRuntimeLauncher = javaRuntimeLauncher;
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    return new CommandTool.Builder(javaRuntimeLauncher)
        .addArg("-jar")
        .addArg(SourcePathArg.of(getSourcePathToOutput()))
        .build();
  }
}
