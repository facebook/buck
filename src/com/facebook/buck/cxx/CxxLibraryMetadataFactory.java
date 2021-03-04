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

package com.facebook.buck.cxx;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.CxxLibraryDescription.CommonArg;
import com.facebook.buck.cxx.CxxPreprocessables.IncludeType;
import com.facebook.buck.cxx.CxxPreprocessorInput.Builder;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.HeadersAsRawHeadersMode;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.StripStyle;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.CompositeArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.macros.AbstractMacroExpanderWithoutPrecomputedWork;
import com.facebook.buck.rules.macros.CxxHeaderTreeMacro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.types.Either;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimaps;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

public class CxxLibraryMetadataFactory {

  private static final FlavorDomain<PicType> PIC_TYPE_FLAVOR_DOMAIN =
      FlavorDomain.from("C++ Pic Type", PicType.class);

  private final ToolchainProvider toolchainProvider;
  private final ProjectFilesystem projectFilesystem;
  private final CxxBuckConfig cxxBuckConfig;
  private final DownwardApiConfig downwardApiConfig;

  public CxxLibraryMetadataFactory(
      ToolchainProvider toolchainProvider,
      ProjectFilesystem projectFilesystem,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.projectFilesystem = projectFilesystem;
    this.cxxBuckConfig = cxxBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
  }

