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
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleFactoryParams;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroException;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

public class GenruleDescription
    implements Description<GenruleDescription.Arg>, ImplicitDepsInferringDescription {

  public static final BuildRuleType TYPE = new BuildRuleType("genrule");

  private final MacroHandler macroHandler;

  public GenruleDescription() {
    BuildTargetParser parser = new BuildTargetParser();
    this.macroHandler = new MacroHandler(
        ImmutableMap.<String, MacroExpander>of(
            "exe", new ExecutableMacroExpander(parser),
            "location", new LocationMacroExpander(parser)));
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
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    ImmutableList<SourcePath> srcs = args.srcs.get();
    ImmutableSortedSet<BuildRule> extraDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
        .addAll(params.getExtraDeps())
        .addAll(pathResolver.filterBuildRuleInputs(srcs))
        .build();

    Function<String, String> expandMacros =
        new Function<String, String>() {
          @Override
          public String apply(String input) {
            try {
              return macroHandler.expand(
                  params.getBuildTarget(),
                  resolver,
                  params.getProjectFilesystem(),
                  input);
            } catch (MacroException e) {
              throw new HumanReadableException("%s: %s", params.getBuildTarget(), e.getMessage());
            }
          }
        };

    return new Genrule(
        params.copyWithExtraDeps(extraDeps),
        pathResolver,
        srcs,
        args.cmd.transform(expandMacros),
        args.bash.transform(expandMacros),
        args.cmdExe.transform(expandMacros),
        args.out,
        params.getPathAbsolutifier());
  }

  @Override
  public Iterable<String> findDepsFromParams(BuildRuleFactoryParams params) {
    ImmutableSet.Builder<String> targets = ImmutableSet.builder();
    addDepsFromParam(params, "bash", targets);
    addDepsFromParam(params, "cmd", targets);
    addDepsFromParam(params, "cmdExe", targets);
    return targets.build();
  }

  private void addDepsFromParam(
      BuildRuleFactoryParams params,
      String paramName,
      ImmutableSet.Builder<String> targets) {
    Object rawCmd = params.getNullableRawAttribute(paramName);
    if (rawCmd == null) {
      return;
    }
    try {
      targets.addAll(
          Iterables.transform(
              macroHandler.extractTargets(
                  params.target,
                  (String) rawCmd),
              Functions.toStringFunction()));
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", params.target, e.getMessage());
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
