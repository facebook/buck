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
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
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
import java.util.stream.Stream;
import org.immutables.value.Value;

public class ShTestDescription
    implements Description<ShTestDescriptionArg>,
        ImplicitDepsInferringDescription<ShTestDescription.AbstractShTestDescriptionArg> {

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.of(
              "location", new LocationMacroExpander(),
              "classpath", new ClasspathMacroExpander(),
              "exe", new ExecutableMacroExpander()));

  private final Optional<Long> defaultTestRuleTimeoutMs;

  public ShTestDescription(Optional<Long> defaultTestRuleTimeoutMs) {
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
  }

  @Override
  public Class<ShTestDescriptionArg> getConstructorArgType() {
    return ShTestDescriptionArg.class;
  }

  @Override
  public ShTest createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      ShTestDescriptionArg args) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    Function<String, com.facebook.buck.rules.args.Arg> toArg =
        MacroArg.toMacroArgFunction(MACRO_HANDLER, params.getBuildTarget(), cellRoots, resolver);
    final ImmutableList<com.facebook.buck.rules.args.Arg> testArgs =
        Stream.concat(
                Optionals.toStream(args.getTest()).map(SourcePathArg::of),
                args.getArgs().stream().map(toArg::apply))
            .collect(MoreCollectors.toImmutableList());
    final ImmutableMap<String, com.facebook.buck.rules.args.Arg> testEnv =
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), toArg));
    return new ShTest(
        params.copyAppendingExtraDeps(
            () ->
                FluentIterable.from(testArgs)
                    .append(testEnv.values())
                    .transformAndConcat(arg -> arg.getDeps(ruleFinder))),
        ruleFinder,
        testArgs,
        testEnv,
        FluentIterable.from(args.getResources())
            .transform(p -> new PathSourcePath(params.getProjectFilesystem(), p))
            .toSortedSet(Ordering.natural()),
        args.getTestRuleTimeoutMs().map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.getRunTestSeparately(),
        args.getLabels(),
        args.getContacts());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractShTestDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Add parse time deps for any macros.
    for (String blob :
        Iterables.concat(constructorArg.getArgs(), constructorArg.getEnv().values())) {
      try {
        MACRO_HANDLER.extractParseTimeDeps(
            buildTarget, cellRoots, blob, extraDepsBuilder, targetGraphOnlyDepsBuilder);
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractShTestDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    Optional<SourcePath> getTest();

    ImmutableList<String> getArgs();

    ImmutableSet<String> getContacts();

    Optional<Long> getTestRuleTimeoutMs();

    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.NaturalOrder
    ImmutableSortedSet<Path> getResources();

    ImmutableMap<String, String> getEnv();
  }
}
