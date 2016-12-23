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
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

public class ReactNativeLibraryGraphEnhancer {

  private static final Flavor REACT_NATIVE_BUNDLE_FLAVOR = ImmutableFlavor.of("bundle");
  private static final Flavor REACT_NATIVE_ANDROID_RES_FLAVOR = ImmutableFlavor.of("android_res");

  private final ReactNativeBuckConfig buckConfig;

  public ReactNativeLibraryGraphEnhancer(ReactNativeBuckConfig buckConfig) {
    this.buckConfig = buckConfig;
  }

  private ReactNativeBundle createReactNativeBundle(
      BuildRuleParams baseParams,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      BuildTarget target,
      ReactNativeLibraryArgs args,
      ReactNativePlatform platform) {
    Tool jsPackager = buckConfig.getPackager(resolver);
    return new ReactNativeBundle(
        baseParams.copyWithChanges(
            target,
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(ruleFinder.filterBuildRuleInputs(args.entryPath))
                    .addAll(ruleFinder.filterBuildRuleInputs(args.srcs))
                    .addAll(jsPackager.getDeps(ruleFinder))
                    .build()),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        pathResolver,
        args.entryPath,
        args.srcs,
        ReactNativeFlavors.useUnbundling(baseParams.getBuildTarget()),
        ReactNativeFlavors.useIndexedUnbundling(baseParams.getBuildTarget()),
        ReactNativeFlavors.isDevMode(baseParams.getBuildTarget()),
        ReactNativeFlavors.exposeSourceMap(baseParams.getBuildTarget()),
        args.bundleName,
        args.packagerFlags,
        jsPackager,
        platform);
  }

  public AndroidReactNativeLibrary enhanceForAndroid(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      AndroidReactNativeLibraryDescription.Args args) {

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    BuildTarget originalBuildTarget = params.getBuildTarget();
    ReactNativeBundle bundle =
        createReactNativeBundle(
            params,
            resolver,
            sourcePathResolver,
            ruleFinder,
            BuildTarget.builder(originalBuildTarget)
                .addFlavors(REACT_NATIVE_BUNDLE_FLAVOR)
                .build(),
            args,
            ReactNativePlatform.ANDROID);
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
                      ImmutableSortedSet.of(bundle)));

      SourcePath resources = new BuildTargetSourcePath(
          bundle.getBuildTarget(),
          bundle.getResources());
      BuildRule resource = new AndroidResource(
          paramsForResource,
          sourcePathResolver,
          ruleFinder,
          /* deps */ ImmutableSortedSet.of(),
          resources,
          /* resSrcs */ ImmutableSortedSet.of(),
          Optional.of(resources),
          args.rDotJavaPackage.get(),
          /* assets */ null,
          /* assetsSrcs */ ImmutableSortedSet.of(),
          Optional.empty(),
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
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    return createReactNativeBundle(
        params,
        resolver,
        new SourcePathResolver(ruleFinder),
        ruleFinder,
        params.getBuildTarget(),
        args,
        ReactNativePlatform.IOS);
  }
}
