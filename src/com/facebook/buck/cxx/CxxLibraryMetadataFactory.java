/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.CxxLibraryDescription.CommonArg;
import com.facebook.buck.cxx.CxxPreprocessables.IncludeType;
import com.facebook.buck.cxx.CxxPreprocessorInput.Builder;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.google.common.base.Function;
import com.google.common.collect.Multimaps;
import java.util.Map;
import java.util.Optional;

public class CxxLibraryMetadataFactory {
  private final ToolchainProvider toolchainProvider;
  private final ProjectFilesystem projectFilesystem;

  public CxxLibraryMetadataFactory(
      ToolchainProvider toolchainProvider, ProjectFilesystem projectFilesystem) {
    this.toolchainProvider = toolchainProvider;
    this.projectFilesystem = projectFilesystem;
  }

  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      CxxLibraryDescriptionArg args,
      Class<U> metadataClass) {

    Map.Entry<Flavor, CxxLibraryDescription.MetadataType> type =
        CxxLibraryDescription.METADATA_TYPE
            .getFlavorAndValue(buildTarget)
            .orElseThrow(IllegalArgumentException::new);
    BuildTarget baseTarget = buildTarget.withoutFlavors(type.getKey());

    switch (type.getValue()) {
      case CXX_HEADERS:
        {
          Optional<CxxHeaders> symlinkTree = Optional.empty();
          if (!args.getExportedHeaders().isEmpty()) {
            HeaderMode mode = CxxLibraryDescription.HEADER_MODE.getRequiredValue(buildTarget);
            baseTarget = baseTarget.withoutFlavors(mode.getFlavor());
            symlinkTree =
                Optional.of(
                    CxxSymlinkTreeHeaders.from(
                        (HeaderSymlinkTree)
                            graphBuilder.requireRule(
                                baseTarget
                                    .withoutFlavors(CxxLibraryDescription.LIBRARY_TYPE.getFlavors())
                                    .withAppendedFlavors(
                                        CxxLibraryDescription.Type.EXPORTED_HEADERS.getFlavor(),
                                        mode.getFlavor())),
                        CxxPreprocessables.IncludeType.LOCAL));
          }
          return symlinkTree.map(metadataClass::cast);
        }

      case CXX_PREPROCESSOR_INPUT:
        {
          Map.Entry<Flavor, UnresolvedCxxPlatform> platform =
              getCxxPlatformsProvider()
                  .getUnresolvedCxxPlatforms()
                  .getFlavorAndValue(buildTarget)
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              String.format(
                                  "%s: cannot extract platform from target flavors (available platforms: %s)",
                                  buildTarget,
                                  getCxxPlatformsProvider()
                                      .getUnresolvedCxxPlatforms()
                                      .getFlavors())));
          Map.Entry<Flavor, HeaderVisibility> visibility =
              CxxLibraryDescription.HEADER_VISIBILITY
                  .getFlavorAndValue(buildTarget)
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              String.format(
                                  "%s: cannot extract visibility from target flavors (available options: %s)",
                                  buildTarget,
                                  CxxLibraryDescription.HEADER_VISIBILITY.getFlavors())));
          baseTarget = baseTarget.withoutFlavors(platform.getKey(), visibility.getKey());

          CxxPreprocessorInput.Builder cxxPreprocessorInputBuilder = CxxPreprocessorInput.builder();

          // TODO(agallagher): We currently always add exported flags and frameworks to the
          // preprocessor input to mimic existing behavior, but this should likely be fixed.
          CxxPlatform cxxPlatform = platform.getValue().resolve(graphBuilder);
          addCxxPreprocessorInputFromArgs(
              cxxPreprocessorInputBuilder,
              args,
              cxxPlatform,
              f ->
                  CxxDescriptionEnhancer.toStringWithMacrosArgs(
                      buildTarget, cellRoots, graphBuilder, cxxPlatform, f));

          if (visibility.getValue() == HeaderVisibility.PRIVATE) {
            if (!args.getHeaders().isEmpty()) {
              HeaderSymlinkTree symlinkTree =
                  (HeaderSymlinkTree)
                      graphBuilder.requireRule(
                          baseTarget.withAppendedFlavors(
                              platform.getKey(), CxxLibraryDescription.Type.HEADERS.getFlavor()));
              cxxPreprocessorInputBuilder.addIncludes(
                  CxxSymlinkTreeHeaders.from(symlinkTree, CxxPreprocessables.IncludeType.LOCAL));
            }

            for (String privateInclude : args.getIncludeDirectories()) {
              cxxPreprocessorInputBuilder.addIncludes(
                  CxxIncludes.of(
                      IncludeType.LOCAL,
                      PathSourcePath.of(
                          projectFilesystem,
                          buildTarget.getBasePath().resolve(privateInclude).normalize())));
            }
          }

          if (visibility.getValue() == HeaderVisibility.PUBLIC) {

            // Add platform-agnostic headers.
            queryMetadataCxxHeaders(
                    graphBuilder,
                    baseTarget,
                    CxxDescriptionEnhancer.getHeaderModeForPlatform(
                        graphBuilder,
                        buildTarget.getTargetConfiguration(),
                        cxxPlatform,
                        args.getXcodePublicHeadersSymlinks()
                            .orElse(cxxPlatform.getPublicHeadersSymlinksEnabled())))
                .ifPresent(cxxPreprocessorInputBuilder::addIncludes);

            // Add platform-specific headers.
            if (!args.getExportedPlatformHeaders()
                .getMatchingValues(platform.getKey().toString())
                .isEmpty()) {
              HeaderSymlinkTree symlinkTree =
                  (HeaderSymlinkTree)
                      graphBuilder.requireRule(
                          baseTarget
                              .withoutFlavors(CxxLibraryDescription.LIBRARY_TYPE.getFlavors())
                              .withAppendedFlavors(
                                  CxxLibraryDescription.Type.EXPORTED_HEADERS.getFlavor(),
                                  platform.getKey()));
              cxxPreprocessorInputBuilder.addIncludes(
                  CxxSymlinkTreeHeaders.from(symlinkTree, CxxPreprocessables.IncludeType.LOCAL));
            }

            if (!args.getRawHeaders().isEmpty()) {
              cxxPreprocessorInputBuilder.addIncludes(CxxRawHeaders.of(args.getRawHeaders()));
            }

            for (String publicInclude : args.getPublicIncludeDirectories()) {
              cxxPreprocessorInputBuilder.addIncludes(
                  CxxIncludes.of(
                      IncludeType.LOCAL,
                      PathSourcePath.of(
                          projectFilesystem,
                          buildTarget.getBasePath().resolve(publicInclude).normalize())));
            }

            for (String publicSystemInclude : args.getPublicSystemIncludeDirectories()) {
              cxxPreprocessorInputBuilder.addIncludes(
                  CxxIncludes.of(
                      IncludeType.SYSTEM,
                      PathSourcePath.of(
                          projectFilesystem,
                          buildTarget.getBasePath().resolve(publicSystemInclude).normalize())));
            }
          }

          CxxPreprocessorInput cxxPreprocessorInput = cxxPreprocessorInputBuilder.build();
          return Optional.of(cxxPreprocessorInput).map(metadataClass::cast);
        }
    }

    throw new IllegalStateException(String.format("unhandled metadata type: %s", type.getValue()));
  }

  public static void addCxxPreprocessorInputFromArgs(
      Builder cxxPreprocessorInputBuilder,
      CommonArg args,
      CxxPlatform platform,
      Function<StringWithMacros, Arg> stringWithMacrosArgFunction) {
    cxxPreprocessorInputBuilder.putAllPreprocessorFlags(
        Multimaps.transformValues(
            CxxFlags.getLanguageFlagsWithMacros(
                args.getExportedPreprocessorFlags(),
                args.getExportedPlatformPreprocessorFlags(),
                args.getExportedLangPreprocessorFlags(),
                args.getExportedLangPlatformPreprocessorFlags(),
                platform),
            stringWithMacrosArgFunction));
    cxxPreprocessorInputBuilder.addAllFrameworks(args.getFrameworks());
  }

  /**
   * Convenience function to query the {@link CxxHeaders} metadata of a target.
   *
   * <p>Use this function instead of constructing the BuildTarget manually.
   */
  private static Optional<CxxHeaders> queryMetadataCxxHeaders(
      ActionGraphBuilder graphBuilder, BuildTarget baseTarget, HeaderMode mode) {
    return graphBuilder.requireMetadata(
        baseTarget.withAppendedFlavors(
            CxxLibraryDescription.MetadataType.CXX_HEADERS.getFlavor(), mode.getFlavor()),
        CxxHeaders.class);
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }
}
