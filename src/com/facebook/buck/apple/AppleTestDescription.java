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
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
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
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.util.Set;

public class AppleTestDescription implements Description<AppleTestDescription.Arg>, Flavored {

  public static final BuildRuleType TYPE = BuildRuleType.of("apple_test");

  /**
   * Flavors for the additional generated build rules.
   */
  private static final Flavor LIBRARY_FLAVOR = ImmutableFlavor.of("apple-test-library");
  private static final Flavor BUNDLE_FLAVOR = ImmutableFlavor.of("apple-test-bundle");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      LIBRARY_FLAVOR, BUNDLE_FLAVOR);

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = Predicates.in(SUPPORTED_FLAVORS);

  private static final Set<Flavor> NON_LIBRARY_FLAVORS = ImmutableSet.of(
      CompilationDatabase.COMPILATION_DATABASE,
      AppleDescriptions.HEADERS,
      CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
      CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR);

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
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR) ||
        appleLibraryDescription.hasFlavors(flavors);
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    boolean createBundle = Sets.intersection(
        params.getBuildTarget().getFlavors(),
        NON_LIBRARY_FLAVORS).isEmpty();
    Set<Flavor> extraFlavors = ImmutableSet.of();
    if (createBundle) {
      extraFlavors = ImmutableSet.of(
          LIBRARY_FLAVOR,
          CxxDescriptionEnhancer.SHARED_FLAVOR);
    }
    BuildRule library = appleLibraryDescription.createBuildRule(
        params.copyWithChanges(
            AppleLibraryDescription.TYPE,
            BuildTarget.builder(params.getBuildTarget())
                .addAllFlavors(extraFlavors)
                .build(),
            Suppliers.ofInstance(params.getDeclaredDeps()),
            Suppliers.ofInstance(params.getExtraDeps())),
        resolver,
        args);
    if (!createBundle) {
      return library;
    }
    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);
    AppleBundle bundle = new AppleBundle(
        params.copyWithChanges(
            AppleBundleDescription.TYPE,
            BuildTarget.builder(params.getBuildTarget()).addFlavors(BUNDLE_FLAVOR).build(),
            // We have to add back the original deps here, since they're likely
            // stripped from the library link above (it doesn't actually depend on them).
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .add(library)
                    .addAll(params.getDeclaredDeps())
                    .build()),
            Suppliers.ofInstance(params.getExtraDeps())),
        sourcePathResolver,
        args.extension,
        args.infoPlist,
        library);
    return new AppleTest(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(bundle)),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        sourcePathResolver,
        bundle,
        args.contacts.get(),
        args.labels.get(),
        ImmutableSet.<BuildRule>of());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AppleNativeTargetDescriptionArg implements HasAppleBundleFields {
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<Boolean> canGroup;

    // Bundle related fields.
    public Either<AppleBundleExtension, String> extension;
    public Optional<SourcePath> infoPlist;
    public Optional<String> xcodeProductType;

    @Override
    public Either<AppleBundleExtension, String> getExtension() {
      return extension;
    }

    @Override
    public Optional<SourcePath> getInfoPlist() {
      return infoPlist;
    }

    @Override
    public Optional<String> getXcodeProductType() {
      return xcodeProductType;
    }

    public boolean canGroup() {
      return canGroup.or(false);
    }
  }
}
