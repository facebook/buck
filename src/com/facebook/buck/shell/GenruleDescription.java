/*
 * Copyright 2014-present Facebook, Inc.
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


import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroException;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class GenruleDescription
    implements
    Description<GenruleDescription.Arg>,
    ImplicitDepsInferringDescription<GenruleDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("genrule");

  private final MacroHandler macroHandler;

  public GenruleDescription() {
    this.macroHandler = new MacroHandler(
        ImmutableMap.<String, MacroExpander>of(
            "classpath", new ClasspathMacroExpander(),
            "exe", new ExecutableMacroExpander(),
            "location", new LocationMacroExpander()));
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> Genrule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableList<SourcePath> srcs = args.srcs.get();
    ImmutableSortedSet<BuildRule> extraDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(params.getExtraDeps())
        .addAll(pathResolver.filterBuildRuleInputs(srcs))
        .build();
    return new Genrule(
        params
            .copyWithExtraDeps(Suppliers.ofInstance(extraDeps))
            // Attach any extra dependencies found from macro expansion.
            .appendExtraDeps(findExtraDepsFromArgs(params.getBuildTarget(), resolver, args)),
        pathResolver,
        srcs,
        macroHandler.getExpander(
            params.getBuildTarget(),
            resolver,
            params.getProjectFilesystem()),
        args.cmd,
        args.bash,
        args.cmdExe,
        args.out,
        params.getPathAbsolutifier());
  }

  private ImmutableList<BuildRule> findExtraDepsFromArgs(
      BuildTarget target,
      BuildRuleResolver resolver,
      Arg arg) {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
    try {
      for (String val :
          Optional.presentInstances(ImmutableList.of(arg.bash, arg.cmd, arg.cmdExe))) {
        deps.addAll(macroHandler.extractAdditionalBuildTimeDeps(target, resolver, val));
      }
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
    return deps.build();
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    if (constructorArg.bash.isPresent()) {
      addDepsFromParam(buildTarget, constructorArg.bash.get(), targets);
    }
    if (constructorArg.cmd.isPresent()) {
      addDepsFromParam(buildTarget, constructorArg.cmd.get(), targets);
    }
    if (constructorArg.cmdExe.isPresent()) {
      addDepsFromParam(buildTarget, constructorArg.cmdExe.get(), targets);
    }
    return targets.build();
  }

  private void addDepsFromParam(
      BuildTarget target,
      String paramValue,
      ImmutableSet.Builder<BuildTarget> targets) {
    try {
      targets.addAll(macroHandler.extractParseTimeDeps(target, paramValue));
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
  }

  @SuppressFieldNotInitialized
  public class Arg {
    public String out;
    public Optional<String> bash;
    public Optional<String> cmd;
    public Optional<String> cmdExe;
    public Optional<ImmutableList<SourcePath>> srcs;

    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
