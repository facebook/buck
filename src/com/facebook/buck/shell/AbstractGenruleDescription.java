/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.MavenCoordinatesMacroExpander;
import com.facebook.buck.rules.macros.WorkerMacroExpander;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public abstract class AbstractGenruleDescription<T extends AbstractGenruleDescription.Arg>
    implements Description<T>, ImplicitDepsInferringDescription<T> {

  public static final MacroHandler MACRO_HANDLER = new MacroHandler(
      ImmutableMap.<String, MacroExpander>builder()
          .put("classpath", new ClasspathMacroExpander())
          .put("exe", new ExecutableMacroExpander())
          .put("worker", new WorkerMacroExpander())
          .put("location", new LocationMacroExpander())
          .put("maven_coords", new MavenCoordinatesMacroExpander())
          .build());

  protected <A extends T> BuildRule createBuildRule(
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args,
      Optional<com.facebook.buck.rules.args.Arg> cmd,
      Optional<com.facebook.buck.rules.args.Arg> bash,
      Optional<com.facebook.buck.rules.args.Arg> cmdExe) {
    return new Genrule(
        params,
        new SourcePathResolver(resolver),
        args.srcs.get(),
        cmd,
        bash,
        cmdExe,
        args.out);
  }

  protected MacroHandler getMacroHandlerForParseTimeDeps() {
    return MACRO_HANDLER;
  }

  @SuppressWarnings("unused")
  protected <A extends AbstractGenruleDescription.Arg> MacroHandler getMacroHandler(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    return MACRO_HANDLER;
  }

  @Override
  public <A extends T> BuildRule createBuildRule(
      final TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args)
      throws NoSuchBuildTargetException {
    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    Function<String, com.facebook.buck.rules.args.Arg> macroArgFunction =
        MacroArg.toMacroArgFunction(
            getMacroHandler(params, resolver, args),
            params.getBuildTarget(),
            params.getCellRoots(),
            resolver);
    final Optional<com.facebook.buck.rules.args.Arg> cmd = args.cmd.transform(macroArgFunction);
    final Optional<com.facebook.buck.rules.args.Arg> bash = args.bash.transform(macroArgFunction);
    final Optional<com.facebook.buck.rules.args.Arg> cmdExe =
        args.cmdExe.transform(macroArgFunction);
    return createBuildRule(
        params.copyWithExtraDeps(
            ImmutableSortedSet.<BuildRule>naturalOrder()
                .addAll(pathResolver.filterBuildRuleInputs(args.srcs.get()))
                // Attach any extra dependencies found from macro expansion.
                .addAll(
                    FluentIterable
                        .from(Optional.presentInstances(ImmutableList.of(cmd, bash, cmdExe)))
                        .transformAndConcat(
                            com.facebook.buck.rules.args.Arg.getDepsFunction(pathResolver)))
                .build()),
        resolver,
        args,
        cmd,
        bash,
        cmdExe);
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      T constructorArg) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    if (constructorArg.bash.isPresent()) {
      addDepsFromParam(buildTarget, cellRoots, constructorArg.bash.get(), targets);
    }
    if (constructorArg.cmd.isPresent()) {
      addDepsFromParam(buildTarget, cellRoots, constructorArg.cmd.get(), targets);
    }
    if (constructorArg.cmdExe.isPresent()) {
      addDepsFromParam(buildTarget, cellRoots, constructorArg.cmdExe.get(), targets);
    }
    return targets.build();
  }

  private void addDepsFromParam(
      BuildTarget target,
      CellPathResolver cellNames,
      String paramValue,
      ImmutableSet.Builder<BuildTarget> targets) {
    try {
      targets.addAll(
          getMacroHandlerForParseTimeDeps().extractParseTimeDeps(target, cellNames, paramValue));
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg implements HasTests {
    public String out;
    public Optional<String> bash;
    public Optional<String> cmd;
    public Optional<String> cmdExe;
    public Optional<ImmutableList<SourcePath>> srcs;

    @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> tests;

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests.get();
    }
  }

}
