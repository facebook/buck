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

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.HasSourceUnderTest;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

public class AppleTestDescription implements Description<AppleTestDescription.Arg> {
  public static final BuildRuleType TYPE = new BuildRuleType("apple_test");

  /**
   * Flavors for the additional generated build rules.
   */
  private static final Flavor LIBRARY_FLAVOR = new Flavor("apple-test-library");
  private static final Flavor BUNDLE_FLAVOR = new Flavor("apple-test-bundle");

  private final AppleLibraryDescription appleLibraryDescription;

  public AppleTestDescription(AppleLibraryDescription description) {
    appleLibraryDescription = description;
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
  public <A extends Arg> AppleTest createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    BuildRule library = appleLibraryDescription.createBuildRule(
        params.copyWithChanges(
            AppleLibraryDescription.TYPE,
            BuildTarget.builder(params.getBuildTarget())
                .addFlavor(LIBRARY_FLAVOR)
                .addFlavor(CxxDescriptionEnhancer.SHARED_FLAVOR)
                .addFlavor("default")
                .build(),
            params.getDeclaredDeps(),
            params.getExtraDeps()),
        resolver,
        args);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    AppleBundle bundle = new AppleBundle(
        params.copyWithChanges(
            AppleBundleDescription.TYPE,
            BuildTarget.builder(params.getBuildTarget()).addFlavor(BUNDLE_FLAVOR).build(),
            ImmutableSortedSet.of(library),
            ImmutableSortedSet.<BuildRule>of()),
        sourcePathResolver,
        args.extension,
        args.infoPlist,
        library);
    return new AppleTest(
        params.copyWithDeps(
            ImmutableSortedSet.<BuildRule>of(bundle),
            ImmutableSortedSet.<BuildRule>of()),
        sourcePathResolver,
        bundle,
        args.contacts.get(),
        args.labels.get(),
        resolver.getAllRules(args.sourceUnderTest.get()));
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AppleNativeTargetDescriptionArg
      implements HasSourceUnderTest, HasAppleBundleFields {
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<ImmutableSortedSet<BuildTarget>> sourceUnderTest;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<Boolean> canGroup;

    // Bundle related fields.
    public Either<AppleBundleExtension, String> extension;
    public Optional<SourcePath> infoPlist;

    @Override
    public ImmutableSortedSet<BuildTarget> getSourceUnderTest() {
      return sourceUnderTest.get();
    }

    @Override
    public Either<AppleBundleExtension, String> getExtension() {
      return extension;
    }

    @Override
    public Optional<SourcePath> getInfoPlist() {
      return infoPlist;
    }

    public boolean canGroup() {
      return canGroup.or(false);
    }
  }
}
