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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasContacts;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasTestTimeout;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.macros.AbstractMacroExpanderWithoutPrecomputedWork;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class ShTestDescription implements Description<ShTestDescriptionArg> {

  private static final ImmutableList<AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>>
      MACRO_EXPANDERS =
          ImmutableList.of(
              new LocationMacroExpander(),
              new ClasspathMacroExpander(),
              new ExecutableMacroExpander());

  private final BuckConfig buckConfig;

  public ShTestDescription(BuckConfig buckConfig) {
    this.buckConfig = buckConfig;
  }

  @Override
  public Class<ShTestDescriptionArg> getConstructorArgType() {
    return ShTestDescriptionArg.class;
  }

  @Override
  public ShTest createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      ShTestDescriptionArg args) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(buildTarget, cellRoots, resolver, MACRO_EXPANDERS);
    ImmutableList<Arg> testArgs =
        Stream.concat(
                Optionals.toStream(args.getTest()).map(SourcePathArg::of),
                args.getArgs().stream().map(macrosConverter::convert))
            .collect(ImmutableList.toImmutableList());
    ImmutableMap<String, Arg> testEnv =
        ImmutableMap.copyOf(Maps.transformValues(args.getEnv(), macrosConverter::convert));
    return new ShTest(
        buildTarget,
        projectFilesystem,
        params.copyAppendingExtraDeps(
            () ->
                FluentIterable.from(testArgs)
                    .append(testEnv.values())
                    .transformAndConcat(
                        arg -> BuildableSupport.getDepsCollection(arg, ruleFinder))),
        testArgs,
        testEnv,
        FluentIterable.from(args.getResources())
            .transform(p -> PathSourcePath.of(projectFilesystem, p))
            .toSortedSet(Ordering.natural()),
        args.getTestRuleTimeoutMs()
            .map(Optional::of)
            .orElse(buckConfig.getDefaultTestRuleTimeoutMs()),
        args.getRunTestSeparately(),
        args.getLabels(),
        args.getType(),
        args.getContacts());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractShTestDescriptionArg
      extends CommonDescriptionArg, HasContacts, HasDeclaredDeps, HasTestTimeout {
    Optional<SourcePath> getTest();

    ImmutableList<StringWithMacros> getArgs();

    Optional<String> getType();

    @Value.Default
    default boolean getRunTestSeparately() {
      return false;
    }

    @Value.NaturalOrder
    ImmutableSortedSet<Path> getResources();

    ImmutableMap<String, StringWithMacros> getEnv();
  }
}
