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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroException;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public abstract class AbstractGenruleDescription<T extends AbstractGenruleDescription.Arg>
    implements Description<T>, ImplicitDepsInferringDescription<T> {

  public static final MacroHandler MACRO_HANDLER = new MacroHandler(
      ImmutableMap.<String, MacroExpander>of(
          "classpath", new ClasspathMacroExpander(),
          "exe", new ExecutableMacroExpander(),
          "location", new LocationMacroExpander()));

  protected abstract <A extends T> BuildRule createBuildRule(
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args,
      ImmutableList<SourcePath> srcs,
      Function<String, String> macroExpander,
      Optional<String> cmd,
      Optional<String> bash,
      Optional<String> cmdExe,
      String out,
      final Function<Path, Path> relativeToAbsolutePathFunction,
      Supplier<ImmutableList<Object>> macroRuleKeyAppendables);

  @Override
  public <A extends T> BuildRule createBuildRule(
      final TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args) {
    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    return createBuildRule(
        params.appendExtraDeps(
            new Supplier<ImmutableSortedSet<BuildRule>>() {
              @Override
              public ImmutableSortedSet<BuildRule> get() {
                return ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(pathResolver.filterBuildRuleInputs(args.srcs.get()))
                        // Attach any extra dependencies found from macro expansion.
                    .addAll(findExtraDepsFromArgs(params.getBuildTarget(), resolver , args))
                    .build();
              }
            }),
        resolver,
        args,
        args.srcs.get(),
        MACRO_HANDLER.getExpander(
            params.getBuildTarget(),
            resolver,
            params.getProjectFilesystem()),
        args.cmd,
        args.bash,
        args.cmdExe,
        args.out,
        params.getProjectFilesystem().getAbsolutifier(),
        new Supplier<ImmutableList<Object>>() {
          @Override
          public ImmutableList<Object> get() {
            return findRuleKeyAppendables(params.getBuildTarget(), resolver, args);
          }
        });
  }

  protected ImmutableList<Object> findRuleKeyAppendables(
      BuildTarget target,
      BuildRuleResolver resolver,
      T arg) {
    ImmutableList.Builder<Object> deps = ImmutableList.builder();
    try {
      for (String val :
          Optional.presentInstances(ImmutableList.of(arg.bash, arg.cmd, arg.cmdExe))) {
        deps.addAll(MACRO_HANDLER.extractRuleKeyAppendables(target, resolver, val));
      }
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
    return deps.build();
  }

  protected ImmutableList<BuildRule> findExtraDepsFromArgs(
      BuildTarget target,
      BuildRuleResolver resolver,
      T arg) {
    ImmutableList.Builder<BuildRule> deps = ImmutableList.builder();
    try {
      for (String val :
          Optional.presentInstances(ImmutableList.of(arg.bash, arg.cmd, arg.cmdExe))) {
        deps.addAll(MACRO_HANDLER.extractAdditionalBuildTimeDeps(target, resolver, val));
      }
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
    return deps.build();
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      T constructorArg) {
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
      targets.addAll(MACRO_HANDLER.extractParseTimeDeps(target, paramValue));
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public String out;
    public Optional<String> bash;
    public Optional<String> cmd;
    public Optional<String> cmdExe;
    public Optional<ImmutableList<SourcePath>> srcs;

    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }

}
