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

package com.facebook.buck.apple;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cxx.Archives;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Either;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

public class AppleBuildRulesTest {

  private AppleLibraryDescription appleLibraryDescription;
  private AppleBundleDescription appleBundleDescription;
  private AppleTestDescription appleTestDescription;

  @Before
  public void setUp() throws IOException {
    appleLibraryDescription = new AppleLibraryDescription(Archives.DEFAULT_ARCHIVE_PATH);
    appleBundleDescription = new AppleBundleDescription();
    appleTestDescription = new AppleTestDescription();
  }

  @Test
  public void testAppleLibraryIsXcodeTargetBuildRuleType() throws Exception {
    assertTrue(AppleBuildRules.isXcodeTargetBuildRuleType(AppleLibraryDescription.TYPE));
  }

  @Test
  public void testIosResourceIsNotXcodeTargetBuildRuleType() throws Exception {
    assertFalse(AppleBuildRules.isXcodeTargetBuildRuleType(AppleResourceDescription.TYPE));
  }

  @Test
  public void testAppleTestIsXcodeTargetTestBuildRuleType() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRuleParams libraryParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg libraryArg =
        appleLibraryDescription.createUnpopulatedConstructorArg();
    libraryArg.configs = Optional.of(
        ImmutableMap.<String, ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>>of());
    libraryArg.srcs = Optional.of(ImmutableList.<AppleSource>of());
    libraryArg.frameworks = Optional.of(ImmutableSortedSet.<String>of());
    libraryArg.weakFrameworks = Optional.of(ImmutableSortedSet.<String>of());
    libraryArg.deps = Optional.absent();
    libraryArg.gid = Optional.absent();
    libraryArg.headerPathPrefix = Optional.absent();
    libraryArg.useBuckHeaderMaps = Optional.absent();
    BuildRule libraryRule =
        appleLibraryDescription.createBuildRule(libraryParams, resolver, libraryArg);
    resolver.addToIndex(libraryRule);

