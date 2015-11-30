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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroExpander;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

public class ShTestDescription implements Description<ShTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("sh_test");

  private static final MacroHandler MACRO_HANDLER =
      new MacroHandler(
          ImmutableMap.<String, MacroExpander>of(
              "location", new LocationMacroExpander()));

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
    final ImmutableList<com.facebook.buck.rules.args.Arg> testArgs =
        FluentIterable.from(args.args.or(ImmutableList.<String>of()))
            .transform(
                MacroArg.toMacroArgFunction(
                    MACRO_HANDLER,
                    params.getBuildTarget(),
                    params.getCellRoots(),
                    resolver,
                    params.getProjectFilesystem()))
            .toList();
    return new ShTest(
        params.appendExtraDeps(
            new Supplier<Iterable<? extends BuildRule>>() {
              @Override
              public Iterable<? extends BuildRule> get() {
                return FluentIterable.from(testArgs)
                    .transformAndConcat(
                        com.facebook.buck.rules.args.Arg.getDepsFunction(pathResolver));
              }
            }),
        pathResolver,
        args.test,
        testArgs,
        args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
        args.labels.get());
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public SourcePath test;
    public Optional<ImmutableList<String>> args;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<Long> testRuleTimeoutMs;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
