/*
 * Copyright 2016-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BinaryBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroException;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;

public class WorkerToolDescription implements Description<WorkerToolDescription.Arg>,
    ImplicitDepsInferringDescription<WorkerToolDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("worker_tool");

  public static final MacroHandler MACRO_HANDLER = new MacroHandler(
      ImmutableMap.<String, MacroExpander>builder()
        .put("location", new LocationMacroExpander())
        .build());

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public WorkerToolDescription.Arg createUnpopulatedConstructorArg() {
    return new WorkerToolDescription.Arg();
  }

  @Override
  public <A extends Arg> WorkerTool createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {

    BuildRule rule = resolver.requireRule(args.exe);
    if (!(rule instanceof BinaryBuildRule)) {
      throw new HumanReadableException("The 'exe' argument of %s, %s, needs to correspond to a " +
          "binary rule, such as sh_binary().",
          params.getBuildTarget(),
          args.exe.getFullyQualifiedName());
    }

    String startupArgs;
    try {
      startupArgs = MACRO_HANDLER.expand(
          params.getBuildTarget(),
          params.getCellRoots(),
          resolver,
          args.args.or(""));
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", params.getBuildTarget(), e.getMessage());
    }

    return new WorkerTool(
        params,
        new SourcePathResolver(resolver),
        (BinaryBuildRule) rule,
        startupArgs);
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Function<Optional<String>, Path> cellRoots,
      WorkerToolDescription.Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    if (constructorArg.args.isPresent()) {
      try {
        targets.addAll(
            MACRO_HANDLER.extractParseTimeDeps(
                buildTarget, cellRoots, constructorArg.args.get()));
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }
    return targets.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<String> args;
    public BuildTarget exe;
  }
}
