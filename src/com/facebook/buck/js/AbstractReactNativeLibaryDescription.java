/*
 * Copyright 2015-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.js;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public abstract class AbstractReactNativeLibaryDescription
    implements Description<AbstractReactNativeLibaryDescription.Arg>, Flavored {

  public enum Platform {
    ANDROID("android"),
    IOS("ios"),
    ;

    private final String name;

    Platform(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  public static final Flavor DEV = ImmutableFlavor.of("dev");

  private static final Flavor REACT_NATIVE_DEPS_FLAVOR = ImmutableFlavor.of("rn_deps");

  private final ReactNativeBuckConfig buckConfig;
  private final Platform platform;

  public AbstractReactNativeLibaryDescription(
      ReactNativeBuckConfig buckConfig,
      Platform platform) {
    this.buckConfig = buckConfig;
    this.platform = platform;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  protected SourcePath getPackager() {
    Optional<SourcePath> packager = buckConfig.getPackager();
    if (!packager.isPresent()) {
      throw new HumanReadableException("In order to use a 'react_native_library' rule, please " +
          "specify 'packager' in .buckconfig under the 'react-native' section.");
    }
    return packager.get();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    BuildTarget originalBuildTarget = params.getBuildTarget();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);

    boolean devMode = originalBuildTarget.getFlavors().contains(DEV);

    BuildTarget depsFinderTarget = BuildTarget.builder(originalBuildTarget)
        .addFlavors(REACT_NATIVE_DEPS_FLAVOR)
        .build();
    BuildRuleParams paramsForDepsFinder = params.copyWithBuildTarget(depsFinderTarget);
    ReactNativeDeps depsFinder = new ReactNativeDeps(
        paramsForDepsFinder,
        sourcePathResolver,
        getPackager(),
        args.srcs.get(),
        args.entryPath,
        platform);
    resolver.addToIndex(depsFinder);

    return new ReactNativeLibrary(
        params.copyWithExtraDeps(
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(depsFinder))),
        new SourcePathResolver(resolver),
        args.entryPath,
        devMode,
        args.bundleName,
        getPackager(),
        platform,
        depsFinder);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return flavors.isEmpty() || flavors.equals(ImmutableSet.of(DEV));
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public SourcePath entryPath;
    public String bundleName;

    public Optional<ImmutableSortedSet<BuildTarget>> deps;
  }
}
