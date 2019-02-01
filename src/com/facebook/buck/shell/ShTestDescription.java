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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasContacts;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTestTimeout;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.macros.AbstractMacroExpanderWithoutPrecomputedWork;
import com.facebook.buck.rules.macros.ClasspathMacroExpander;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.test.config.TestBuckConfig;
import com.facebook.buck.util.Optionals;
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

public class ShTestDescription implements DescriptionWithTargetGraph<ShTestDescriptionArg> {

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
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ShTestDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(buildTarget, context.getCellPathResolver(), MACRO_EXPANDERS);
    ImmutableList<Arg> testArgs =
        Stream.concat(
                Optionals.toStream(args.getTest()).map(SourcePathArg::of),
                args.getArgs().stream().map(x -> macrosConverter.convert(x, graphBuilder)))
            .collect(ImmutableList.toImmutableList());
    ImmutableMap<String, Arg> testEnv =
        ImmutableMap.copyOf(
            Maps.transformValues(args.getEnv(), x -> macrosConverter.convert(x, graphBuilder)));
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
            .orElse(buckConfig.getView(TestBuckConfig.class).getDefaultTestRuleTimeoutMs()),
        args.getRunTestSeparately(),
        args.getLabels(),
        args.getType(),
        args.getContacts());
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
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
