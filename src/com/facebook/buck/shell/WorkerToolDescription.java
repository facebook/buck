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

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class WorkerToolDescription
    implements Description<WorkerToolDescriptionArg>,
        ImplicitDepsInferringDescription<WorkerToolDescription.AbstractWorkerToolDescriptionArg> {

  private static final String CONFIG_SECTION = "worker";
  private static final String CONFIG_PERSISTENT_KEY = "persistent";

  public static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>builder()
              .put("location", new LocationMacroExpander())
              .put("classpath", new ClasspathMacroExpander())
              .put("exe", new ExecutableMacroExpander())
              .build());

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
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      WorkerToolDescriptionArg args)
      throws NoSuchBuildTargetException {

    BuildRule rule = resolver.requireRule(args.getExe());
    if (!(rule instanceof BinaryBuildRule)) {
      throw new HumanReadableException(
          "The 'exe' argument of %s, %s, needs to correspond to a "
              + "binary rule, such as sh_binary().",
          params.getBuildTarget(), args.getExe().getFullyQualifiedName());
    }

    Function<String, com.facebook.buck.rules.args.Arg> toArg =
        MacroArg.toMacroArgFunction(MACRO_HANDLER, params.getBuildTarget(), cellRoots, resolver);
    final ImmutableList<com.facebook.buck.rules.args.Arg> workerToolArgs =
        args.getStartupArgs().stream().map(toArg::apply).collect(MoreCollectors.toImmutableList());

    ImmutableMap<String, com.facebook.buck.rules.args.Arg> unexpandedEnv =
        args.getEnv()
            .entrySet()
            .stream()
            .collect(
                MoreCollectors.toImmutableMap(Map.Entry::getKey, e -> toArg.apply(e.getValue())));

    // negative or zero: unlimited number of worker processes
    int maxWorkers = args.getMaxWorkers() < 1 ? Integer.MAX_VALUE : args.getMaxWorkers();

    return new DefaultWorkerTool(
        params,
        (BinaryBuildRule) rule,
        workerToolArgs,
        unexpandedEnv,
        maxWorkers,
        args.getPersistent()
            .orElse(buckConfig.getBooleanValue(CONFIG_SECTION, CONFIG_PERSISTENT_KEY, false)));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractWorkerToolDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    try {
      for (String arg : constructorArg.getStartupArgs()) {
        MACRO_HANDLER.extractParseTimeDeps(
            buildTarget, cellRoots, arg, extraDepsBuilder, targetGraphOnlyDepsBuilder);
      }
      for (Map.Entry<String, String> env : constructorArg.getEnv().entrySet()) {
        MACRO_HANDLER.extractParseTimeDeps(
            buildTarget, cellRoots, env.getValue(), extraDepsBuilder, targetGraphOnlyDepsBuilder);
      }
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractWorkerToolDescriptionArg extends CommonDescriptionArg {
    @Value.Derived
    default ImmutableList<String> getStartupArgs() {
      if (getArgs().isLeft()) {
        return ImmutableList.of(getArgs().getLeft());
      } else {
        return getArgs().getRight();
      }
    }

    ImmutableMap<String, String> getEnv();

    @Value.Default
    default Either<String, ImmutableList<String>> getArgs() {
      return Either.ofRight(ImmutableList.of());
    }

    BuildTarget getExe();

    @Value.Default
    default int getMaxWorkers() {
      return 1;
    }

    Optional<Boolean> getPersistent();
  }
}
