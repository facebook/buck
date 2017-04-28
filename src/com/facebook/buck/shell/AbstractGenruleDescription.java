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

import com.facebook.buck.io.ProjectFilesystem;
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
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.coercer.Hint;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.MavenCoordinatesMacroExpander;
import com.facebook.buck.rules.macros.QueryOutputsMacroExpander;
import com.facebook.buck.rules.macros.QueryTargetsMacroExpander;
import com.facebook.buck.rules.macros.WorkerMacroExpander;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.Optionals;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

public abstract class AbstractGenruleDescription<T extends AbstractGenruleDescription.Arg>
    implements Description<T>, ImplicitDepsInferringDescription<T> {

  public static final MacroHandler PARSE_TIME_MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>builder()
              .put("classpath", new ClasspathMacroExpander())
              .put("exe", new ExecutableMacroExpander())
              .put("worker", new WorkerMacroExpander())
              .put("location", new LocationMacroExpander())
              .put("maven_coords", new MavenCoordinatesMacroExpander())
              .put("query_targets", new QueryTargetsMacroExpander(Optional.empty()))
              .put("query_outputs", new QueryOutputsMacroExpander(Optional.empty()))
              .build());

  protected BuildRule createBuildRule(
      final BuildRuleParams params,
      @SuppressWarnings("unused") final BuildRuleResolver resolver,
      T args,
      Optional<com.facebook.buck.rules.args.Arg> cmd,
      Optional<com.facebook.buck.rules.args.Arg> bash,
      Optional<com.facebook.buck.rules.args.Arg> cmdExe) {
    return new Genrule(params, args.srcs, cmd, bash, cmdExe, args.type, args.out);
  }

  protected MacroHandler getMacroHandlerForParseTimeDeps() {
    return PARSE_TIME_MACRO_HANDLER;
  }

  protected MacroHandler getMacroHandler(
      @SuppressWarnings("unused") BuildTarget buildTarget,
      @SuppressWarnings("unused") ProjectFilesystem filesystem,
      @SuppressWarnings("unused") BuildRuleResolver resolver,
      TargetGraph targetGraph,
      @SuppressWarnings("unused") Arg args) {
    return new MacroHandler(
        ImmutableMap.<String, MacroExpander>builder()
            .put("classpath", new ClasspathMacroExpander())
            .put("exe", new ExecutableMacroExpander())
            .put("worker", new WorkerMacroExpander())
            .put("location", new LocationMacroExpander())
            .put("maven_coords", new MavenCoordinatesMacroExpander())
            .put("query_targets", new QueryTargetsMacroExpander(Optional.of(targetGraph)))
            .put("query_outputs", new QueryOutputsMacroExpander(Optional.of(targetGraph)))
            .build());
  }

  @Override
  public BuildRule createBuildRule(
      final TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final T args)
      throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    java.util.function.Function<String, com.facebook.buck.rules.args.Arg> macroArgFunction =
        MacroArg.toMacroArgFunction(
                getMacroHandler(
                    params.getBuildTarget(),
                    params.getProjectFilesystem(),
                    resolver,
                    targetGraph,
                    args),
                params.getBuildTarget(),
                cellRoots,
                resolver)
            ::apply;
    final Optional<com.facebook.buck.rules.args.Arg> cmd = args.cmd.map(macroArgFunction);
    final Optional<com.facebook.buck.rules.args.Arg> bash = args.bash.map(macroArgFunction);
    final Optional<com.facebook.buck.rules.args.Arg> cmdExe = args.cmdExe.map(macroArgFunction);
    return createBuildRule(
        params.copyReplacingExtraDeps(
            Suppliers.ofInstance(
                Stream.concat(
                        ruleFinder.filterBuildRuleInputs(args.srcs).stream(),
                        Stream.of(cmd, bash, cmdExe)
                            .flatMap(Optionals::toStream)
                            .flatMap(input -> input.getDeps(ruleFinder).stream()))
                    .collect(
                        MoreCollectors.toImmutableSortedSet(
                            Comparator.<BuildRule>naturalOrder())))),
        resolver,
        args,
        cmd,
        bash,
        cmdExe);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      T constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (constructorArg.bash.isPresent()) {
      addDepsFromParam(
          buildTarget,
          cellRoots,
          constructorArg.bash.get(),
          extraDepsBuilder,
          targetGraphOnlyDepsBuilder);
    }
    if (constructorArg.cmd.isPresent()) {
      addDepsFromParam(
          buildTarget,
          cellRoots,
          constructorArg.cmd.get(),
          extraDepsBuilder,
          targetGraphOnlyDepsBuilder);
    }
    if (constructorArg.cmdExe.isPresent()) {
      addDepsFromParam(
          buildTarget,
          cellRoots,
          constructorArg.cmdExe.get(),
          extraDepsBuilder,
          targetGraphOnlyDepsBuilder);
    }
  }

  private void addDepsFromParam(
      BuildTarget target,
      CellPathResolver cellNames,
      String paramValue,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    try {
      getMacroHandlerForParseTimeDeps()
          .extractParseTimeDeps(
              target, cellNames, paramValue, extraDepsBuilder, targetGraphOnlyDepsBuilder);
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
    public Optional<String> type;
    public ImmutableList<SourcePath> srcs = ImmutableList.of();

    @Hint(isDep = false)
    public ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests;
    }
  }
}
