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
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import java.nio.file.Path;
import java.util.Optional;

public class ShTestDescription implements
    Description<ShTestDescription.Arg>,
    ImplicitDepsInferringDescription<ShTestDescription.Arg> {

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.of(
              "location", new LocationMacroExpander(),
              "classpath", new ClasspathMacroExpander(),
              "exe", new ExecutableMacroExpander()));

  private final Optional<Long> defaultTestRuleTimeoutMs;

  public ShTestDescription(
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> ShTest createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    Function<String, com.facebook.buck.rules.args.Arg> toArg =
        MacroArg.toMacroArgFunction(
            MACRO_HANDLER,
            params.getBuildTarget(),
            cellRoots,
            resolver);
    final ImmutableList<com.facebook.buck.rules.args.Arg> testArgs =
        args.args.stream()
            .map(toArg::apply)
            .collect(MoreCollectors.toImmutableList());
    final ImmutableMap<String, com.facebook.buck.rules.args.Arg> testEnv =
        ImmutableMap.copyOf(
            Maps.transformValues(
                args.env,
                toArg));
    return new ShTest(
        params.copyAppendingExtraDeps(
            () -> FluentIterable.from(testArgs)
                .append(testEnv.values())
                .transformAndConcat(arg -> arg.getDeps(ruleFinder))),
        ruleFinder,
        args.test,
        testArgs,
        testEnv,
        FluentIterable.from(args.resources)
            .transform(p -> new PathSourcePath(params.getProjectFilesystem(), p))
            .toSortedSet(Ordering.natural()),
        args.testRuleTimeoutMs.map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.runTestSeparately.orElse(false),
        args.labels,
        args.contacts);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Add parse time deps for any macros.
    for (String blob :
         Iterables.concat(
             constructorArg.args,
             constructorArg.env.values())) {
      try {
        MACRO_HANDLER.extractParseTimeDeps(
            buildTarget,
            cellRoots,
            blob,
            extraDepsBuilder,
            targetGraphOnlyDepsBuilder);
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public SourcePath test;
    public ImmutableList<String> args = ImmutableList.of();
    public ImmutableSet<String> contacts = ImmutableSet.of();
    public Optional<Long> testRuleTimeoutMs;
    public Optional<Boolean> runTestSeparately;
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public ImmutableSortedSet<Path> resources = ImmutableSortedSet.of();
    public ImmutableMap<String, String> env = ImmutableMap.of();
  }

}
