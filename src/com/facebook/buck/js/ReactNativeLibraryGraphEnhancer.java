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

import com.facebook.buck.android.AndroidResource;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class ReactNativeLibraryGraphEnhancer {

  private static final Flavor REACT_NATIVE_DEPS_FLAVOR = ImmutableFlavor.of("rn_deps");
  private static final Flavor REACT_NATIVE_BUNDLE_FLAVOR = ImmutableFlavor.of("bundle");
  private static final Flavor REACT_NATIVE_ANDROID_RES_FLAVOR = ImmutableFlavor.of("android_res");

  private final ReactNativeBuckConfig buckConfig;

  public ReactNativeLibraryGraphEnhancer(ReactNativeBuckConfig buckConfig) {
    this.buckConfig = buckConfig;
  }

  private ReactNativeDeps createReactNativeDeps(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ReactNativeLibraryArgs args,
      ReactNativePlatform platform) {
    BuildTarget originalBuildTarget = params.getBuildTarget();
    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);


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
    return resolver.addToIndex(depsFinder);
  }

  public AndroidReactNativeLibrary enhanceForAndroid(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      AndroidReactNativeLibraryDescription.Args args) {
    final ReactNativeDeps reactNativeDeps =
        createReactNativeDeps(params, resolver, args, ReactNativePlatform.ANDROID);

    SourcePathResolver sourcePathResolver = new SourcePathResolver(resolver);

    BuildTarget originalBuildTarget = params.getBuildTarget();
    BuildRuleParams paramsForBundle =
        params.copyWithBuildTarget(
            BuildTarget.builder(originalBuildTarget)
                .addFlavors(REACT_NATIVE_BUNDLE_FLAVOR)
                .build())
            .copyWithExtraDeps(
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of(reactNativeDeps)));
    ReactNativeBundle bundle = new ReactNativeBundle(
        paramsForBundle,
        sourcePathResolver,
        args.entryPath,
        ReactNativeFlavors.isDevMode(originalBuildTarget),
        args.bundleName,
        getPackager(),
        ReactNativePlatform.ANDROID,
        reactNativeDeps);
    resolver.addToIndex(bundle);

    ImmutableList.Builder<BuildRule> extraDeps = ImmutableList.builder();
    extraDeps.add(bundle);
    if (args.rDotJavaPackage.isPresent()) {
      BuildRuleParams paramsForResource =
          params.copyWithBuildTarget(
              BuildTarget.builder(originalBuildTarget)
                  .addFlavors(REACT_NATIVE_ANDROID_RES_FLAVOR)
                  .build())
              .copyWithExtraDeps(Suppliers.ofInstance(
                      ImmutableSortedSet.<BuildRule>of(bundle, reactNativeDeps)));

      BuildRule resource = new AndroidResource(
              paramsForResource,
              sourcePathResolver,
              /* deps */ ImmutableSortedSet.<BuildRule>of(),
              new PathSourcePath(params.getProjectFilesystem(), bundle.getResources()),
              /* resSrcs */ ImmutableSortedSet.<Path>of(),
              args.rDotJavaPackage.get(),
              /* assets */ null,
              /* assetsSrcs */ ImmutableSortedSet.<Path>of(),
              /* manifest */ null,
              /* hasWhitelistedStrings */ false,
              Optional.of(
                  Suppliers.memoize(
                      new Supplier<Sha1HashCode>() {
                        @Override
                        public Sha1HashCode get() {
                          return reactNativeDeps.getInputsHash();
                        }
                      })));
      resolver.addToIndex(resource);
      extraDeps.add(resource);
    }

    return new AndroidReactNativeLibrary(
        params.appendExtraDeps(extraDeps.build()),
        sourcePathResolver,
        bundle);
  }

  public ReactNativeBundle enhanceForIos(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      ReactNativeLibraryArgs args) {
    ReactNativeDeps reactNativeDeps =
        createReactNativeDeps(params, resolver, args, ReactNativePlatform.IOS);

    return new ReactNativeBundle(
        params.appendExtraDeps(ImmutableList.of((BuildRule) reactNativeDeps)),
        new SourcePathResolver(resolver),
        args.entryPath,
        ReactNativeFlavors.isDevMode(params.getBuildTarget()),
        args.bundleName,
        getPackager(),
        ReactNativePlatform.IOS,
        reactNativeDeps);
  }

  private SourcePath getPackager() {
    Optional<SourcePath> packager = buckConfig.getPackager();
    if (!packager.isPresent()) {
      throw new HumanReadableException("In order to use a 'react_native_library' rule, please " +
          "specify 'packager' in .buckconfig under the 'react-native' section.");
    }
    return packager.get();
  }
}
