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

import com.facebook.buck.android.AndroidLegacyToolchain;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasTests;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.rules.macros.MavenCoordinatesMacroExpander;
import com.facebook.buck.rules.macros.QueryOutputsMacroExpander;
import com.facebook.buck.rules.macros.QueryPathsMacroExpander;
import com.facebook.buck.rules.macros.QueryTargetsAndOutputsMacroExpander;
import com.facebook.buck.rules.macros.QueryTargetsMacroExpander;
import com.facebook.buck.rules.macros.WorkerMacroExpander;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Optionals;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public abstract class AbstractGenruleDescription<T extends AbstractGenruleDescription.CommonArg>
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
              .put("query_paths", new QueryPathsMacroExpander(Optional.empty()))
              .put(
                  "query_targets_and_outputs",
                  new QueryTargetsAndOutputsMacroExpander(Optional.empty()))
              .build());

  protected final ToolchainProvider toolchainProvider;
  protected final SandboxExecutionStrategy sandboxExecutionStrategy;
  protected final boolean enableSandbox;

  protected AbstractGenruleDescription(
      ToolchainProvider toolchainProvider,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      boolean enableSandbox) {
    this.toolchainProvider = toolchainProvider;
    this.sandboxExecutionStrategy = sandboxExecutionStrategy;
    this.enableSandbox = enableSandbox;
  }

  protected BuildRule createBuildRule(
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      T args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe) {
    AndroidLegacyToolchain androidLegacyToolchain =
        toolchainProvider.getByName(
            AndroidLegacyToolchain.DEFAULT_NAME, AndroidLegacyToolchain.class);
    return new Genrule(
        buildTarget,
        projectFilesystem,
        androidLegacyToolchain,
        resolver,
        params,
        sandboxExecutionStrategy,
        args.getSrcs(),
        cmd,
        bash,
        cmdExe,
        args.getType(),
        args.getOut(),
        args.getEnableSandbox().orElse(enableSandbox),
        true,
        args.getEnvironmentExpansionSeparator());
  }

  protected MacroHandler getMacroHandlerForParseTimeDeps() {
    return PARSE_TIME_MACRO_HANDLER;
  }

  protected Optional<MacroHandler> getMacroHandler(
      @SuppressWarnings("unused") BuildTarget buildTarget,
      @SuppressWarnings("unused") ProjectFilesystem filesystem,
      @SuppressWarnings("unused") BuildRuleResolver resolver,
      TargetGraph targetGraph,
      @SuppressWarnings("unused") T args) {
    return Optional.of(
        new MacroHandler(
            ImmutableMap.<String, MacroExpander>builder()
                .put("classpath", new ClasspathMacroExpander())
                .put("exe", new ExecutableMacroExpander())
                .put("worker", new WorkerMacroExpander())
                .put("location", new LocationMacroExpander())
                .put("maven_coords", new MavenCoordinatesMacroExpander())
                .put("query_targets", new QueryTargetsMacroExpander(Optional.of(targetGraph)))
                .put("query_outputs", new QueryOutputsMacroExpander(Optional.of(targetGraph)))
                .put("query_paths", new QueryPathsMacroExpander(Optional.of(targetGraph)))
                .put(
                    "query_targets_and_outputs",
                    new QueryTargetsAndOutputsMacroExpander(Optional.of(targetGraph)))
                .build()));
  }

  @Override
  public BuildRule createBuildRule(
      final TargetGraph targetGraph,
      BuildTarget buildTarget,
      final ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      final T args) {
    Optional<MacroHandler> maybeMacroHandler =
        getMacroHandler(buildTarget, projectFilesystem, resolver, targetGraph, args);
    if (maybeMacroHandler.isPresent()) {
      MacroHandler macroHandler = maybeMacroHandler.get();
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      Function<String, Arg> macroArgFunction =
          MacroArg.toMacroArgFunction(macroHandler, buildTarget, cellRoots, resolver);
      final Optional<Arg> cmd = args.getCmd().map(macroArgFunction);
      final Optional<Arg> bash = args.getBash().map(macroArgFunction);
      final Optional<Arg> cmdExe = args.getCmdExe().map(macroArgFunction);
      return createBuildRule(
          buildTarget,
          projectFilesystem,
          params.withExtraDeps(
              Stream.concat(
                      ruleFinder.filterBuildRuleInputs(args.getSrcs()).stream(),
                      Stream.of(cmd, bash, cmdExe)
                          .flatMap(Optionals::toStream)
                          .flatMap(input -> input.getDeps(ruleFinder).stream()))
                  .collect(
                      ImmutableSortedSet.toImmutableSortedSet(
                          Comparator.<BuildRule>naturalOrder()))),
          resolver,
          args,
          cmd,
          bash,
          cmdExe);
    }
    return createBuildRule(
        buildTarget,
        projectFilesystem,
        params,
        resolver,
        args,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      T constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (constructorArg.getBash().isPresent()) {
      addDepsFromParam(
          buildTarget,
          cellRoots,
          constructorArg.getBash().get(),
          extraDepsBuilder,
          targetGraphOnlyDepsBuilder);
    }
    if (constructorArg.getCmd().isPresent()) {
      addDepsFromParam(
          buildTarget,
          cellRoots,
          constructorArg.getCmd().get(),
          extraDepsBuilder,
          targetGraphOnlyDepsBuilder);
    }
    if (constructorArg.getCmdExe().isPresent()) {
      addDepsFromParam(
          buildTarget,
          cellRoots,
          constructorArg.getCmdExe().get(),
          extraDepsBuilder,
          targetGraphOnlyDepsBuilder);
    }
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      TargetGraph targetGraph,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      T constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> nonBuildDepsBuilder) {
    Optional<MacroHandler> maybeMacroHandler =
        getMacroHandler(buildTarget, projectFilesystem, resolver, targetGraph, constructorArg);
    maybeMacroHandler.ifPresent(
        macroHandler -> {
          Stream.of(constructorArg.getCmd(), constructorArg.getCmd(), constructorArg.getCmdExe())
              .flatMap(Optionals::toStream)
              .forEach(
                  s -> {
                    try {
                      macroHandler.extractParseTimeDeps(
                          buildTarget, cellRoots, s, extraDepsBuilder, nonBuildDepsBuilder);
                      ImmutableList<BuildRule> buildDeps =
                          macroHandler.extractBuildTimeDeps(buildTarget, cellRoots, resolver, s);
                      for (BuildRule dep : buildDeps) {
                        extraDepsBuilder.add(dep.getBuildTarget());
                      }
                    } catch (MacroException e) {
                      throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
                    }
                  });
        });
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
  public interface CommonArg extends CommonDescriptionArg, HasTests {
    String getOut();

    Optional<String> getBash();

    Optional<String> getCmd();

    Optional<String> getCmdExe();

    Optional<String> getType();

    ImmutableList<SourcePath> getSrcs();

    Optional<Boolean> getEnableSandbox();

    Optional<String> getEnvironmentExpansionSeparator();
  }
}