  private CxxPlatform extractPlatform(ActionGraphBuilder graphBuilder, BuildTarget buildTarget) {
    Map.Entry<Flavor, UnresolvedCxxPlatform> platform =
        getCxxPlatformsProvider(buildTarget.getTargetConfiguration())
            .getUnresolvedCxxPlatforms()
            .getFlavorAndValue(buildTarget)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "%s: cannot extract platform from target flavors (available platforms: %s)",
                            buildTarget,
                            getCxxPlatformsProvider(buildTarget.getTargetConfiguration())
                                .getUnresolvedCxxPlatforms()
                                .getFlavors())));
    return platform.getValue().resolve(graphBuilder, buildTarget.getTargetConfiguration());
  }

  /**
   * A wrapper around the metadata API for getting C++ object files, which is backed by and caches
   * calls to {@link CxxLibraryFactory#requireObjects}.
   *
   * @return load and create object file rules through the metadata interface.
   */
  public static ImmutableList<SourcePath> requireObjects(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      PicType picType,
      Optional<StripStyle> stripStyle) {
    // Create rules for compiling the object files.
    BuildTarget objectsTarget =
        buildTarget.withAppendedFlavors(
            cxxPlatform.getFlavor(),
            CxxLibraryDescription.MetadataType.OBJECTS.getFlavor(),
            picType.getFlavor());
    if (stripStyle.isPresent()) {
      objectsTarget = objectsTarget.withAppendedFlavors(stripStyle.get().getFlavor());
    }
    @SuppressWarnings("unchecked")
    ImmutableList<SourcePath> objectsFromMetadata =
        graphBuilder
            .requireMetadata(objectsTarget, ImmutableList.class)
            .orElseThrow(IllegalStateException::new);
    return objectsFromMetadata;
  }

  @VisibleForTesting
  static class AsRawHeadersResult {
    final ImmutableSortedSet<SourcePath> headers;
    final ImmutableSortedSet<PathSourcePath> includeRoots;

    public AsRawHeadersResult(
        ImmutableSortedSet<SourcePath> headers, ImmutableSortedSet<PathSourcePath> includeRoots) {
      this.headers = headers;
      this.includeRoots = includeRoots;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      AsRawHeadersResult that = (AsRawHeadersResult) o;
      return Objects.equals(headers, that.headers)
          && Objects.equals(includeRoots, that.includeRoots);
    }

    @Override
    public int hashCode() {
      return Objects.hash(headers, includeRoots);
    }
  }

  @VisibleForTesting
  static class AsRawHeadersError {
    final RelPath header;
    final SourcePath path;

    public AsRawHeadersError(RelPath header, SourcePath path) {
      this.header = header;
      this.path = path;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      AsRawHeadersError that = (AsRawHeadersError) o;
      return Objects.equals(header, that.header) && Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
      return Objects.hash(header, path);
    }
  }

  /** @return {@code headers} converted to raw headers, or an error if this wasn't possible. */
  @VisibleForTesting
  protected static Either<AsRawHeadersError, AsRawHeadersResult> asRawHeaders(
      ProjectFilesystem filesystem,
      ActionGraphBuilder graphBuilder,
      ImmutableMap<Path, SourcePath> headers) {

    ImmutableSortedSet.Builder<PathSourcePath> includeRoots = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet.Builder<SourcePath> rawHeaders = ImmutableSortedSet.naturalOrder();
    for (Map.Entry<Path, SourcePath> ent : headers.entrySet()) {
      RelPath dst = RelPath.of(ent.getKey());
      // Currently we don't supported generated headers in raw headers.
      if (!(ent.getValue() instanceof PathSourcePath)) {
        return Either.ofLeft(new AsRawHeadersError(dst, ent.getValue()));
      }
      PathSourcePath val = (PathSourcePath) ent.getValue();
      RelPath src = graphBuilder.getSourcePathResolver().getRelativePath(filesystem, val);
      Optional<RelPath> prefix = MorePaths.stripSuffix(src, dst);
      // If the exported path/name of header isn't a suffix of the in-source header, then we cant'
      // use it as a raw header.
      if (!prefix.isPresent()) {
        return Either.ofLeft(new AsRawHeadersError(dst, val));
      }
      includeRoots.add(PathSourcePath.of(filesystem, prefix.get().getPath()));
      rawHeaders.add(val);
    }

    return Either.ofRight(new AsRawHeadersResult(rawHeaders.build(), includeRoots.build()));
  }

  private void addHeaders(
      CxxPreprocessorInput.Builder cxxPreprocessorInputBuilder,
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args) {

    HeadersAsRawHeadersMode mode =
        cxxPlatform
            .getHeadersAsRawHeadersMode()
            .map(args.getHeadersAsRawHeadersMode()::orElse)
            .orElse(HeadersAsRawHeadersMode.DISABLED);
    switch (mode) {
      case REQUIRED:
      case PREFERRED:
        {
          Either<AsRawHeadersError, AsRawHeadersResult> result =
              asRawHeaders(
                  projectFilesystem,
                  graphBuilder,
                  CxxDescriptionEnhancer.parseHeaders(
                      target, graphBuilder, projectFilesystem, Optional.of(cxxPlatform), args));
          if (result.isRight()) {
            cxxPreprocessorInputBuilder.addIncludes(CxxRawHeaders.of(result.getRight().headers));
            result
                .getRight()
                .includeRoots
                .forEach(
                    includeRoot ->
                        cxxPreprocessorInputBuilder.addIncludes(
                            ImmutableCxxIncludes.ofImpl(IncludeType.LOCAL, includeRoot)));
            break;
          } else if (mode == HeadersAsRawHeadersMode.REQUIRED) {
            throw new HumanReadableException(
                "%s: cannot convert %s (mapped to %s) to raw header",
                target, result.getLeft().path, result.getLeft().header);
          }
        }
        // $FALL-THROUGH$
      case DISABLED:
        {
          if (!args.getHeaders().isEmpty()) {
            HeaderSymlinkTree symlinkTree =
                (HeaderSymlinkTree)
                    graphBuilder.requireRule(
                        target.withAppendedFlavors(
                            cxxPlatform.getFlavor(),
                            CxxLibraryDescription.Type.HEADERS.getFlavor()));
            cxxPreprocessorInputBuilder.addIncludes(
                CxxSymlinkTreeHeaders.from(symlinkTree, CxxPreprocessables.IncludeType.LOCAL));
          }
          break;
        }
    }
  }

  private void addExportedHeaders(
      CxxPreprocessorInput.Builder cxxPreprocessorInputBuilder,
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      CxxPlatform cxxPlatform,
      CxxLibraryDescriptionArg args) {

    HeadersAsRawHeadersMode mode =
        cxxPlatform
            .getHeadersAsRawHeadersMode()
            .map(args.getHeadersAsRawHeadersMode()::orElse)
            .orElse(HeadersAsRawHeadersMode.DISABLED);
    switch (mode) {
      case REQUIRED:
      case PREFERRED:
        {
          Either<AsRawHeadersError, AsRawHeadersResult> result =
              asRawHeaders(
                  projectFilesystem,
                  graphBuilder,
                  CxxDescriptionEnhancer.parseExportedHeaders(
                      target, graphBuilder, projectFilesystem, Optional.of(cxxPlatform), args));
          if (result.isRight()) {
            cxxPreprocessorInputBuilder.addIncludes(CxxRawHeaders.of(result.getRight().headers));
            result
                .getRight()
                .includeRoots
                .forEach(
                    includeRoot ->
                        cxxPreprocessorInputBuilder.addIncludes(
                            ImmutableCxxIncludes.ofImpl(
                                args.getExportedHeaderStyle(), includeRoot)));
            break;
          } else if (mode == HeadersAsRawHeadersMode.REQUIRED) {
            throw new HumanReadableException(
                "%s: cannot convert %s (mapped to %s) to raw header",
                target, result.getLeft().path, result.getLeft().header);
          }
        }
        // $FALL-THROUGH$
      case DISABLED:
        {
          // Add platform-agnostic headers.
          queryMetadataCxxHeaders(
                  graphBuilder,
                  target,
                  CxxDescriptionEnhancer.getHeaderModeForPlatform(
                      graphBuilder,
                      target.getTargetConfiguration(),
                      cxxPlatform,
                      args.getXcodePublicHeadersSymlinks()
                          .orElse(cxxPlatform.getPublicHeadersSymlinksEnabled())))
              .ifPresent(cxxPreprocessorInputBuilder::addIncludes);

          // Add platform-specific headers.
          if (!args.getExportedPlatformHeaders()
                  .getMatchingValues(cxxPlatform.getFlavor().toString())
                  .isEmpty()
              || CxxGenruleDescription.wrapsCxxGenrule(graphBuilder, args.getExportedHeaders())) {
            HeaderSymlinkTree symlinkTree =
                (HeaderSymlinkTree)
                    graphBuilder.requireRule(
                        target
                            .withoutFlavors(CxxLibraryDescription.LIBRARY_TYPE.getFlavors())
                            .withAppendedFlavors(
                                CxxLibraryDescription.Type.EXPORTED_HEADERS.getFlavor(),
                                cxxPlatform.getFlavor()));
            cxxPreprocessorInputBuilder.addIncludes(
                CxxSymlinkTreeHeaders.from(symlinkTree, args.getExportedHeaderStyle()));
          }
          break;
        }
    }
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
                        args.getExportedHeaderStyle()));
          }
          return symlinkTree.map(metadataClass::cast);
        }

      case CXX_PREPROCESSOR_INPUT:
        {
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
          baseTarget = baseTarget.withoutFlavors(visibility.getKey());

          CxxPlatform cxxPlatform = extractPlatform(graphBuilder, baseTarget);
          baseTarget = baseTarget.withoutFlavors(cxxPlatform.getFlavor());

          CxxPreprocessorInput.Builder cxxPreprocessorInputBuilder = CxxPreprocessorInput.builder();

          // TODO(agallagher): We currently always add exported flags and frameworks to the
          // preprocessor input to mimic existing behavior, but this should likely be fixed.
          addCxxPreprocessorInputFromArgs(
              cxxPreprocessorInputBuilder,
              args,
              cxxPlatform,
              CxxDescriptionEnhancer.getStringWithMacrosArgsConverter(
                      baseTarget,
                      cellRoots,
                      graphBuilder,
                      cxxPlatform,
                      new CxxHeaderTreeMacroExpander(
                          CxxDescriptionEnhancer.getHeaderModeForPlatform(
                              graphBuilder,
                              buildTarget.getTargetConfiguration(),
                              cxxPlatform,
                              args.getXcodePublicHeadersSymlinks()
                                  .orElse(cxxPlatform.getPublicHeadersSymlinksEnabled()))))
                  ::convert);

          if (visibility.getValue() == HeaderVisibility.PRIVATE) {

            // Add headers from the headers parameter.
            addHeaders(cxxPreprocessorInputBuilder, baseTarget, graphBuilder, cxxPlatform, args);

            for (String privateInclude : args.getIncludeDirectories()) {
              cxxPreprocessorInputBuilder.addIncludes(
                  ImmutableCxxIncludes.ofImpl(
                      IncludeType.LOCAL,
                      PathSourcePath.of(
                          projectFilesystem,
                          buildTarget
                              .getCellRelativeBasePath()
                              .getPath()
                              .toPath(projectFilesystem.getFileSystem())
                              .resolve(privateInclude)
                              .normalize())));
            }
          }

          if (visibility.getValue() == HeaderVisibility.PUBLIC) {

            // Add headers from the headers parameter.
            addExportedHeaders(
                cxxPreprocessorInputBuilder, baseTarget, graphBuilder, cxxPlatform, args);

            if (!args.getRawHeaders().isEmpty()) {
              cxxPreprocessorInputBuilder.addIncludes(CxxRawHeaders.of(args.getRawHeaders()));
            }

            for (String publicInclude : args.getPublicIncludeDirectories()) {
              cxxPreprocessorInputBuilder.addIncludes(
                  ImmutableCxxIncludes.ofImpl(
                      IncludeType.LOCAL,
                      PathSourcePath.of(
                          projectFilesystem,
                          buildTarget
                              .getCellRelativeBasePath()
                              .getPath()
                              .toPath(projectFilesystem.getFileSystem())
                              .resolve(publicInclude)
                              .normalize())));
            }

            for (String publicSystemInclude : args.getPublicSystemIncludeDirectories()) {
              cxxPreprocessorInputBuilder.addIncludes(
                  ImmutableCxxIncludes.ofImpl(
                      IncludeType.SYSTEM,
                      PathSourcePath.of(
                          projectFilesystem,
                          buildTarget
                              .getCellRelativeBasePath()
                              .getPath()
                              .toPath(projectFilesystem.getFileSystem())
                              .resolve(publicSystemInclude)
                              .normalize())));
            }
          }

          CxxPreprocessorInput cxxPreprocessorInput = cxxPreprocessorInputBuilder.build();
          return Optional.of(cxxPreprocessorInput).map(metadataClass::cast);
        }

      case OBJECTS:
        {
          CxxPlatform cxxPlatform = extractPlatform(graphBuilder, baseTarget);
          baseTarget = baseTarget.withoutFlavors(cxxPlatform.getFlavor());

          Map.Entry<Flavor, PicType> picType =
              PIC_TYPE_FLAVOR_DOMAIN
                  .getFlavorAndValue(baseTarget)
                  .orElseThrow(IllegalStateException::new);
          baseTarget = baseTarget.withoutFlavors(picType.getKey());

          // If a strip style is present generate stripped objects.
          Optional<StripStyle> stripStyle = StripStyle.FLAVOR_DOMAIN.getValue(baseTarget);
          if (stripStyle.isPresent()) {
            baseTarget = buildTarget.withoutFlavors(stripStyle.get().getFlavor());

            // Get the original, unstripped objects.
            ImmutableList<SourcePath> objects =
                requireObjects(
                    baseTarget, graphBuilder, cxxPlatform, picType.getValue(), Optional.empty());

            // Generate stripped objects from them.
            ImmutableList<SourcePath> strippedObjects =
                CxxDescriptionEnhancer.requireStrippedObjects(
                    graphBuilder,
                    projectFilesystem,
                    cxxPlatform,
                    downwardApiConfig.isEnabledForCxx(),
                    stripStyle.get(),
                    objects);

            return Optional.of(strippedObjects).map(metadataClass::cast);
          }

          CxxDeps cxxDeps = CxxDeps.builder().addDeps(args.getCxxDeps()).build();
          ImmutableList<SourcePath> objects =
              CxxLibraryFactory.requireObjects(
                  baseTarget,
                  projectFilesystem,
                  graphBuilder,
                  cellRoots,
                  cxxBuckConfig,
                  downwardApiConfig,
                  cxxPlatform,
                  picType.getValue(),
                  args,
                  cxxDeps.get(graphBuilder, cxxPlatform),
                  CxxLibraryDescription.TransitiveCxxPreprocessorInputFunction.fromLibraryRule(),
                  Optional.empty());

          return Optional.of(objects).map(metadataClass::cast);
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

  private CxxPlatformsProvider getCxxPlatformsProvider(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME,
        toolchainTargetConfiguration,
        CxxPlatformsProvider.class);
  }

  private static class CxxHeaderTreeMacroExpander
      extends AbstractMacroExpanderWithoutPrecomputedWork<CxxHeaderTreeMacro> {

    private final HeaderMode headerMode;

    public CxxHeaderTreeMacroExpander(HeaderMode headerMode) {
      this.headerMode = headerMode;
    }

    @Override
    public Arg expandFrom(
        BuildTarget target, ActionGraphBuilder graphBuilder, CxxHeaderTreeMacro input)
        throws MacroException {
      CxxHeaders cxxHeaders =
          queryMetadataCxxHeaders(graphBuilder, target, headerMode)
              .orElseThrow(
                  () ->
                      new MacroException(
                          String.format("Cannot find exported header tree (%s)", headerMode)));
      return CompositeArg.of(SourcePathArg.from(cxxHeaders.getRoot()));
    }

    @Override
    public Class<CxxHeaderTreeMacro> getInputClass() {
      return CxxHeaderTreeMacro.class;
    }
  }
}
