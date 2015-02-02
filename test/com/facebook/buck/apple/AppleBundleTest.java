/*
 * Copyright 2013-present Facebook, Inc.
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

import static com.facebook.buck.apple.ProjectGeneratorTestUtils.createDescriptionArgWithDefaults;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.coercer.Either;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

public class AppleBundleTest {

  private AppleBundleDescription description = new AppleBundleDescription();

  @Test
  public void getKnownBundleExtension() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    AppleNativeTargetDescriptionArg libraryArg =
        createDescriptionArgWithDefaults(FakeAppleRuleDescriptions.LIBRARY_DESCRIPTION);
    BuildRuleParams libraryParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build()).build();
    BuildRule library = resolver.addToIndex(
        FakeAppleRuleDescriptions.LIBRARY_DESCRIPTION.createBuildRule(
            libraryParams, resolver, libraryArg));

    AppleBundleDescription.Arg arg = description.createUnpopulatedConstructorArg();
    arg.extension = Either.ofLeft(AppleBundleExtension.FRAMEWORK);
    arg.infoPlist = Optional.absent();
    arg.deps = Optional.absent();
    arg.binary = library.getBuildTarget();
    arg.deps = Optional.of(ImmutableSortedSet.of(arg.binary));

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "bundle").build()).build();
    AppleBundle bundle = description.createBuildRule(params, resolver, arg);
    resolver.addToIndex(bundle);

    assertEquals(bundle.getExtensionString(), "framework");
    assertEquals(bundle.getExtensionValue(), Optional.of(AppleBundleExtension.FRAMEWORK));
  }

  @Test
  public void getUnknownBundleExtension() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    AppleNativeTargetDescriptionArg libraryArg =
        createDescriptionArgWithDefaults(FakeAppleRuleDescriptions.LIBRARY_DESCRIPTION);
    BuildRuleParams libraryParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build()).build();
    BuildRule library = resolver.addToIndex(
        FakeAppleRuleDescriptions.LIBRARY_DESCRIPTION.createBuildRule(
            libraryParams, resolver, libraryArg));

    AppleBundleDescription.Arg arg = description.createUnpopulatedConstructorArg();
    arg.extension = Either.ofRight("grplugin");
    arg.infoPlist = Optional.absent();
    arg.binary = library.getBuildTarget();
    arg.deps = Optional.of(ImmutableSortedSet.of(arg.binary));

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "bundle").build()).build();
    AppleBundle bundle = description.createBuildRule(params, resolver, arg);
    resolver.addToIndex(bundle);

    assertEquals(bundle.getExtensionString(), "grplugin");
    assertEquals(bundle.getExtensionValue(), Optional.absent());
  }
}
