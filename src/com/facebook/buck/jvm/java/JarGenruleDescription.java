/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.google.common.collect.ImmutableCollection.Builder;
import java.util.Optional;
import java.util.function.Supplier;
import org.immutables.value.Value;

/**
 * Based on {@link com.facebook.buck.shell.GenruleDescription} except specialized to produce a jar.
 *
 * <p>The produced jar behaves similarly to a jar produced by java_binary, which means it can be
 * executed by {@code buck run} or using the {@code $(exe )} macro.
 */
public class JarGenruleDescription extends AbstractGenruleDescription<JarGenruleDescriptionArg>
    implements ImplicitDepsInferringDescription<JarGenruleDescriptionArg> {

  private final Supplier<JavaOptions> javaOptions;

  public JarGenruleDescription(
      ToolchainProvider toolchainProvider, SandboxExecutionStrategy sandboxExecutionStrategy) {
    super(toolchainProvider, sandboxExecutionStrategy, false);
    this.javaOptions = JavaOptionsProvider.getDefaultJavaOptions(toolchainProvider);
  }

  @Override
  public Class<JarGenruleDescriptionArg> getConstructorArgType() {
    return JarGenruleDescriptionArg.class;
  }

  @Override
  protected BuildRule createBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      JarGenruleDescriptionArg args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe) {

    return new JarGenrule(
        buildTarget,
        projectFilesystem,
        sandboxExecutionStrategy,
        graphBuilder,
        params,
        args.getSrcs(),
        cmd,
        bash,
        cmdExe,
        args.getType(),
        buildTarget.getShortName(),
        args.getCacheable().orElse(true),
        args.getEnvironmentExpansionSeparator(),
        javaOptions
            .get()
            .getJavaRuntimeLauncher(graphBuilder, buildTarget.getTargetConfiguration()));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      JarGenruleDescriptionArg constructorArg,
      Builder<BuildTarget> extraDepsBuilder,
      Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    javaOptions
        .get()
        .addParseTimeDeps(targetGraphOnlyDepsBuilder, buildTarget.getTargetConfiguration());
  }

  /** jar_genrule constructor arg. */
  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractJarGenruleDescriptionArg extends AbstractGenruleDescription.CommonArg {}
}
