/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple;

import com.facebook.buck.apple.common.AppleCompilerTargetTriple;
import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.apple.toolchain.AppleSdk;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.cxx.CxxLibraryGroup;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxToolFlags;
import com.facebook.buck.cxx.ExplicitCxxToolFlags;
import com.facebook.buck.cxx.HeaderSymlinkTreeWithHeaderMap;
import com.facebook.buck.cxx.PreprocessorFlags;
import com.facebook.buck.cxx.TransitiveCxxPreprocessorInputCache;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftCompile;
import com.facebook.buck.swift.SwiftDescriptions;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.swift.SwiftLibraryDescriptionArg;
import com.facebook.buck.swift.toolchain.SwiftPlatform;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;

public class AppleLibraryDescriptionSwiftEnhancer {
  public static BuildRule createSwiftCompileRule(
      BuildTarget target,
      CellPathResolver cellRoots,
      ActionGraphBuilder graphBuilder,
      AppleNativeTargetDescriptionArg args,
      ProjectFilesystem filesystem,
      CxxPlatform platform,
      AppleCxxPlatform applePlatform,
      SwiftBuckConfig swiftBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      ImmutableSet<CxxPreprocessorInput> inputs) {

    SwiftLibraryDescriptionArg swiftArgs =
        getSwiftArgs(target, graphBuilder, args, swiftBuckConfig);

    Preprocessor preprocessor = getPreprocessor(target, graphBuilder, platform);

    PreprocessorFlags preprocessorFlags = getPreprocessorFlags(inputs, applePlatform.getAppleSdk());

    Optional<CxxPreprocessorInput> underlyingModule =
        getUnderlyingModulePreprocessorInput(target, graphBuilder, platform);

    SwiftPlatform swiftPlatform = applePlatform.getSwiftPlatform().get();

    return SwiftLibraryDescription.createSwiftCompileRule(
        platform,
        swiftPlatform,
        swiftBuckConfig,
        cxxBuckConfig,
        downwardApiConfig,
        target,
        graphBuilder,
        cellRoots,
        filesystem,
        swiftArgs,
        preprocessor,
        preprocessorFlags,
        underlyingModule.isPresent(),
        getSwiftTargetTruple(args, swiftPlatform),
        applePlatform.getAppleSdk());
  }

  private static Optional<AppleCompilerTargetTriple> getSwiftTargetTruple(
      AppleNativeTargetDescriptionArg args, SwiftPlatform swiftPlatform) {
    return args.getTargetSdkVersion()
        .map(version -> swiftPlatform.getSwiftTarget().withTargetSdkVersion(version));
  }

  private static SwiftLibraryDescriptionArg getSwiftArgs(
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      AppleNativeTargetDescriptionArg args,
      SwiftBuckConfig swiftBuckConfig) {
    SwiftLibraryDescriptionArg.Builder delegateArgsBuilder = SwiftLibraryDescriptionArg.builder();
    SwiftDescriptions.populateSwiftLibraryDescriptionArg(
        swiftBuckConfig, graphBuilder.getSourcePathResolver(), delegateArgsBuilder, args, target);
    delegateArgsBuilder.setTargetSdkVersion(args.getTargetSdkVersion());
    return delegateArgsBuilder.build();
  }

  private static Preprocessor getPreprocessor(
      BuildTarget target, ActionGraphBuilder graphBuilder, CxxPlatform platform) {
    return platform.getCpp().resolve(graphBuilder, target.getTargetConfiguration());
  }

  private static PreprocessorFlags getPreprocessorFlags(
      ImmutableSet<CxxPreprocessorInput> inputs, AppleSdk appleSdk) {
    PreprocessorFlags.Builder flagsBuilder = PreprocessorFlags.builder();
    ExplicitCxxToolFlags.Builder explicitCxxToolFlags = CxxToolFlags.explicitBuilder();

    for (String headerSearchPath : appleSdk.getAdditionalSystemHeaderSearchPaths()) {
      explicitCxxToolFlags.addPlatformFlags(
          StringArg.of("-isystem"), StringArg.of(headerSearchPath));
    }

    inputs.forEach(input -> flagsBuilder.addAllIncludes(input.getIncludes()));
    inputs.forEach(input -> flagsBuilder.addAllFrameworkPaths(input.getFrameworks()));
    inputs.forEach(
        input -> {
          explicitCxxToolFlags.addAllRuleFlags(
              input.getPreprocessorFlags().get(CxxSource.Type.SWIFT));
        });
    flagsBuilder.setOtherFlags(explicitCxxToolFlags.build());
    return flagsBuilder.build();
  }

  private static Optional<CxxPreprocessorInput> getUnderlyingModulePreprocessorInput(
      BuildTarget target, ActionGraphBuilder graphBuilder, CxxPlatform platform) {
    return AppleLibraryDescription.underlyingModuleCxxPreprocessorInput(
        target, graphBuilder, platform);
  }

