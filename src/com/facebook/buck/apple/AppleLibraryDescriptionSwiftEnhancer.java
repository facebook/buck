/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftDescriptions;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.swift.SwiftLibraryDescriptionArg;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class AppleLibraryDescriptionSwiftEnhancer {
  @SuppressWarnings("unused")
  public static BuildRule createSwiftCompileRule(
      BuildTarget target,
      CellPathResolver cellRoots,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      BuildRuleParams params,
      CxxLibraryDescription.CommonArg args,
      ProjectFilesystem filesystem,
      CxxPlatform platform,
      AppleCxxPlatform applePlatform,
      SwiftBuckConfig swiftBuckConfig) {

    SourcePathRuleFinder rulePathFinder = new SourcePathRuleFinder(resolver);
    SwiftLibraryDescriptionArg.Builder delegateArgsBuilder = SwiftLibraryDescriptionArg.builder();
    SwiftDescriptions.populateSwiftLibraryDescriptionArg(
        DefaultSourcePathResolver.from(rulePathFinder), delegateArgsBuilder, args, target);
    SwiftLibraryDescriptionArg swiftArgs = delegateArgsBuilder.build();

    Preprocessor preprocessor = platform.getCpp().resolve(resolver);

    CxxLibrary lib = (CxxLibrary) resolver.requireRule(target.withFlavors());
    ImmutableMap<BuildTarget, CxxPreprocessorInput> transitiveMap =
        CxxPreprocessables.computeTransitiveCxxToPreprocessorInputMap(platform, lib, false);

    ImmutableSet.Builder<CxxPreprocessorInput> builder = ImmutableSet.builder();
    builder.addAll(transitiveMap.values());
    builder.add(lib.getPublicCxxPreprocessorInput(platform));

    ImmutableSet<CxxPreprocessorInput> inputs = builder.build();
    ImmutableSet<BuildRule> inputDeps =
        RichStream.from(inputs)
            .flatMap(input -> RichStream.from(input.getDeps(resolver, rulePathFinder)))
            .toImmutableSet();

    ImmutableSortedSet.Builder<BuildRule> sortedDeps = ImmutableSortedSet.naturalOrder();
    sortedDeps.addAll(inputDeps);

    BuildRuleParams paramsWithDeps = params.withExtraDeps(sortedDeps.build());

    PreprocessorFlags.Builder flagsBuilder = PreprocessorFlags.builder();
    inputs.forEach(input -> flagsBuilder.addAllIncludes(input.getIncludes()));
    PreprocessorFlags preprocessorFlags = flagsBuilder.build();

    return SwiftLibraryDescription.createSwiftCompileRule(
        platform,
        applePlatform.getSwiftPlatform().get(),
        swiftBuckConfig,
        target,
        paramsWithDeps,
        resolver,
        cellRoots,
        filesystem,
        swiftArgs,
        preprocessor,
        preprocessorFlags);
  }

  public static BuildTarget createBuildTargetForSwiftCode(BuildTarget target) {
    return target.withFlavors(AppleLibraryDescription.Type.SWIFT_COMPILE.getFlavor());
  }
}
