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

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.tool.BinaryBuildRule;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.ProxyArg;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.ExecutableTargetMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroExpander;
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

  public static final ImmutableList<MacroExpander<? extends Macro, ?>> MACRO_EXPANDERS =
      ImmutableList.of(
          LocationMacroExpander.INSTANCE,
          new ClasspathMacroExpander(),
          new ExecutableMacroExpander<>(ExecutableMacro.class),
          new ExecutableMacroExpander<>(ExecutableTargetMacro.class));

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

      builder =
          new CommandTool.Builder(
              ((BinaryBuildRule) rule).getExecutableCommand(OutputLabel.defaultLabel()));
    } else {
      builder = new CommandTool.Builder();
    }

    builder.addInputs(
        params.getBuildDeps().stream()
            .map(BuildRule::getSourcePathToOutput)
            .filter(Objects::nonNull)
            .collect(ImmutableList.toImmutableList()));

    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            buildTarget,
            context.getCellPathResolver().getCellNameResolver(),
            graphBuilder,
            MACRO_EXPANDERS);

    if (args.getArgs().isLeft()) {
      builder.addArg(new SingleStringMacroArg(macrosConverter.convert(args.getArgs().getLeft())));
    } else {
      for (StringWithMacros arg : args.getArgs().getRight()) {
        builder.addArg(macrosConverter.convert(arg));
      }
    }
    for (Map.Entry<String, StringWithMacros> e : args.getEnv().entrySet()) {
      builder.addEnv(e.getKey(), macrosConverter.convert(e.getValue()));
    }

    boolean async = args.getSoloAsync().orElse(false);

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
      maxWorkers =
          (int)
              Math.max(
                  1, percent / 100.0 * buckConfig.getView(BuildBuckConfig.class).getNumThreads());
    } else {
      // negative or zero: unlimited number of worker processes
      maxWorkers =
          args.getMaxWorkers()
              .map(x -> x < 1 ? buckConfig.getView(BuildBuckConfig.class).getNumThreads() : x)
              .orElse(1);
    }

    CommandTool tool = builder.build();
    return new DefaultWorkerToolRule(
        buildTarget,
        context.getProjectFilesystem(),
        graphBuilder,
        tool,
        maxWorkers,
        async,
        args.getPersistent()
            .orElse(buckConfig.getBooleanValue(CONFIG_SECTION, CONFIG_PERSISTENT_KEY, false)));
  }

  /**
   * ProxyArg representing a single string retrieved from a macro invocation. Unlike a normal Arg,
   * this class splits its command-line arguments by space separators before appending them to a
   * command line.
   */
  private static class SingleStringMacroArg extends ProxyArg {
    public SingleStringMacroArg(Arg arg) {
      super(arg);
    }

    @Override
    public void appendToCommandLine(
        Consumer<String> consumer, SourcePathResolverAdapter pathResolver) {
      ImmutableList.Builder<String> subBuilder = ImmutableList.builder();
      super.appendToCommandLine(subBuilder::add, pathResolver);
      for (String arg : subBuilder.build()) {
        for (String splitArg : arg.split("\\s+")) {
          consumer.accept(splitArg);
        }
      }
    }
  }

  @RuleArg
  interface AbstractWorkerToolDescriptionArg extends BuildRuleArg {
    ImmutableMap<String, StringWithMacros> getEnv();

    @Value.Default
    default Either<StringWithMacros, ImmutableList<StringWithMacros>> getArgs() {
      return Either.ofRight(ImmutableList.of());
    }

    Optional<BuildTarget> getExe();

    Optional<Integer> getMaxWorkers();

    Optional<Integer> getMaxWorkersPerThreadPercent();

    Optional<Boolean> getPersistent();

    Optional<Boolean> getSoloAsync();
  }
}
