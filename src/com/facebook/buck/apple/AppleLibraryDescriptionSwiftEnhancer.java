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

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.CxxLibrary;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.HeaderSymlinkTreeWithHeaderMap;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.TransitiveCxxPreprocessorInputCache;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftCompile;
import com.facebook.buck.swift.SwiftDescriptions;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.swift.SwiftLibraryDescriptionArg;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppleLibraryDescriptionSwiftEnhancer {
  @SuppressWarnings("unused")
  public static BuildRule createSwiftCompileRule(
      BuildTarget target,
      CellPathResolver cellRoots,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      BuildRuleParams params,
      AppleNativeTargetDescriptionArg args,
      ProjectFilesystem filesystem,
      CxxPlatform platform,
      AppleCxxPlatform applePlatform,
      SwiftBuckConfig swiftBuckConfig,
      ImmutableSet<CxxPreprocessorInput> inputs) {

    SourcePathRuleFinder rulePathFinder = new SourcePathRuleFinder(resolver);
    SwiftLibraryDescriptionArg.Builder delegateArgsBuilder = SwiftLibraryDescriptionArg.builder();
    SwiftDescriptions.populateSwiftLibraryDescriptionArg(
        DefaultSourcePathResolver.from(rulePathFinder), delegateArgsBuilder, args, target);
    SwiftLibraryDescriptionArg swiftArgs = delegateArgsBuilder.build();

    Preprocessor preprocessor = platform.getCpp().resolve(resolver);

    ImmutableSet<BuildRule> inputDeps =
        RichStream.from(inputs)
            .flatMap(input -> RichStream.from(input.getDeps(resolver, rulePathFinder)))
            .toImmutableSet();

    ImmutableSortedSet.Builder<BuildRule> sortedDeps = ImmutableSortedSet.naturalOrder();
    sortedDeps.addAll(inputDeps);

    BuildRuleParams paramsWithDeps = params.withExtraDeps(sortedDeps.build());

    PreprocessorFlags.Builder flagsBuilder = PreprocessorFlags.builder();
    inputs.forEach(input -> flagsBuilder.addAllIncludes(input.getIncludes()));
    inputs.forEach(input -> flagsBuilder.addAllFrameworkPaths(input.getFrameworks()));
    PreprocessorFlags preprocessorFlags = flagsBuilder.build();

    return SwiftLibraryDescription.createSwiftCompileRule(
        platform,
        applePlatform.getSwiftPlatform().get(),
        swiftBuckConfig,
        target,
        paramsWithDeps,
        resolver,
        rulePathFinder,
        cellRoots,
        filesystem,
        swiftArgs,
        preprocessor,
        preprocessorFlags);
  }

  /**
   * Returns transitive preprocessor inputs excluding those from the swift delegate of the given
   * CxxLibrary.
   */
  public static ImmutableSet<CxxPreprocessorInput> getPreprocessorInputsForAppleLibrary(
      BuildTarget target, BuildRuleResolver resolver, CxxPlatform platform) {
    CxxLibrary lib = (CxxLibrary) resolver.requireRule(target.withFlavors());
    ImmutableMap<BuildTarget, CxxPreprocessorInput> transitiveMap =
        TransitiveCxxPreprocessorInputCache.computeTransitiveCxxToPreprocessorInputMap(
            platform, lib, false, resolver);

    ImmutableSet.Builder<CxxPreprocessorInput> builder = ImmutableSet.builder();
    builder.addAll(transitiveMap.values());
    builder.add(lib.getPublicCxxPreprocessorInputExcludingDelegate(platform, resolver));

    return builder.build();
  }

  public static BuildRule createObjCGeneratedHeaderBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      BuildRuleResolver resolver,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    BuildTarget swiftCompileTarget = createBuildTargetForSwiftCompile(buildTarget, cxxPlatform);
    SwiftCompile compile = (SwiftCompile) resolver.requireRule(swiftCompileTarget);

    Path objCImportPath = getObjCGeneratedHeaderSourceIncludePath(headerVisibility, compile);
    SourcePath objCGeneratedPath = compile.getObjCGeneratedHeaderPath();

    ImmutableMap.Builder<Path, SourcePath> headerLinks = ImmutableMap.builder();
    headerLinks.put(objCImportPath, objCGeneratedPath);

    Path outputPath = BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s");
    HeaderSymlinkTreeWithHeaderMap headerMapRule =
        HeaderSymlinkTreeWithHeaderMap.create(
            buildTarget, projectFilesystem, outputPath, headerLinks.build(), ruleFinder);

    return headerMapRule;
  }

  private static Path getObjCGeneratedHeaderSourceIncludePath(
      HeaderVisibility headerVisibility, SwiftCompile compile) {
    Path publicPath = Paths.get(compile.getModuleName(), compile.getObjCGeneratedHeaderFileName());
    switch (headerVisibility) {
      case PUBLIC:
        return publicPath;
      case PRIVATE:
        return Paths.get(compile.getObjCGeneratedHeaderFileName());
    }

    return publicPath;
  }

  public static BuildTarget createBuildTargetForObjCGeneratedHeaderBuildRule(
      BuildTarget buildTarget, HeaderVisibility headerVisibility, CxxPlatform cxxPlatform) {
    AppleLibraryDescription.Type appleLibType = null;
    switch (headerVisibility) {
      case PUBLIC:
        appleLibType = AppleLibraryDescription.Type.SWIFT_EXPORTED_OBJC_GENERATED_HEADER;
        break;
      case PRIVATE:
        appleLibType = AppleLibraryDescription.Type.SWIFT_OBJC_GENERATED_HEADER;
        break;
    }

    Preconditions.checkNotNull(appleLibType);

    return buildTarget.withFlavors(appleLibType.getFlavor(), cxxPlatform.getFlavor());
  }

  public static BuildTarget createBuildTargetForSwiftCompile(
      BuildTarget target, CxxPlatform cxxPlatform) {
    // `target` is not necessarily flavored with just `apple_library` flavors, that's because
    // Swift compile rules can be required by other rules (e.g., `apple_test`, `apple_binary` etc).
    return target.withFlavors(
        AppleLibraryDescription.Type.SWIFT_COMPILE.getFlavor(), cxxPlatform.getFlavor());
  }
}
