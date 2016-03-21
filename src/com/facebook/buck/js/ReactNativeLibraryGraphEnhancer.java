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
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Collections;

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
    BuildRuleParams paramsForDepsFinder =
        params.copyWithBuildTarget(depsFinderTarget).appendExtraDeps(
            resolver.getAllRules(
                SourcePaths.filterBuildTargetSourcePaths(
                    Collections.singleton(buckConfig.getPackager()))));
    ReactNativeDeps depsFinder = new ReactNativeDeps(
        paramsForDepsFinder,
        sourcePathResolver,
        buckConfig.getPackager(),
        args.srcs.get(),
        args.entryPath,
        platform,
        args.packagerFlags,
        buckConfig.useWorker());
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
    BuildRuleParams paramsForBundle = params
        .copyWithBuildTarget(
            BuildTarget.builder(originalBuildTarget)
                .addFlavors(REACT_NATIVE_BUNDLE_FLAVOR)
                .build())
        .appendExtraDeps(
            ImmutableList.<BuildRule>builder()
                .add(reactNativeDeps)
                .addAll(
                    resolver.getAllRules(
                        SourcePaths.filterBuildTargetSourcePaths(
                            Collections.singleton(buckConfig.getPackager()))))
                .build());
    ReactNativeBundle bundle = new ReactNativeBundle(
        paramsForBundle,
        sourcePathResolver,
        args.entryPath,
        ReactNativeFlavors.useUnbundling(originalBuildTarget),
        ReactNativeFlavors.isDevMode(originalBuildTarget),
        args.bundleName,
        args.packagerFlags,
        buckConfig.getPackager(),
        ReactNativePlatform.ANDROID,
        reactNativeDeps,
        buckConfig.useWorker());
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

      SourcePath resources = new BuildTargetSourcePath(
          bundle.getBuildTarget(),
          bundle.getResources());
      BuildRule resource = new AndroidResource(
          paramsForResource,
          sourcePathResolver,
          /* deps */ ImmutableSortedSet.<BuildRule>of(),
          resources,
          /* resSrcs */ ImmutableSortedSet.<SourcePath>of(),
          Optional.of(resources),
          args.rDotJavaPackage.get(),
          /* assets */ null,
          /* assetsSrcs */ ImmutableSortedSet.<SourcePath>of(),
          Optional.<SourcePath>absent(),
          /* manifest */ null,
          /* hasWhitelistedStrings */ false);
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
        params.appendExtraDeps(
            ImmutableList.<BuildRule>builder()
                .add(reactNativeDeps)
                .addAll(
                    resolver.getAllRules(
                        SourcePaths.filterBuildTargetSourcePaths(
                            Collections.singleton(buckConfig.getPackager()))))
                .build()),
        new SourcePathResolver(resolver),
        args.entryPath,
        ReactNativeFlavors.useUnbundling(params.getBuildTarget()),
        ReactNativeFlavors.isDevMode(params.getBuildTarget()),
        args.bundleName,
        args.packagerFlags,
        buckConfig.getPackager(),
        ReactNativePlatform.IOS,
        reactNativeDeps,
        buckConfig.useWorker());
  }
}