  /** Forwards to {@link SwiftLibraryDescription} to create a Swift command rule. */
  public static BuildRule createSwiftCompilationDatabaseRule(
      BuildTarget target,
      CellPathResolver cellRoots,
      ActionGraphBuilder graphBuilder,
      AppleNativeTargetDescriptionArg args,
      ProjectFilesystem filesystem,
      CxxPlatform platform,
      AppleCxxPlatform applePlatform,
      SwiftBuckConfig swiftBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig,
      ImmutableSet<CxxPreprocessorInput> inputs) {

    Optional<CxxPreprocessorInput> underlyingModule =
        getUnderlyingModulePreprocessorInput(target, graphBuilder, platform);
    SwiftPlatform swiftPlatform = applePlatform.getSwiftPlatform().get();

    return SwiftLibraryDescription.createSwiftCompilationDatabaseRule(
        applePlatform,
        platform,
        swiftPlatform,
        swiftBuckConfig,
        cxxBuckConfig,
        downwardApiConfig,
        target,
        graphBuilder,
        cellRoots,
        filesystem,
        getSwiftArgs(target, graphBuilder, args, swiftBuckConfig),
        getPreprocessor(target, graphBuilder, platform),
        getPreprocessorFlags(inputs, applePlatform.getAppleSdk()),
        underlyingModule.isPresent(),
        getSwiftTargetTruple(args, swiftPlatform));
  }

  /**
   * Returns transitive preprocessor inputs excluding those from the swift delegate of the given
   * CxxLibrary.
   */
  public static ImmutableSet<CxxPreprocessorInput> getPreprocessorInputsForAppleLibrary(
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      CxxPlatform platform,
      AppleNativeTargetDescriptionArg arg) {
    CxxLibraryGroup lib = (CxxLibraryGroup) graphBuilder.requireRule(target.withFlavors());
    ImmutableMap<BuildTarget, CxxPreprocessorInput> transitiveMap =
        TransitiveCxxPreprocessorInputCache.computeTransitiveCxxToPreprocessorInputMap(
            platform, lib, false, graphBuilder);

    ImmutableSet.Builder<CxxPreprocessorInput> builder = ImmutableSet.builder();
    builder.addAll(transitiveMap.values());

    if (arg.isModular()) {
      Optional<CxxPreprocessorInput> underlyingModule =
          getUnderlyingModulePreprocessorInput(target, graphBuilder, platform);
      underlyingModule.ifPresent(builder::add);
    } else {
      builder.add(lib.getPublicCxxPreprocessorInputExcludingDelegate(platform, graphBuilder));
    }

    return builder.build();
  }

  public static BuildRule createObjCGeneratedHeaderBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    ImmutableMap<Path, SourcePath> headers =
        getObjCGeneratedHeader(buildTarget, graphBuilder, cxxPlatform, headerVisibility);

    RelPath outputPath =
        BuildTargetPaths.getGenPath(projectFilesystem.getBuckPaths(), buildTarget, "%s");

    return HeaderSymlinkTreeWithHeaderMap.create(
        buildTarget, projectFilesystem, outputPath.getPath(), headers);
  }

  public static ImmutableMap<Path, SourcePath> getObjCGeneratedHeader(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      HeaderVisibility headerVisibility) {
    BuildTarget swiftCompileTarget = createBuildTargetForSwiftCompile(buildTarget, cxxPlatform);
    SwiftCompile compile = (SwiftCompile) graphBuilder.requireRule(swiftCompileTarget);

    Path objCImportPath = getObjCGeneratedHeaderSourceIncludePath(headerVisibility, compile);
    SourcePath objCGeneratedPath = compile.getObjCGeneratedHeaderPath();

    ImmutableMap.Builder<Path, SourcePath> headerLinks = ImmutableMap.builder();
    headerLinks.put(objCImportPath, objCGeneratedPath);
    return headerLinks.build();
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

    Objects.requireNonNull(appleLibType);

    return buildTarget.withFlavors(appleLibType.getFlavor(), cxxPlatform.getFlavor());
  }

  public static BuildTarget createBuildTargetForSwiftCompile(
      BuildTarget target, CxxPlatform cxxPlatform) {
    // `target` is not necessarily flavored with just `apple_library` flavors, that's because
    // Swift compile rules can be required by other rules (e.g., `apple_test`, `apple_binary` etc).
    return target.withFlavors(
        AppleLibraryDescription.Type.SWIFT_COMPILE.getFlavor(), cxxPlatform.getFlavor());
  }

  public static BuildTarget createBuildTargetForSwiftCommand(
      BuildTarget target, CxxPlatform cxxPlatform) {
    return target.withFlavors(
        AppleLibraryDescription.Type.SWIFT_COMMAND.getFlavor(), cxxPlatform.getFlavor());
  }

  /** Creates a target triple to be used to compile Swift code. */
  public static AppleCompilerTargetTriple createSwiftTargetTriple(
      String architecture, AppleSdk appleSdk, String targetSdkVersion) {
    String fallbackPlatformName =
        appleSdk.getApplePlatform().getSwiftName().orElse(appleSdk.getApplePlatform().getName());
    return AppleCompilerTargetTriple.of(
        architecture,
        appleSdk.getTargetTripleVendor().orElse("apple"),
        appleSdk.getTargetTriplePlatformName().orElse(fallbackPlatformName),
        Optional.of(targetSdkVersion),
        appleSdk.getTargetTripleEnvironment());
  }
}
