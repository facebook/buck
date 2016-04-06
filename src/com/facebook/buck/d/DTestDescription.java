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

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class DTestDescription implements
    Description<DTestDescription.Arg>,
    ImplicitDepsInferringDescription<DTestDescription.Arg> {

  private static final BuildRuleType TYPE = BuildRuleType.of("d_test");

  private final DBuckConfig dBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform cxxPlatform;
  private final Optional<Long> defaultTestRuleTimeoutMs;

  public DTestDescription(
      DBuckConfig dBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform cxxPlatform,
      Optional<Long> defaultTestRuleTimeoutMs) {
    this.dBuckConfig = dBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatform = cxxPlatform;
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
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver buildRuleResolver,
      A args)
      throws NoSuchBuildTargetException {

    BuildTarget target = params.getBuildTarget();

    SourcePathResolver pathResolver = new SourcePathResolver(buildRuleResolver);

    SymlinkTree sourceTree =
        buildRuleResolver.addToIndex(
            DDescriptionUtils.createSourceSymlinkTree(
                DDescriptionUtils.getSymlinkTreeTarget(params.getBuildTarget()),
                params,
                pathResolver,
                args.srcs));

    // Create a helper rule to build the test binary.
    // The rule needs its own target so that we can depend on it without creating cycles.
    BuildTarget binaryTarget =
        DDescriptionUtils.createBuildTargetForFile(
            target,
            "build-",
            target.getFullyQualifiedName(),
            cxxPlatform);

    BuildRule binaryRule =
        DDescriptionUtils.createNativeLinkable(
            params.copyWithBuildTarget(binaryTarget),
            buildRuleResolver,
            cxxPlatform,
            dBuckConfig,
            cxxBuckConfig,
            ImmutableList.of("-unittest"),
            args.srcs,
            DIncludes.builder()
                .setLinkTree(new BuildTargetSourcePath(sourceTree.getBuildTarget()))
                .addAllSources(args.srcs.getPaths())
                .build());

    return new DTest(
        params.appendExtraDeps(ImmutableList.of(binaryRule)),
        new SourcePathResolver(buildRuleResolver),
        binaryRule,
        args.contacts.get(),
        args.labels.get(),
        args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
        buildRuleResolver.getAllRules(
            args.sourceUnderTest.or(ImmutableSortedSet.<BuildTarget>of())));
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Function<Optional<String>, Path> cellRoots,
      Arg constructorArg) {
    return cxxPlatform.getLd().getParseTimeDeps();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public SourceList srcs;
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<ImmutableSortedSet<BuildTarget>> sourceUnderTest;
    public Optional<Long> testRuleTimeoutMs;
    public ImmutableSortedSet<BuildTarget> deps;
  }
}
