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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.path.GenruleOutPath;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.sandbox.SandboxConfig;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.immutables.value.Value;

public class GenruleDescription extends AbstractGenruleDescription<GenruleDescriptionArg>
    implements VersionRoot<GenruleDescriptionArg> {

  public GenruleDescription(
      ToolchainProvider toolchainProvider,
      BuckConfig buckConfig,
      SandboxExecutionStrategy sandboxExecutionStrategy) {
    super(toolchainProvider, buckConfig, sandboxExecutionStrategy, false);
  }

  @Override
  public Class<GenruleDescriptionArg> getConstructorArgType() {
    return GenruleDescriptionArg.class;
  }

  @Override
  protected BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      GenruleDescriptionArg args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe) {
    if (!args.getExecutable().orElse(false)) {
      SandboxConfig sandboxConfig = buckConfig.getView(SandboxConfig.class);
      return new Genrule(
          buildTarget,
          projectFilesystem,
          graphBuilder,
          sandboxExecutionStrategy,
          args.getSrcs(),
          cmd,
          bash,
          cmdExe,
          args.getType(),
          args.getOut(),
          args.getOuts(),
          sandboxConfig.isSandboxEnabledForCurrentPlatform()
              && args.getEnableSandbox().orElse(sandboxConfig.isGenruleSandboxEnabled()),
          args.getCacheable().orElse(true),
          args.getEnvironmentExpansionSeparator(),
          getAndroidToolsOptional(args, buildTarget.getTargetConfiguration()),
          canExecuteRemotely(args));
    } else {
      return new GenruleBinary(
          buildTarget,
          projectFilesystem,
          sandboxExecutionStrategy,
          graphBuilder,
          args.getSrcs(),
          cmd,
          bash,
          cmdExe,
          args.getType(),
          args.getOut(),
          args.getOuts(),
          args.getCacheable().orElse(true),
          args.getEnvironmentExpansionSeparator(),
          getAndroidToolsOptional(args, buildTarget.getTargetConfiguration()),
          canExecuteRemotely(args));
    }
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @RuleArg
  interface AbstractGenruleDescriptionArg extends AbstractGenruleDescription.CommonArg {
    // Only one of out or outs should be used. out will be deprecated and removed once outs becomes
    // stable.
    Optional<String> getOut();

    Optional<ImmutableMap<String, ImmutableSet<String>>> getOuts();

    Optional<Boolean> getExecutable();

    @Value.Check
    default void check() {
      if (!(getOut().isPresent() ^ getOuts().isPresent())) {
        throw new HumanReadableException(
            "One and only one of 'out' or 'outs' must be present in genrule.");
      }
      // Lets check if out fields are valid GenruleOutPath
      if (getOut().isPresent()) {
        GenruleOutPath.of(getOut().get());
      } else if (getOuts().isPresent()) {
        getOuts().get().forEach((key, paths) -> paths.forEach(path -> GenruleOutPath.of(path)));
      }
    }
  }
}
