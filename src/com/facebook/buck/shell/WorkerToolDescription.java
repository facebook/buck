/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.rules.args.ProxyArg;
import com.facebook.buck.rules.macros.AbstractMacroExpander;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.immutables.value.Value;

public class WorkerToolDescription implements DescriptionWithTargetGraph<WorkerToolDescriptionArg> {

  private static final String CONFIG_SECTION = "worker";
  private static final String CONFIG_PERSISTENT_KEY = "persistent";

  public static final ImmutableList<AbstractMacroExpander<? extends Macro, ?>> MACRO_EXPANDERS =
      ImmutableList.of(
          new LocationMacroExpander(), new ClasspathMacroExpander(), new ExecutableMacroExpander());

  private final BuckConfig buckConfig;

  public WorkerToolDescription(BuckConfig buckConfig) {
    this.buckConfig = buckConfig;
  }

  @Override
  public Class<WorkerToolDescriptionArg> getConstructorArgType() {
    return WorkerToolDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      WorkerToolDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();

    CommandTool.Builder builder;
    if (args.getExe().isPresent()) {
      BuildRule rule = graphBuilder.requireRule(args.getExe().get());
      if (!(rule instanceof BinaryBuildRule)) {
        throw new HumanReadableException(
            "The 'exe' argument of %s, %s, needs to correspond to a "
                + "binary rule, such as sh_binary().",
            buildTarget, args.getExe().get().getFullyQualifiedName());
      }

      builder = new CommandTool.Builder(((BinaryBuildRule) rule).getExecutableCommand());
    } else {
      builder = new CommandTool.Builder();
    }

    builder.addInputs(
        params
            .getBuildDeps()
            .stream()
            .map(BuildRule::getSourcePathToOutput)
            .filter(Objects::nonNull)
            .collect(ImmutableList.toImmutableList()));

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.builder()
            .setBuildTarget(buildTarget)
            .setCellPathResolver(context.getCellPathResolver())
            .setExpanders(MACRO_EXPANDERS)
            .build();

    if (args.getArgs().isLeft()) {
      builder.addArg(
          new ProxyArg(macrosConverter.convert(args.getArgs().getLeft(), graphBuilder)) {
            @Override
            public void appendToCommandLine(
                Consumer<String> consumer, SourcePathResolver pathResolver) {
              ImmutableList.Builder<String> subBuilder = ImmutableList.builder();
              super.appendToCommandLine(subBuilder::add, pathResolver);
              for (String arg : subBuilder.build()) {
                for (String splitArg : arg.split("\\s+")) {
                  consumer.accept(splitArg);
                }
              }
            }
          });
    } else {
      for (StringWithMacros arg : args.getArgs().getRight()) {
        builder.addArg(macrosConverter.convert(arg, graphBuilder));
      }
    }
    for (Map.Entry<String, StringWithMacros> e : args.getEnv().entrySet()) {
      builder.addEnv(e.getKey(), macrosConverter.convert(e.getValue(), graphBuilder));
    }

    Preconditions.checkArgument(
        !(args.getMaxWorkers().isPresent() && args.getMaxWorkersPerThreadPercent().isPresent()),
        "max_workers and max_workers_per_thread_percent must not be used together.");

    int maxWorkers;
    if (args.getMaxWorkersPerThreadPercent().isPresent()) {
      int percent = args.getMaxWorkersPerThreadPercent().get();
      Preconditions.checkArgument(
          percent > 0, "max_workers_per_thread_percent must be greater than 0.");
      Preconditions.checkArgument(
          percent <= 100, "max_workers_per_thread_percent must not be greater than 100.");
      maxWorkers = (int) Math.max(1, percent / 100.0 * buckConfig.getNumThreads());
    } else {
      // negative or zero: unlimited number of worker processes
      maxWorkers = args.getMaxWorkers().map(x -> x < 1 ? buckConfig.getNumThreads() : x).orElse(1);
    }

    CommandTool tool = builder.build();
    return new DefaultWorkerTool(
        buildTarget,
        context.getProjectFilesystem(),
        new SourcePathRuleFinder(graphBuilder),
        tool,
        maxWorkers,
        args.getPersistent()
            .orElse(buckConfig.getBooleanValue(CONFIG_SECTION, CONFIG_PERSISTENT_KEY, false)));
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractWorkerToolDescriptionArg extends CommonDescriptionArg {
    ImmutableMap<String, StringWithMacros> getEnv();

    @Value.Default
    default Either<StringWithMacros, ImmutableList<StringWithMacros>> getArgs() {
      return Either.ofRight(ImmutableList.of());
    }

    Optional<BuildTarget> getExe();

    Optional<Integer> getMaxWorkers();

    Optional<Integer> getMaxWorkersPerThreadPercent();

    Optional<Boolean> getPersistent();
  }
}