    BuildRuleParams xctestParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "xctest").build())
            .setDeps(ImmutableSortedSet.of(libraryRule))
            .setType(AppleBundleDescription.TYPE)
            .build();

    AppleBundleDescription.Arg xctestArg =
        appleBundleDescription.createUnpopulatedConstructorArg();
    xctestArg.infoPlist = Optional.<SourcePath>of(new TestSourcePath("Info.plist"));
    xctestArg.binary = libraryRule.getBuildTarget();
    xctestArg.extension = Either.ofLeft(AppleBundleExtension.XCTEST);
    xctestArg.deps = Optional.absent();

    BuildRule xctestRule = appleBundleDescription.createBuildRule(
        xctestParams,
        resolver,
        xctestArg);
    resolver.addToIndex(xctestRule);

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "test").build())
            .setDeps(ImmutableSortedSet.of(xctestRule))
            .setType(AppleTestDescription.TYPE)
            .build();

    AppleTestDescription.Arg arg =
        appleTestDescription.createUnpopulatedConstructorArg();
    arg.testBundle = xctestRule.getBuildTarget();
    arg.contacts = Optional.of(ImmutableSortedSet.<String>of());
    arg.labels = Optional.of(ImmutableSortedSet.<Label>of());
    arg.deps = Optional.of(ImmutableSortedSet.of(xctestRule.getBuildTarget()));
    arg.sourceUnderTest = Optional.of(ImmutableSortedSet.<BuildTarget>of());

    BuildRule testRule = appleTestDescription.createBuildRule(
        params,
        resolver,
        arg);
    resolver.addToIndex(testRule);

    assertTrue(AppleBuildRules.isXcodeTargetTestBuildRule(testRule));
  }

  @Test
  public void testAppleLibraryIsNotXcodeTargetTestBuildRuleType() throws Exception {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg =
        appleLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = Optional.of(
        ImmutableMap.<String, ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>>of());
    arg.srcs = Optional.of(ImmutableList.<AppleSource>of());
    arg.frameworks = Optional.of(ImmutableSortedSet.<String>of());
    arg.weakFrameworks = Optional.of(ImmutableSortedSet.<String>of());
    arg.deps = Optional.absent();
    arg.gid = Optional.absent();
    arg.headerPathPrefix = Optional.absent();
    arg.useBuckHeaderMaps = Optional.absent();
    AppleLibrary libraryRule =
        appleLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    assertFalse(AppleBuildRules.isXcodeTargetTestBuildRule(libraryRule));
  }

  @Test
  public void testXctestIsTestBundleExtension() throws Exception {
    assertTrue(AppleBuildRules.isXcodeTargetTestBundleExtension(AppleBundleExtension.XCTEST));
  }

  @Test
  public void testOctestIsTestBundleExtension() throws Exception {
    assertTrue(AppleBuildRules.isXcodeTargetTestBundleExtension(AppleBundleExtension.OCTEST));
  }

  @Test
  public void testRecursiveTargetsIncludesBundleBinaryFromOutsideBundle() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRuleParams libraryParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg libraryArg =
        appleLibraryDescription.createUnpopulatedConstructorArg();
    libraryArg.configs = Optional.of(
        ImmutableMap.<String, ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>>of());
    libraryArg.srcs = Optional.of(ImmutableList.<AppleSource>of());
    libraryArg.frameworks = Optional.of(ImmutableSortedSet.<String>of());
    libraryArg.weakFrameworks = Optional.of(ImmutableSortedSet.<String>of());
    libraryArg.deps = Optional.absent();
    libraryArg.gid = Optional.absent();
    libraryArg.headerPathPrefix = Optional.absent();
    libraryArg.useBuckHeaderMaps = Optional.absent();
    BuildRule libraryRule =
        appleLibraryDescription.createBuildRule(libraryParams, resolver, libraryArg);
    resolver.addToIndex(libraryRule);

    BuildRuleParams bundleParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "bundle").build())
            .setDeps(ImmutableSortedSet.of(libraryRule))
            .setType(AppleBundleDescription.TYPE)
            .build();

    AppleBundleDescription.Arg bundleArg =
        appleBundleDescription.createUnpopulatedConstructorArg();
    bundleArg.infoPlist = Optional.<SourcePath>of(new TestSourcePath("Info.plist"));
    bundleArg.binary = libraryRule.getBuildTarget();
    bundleArg.extension = Either.ofLeft(AppleBundleExtension.BUNDLE);
    bundleArg.deps = Optional.of(ImmutableSortedSet.of(libraryRule.getBuildTarget()));

    BuildRule bundleRule = appleBundleDescription.createBuildRule(
        bundleParams,
        resolver,
        bundleArg);
    resolver.addToIndex(bundleRule);

    BuildRuleParams rootParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "root").build())
            .setDeps(ImmutableSortedSet.of(bundleRule, libraryRule))
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg rootArg =
        appleLibraryDescription.createUnpopulatedConstructorArg();
    rootArg.configs = Optional.of(
        ImmutableMap.<String, ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>>of());
    rootArg.srcs = Optional.of(ImmutableList.<AppleSource>of());
    rootArg.frameworks = Optional.of(ImmutableSortedSet.<String>of());
    rootArg.weakFrameworks = Optional.of(ImmutableSortedSet.<String>of());
    rootArg.deps = Optional.of(
        ImmutableSortedSet.of(bundleRule.getBuildTarget(), libraryRule.getBuildTarget()));
    rootArg.gid = Optional.absent();
    rootArg.headerPathPrefix = Optional.absent();
    rootArg.useBuckHeaderMaps = Optional.absent();
    BuildRule rootRule =
        appleLibraryDescription.createBuildRule(rootParams, resolver, rootArg);
    resolver.addToIndex(rootRule);

    Iterable<BuildRule> rules = AppleBuildRules.getRecursiveRuleDependenciesOfTypes(
        AppleBuildRules.RecursiveRuleDependenciesMode.BUILDING,
        rootRule,
        Optional.<ImmutableSet<BuildRuleType>>absent());

    assertTrue(Iterables.elementsEqual(ImmutableSortedSet.of(bundleRule, libraryRule), rules));
  }
}
