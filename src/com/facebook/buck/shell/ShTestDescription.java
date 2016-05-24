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
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
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
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;

import java.nio.file.Path;

public class ShTestDescription implements
    Description<ShTestDescription.Arg>,
    ImplicitDepsInferringDescription<ShTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("sh_test");

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>of(
              "location", new LocationMacroExpander(),
              "classpath", new ClasspathMacroExpander(),
              "exe", new ExecutableMacroExpander()));

  private final Optional<Long> defaultTestRuleTimeoutMs;

  public ShTestDescription(
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
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
  public <A extends Arg> ShTest createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    final SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    Function<String, com.facebook.buck.rules.args.Arg> toArg =
        MacroArg.toMacroArgFunction(
            MACRO_HANDLER,
            params.getBuildTarget(),
            params.getCellRoots(),
            resolver);
    final ImmutableList<com.facebook.buck.rules.args.Arg> testArgs =
        FluentIterable.from(args.args.or(ImmutableList.<String>of()))
            .transform(toArg)
            .toList();
    final ImmutableMap<String, com.facebook.buck.rules.args.Arg> testEnv =
        ImmutableMap.copyOf(
            Maps.transformValues(
                args.env.or(ImmutableMap.<String, String>of()),
                toArg));
    return new ShTest(
        params.appendExtraDeps(
            new Supplier<Iterable<? extends BuildRule>>() {
              @Override
              public Iterable<? extends BuildRule> get() {
                return FluentIterable.from(testArgs)
                    .append(testEnv.values())
                    .transformAndConcat(
                        com.facebook.buck.rules.args.Arg.getDepsFunction(pathResolver));
              }
            }),
        pathResolver,
        args.test,
        testArgs,
        testEnv,
        FluentIterable.from(args.resources.or(ImmutableSortedSet.<Path>of()))
            .transform(SourcePaths.toSourcePath(params.getProjectFilesystem()))
            .toSortedSet(Ordering.natural()),
        args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
        args.labels.get(),
        args.contacts.or(ImmutableSet.<String>of()));
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    // Add parse time deps for any macros.
    for (String blob :
         Iterables.concat(
             constructorArg.args.or(ImmutableList.<String>of()),
             constructorArg.env.or(ImmutableMap.<String, String>of()).values())) {
      try {
        deps.addAll(MACRO_HANDLER.extractParseTimeDeps(buildTarget, cellRoots, blob));
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }

    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public SourcePath test;
    public Optional<ImmutableList<String>> args;
    public Optional<ImmutableSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<Long> testRuleTimeoutMs;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<ImmutableSortedSet<Path>> resources;
    public Optional<ImmutableMap<String, String>> env;
  }

}
