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
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import java.util.Map;
import java.util.Optional;

public class WorkerToolDescription implements Description<WorkerToolDescription.Arg>,
    ImplicitDepsInferringDescription<WorkerToolDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("worker_tool");

  private static final String CONFIG_SECTION = "worker";
  private static final String CONFIG_PERSISTENT_KEY = "persistent";

  public static final MacroHandler MACRO_HANDLER = new MacroHandler(
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
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public WorkerToolDescription.Arg createUnpopulatedConstructorArg() {
    return new WorkerToolDescription.Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    BuildRule rule = resolver.requireRule(args.exe);
    if (!(rule instanceof BinaryBuildRule)) {
      throw new HumanReadableException("The 'exe' argument of %s, %s, needs to correspond to a " +
          "binary rule, such as sh_binary().",
          params.getBuildTarget(),
          args.exe.getFullyQualifiedName());
    }

    String expandedStartupArgs;
    try {
      expandedStartupArgs = MACRO_HANDLER.expand(
          params.getBuildTarget(),
          params.getCellRoots(),
          resolver,
          args.args.orElse(""));
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", params.getBuildTarget(), e.getMessage());
    }

    ImmutableMap<String, String> expandedEnv = ImmutableMap.copyOf(
        FluentIterable.from(args.env.entrySet())
            .transform(input -> {
              try {
                return Maps.immutableEntry(
                    input.getKey(),
                    MACRO_HANDLER.expand(
                        params.getBuildTarget(),
                        params.getCellRoots(),
                        resolver,
                        input.getValue()));
              } catch (MacroException e) {
                throw new HumanReadableException(
                    e, "%s: %s", params.getBuildTarget(), e.getMessage());
              }
            }));

    int maxWorkers;
    if (args.maxWorkers.isPresent()) {
      // negative or zero: unlimited number of worker processes
      maxWorkers = args.maxWorkers.get() < 1 ? Integer.MAX_VALUE : args.maxWorkers.get();
    } else {
      // default is 1 worker process (for backwards compatibility)
      maxWorkers = 1;
    }

    return new DefaultWorkerTool(
        params,
        new SourcePathResolver(resolver),
        (BinaryBuildRule) rule,
        expandedStartupArgs,
        expandedEnv,
        maxWorkers,
        args.persistent.orElse(
            buckConfig.getBooleanValue(CONFIG_SECTION, CONFIG_PERSISTENT_KEY, false)));
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      WorkerToolDescription.Arg constructorArg) {
    ImmutableSortedSet.Builder<BuildTarget> targets = ImmutableSortedSet.naturalOrder();
    try {
      if (constructorArg.args.isPresent()) {
        targets.addAll(
            MACRO_HANDLER.extractParseTimeDeps(
                buildTarget, cellRoots, constructorArg.args.get()));
      }
      for (Map.Entry<String, String> env : constructorArg.env.entrySet()) {
        targets.addAll(
            MACRO_HANDLER.extractParseTimeDeps(
                buildTarget, cellRoots, env.getValue()));
      }
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
    }

    return targets.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableMap<String, String> env = ImmutableMap.of();
    public Optional<String> args;
    public BuildTarget exe;
    public Optional<Integer> maxWorkers;
    public Optional<Boolean> persistent;
  }
}
