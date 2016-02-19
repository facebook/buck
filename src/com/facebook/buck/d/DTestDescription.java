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

package com.facebook.buck.d;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

public class DTestDescription implements Description<DTestDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("d_test");

  private final DBuckConfig dBuckConfig;
  private final CxxPlatform cxxPlatform;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public DTestDescription(
      DBuckConfig dBuckConfig,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform) {
    this.dBuckConfig = dBuckConfig;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
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
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      A args) throws NoSuchBuildTargetException {

    BuildTarget target = params.getBuildTarget();

    // Create a helper rule to build the test binary.
    // The rule needs its own target so that we can depend on it without creating cycles.
    BuildTarget binaryTarget = DDescriptionUtils.createBuildTargetForFile(
        target,
        "build-",
        target.getFullyQualifiedName(),
        cxxPlatform);
    BuildRule binaryRule = DDescriptionUtils.createNativeLinkable(
        params.copyWithBuildTarget(binaryTarget),
        args.srcs,
        ImmutableList.of("-unittest"),
        buildRuleResolver,
        cxxPlatform,
        dBuckConfig);

    return new DTest(
        params.appendExtraDeps(ImmutableList.of(binaryRule)),
        new SourcePathResolver(buildRuleResolver),
        binaryRule.getPathToOutput(),
        args.contacts.get(),
        args.labels.get(),
        args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
        buildRuleResolver.getAllRules(
            args.sourceUnderTest.or(ImmutableSortedSet.<BuildTarget>of())));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableSortedSet<SourcePath> srcs;
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<ImmutableSortedSet<BuildTarget>> sourceUnderTest;
    public Optional<Long> testRuleTimeoutMs;
    public ImmutableSortedSet<BuildTarget> deps;
  }
}
