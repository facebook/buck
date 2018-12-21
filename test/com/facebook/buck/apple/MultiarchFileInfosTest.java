/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleCxxPlatformsProvider;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.Test;

public class MultiarchFileInfosTest {

  private static SourcePathResolver newSourcePathResolver() {
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    return DefaultSourcePathResolver.from(ruleFinder);
  }

  @Test
  public void testOutputFormatStringEmptyThinRules() {
    SourcePathResolver pathResolver = newSourcePathResolver();

    ImmutableSortedSet.Builder<SourcePath> inputsBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet<SourcePath> inputs = inputsBuilder.build();

    String outputFormatString =
        MultiarchFileInfos.getMultiarchOutputFormatString(pathResolver, inputs);

    assertThat(outputFormatString, equalTo("%s"));
  }

  @Test
  public void testOutputFormatStringSingleThinRule() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver = newSourcePathResolver();

    ImmutableSortedSet<SourcePath> inputs =
        ImmutableSortedSet.of(PathSourcePath.of(filesystem, Paths.get("libNiceLibrary.a")));

    String outputFormatString =
        MultiarchFileInfos.getMultiarchOutputFormatString(pathResolver, inputs);

    assertThat(outputFormatString, equalTo("%s/libNiceLibrary.a"));
  }

  @Test
  public void testOutputFormatStringDifferentOutputFileNameThinRules() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver = newSourcePathResolver();

    ImmutableSortedSet.Builder<SourcePath> inputsBuilder = ImmutableSortedSet.naturalOrder();

    inputsBuilder.add(PathSourcePath.of(filesystem, Paths.get("libNiceLibrary.a")));
    inputsBuilder.add(PathSourcePath.of(filesystem, Paths.get("libBadLibrary.a")));

    ImmutableSortedSet<SourcePath> inputs = inputsBuilder.build();

    String outputFormatString =
        MultiarchFileInfos.getMultiarchOutputFormatString(pathResolver, inputs);

    assertThat(outputFormatString, equalTo("%s"));
  }

  @Test
  public void testOutputFormatStringSameOutputFileNameThinRules() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver = newSourcePathResolver();

    ImmutableSortedSet.Builder<SourcePath> inputsBuilder = ImmutableSortedSet.naturalOrder();

    inputsBuilder.add(PathSourcePath.of(filesystem, Paths.get("libNiceLibrary.a")));
    inputsBuilder.add(PathSourcePath.of(filesystem, Paths.get("libNiceLibrary.a")));

    ImmutableSortedSet<SourcePath> inputs = inputsBuilder.build();

    String outputFormatString =
        MultiarchFileInfos.getMultiarchOutputFormatString(pathResolver, inputs);

    assertThat(outputFormatString, equalTo("%s/libNiceLibrary.a"));
  }

  @Test
  public void testRequireMultiarchRuleThinRulesDifferentOutput() {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget fatBuildTarget =
        BuildTargetFactory.newInstance("//fake/rule:output#watchos-arm64_32,watchos-armv7k");

    MultiarchFileInfo multiarchFileInfo =
        MultiarchFileInfos.create(getAppleCxxPlatformFlavorDomain(), fatBuildTarget).get();

    FakeBuildRule rule1 =
        new FakeBuildRule(BuildTargetFactory.newInstance("//fake/rule:output#watchos-arm64_32"));
    rule1.setOutputFile("libFakeRuleOutput.a");
    FakeBuildRule rule2 =
        new FakeBuildRule(BuildTargetFactory.newInstance("//fake/rule:output#watchos-armv7k"));
    rule2.setOutputFile(null);

    ImmutableSortedSet<BuildRule> inputs = ImmutableSortedSet.of(rule1, rule2);

    BuildRule outputRule =
        MultiarchFileInfos.requireMultiarchRule(
            multiarchFileInfo.getFatTarget(),
            filesystem,
            TestBuildRuleParams.create(),
            buildActionGraphBuilder(filesystem, fatBuildTarget, inputs),
            multiarchFileInfo,
            inputs,
            CxxPlatformUtils.DEFAULT_CONFIG);

    assertThat(outputRule, instanceOf(MultiarchFile.class));
  }

  @Test
  public void testRequireMultiarchRuleThinRulesMissingOutput() {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget fatBuildTarget =
        BuildTargetFactory.newInstance("//fake/rule:output#watchos-arm64_32,watchos-armv7k");

    MultiarchFileInfo multiarchFileInfo =
        MultiarchFileInfos.create(getAppleCxxPlatformFlavorDomain(), fatBuildTarget).get();

    FakeBuildRule rule1 =
        new FakeBuildRule(BuildTargetFactory.newInstance("//fake/rule:output#watchos-arm64_32"));
    rule1.setOutputFile(null);
    FakeBuildRule rule2 =
        new FakeBuildRule(BuildTargetFactory.newInstance("//fake/rule:output#watchos-armv7k"));
    rule2.setOutputFile(null);

    ImmutableSortedSet<BuildRule> inputs = ImmutableSortedSet.of(rule1, rule2);

    BuildRule outputRule =
        MultiarchFileInfos.requireMultiarchRule(
            multiarchFileInfo.getFatTarget(),
            filesystem,
            TestBuildRuleParams.create(),
            buildActionGraphBuilder(filesystem, fatBuildTarget, inputs),
            multiarchFileInfo,
            inputs,
            CxxPlatformUtils.DEFAULT_CONFIG);

    assertThat(outputRule, instanceOf(NoopBuildRule.class));
  }

  @Test
  public void testRequireMultiarchRuleThinRulesContainOutput() {
    assumeThat(Platform.detect(), is(Platform.MACOS));

    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    BuildTarget fatBuildTarget =
        BuildTargetFactory.newInstance("//fake/rule:output#watchos-arm64_32,watchos-armv7k");

    MultiarchFileInfo multiarchFileInfo =
        MultiarchFileInfos.create(getAppleCxxPlatformFlavorDomain(), fatBuildTarget).get();

    FakeBuildRule rule1 =
        new FakeBuildRule(BuildTargetFactory.newInstance("//fake/rule:output#watchos-arm64_32"));
    rule1.setOutputFile("libFakeRuleOutput.a");
    FakeBuildRule rule2 =
        new FakeBuildRule(BuildTargetFactory.newInstance("//fake/rule:output#watchos-armv7k"));
    rule2.setOutputFile("libFakeRuleOutput.a");

    ImmutableSortedSet<BuildRule> inputs = ImmutableSortedSet.of(rule1, rule2);

    BuildRule outputRule =
        MultiarchFileInfos.requireMultiarchRule(
            multiarchFileInfo.getFatTarget(),
            filesystem,
            TestBuildRuleParams.create(),
            buildActionGraphBuilder(filesystem, fatBuildTarget, inputs),
            multiarchFileInfo,
            inputs,
            CxxPlatformUtils.DEFAULT_CONFIG);

    assertThat(outputRule, instanceOf(MultiarchFile.class));
  }

  private ActionGraphBuilder buildActionGraphBuilder(
      ProjectFilesystem filesystem,
      BuildTarget fatBuildTarget,
      ImmutableSortedSet<BuildRule> inputs) {
    SortedSet<TargetNode<?>> targetNodes = buildTargetNodes(fatBuildTarget);
    TargetGraph targetGraph = TargetGraphFactory.newInstance(targetNodes);
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph, filesystem);
    for (BuildRule rule : inputs) {
      graphBuilder.addToIndex(rule);
    }
    return graphBuilder;
  }

  private SortedSet<TargetNode<?>> buildTargetNodes(BuildTarget fatBuildTarget) {
    SortedSet<TargetNode<?>> buildRules = new TreeSet<>();
    TargetNode<?> node = AppleLibraryBuilder.createBuilder(fatBuildTarget).build();
    buildRules.add(node);
    return buildRules;
  }

  private FlavorDomain<AppleCxxPlatform> getAppleCxxPlatformFlavorDomain() {
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                AppleCxxPlatformsProvider.DEFAULT_NAME,
                AppleCxxPlatformsProvider.of(
                    FakeAppleRuleDescriptions.DEFAULT_APPLE_CXX_PLATFORM_FLAVOR_DOMAIN))
            .build();

    AppleCxxPlatformsProvider appleCxxPlatformsProvider =
        toolchainProvider.getByName(
            AppleCxxPlatformsProvider.DEFAULT_NAME, AppleCxxPlatformsProvider.class);
    return appleCxxPlatformsProvider.getAppleCxxPlatforms();
  }
}
