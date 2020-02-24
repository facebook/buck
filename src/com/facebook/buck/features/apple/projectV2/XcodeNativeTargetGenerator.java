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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.apple.AppleAssetCatalogDescriptionArg;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBuildRules.RecursiveDependenciesMode;
import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleDescriptionArg;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleDependenciesCache;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.AppleResourceDescriptionArg;
import com.facebook.buck.apple.AppleResources;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.AppleTestDescriptionArg;
import com.facebook.buck.apple.AppleWrapperResourceArg;
import com.facebook.buck.apple.HasAppleBundleFields;
import com.facebook.buck.apple.InfoPlistSubstitution;
import com.facebook.buck.apple.PrebuiltAppleFrameworkDescription;
import com.facebook.buck.apple.PrebuiltAppleFrameworkDescriptionArg;
import com.facebook.buck.apple.XCodeDescriptions;
import com.facebook.buck.apple.xcode.AbstractPBXObjectFactory;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.parser.buildtargetpattern.BuildTargetLanguageConstants;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLibraryDescription.CommonArg;
import com.facebook.buck.cxx.CxxPrecompiledHeaderTemplate;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.PrebuiltCxxLibraryDescription;
import com.facebook.buck.cxx.PrebuiltCxxLibraryDescriptionArg;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HasSystemFrameworkAndLibraries;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableGroup.Linkage;
import com.facebook.buck.features.halide.HalideBuckConfig;
import com.facebook.buck.features.halide.HalideLibraryDescription;
import com.facebook.buck.features.halide.HalideLibraryDescriptionArg;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generates all the target attributes {@link GeneratedTargetAttributes} to be written into a {@link
 * com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget}.
 */
public class XcodeNativeTargetGenerator {
  private static final Logger LOG = Logger.get(XcodeNativeTargetGenerator.class);

  private static final String PRODUCT_NAME = "PRODUCT_NAME";
  private static final String BUILD_TARGET = "BUILD_TARGET";

  private final XCodeDescriptions xcodeDescriptions;
  private final TargetGraph targetGraph;
  private final AppleDependenciesCache dependenciesCache;
  private final ProjectGenerationStateCache projGenerationStateCache;
  private final ProjectFilesystem projectFilesystem;
  private final PathRelativizer pathRelativizer;

  private final String buildFileName;
  private final ProjectGeneratorOptions options;
  private final CxxPlatform defaultCxxPlatform;

  private final Function<? super TargetNode<?>, ActionGraphBuilder> actionGraphBuilderForNode;
  private final SourcePathResolverAdapter defaultPathResolver;

  private final ImmutableSet<Flavor> appleCxxFlavors;
  private final HalideBuckConfig halideBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final SwiftBuckConfig swiftBuckConfig;
  private final AppleConfig appleConfig;

  private final SwiftAttributeParser swiftAttributeParser;
  private final ProjectSourcePathResolver projectSourcePathResolver;
  private final HeaderSearchPaths headerSearchPaths;
  private final FlagParser flagParser;
  private final Path sourceRoot;

  private final AbstractPBXObjectFactory objectFactory;

  /**
   * Mapping from an apple_library target to the associated apple_bundle which names it as its
   * 'binary'
   */
  private final Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle;

  public XcodeNativeTargetGenerator(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      AppleDependenciesCache dependenciesCache,
      ProjectGenerationStateCache projGenerationStateCache,
      ProjectFilesystem projectFilesystem,
      Path sourceRoot,
      String buildFileName,
      PathRelativizer pathRelativizer,
      SourcePathResolverAdapter defaultPathResolver,
      ProjectSourcePathResolver projectSourcePathResolver,
      ProjectGeneratorOptions options,
      CxxPlatform defaultCxxPlatform,
      ImmutableSet<Flavor> appleCxxFlavors,
      Function<? super TargetNode<?>, ActionGraphBuilder> actionGraphBuilderForNode,
      HalideBuckConfig halideBuckConfig,
      HeaderSearchPaths headerSearchPaths,
      CxxBuckConfig cxxBuckConfig,
      AppleConfig appleConfig,
      SwiftBuckConfig swiftBuckConfig,
      SwiftAttributeParser swiftAttributeParser,
      FlagParser flagParser,
      Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle,
      AbstractPBXObjectFactory objectFactory) {
    this.xcodeDescriptions = xcodeDescriptions;
    this.targetGraph = targetGraph;
    this.dependenciesCache = dependenciesCache;
    this.projGenerationStateCache = projGenerationStateCache;
    this.projectFilesystem = projectFilesystem;
    this.buildFileName = buildFileName;
    this.options = options;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.appleCxxFlavors = appleCxxFlavors;
    this.actionGraphBuilderForNode = actionGraphBuilderForNode;
    this.defaultPathResolver = defaultPathResolver;
    this.projectSourcePathResolver = projectSourcePathResolver;
    this.pathRelativizer = pathRelativizer;
    this.sharedLibraryToBundle = sharedLibraryToBundle;
    this.sourceRoot = sourceRoot;

    this.halideBuckConfig = halideBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.appleConfig = appleConfig;
    this.swiftBuckConfig = swiftBuckConfig;

    this.swiftAttributeParser = swiftAttributeParser;
    this.headerSearchPaths = headerSearchPaths;
    this.flagParser = flagParser;
    this.objectFactory = objectFactory;
  }

  /**
   * Get the {@link ProductType} for a target node within a target graph.
   *
   * @param targetNode Target node to get the product type.
   * @param targetGraph Target graph the {@code targetNode}
   * @return A {@link ProductType} corresponding to the target if one exists.
   */
  @VisibleForTesting
  static Optional<ProductType> getProductType(TargetNode<?> targetNode, TargetGraph targetGraph) {
    BaseDescription<?> targetNodeDesc = targetNode.getDescription();

    if (targetNodeDesc instanceof AppleBinaryDescription) {
      // We treat apple binary targets as a TOOL
      // TODO(chatatap): Figure out why this is, and why bundles are extracted for their binaries
      return Optional.of(ProductTypes.TOOL);
    } else if (targetNodeDesc instanceof AppleBundleDescription) {
      TargetNode<AppleBundleDescriptionArg> bundleNode =
          TargetNodes.castArg(targetNode, AppleBundleDescriptionArg.class).get();
      Optional<String> xCodeProductType = bundleNode.getConstructorArg().getXcodeProductType();
      if (xCodeProductType.isPresent()) {
        // If there is an Xcode product type set, use that directly.
        return Optional.of(ProductType.of(xCodeProductType.get()));
      } else if (bundleNode.getConstructorArg().getExtension().isLeft()) {
        // apple_bundles (https://buck.build/rule/apple_bundle.html) must have a binary (which must
        // be an apple_binary or apple_library) attribute.
        TargetNode<AppleNativeTargetDescriptionArg> binaryNode =
            TargetNodes.castArg(
                    targetGraph.get(getBundleBinaryTarget(bundleNode)),
                    AppleNativeTargetDescriptionArg.class)
                .get();
        DescriptionWithTargetGraph<?> binaryNodeDesc =
            (DescriptionWithTargetGraph<?>) binaryNode.getDescription();
        AppleBundleExtension extension = bundleNode.getConstructorArg().getExtension().getLeft();
        if (binaryNodeDesc instanceof AppleLibraryDescription
            || binaryNodeDesc instanceof CxxLibraryDescription) {
          // If an apple_library attribute,
          if (binaryNode
              .getBuildTarget()
              .getFlavors()
              .contains(CxxDescriptionEnhancer.SHARED_FLAVOR)) {
            // That is build as a shared library, get the product type based on the file extension
            // of the output.
            return dylibProductTypeByBundleExtension(extension);
          } else if (extension == AppleBundleExtension.FRAMEWORK) {
            // otherwise treat as a static framework.
            return Optional.of(ProductTypes.STATIC_FRAMEWORK);
          }
        } else if (binaryNodeDesc instanceof AppleBinaryDescription) {
          // If a binary is set, the bundle extension should also be .app
          if (extension == AppleBundleExtension.APP) {
            return Optional.of(ProductTypes.APPLICATION);
          }
        }
      }
      // Fall through and treat as an apple bundle. The targets are most likely not configured
      // correctly.
      return Optional.of(ProductTypes.BUNDLE);
    } else if (targetNodeDesc instanceof AppleLibraryDescription
        || targetNodeDesc instanceof CxxLibraryDescription) {
      // Apple/Cxx libraries are treated as dynamic or static libraries based on the shared flavor.
      boolean isShared =
          targetNode.getBuildTarget().getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR);
      return Optional.of(isShared ? ProductTypes.DYNAMIC_LIBRARY : ProductTypes.STATIC_LIBRARY);
    } else if (targetNodeDesc instanceof AppleTestDescription) {
      // Determine whether it is a UI or Unit test.
      TargetNode<AppleTestDescriptionArg> testNode =
          TargetNodes.castArg(targetNode, AppleTestDescriptionArg.class).get();
      boolean isUITest = testNode.getConstructorArg().getIsUiTest();
      return Optional.of(isUITest ? ProductTypes.UI_TEST : ProductTypes.UNIT_TEST);
    } else if (targetNodeDesc instanceof HalideLibraryDescription) {
      // Halide libraries are treated as static libraries.
      return Optional.of(ProductTypes.STATIC_LIBRARY);
    }
    return Optional.empty();
  }

  /** @return Product Type of a bundle containing a dylib. */
  static Optional<ProductType> dylibProductTypeByBundleExtension(AppleBundleExtension extension) {
    switch (extension) {
      case FRAMEWORK:
        return Optional.of(ProductTypes.FRAMEWORK);
      case APPEX:
        return Optional.of(ProductTypes.APP_EXTENSION);
      case BUNDLE:
        return Optional.of(ProductTypes.BUNDLE);
      case XCTEST:
        return Optional.of(ProductTypes.UNIT_TEST);
        // $CASES-OMITTED$
      default:
        return Optional.empty();
    }
  }

  /**
   * Get the binary build target defined on the apple_bundle.
   *
   * @param bundle The bundle target node to process.
   * @return The binary build target defined on the bundle.
   */
  static BuildTarget getBundleBinaryTarget(TargetNode<AppleBundleDescriptionArg> bundle) {
    return bundle
        .getConstructorArg()
        .getBinary()
        .orElseThrow(
            () ->
                new HumanReadableException(
                    "apple_bundle rules without binary attribute are not supported."));
  }

  /** The result of materializing the build target. */
  public static class Result {
    public final TargetNode<?> targetNode;
    public final XCodeNativeTargetAttributes targetAttributes;
    public final ImmutableList<BuildTarget> dependencies;
    public final ImmutableSet<BuildTarget> requiredBuildTargets;
    public final ImmutableSet<Path> xcconfigPaths;
    public final ImmutableSet<String> targetConfigNames;
    public final Optional<HeaderSearchPathAttributes> headerSearchPathAttributes;

    public Result(TargetNode<?> targetNode, XCodeNativeTargetAttributes targetAttributes) {
      this(
          targetNode,
          targetAttributes,
          ImmutableList.of(),
          ImmutableSet.of(),
          ImmutableSet.of(),
          ImmutableSet.of(),
          Optional.empty());
    }

    public Result(
        TargetNode<?> targetNode,
        XCodeNativeTargetAttributes targetAttributes,
        ImmutableList<BuildTarget> dependencies,
        ImmutableSet<BuildTarget> requiredBuildTargets,
        ImmutableSet<Path> xcconfigPaths,
        ImmutableSet<String> targetConfigNames,
        Optional<HeaderSearchPathAttributes> headerSearchPathAttributes) {
      this.targetNode = targetNode;
      this.targetAttributes = targetAttributes;
      this.dependencies = dependencies;
      this.requiredBuildTargets = requiredBuildTargets;
      this.xcconfigPaths = xcconfigPaths;
      this.targetConfigNames = targetConfigNames;
      this.headerSearchPathAttributes = headerSearchPathAttributes;
    }
  }

  /**
   * Generates the target node.
   *
   * @param targetNode The node to generate
   * @return A result with all of the data aggregated for this node.
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  public Result generateTarget(TargetNode<?> targetNode) throws IOException {

    ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<Path> xcconfigPathsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<String> targetConfigNamesBuilder = ImmutableSet.builder();

    ImmutableXCodeNativeTargetAttributes.Builder nativeTargetBuilder =
        ImmutableXCodeNativeTargetAttributes.builder().setAppleConfig(appleConfig);

    // Not sure if this is still needed -- IT excludes BUCK compilation targets, according to the
    // comment
    // Something we can revisit. (@cjjones)
    if (shouldExcludeLibraryFromProject(targetNode)) {
      return new Result(targetNode, nativeTargetBuilder.build());
    }

    HeaderSearchPathAttributes headerSearchPathAttributes = null;
    ImmutableList<BuildTarget> dependencies = ImmutableList.of();
    if (targetNode.getDescription() instanceof AppleLibraryDescription) {
      TargetNode<AppleNativeTargetDescriptionArg> libraryDescriptionTarget =
          (TargetNode<AppleNativeTargetDescriptionArg>) targetNode;
      headerSearchPathAttributes =
          headerSearchPaths.getHeaderSearchPathAttributes(libraryDescriptionTarget);
      dependencies =
          generateAppleLibraryTarget(
              nativeTargetBuilder,
              requiredBuildTargetsBuilder,
              xcconfigPathsBuilder,
              targetConfigNamesBuilder,
              headerSearchPathAttributes,
              libraryDescriptionTarget,
              Optional.empty());
    } else if (targetNode.getDescription() instanceof CxxLibraryDescription) {
      TargetNode<CommonArg> libraryDescriptionTarget = (TargetNode<CommonArg>) targetNode;
      headerSearchPathAttributes =
          headerSearchPaths.getHeaderSearchPathAttributes(libraryDescriptionTarget);
      dependencies =
          generateCxxLibraryTarget(
              nativeTargetBuilder,
              requiredBuildTargetsBuilder,
              xcconfigPathsBuilder,
              targetConfigNamesBuilder,
              headerSearchPathAttributes,
              libraryDescriptionTarget,
              ImmutableSet.of(),
              ImmutableSet.of(),
              Optional.empty());
    } else if (targetNode.getDescription() instanceof AppleBinaryDescription) {
      TargetNode<AppleNativeTargetDescriptionArg> binaryDescriptionTarget =
          (TargetNode<AppleNativeTargetDescriptionArg>) targetNode;
      headerSearchPathAttributes =
          headerSearchPaths.getHeaderSearchPathAttributes(binaryDescriptionTarget);
      dependencies =
          generateAppleBinaryTarget(
              nativeTargetBuilder,
              requiredBuildTargetsBuilder,
              xcconfigPathsBuilder,
              targetConfigNamesBuilder,
              headerSearchPathAttributes,
              binaryDescriptionTarget);
    } else if (targetNode.getDescription() instanceof AppleBundleDescription) {
      TargetNode<AppleBundleDescriptionArg> bundleTargetNode =
          (TargetNode<AppleBundleDescriptionArg>) targetNode;
      TargetNode<AppleNativeTargetDescriptionArg> nativeTargetNode =
          (TargetNode<AppleNativeTargetDescriptionArg>)
              targetGraph.get(XcodeNativeTargetGenerator.getBundleBinaryTarget(bundleTargetNode));
      headerSearchPathAttributes =
          headerSearchPaths.getHeaderSearchPathAttributes(nativeTargetNode);
      dependencies =
          generateAppleBundleTarget(
              nativeTargetBuilder,
              requiredBuildTargetsBuilder,
              xcconfigPathsBuilder,
              targetConfigNamesBuilder,
              headerSearchPathAttributes,
              bundleTargetNode,
              nativeTargetNode,
              Optional.empty());
    } else if (targetNode.getDescription() instanceof AppleTestDescription) {
      TargetNode<AppleTestDescriptionArg> testNode =
          (TargetNode<AppleTestDescriptionArg>) targetNode;
      headerSearchPathAttributes = headerSearchPaths.getHeaderSearchPathAttributes(testNode);
      dependencies =
          generateAppleTestTarget(
              testNode,
              requiredBuildTargetsBuilder,
              xcconfigPathsBuilder,
              targetConfigNamesBuilder,
              headerSearchPathAttributes,
              nativeTargetBuilder);
    } else if (targetNode.getDescription() instanceof AppleResourceDescription) {
      checkAppleResourceTargetNodeReferencingValidContents(
          (TargetNode<AppleResourceDescriptionArg>) targetNode);
    } else if (targetNode.getDescription() instanceof HalideLibraryDescription) {
      TargetNode<HalideLibraryDescriptionArg> halideTargetNode =
          (TargetNode<HalideLibraryDescriptionArg>) targetNode;
      BuildTarget buildTarget = targetNode.getBuildTarget();

      // The generated target just runs a shell script that invokes the "compiler" with the
      // correct target architecture.
      generateHalideLibraryTarget(
          nativeTargetBuilder, xcconfigPathsBuilder, targetConfigNamesBuilder, halideTargetNode);

      // Make sure the compiler gets built at project time, since we'll need
      // it to generate the shader code during the Xcode build.
      requiredBuildTargetsBuilder.add(
          HalideLibraryDescription.createHalideCompilerBuildTarget(buildTarget));

      // HACK: Don't generate the Halide headers unless the compiler is expected
      // to generate output for the default platform -- a Halide library that
      // uses a platform regex may not be able to use the default platform.
      // This assumes that there's a 'default' variant of the rule to generate
      // headers from.
      if (HalideLibraryDescription.isPlatformSupported(
          halideTargetNode.getConstructorArg(), defaultCxxPlatform)) {

        // Run the compiler once at project time to generate the header
        // file needed for compilation if the Halide target is for the default
        // platform.
        requiredBuildTargetsBuilder.add(
            buildTarget.withFlavors(
                HalideLibraryDescription.HALIDE_COMPILE_FLAVOR, defaultCxxPlatform.getFlavor()));
      }
    } else if (targetNode.getDescription() instanceof AbstractGenruleDescription) {
      TargetNode<AbstractGenruleDescription.CommonArg> genruleNode =
          (TargetNode<AbstractGenruleDescription.CommonArg>) targetNode;

      for (SourcePath genruleFilePath : genruleNode.getConstructorArg().getSrcs().getPaths()) {
        nativeTargetBuilder.addGenruleFiles(genruleFilePath);
      }
    }

    XCodeNativeTargetAttributes nativeTargetAttributes = nativeTargetBuilder.build();
    addRequiredBuildTargetsFromAttributes(nativeTargetAttributes, requiredBuildTargetsBuilder);

    return new Result(
        targetNode,
        nativeTargetAttributes,
        dependencies,
        requiredBuildTargetsBuilder.build(),
        xcconfigPathsBuilder.build(),
        targetConfigNamesBuilder.build(),
        Optional.ofNullable(headerSearchPathAttributes));
  }

  private void addRequiredBuildTargetsFromAttributes(
      XCodeNativeTargetAttributes nativeTargetAttributes,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder) {
    nativeTargetAttributes.sourcesWithFlags().stream()
        .map(
            sourceWithFlags ->
                Utils.sourcePathTryIntoBuildTargetSourcePath(sourceWithFlags.getSourcePath()))
        .filter(Optional::isPresent)
        .forEach(
            source -> {
              Utils.addRequiredBuildTargetFromSourcePath(
                  source.get(),
                  requiredBuildTargetsBuilder,
                  targetGraph,
                  actionGraphBuilderForNode);
            });

    Streams.concat(
            nativeTargetAttributes.privateHeaders().stream(),
            nativeTargetAttributes.publicHeaders().stream(),
            nativeTargetAttributes.extraXcodeSources().stream(),
            nativeTargetAttributes.extraXcodeFiles().stream(),
            nativeTargetAttributes.genruleFiles().stream())
        .map(Utils::sourcePathTryIntoBuildTargetSourcePath)
        .filter(Optional::isPresent)
        .forEach(
            sourcePath ->
                Utils.addRequiredBuildTargetFromSourcePath(
                    sourcePath.get(),
                    requiredBuildTargetsBuilder,
                    targetGraph,
                    actionGraphBuilderForNode));

    nativeTargetAttributes.directResources().stream()
        .forEach(
            arg -> {
              arg.getFiles().stream()
                  .map(Utils::sourcePathTryIntoBuildTargetSourcePath)
                  .filter(Optional::isPresent)
                  .forEach(
                      sourcePath ->
                          Utils.addRequiredBuildTargetFromSourcePath(
                              sourcePath.get(),
                              requiredBuildTargetsBuilder,
                              targetGraph,
                              actionGraphBuilderForNode));
              arg.getDirs().stream()
                  .map(Utils::sourcePathTryIntoBuildTargetSourcePath)
                  .filter(Optional::isPresent)
                  .forEach(
                      sourcePath ->
                          Utils.addRequiredBuildTargetFromSourcePath(
                              sourcePath.get(),
                              requiredBuildTargetsBuilder,
                              targetGraph,
                              actionGraphBuilderForNode));
              arg.getVariants().stream()
                  .map(Utils::sourcePathTryIntoBuildTargetSourcePath)
                  .filter(Optional::isPresent)
                  .forEach(
                      sourcePath ->
                          Utils.addRequiredBuildTargetFromSourcePath(
                              sourcePath.get(),
                              requiredBuildTargetsBuilder,
                              targetGraph,
                              actionGraphBuilderForNode));
            });

    nativeTargetAttributes.directAssetCatalogs().stream()
        .forEach(
            arg ->
                arg.getDirs().stream()
                    .map(Utils::sourcePathTryIntoBuildTargetSourcePath)
                    .filter(Optional::isPresent)
                    .forEach(
                        sourcePath ->
                            Utils.addRequiredBuildTargetFromSourcePath(
                                sourcePath.get(),
                                requiredBuildTargetsBuilder,
                                targetGraph,
                                actionGraphBuilderForNode)));

    nativeTargetAttributes
        .infoPlist()
        .map(Utils::sourcePathTryIntoBuildTargetSourcePath)
        .ifPresent(
            buildTargetSourcePath ->
                buildTargetSourcePath.ifPresent(
                    sourcePath ->
                        Utils.addRequiredBuildTargetFromSourcePath(
                            sourcePath,
                            requiredBuildTargetsBuilder,
                            targetGraph,
                            actionGraphBuilderForNode)));
    nativeTargetAttributes
        .prefixHeader()
        .map(Utils::sourcePathTryIntoBuildTargetSourcePath)
        .ifPresent(
            sourcePath ->
                sourcePath.ifPresent(
                    buildTargetSourcePath ->
                        Utils.addRequiredBuildTargetFromSourcePath(
                            buildTargetSourcePath,
                            requiredBuildTargetsBuilder,
                            targetGraph,
                            actionGraphBuilderForNode)));
    nativeTargetAttributes
        .bridgingHeader()
        .map(Utils::sourcePathTryIntoBuildTargetSourcePath)
        .ifPresent(
            sourcePath ->
                sourcePath.ifPresent(
                    buildTargetSourcePath ->
                        Utils.addRequiredBuildTargetFromSourcePath(
                            buildTargetSourcePath,
                            requiredBuildTargetsBuilder,
                            targetGraph,
                            actionGraphBuilderForNode)));
  }

  private static Path getHalideOutputPath(ProjectFilesystem filesystem, BuildTarget target) {
    return filesystem
        .getBuckPaths()
        .getConfiguredBuckOut()
        .resolve("halide")
        .resolve(target.getCellRelativeBasePath().getPath().toPath(filesystem.getFileSystem()))
        .resolve(target.getShortName());
  }

  private void generateHalideLibraryTarget(
      ImmutableXCodeNativeTargetAttributes.Builder xcodeNativeTargetAttributesBuilder,
      ImmutableSet.Builder<Path> xcconfigPathsBuilder,
      ImmutableSet.Builder<String> targetConfigNamesBuilder,
      TargetNode<HalideLibraryDescriptionArg> targetNode)
      throws IOException {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    xcodeNativeTargetAttributesBuilder.setTarget(Optional.of(buildTarget));

    String productName = getProductNameForBuildTargetNode(targetNode);
    Path outputPath = getHalideOutputPath(targetNode.getFilesystem(), buildTarget);

    Path scriptPath = halideBuckConfig.getXcodeCompileScriptPath();
    Optional<String> script = projectFilesystem.readFileIfItExists(scriptPath);
    PBXShellScriptBuildPhase scriptPhase = objectFactory.createShellScriptBuildPhase();
    scriptPhase.setShellScript(script.orElse(""));

    xcodeNativeTargetAttributesBuilder.setProduct(
        Optional.of(
            new XcodeProductMetadata(ProductTypes.STATIC_LIBRARY, productName, outputPath)));

    BuildTarget compilerTarget =
        HalideLibraryDescription.createHalideCompilerBuildTarget(buildTarget);
    Path compilerPath = BuildTargetPaths.getGenPath(projectFilesystem, compilerTarget, "%s");
    ImmutableMap<String, String> appendedConfig = ImmutableMap.of();
    ImmutableMap<String, String> extraSettings = ImmutableMap.of();
    Builder<String, String> defaultSettingsBuilder = ImmutableMap.builder();
    defaultSettingsBuilder.put("REPO_ROOT", projectFilesystem.getRootPath().normalize().toString());
    defaultSettingsBuilder.put("HALIDE_COMPILER_PATH", compilerPath.toString());

    // pass the source list to the xcode script
    String halideCompilerSrcs;
    Iterable<Path> compilerSrcFiles =
        Iterables.transform(
            targetNode.getConstructorArg().getSrcs(),
            input -> resolveSourcePath(input.getSourcePath()));
    halideCompilerSrcs = Joiner.on(" ").join(compilerSrcFiles);
    defaultSettingsBuilder.put("HALIDE_COMPILER_SRCS", halideCompilerSrcs);
    String halideCompilerFlags;
    halideCompilerFlags = Joiner.on(" ").join(targetNode.getConstructorArg().getCompilerFlags());
    defaultSettingsBuilder.put("HALIDE_COMPILER_FLAGS", halideCompilerFlags);

    defaultSettingsBuilder.put("HALIDE_OUTPUT_PATH", outputPath.toString());
    defaultSettingsBuilder.put("HALIDE_FUNC_NAME", buildTarget.getShortName());
    defaultSettingsBuilder.put(PRODUCT_NAME, productName);

    BuildConfiguration.writeBuildConfigurationsForTarget(
        targetNode,
        buildTarget,
        defaultCxxPlatform,
        defaultPathResolver,
        xcodeNativeTargetAttributesBuilder,
        extraSettings,
        defaultSettingsBuilder.build(),
        appendedConfig,
        projectFilesystem,
        options.shouldGenerateReadOnlyFiles(),
        targetConfigNamesBuilder,
        xcconfigPathsBuilder);
  }

  private ImmutableList<BuildTarget> generateAppleTestTarget(
      TargetNode<AppleTestDescriptionArg> testTargetNode,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder,
      ImmutableSet.Builder<Path> xcconfigPathsBuilder,
      ImmutableSet.Builder<String> targetConfigNamesBuilder,
      HeaderSearchPathAttributes headerSearchPathAttributes,
      ImmutableXCodeNativeTargetAttributes.Builder nativeTargetBuilder)
      throws IOException {
    AppleTestDescriptionArg args = testTargetNode.getConstructorArg();
    Optional<BuildTarget> testTargetApp = extractTestTargetForTestDescriptionArg(args);
    Optional<TargetNode<AppleBundleDescriptionArg>> testHostBundle =
        testTargetApp.map(
            testHostBundleTarget -> {
              TargetNode<?> testHostBundleNode = targetGraph.get(testHostBundleTarget);
              return TargetNodes.castArg(testHostBundleNode, AppleBundleDescriptionArg.class)
                  .orElseGet(
                      () -> {
                        throw new HumanReadableException(
                            "The test host target '%s' has the wrong type (%s), must be apple_bundle",
                            testHostBundleTarget, testHostBundleNode.getDescription().getClass());
                      });
            });
    return generateAppleBundleTarget(
        nativeTargetBuilder,
        requiredBuildTargetsBuilder,
        xcconfigPathsBuilder,
        targetConfigNamesBuilder,
        headerSearchPathAttributes,
        testTargetNode,
        testTargetNode,
        testHostBundle);
  }

  private Optional<BuildTarget> extractTestTargetForTestDescriptionArg(
      AppleTestDescriptionArg args) {
    if (args.getUiTestTargetApp().isPresent()) {
      return args.getUiTestTargetApp();
    }
    return args.getTestHostApp();
  }

  private void checkAppleResourceTargetNodeReferencingValidContents(
      TargetNode<AppleResourceDescriptionArg> resource) {
    // Check that the resource target node is referencing valid files or directories.
    // If a SourcePath is a BuildTargetSourcePath (or some hypothetical future implementation of
    // SourcePath), just assume it's the right type; we have no way of checking now as it
    // may not exist yet.
    AppleResourceDescriptionArg arg = resource.getConstructorArg();
    for (SourcePath dir : arg.getDirs()) {
      if (dir instanceof PathSourcePath && !projectFilesystem.isDirectory(resolveSourcePath(dir))) {
        throw new HumanReadableException(
            "%s specified in the dirs parameter of %s is not a directory",
            dir.toString(), resource.toString());
      }
    }
    for (SourcePath file : arg.getFiles()) {
      if (file instanceof PathSourcePath && !projectFilesystem.isFile(resolveSourcePath(file))) {
        throw new HumanReadableException(
            "%s specified in the files parameter of %s is not a regular file",
            file.toString(), resource.toString());
      }
    }
  }

  private ImmutableList<BuildTarget> generateAppleBundleTarget(
      ImmutableXCodeNativeTargetAttributes.Builder nativeTargetBuilder,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder,
      ImmutableSet.Builder<Path> xcconfigPathsBuilder,
      ImmutableSet.Builder<String> targetConfigNamesBuilder,
      HeaderSearchPathAttributes headerSearchPathAttributes,
      TargetNode<? extends HasAppleBundleFields> targetNode,
      TargetNode<? extends AppleNativeTargetDescriptionArg> binaryNode,
      Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode)
      throws IOException {
    Path infoPlistPath =
        Objects.requireNonNull(resolveSourcePath(targetNode.getConstructorArg().getInfoPlist()));

    RecursiveDependenciesMode mode =
        appleConfig.shouldIncludeSharedLibraryResources()
            ? RecursiveDependenciesMode.COPYING_INCLUDE_SHARED_RESOURCES
            : RecursiveDependenciesMode.COPYING;

    ImmutableSet<AppleWrapperResourceArg> allWrapperResources =
        AppleBuildRules.collectRecursiveWrapperResources(
            xcodeDescriptions,
            targetGraph,
            Optional.of(dependenciesCache),
            ImmutableList.of(targetNode),
            mode);

    ImmutableSet<AppleWrapperResourceArg> coreDataResources =
        AppleBuildRules.collectTransitiveBuildTargetArg(
            xcodeDescriptions,
            targetGraph,
            Optional.of(dependenciesCache),
            AppleBuildRules.CORE_DATA_MODEL_DESCRIPTION_CLASSES,
            ImmutableList.of(targetNode),
            RecursiveDependenciesMode.COPYING,
            Predicates.alwaysTrue());

    // As of now, CoreDataResources are AppleWrapperResourceArgs so they will both be returned when
    // querying for recursive wrapper resources above. We want to separate these out and handle
    // them properly -- core data resources need to be part of the build phase, and we want to
    // render them differently since they are "versioned" and should use PBXVersionGroup.
    //
    // Ideally, we would separate these out so that way the CoreData objects are not recursive
    // wrapper resources, but since that would change things for regular buck project given that
    // this code is shared, we can just diff the sets here.
    ImmutableSet<AppleWrapperResourceArg> filteredWrapperResources =
        Sets.difference(allWrapperResources, coreDataResources).immutableCopy();

    ImmutableList<BuildTarget> result =
        generateBinaryTarget(
            nativeTargetBuilder,
            requiredBuildTargetsBuilder,
            xcconfigPathsBuilder,
            targetConfigNamesBuilder,
            headerSearchPathAttributes,
            Optional.of(targetNode),
            binaryNode,
            "%s." + getExtensionString(targetNode.getConstructorArg().getExtension()),
            Optional.of(infoPlistPath),
            /* includeFrameworks */ true,
            AppleResources.collectDirectResources(targetGraph, targetNode),
            AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
            filteredWrapperResources,
            coreDataResources,
            bundleLoaderNode);

    if (bundleLoaderNode.isPresent()) {
      LOG.debug(
          "Generated iOS bundle target %s with binarynode: %s bundleLoadernode: %s",
          targetNode.getBuildTarget().getFullyQualifiedName(),
          binaryNode.getBuildTarget().getFullyQualifiedName(),
          binaryNode.getBuildTarget().getFullyQualifiedName(),
          bundleLoaderNode.get().getBuildTarget().getFullyQualifiedName());
    } else {
      LOG.debug(
          "Generated iOS bundle target %s with binarynode: %s and without bundleloader",
          targetNode.getBuildTarget().getFullyQualifiedName(),
          binaryNode.getBuildTarget().getFullyQualifiedName());
    }

    return result;
  }

  private ImmutableList<BuildTarget> generateAppleBinaryTarget(
      ImmutableXCodeNativeTargetAttributes.Builder nativeTargetBuilder,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder,
      ImmutableSet.Builder<Path> xcconfigPathsBuilder,
      ImmutableSet.Builder<String> targetConfigNamesBuilder,
      HeaderSearchPathAttributes headerSearchPathAttributes,
      TargetNode<AppleNativeTargetDescriptionArg> targetNode)
      throws IOException {
    ImmutableList<BuildTarget> result =
        generateBinaryTarget(
            nativeTargetBuilder,
            requiredBuildTargetsBuilder,
            xcconfigPathsBuilder,
            targetConfigNamesBuilder,
            headerSearchPathAttributes,
            Optional.empty(),
            targetNode,
            "%s",
            Optional.empty(),
            /* includeFrameworks */ true,
            AppleResources.collectDirectResources(targetGraph, targetNode),
            AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
            ImmutableSet.of(),
            ImmutableSet.of(),
            Optional.empty());

    LOG.debug(
        "Generated Apple binary target %s", targetNode.getBuildTarget().getFullyQualifiedName());
    return result;
  }

  private ImmutableList<BuildTarget> generateAppleLibraryTarget(
      ImmutableXCodeNativeTargetAttributes.Builder nativeTargetBuilder,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder,
      ImmutableSet.Builder<Path> xcconfigPathsBuilder,
      ImmutableSet.Builder<String> targetConfigNamesBuilder,
      HeaderSearchPathAttributes headerSearchPathAttributes,
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode,
      Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode)
      throws IOException {
    ImmutableList<BuildTarget> result =
        generateCxxLibraryTarget(
            nativeTargetBuilder,
            requiredBuildTargetsBuilder,
            xcconfigPathsBuilder,
            targetConfigNamesBuilder,
            headerSearchPathAttributes,
            targetNode,
            AppleResources.collectDirectResources(targetGraph, targetNode),
            AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
            bundleLoaderNode);
    LOG.debug(
        "Generated iOS library target %s", targetNode.getBuildTarget().getFullyQualifiedName());
    return result;
  }

  private ImmutableList<BuildTarget> generateCxxLibraryTarget(
      ImmutableXCodeNativeTargetAttributes.Builder nativeTargetBuilder,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder,
      ImmutableSet.Builder<Path> xcconfigPathsBuilder,
      ImmutableSet.Builder<String> targetConfigNamesBuilder,
      HeaderSearchPathAttributes headerSearchPathAttributes,
      TargetNode<? extends CommonArg> targetNode,
      ImmutableSet<AppleResourceDescriptionArg> directResources,
      ImmutableSet<AppleAssetCatalogDescriptionArg> directAssetCatalogs,
      Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode)
      throws IOException {
    boolean isShared =
        targetNode.getBuildTarget().getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR);

    ImmutableList<BuildTarget> result =
        generateBinaryTarget(
            nativeTargetBuilder,
            requiredBuildTargetsBuilder,
            xcconfigPathsBuilder,
            targetConfigNamesBuilder,
            headerSearchPathAttributes,
            Optional.empty(),
            targetNode,
            AppleBuildRules.getOutputFileNameFormatForLibrary(isShared),
            Optional.empty(),
            /* includeFrameworks */ isShared,
            directResources,
            directAssetCatalogs,
            ImmutableSet.of(),
            ImmutableSet.of(),
            bundleLoaderNode);

    LOG.debug(
        "Generated Cxx library target %s", targetNode.getBuildTarget().getFullyQualifiedName());
    return result;
  }

  private static String sourceNameRelativeToOutput(
      SourcePath source, SourcePathResolverAdapter pathResolver, Path outputDirectory) {
    Path pathRelativeToCell = pathResolver.getRelativePath(source);
    Path pathRelativeToOutput =
        MorePaths.relativizeWithDotDotSupport(outputDirectory, pathRelativeToCell);
    return pathRelativeToOutput.toString();
  }

  private static void appendPlatformSourceToAllPlatformSourcesAndSourcesByPlatform(
      Set<String> allPlatformSources,
      Map<String, Set<String>> platformSourcesByPlatform,
      String platformName,
      String sourceName) {
    allPlatformSources.add(sourceName);
    if (platformSourcesByPlatform.get(platformName) != null) {
      platformSourcesByPlatform.get(platformName).add(sourceName);
    }
  }

  @VisibleForTesting
  static ImmutableMap<String, ImmutableSortedSet<String>> gatherExcludedSources(
      ImmutableSet<Flavor> appleCxxFlavors,
      ImmutableList<Pair<Pattern, ImmutableSortedSet<SourceWithFlags>>> platformSources,
      ImmutableList<Pair<Pattern, Iterable<SourcePath>>> platformHeaders,
      Path outputDirectory,
      SourcePathResolverAdapter pathResolver) {
    Set<String> allPlatformSpecificSources = new HashSet<>();
    Map<String, Set<String>> includedSourcesByPlatform = new HashMap<>();

    for (Pair<Pattern, ImmutableSortedSet<SourceWithFlags>> platformSource : platformSources) {
      String platformName = platformSource.getFirst().toString();
      includedSourcesByPlatform.putIfAbsent(platformName, new HashSet<>());

      for (SourceWithFlags source : platformSource.getSecond()) {
        appendPlatformSourceToAllPlatformSourcesAndSourcesByPlatform(
            allPlatformSpecificSources,
            includedSourcesByPlatform,
            platformName,
            sourceNameRelativeToOutput(source.getSourcePath(), pathResolver, outputDirectory));
      }
    }

    for (Pair<Pattern, Iterable<SourcePath>> platformHeader : platformHeaders) {
      String platformName = platformHeader.getFirst().toString();
      includedSourcesByPlatform.putIfAbsent(platformName, new HashSet<>());

      for (SourcePath source : platformHeader.getSecond()) {
        appendPlatformSourceToAllPlatformSourcesAndSourcesByPlatform(
            allPlatformSpecificSources,
            includedSourcesByPlatform,
            platformName,
            sourceNameRelativeToOutput(source, pathResolver, outputDirectory));
      }
    }

    Map<String, SortedSet<String>> result = new HashMap<>();
    result.put(
        "EXCLUDED_SOURCE_FILE_NAMES",
        ImmutableSortedSet.copyOf(
            allPlatformSpecificSources.stream()
                .map(s -> "'" + s + "'")
                .collect(Collectors.toSet())));

    // Determine if any of the flavors match the regex. This will include prefix matching such as
    // `iphoneos` matching `iphoneos-arm64` and `iphoneos-armv7`. It will split the platform and
    // arch so it makes sense to Xcode. This will look like:
    //
    //   INCLUDED_SOURCE_FILE_NAMES[platform=iphoneos*][arch=arm64] = [...]
    //   INCLUDED_SOURCE_FILE_NAMES[platform=iphoneos*][arch=armv7] = [...]
    //
    // We need to convert the regex to a glob that Xcode will recognize so we match the regex
    // against the name of a known flavor with the matcher, then glob that.
    for (String platformMatcher : includedSourcesByPlatform.keySet()) {
      for (Flavor flavor : appleCxxFlavors) {
        Pattern pattern = Pattern.compile(platformMatcher);
        Matcher matcher = pattern.matcher(flavor.getName());
        if (matcher.lookingAt()) {
          Pair<String, String> applePlatformAndArch = applePlatformAndArchitecture(flavor);
          String platform = applePlatformAndArch.getFirst();
          String arch = applePlatformAndArch.getSecond();

          String key = "INCLUDED_SOURCE_FILE_NAMES[sdk=" + platform + "*][arch=" + arch + "]";
          Set<String> sourcesMatchingPlatform = includedSourcesByPlatform.get(platformMatcher);
          if (sourcesMatchingPlatform != null) {
            Set<String> quotedSources =
                sourcesMatchingPlatform.stream()
                    .map(s -> "'" + s + "'")
                    .collect(Collectors.toSet());
            // They may have different matchers for similar things in which case the key will
            // already be included
            if (result.get(key) != null) {
              result.get(key).addAll(quotedSources);
            } else {
              result.put(key, new TreeSet<>(quotedSources));
            }
          }
        }
      }
    }

    Builder<String, ImmutableSortedSet<String>> finalResultBuilder = ImmutableMap.builder();

    for (Map.Entry<String, SortedSet<String>> entry : result.entrySet()) {
      finalResultBuilder.put(entry.getKey(), ImmutableSortedSet.copyOf(entry.getValue()));
    }
    return finalResultBuilder.build();
  }

  @VisibleForTesting
  static Pair<String, String> applePlatformAndArchitecture(Flavor platformFlavor) {
    String platformName = platformFlavor.getName();
    int index = platformName.lastIndexOf('-');
    String sdk = platformName.substring(0, index);
    String sdkWithoutVersion = sdk.split("\\d+")[0];
    String arch = platformName.substring(index + 1);
    return new Pair<>(sdkWithoutVersion, arch);
  }

  private ImmutableList<BuildTarget> generateBinaryTarget(
      ImmutableXCodeNativeTargetAttributes.Builder xcodeNativeTargetAttributesBuilder,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder,
      ImmutableSet.Builder<Path> xcconfigPathsBuilder,
      ImmutableSet.Builder<String> targetConfigNamesBuilder,
      HeaderSearchPathAttributes headerSearchPathAttributes,
      Optional<? extends TargetNode<? extends HasAppleBundleFields>> bundle,
      TargetNode<? extends CommonArg> targetNode,
      String productOutputFormat,
      Optional<Path> infoPlistOptional,
      boolean includeFrameworks,
      ImmutableSet<AppleResourceDescriptionArg> directResources,
      ImmutableSet<AppleAssetCatalogDescriptionArg> directAssetCatalogs,
      ImmutableSet<AppleWrapperResourceArg> wrapperResources,
      ImmutableSet<AppleWrapperResourceArg> coreDataResources,
      Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode)
      throws IOException {

    LOG.debug("Generating binary target for node %s", targetNode);

    TargetNode<?> buildTargetNode = bundle.isPresent() ? bundle.get() : targetNode;

    // TODO(chatatap): Whenever generateBinaryTarget is called, productType should be set. As we
    // upstream XcodeNativeTargetGenerator, this should become a precondition check for any of its
    // uses.
    ProductType productType = getProductType(buildTargetNode, targetGraph).get();

    BuildTarget buildTarget = buildTargetNode.getBuildTarget();
    boolean containsSwiftCode = projGenerationStateCache.targetContainsSwiftSourceCode(targetNode);

    xcodeNativeTargetAttributesBuilder.setTarget(Optional.of(buildTarget));

    String buildTargetName = getProductNameForBuildTargetNode(buildTargetNode);
    CommonArg arg = targetNode.getConstructorArg();

    // Both exported headers and exported platform headers will be put into the symlink tree
    // exported platform headers will be excluded and then included by platform
    ImmutableSet.Builder<SourcePath> exportedHeadersBuilder = ImmutableSet.builder();
    exportedHeadersBuilder.addAll(getHeaderSourcePaths(arg.getExportedHeaders()));
    PatternMatchedCollection<SourceSortedSet> exportedPlatformHeaders =
        arg.getExportedPlatformHeaders();
    for (SourceSortedSet headersSet : exportedPlatformHeaders.getValues()) {
      exportedHeadersBuilder.addAll(getHeaderSourcePaths(headersSet));
    }

    ImmutableSet<SourcePath> exportedHeaders = exportedHeadersBuilder.build();
    ImmutableSet.Builder<SourcePath> headersBuilder = ImmutableSet.builder();
    headersBuilder.addAll(getHeaderSourcePaths(arg.getHeaders()));
    for (SourceSortedSet headersSet : arg.getPlatformHeaders().getValues()) {
      headersBuilder.addAll(getHeaderSourcePaths(headersSet));
    }
    ImmutableSet<SourcePath> headers = headersBuilder.build();
    ImmutableMap<CxxSource.Type, ImmutableList<StringWithMacros>> langPreprocessorFlags =
        targetNode.getConstructorArg().getLangPreprocessorFlags();

    SwiftAttributes swiftAttributes = swiftAttributeParser.parseSwiftAttributes(targetNode);

    Optional<String> swiftVersion = swiftAttributes.swiftVersion();
    boolean hasSwiftVersionArg = swiftVersion.isPresent();
    if (!swiftVersion.isPresent()) {
      swiftVersion = swiftBuckConfig.getVersion();
    }

    xcodeNativeTargetAttributesBuilder.setProduct(
        Optional.of(
            new XcodeProductMetadata(
                productType,
                buildTargetName,
                Paths.get(String.format(productOutputFormat, buildTargetName)))));

    boolean isModularAppleLibrary = NodeHelper.isModularAppleLibrary(targetNode);
    xcodeNativeTargetAttributesBuilder.setFrameworkHeadersEnabled(isModularAppleLibrary);

    Builder<String, String> swiftDepsSettingsBuilder = ImmutableMap.builder();

    Builder<String, String> extraSettingsBuilder = ImmutableMap.builder();
    Builder<String, String> defaultSettingsBuilder = ImmutableMap.builder();

    // XCConfigs treat '//' as comments and must be escaped.
    String cellRelativeBuildTarget = buildTarget.getCellRelativeName();
    extraSettingsBuilder.put(
        BUILD_TARGET,
        cellRelativeBuildTarget.replaceAll(BuildTargetLanguageConstants.ROOT_SYMBOL, "\\\\/\\\\/"));

    ImmutableList<Pair<Pattern, SourceSortedSet>> platformHeaders =
        arg.getPlatformHeaders().getPatternsAndValues();
    ImmutableList.Builder<Pair<Pattern, Iterable<SourcePath>>> platformHeadersIterableBuilder =
        ImmutableList.builder();
    for (Pair<Pattern, SourceSortedSet> platformHeader : platformHeaders) {
      platformHeadersIterableBuilder.add(
          new Pair<>(platformHeader.getFirst(), getHeaderSourcePaths(platformHeader.getSecond())));
    }

    ImmutableList<Pair<Pattern, SourceSortedSet>> exportedPlatformHeadersPatternsAndValues =
        exportedPlatformHeaders.getPatternsAndValues();
    for (Pair<Pattern, SourceSortedSet> exportedPlatformHeader :
        exportedPlatformHeadersPatternsAndValues) {
      platformHeadersIterableBuilder.add(
          new Pair<>(
              exportedPlatformHeader.getFirst(),
              getHeaderSourcePaths(exportedPlatformHeader.getSecond())));
    }

    ImmutableList<Pair<Pattern, Iterable<SourcePath>>> platformHeadersIterable =
        platformHeadersIterableBuilder.build();

    ImmutableList<Pair<Pattern, ImmutableSortedSet<SourceWithFlags>>> platformSources =
        arg.getPlatformSrcs().getPatternsAndValues();
    ImmutableMap<String, ImmutableSortedSet<String>> platformExcludedSourcesMapping =
        XcodeNativeTargetGenerator.gatherExcludedSources(
            appleCxxFlavors,
            platformSources,
            platformHeadersIterable,
            sourceRoot,
            defaultPathResolver);
    for (Map.Entry<String, ImmutableSortedSet<String>> platformExcludedSources :
        platformExcludedSourcesMapping.entrySet()) {
      if (platformExcludedSources.getValue().size() > 0) {
        extraSettingsBuilder.put(
            platformExcludedSources.getKey(), String.join(" ", platformExcludedSources.getValue()));
      }
    }

    ImmutableSortedSet<SourceWithFlags> nonPlatformSrcs = arg.getSrcs();
    ImmutableSortedSet.Builder<SourceWithFlags> allSrcsBuilder = ImmutableSortedSet.naturalOrder();
    allSrcsBuilder.addAll(nonPlatformSrcs);
    for (Pair<Pattern, ImmutableSortedSet<SourceWithFlags>> platformSource : platformSources) {
      allSrcsBuilder.addAll(platformSource.getSecond());
    }

    ImmutableSortedSet<SourceWithFlags> allSrcs = allSrcsBuilder.build();

    xcodeNativeTargetAttributesBuilder
        .setLangPreprocessorFlags(
            ImmutableMap.copyOf(
                Maps.transformValues(
                    langPreprocessorFlags,
                    f ->
                        flagParser.convertStringWithMacros(
                            targetNode, f, requiredBuildTargetsBuilder))))
        .setPublicHeaders(exportedHeaders)
        .setPrefixHeader(getPrefixHeaderSourcePath(arg))
        .setSourcesWithFlags(ImmutableSet.copyOf(allSrcs))
        .setPrivateHeaders(headers)
        .setDirectResources(directResources)
        .setWrapperResources(wrapperResources)
        .setExtraXcodeSources(ImmutableSet.copyOf(arg.getExtraXcodeSources()))
        .setExtraXcodeFiles(ImmutableSet.copyOf(arg.getExtraXcodeFiles()));

    if (bundle.isPresent()) {
      HasAppleBundleFields bundleArg = bundle.get().getConstructorArg();
      xcodeNativeTargetAttributesBuilder.setInfoPlist(Optional.of(bundleArg.getInfoPlist()));
    }

    xcodeNativeTargetAttributesBuilder.setBridgingHeader(arg.getBridgingHeader());

    if (!directAssetCatalogs.isEmpty()) {
      xcodeNativeTargetAttributesBuilder.setDirectAssetCatalogs(directAssetCatalogs);
    }

    FluentIterable<TargetNode<?>> depTargetNodes = collectRecursiveLibraryDepTargets(targetNode);

    if (includeFrameworks) {
      if (!options.shouldAddLinkedLibrariesAsFlags()) {
        ImmutableSet.Builder<FrameworkPath> frameworksBuilder = ImmutableSet.builder();
        frameworksBuilder.addAll(collectRecursiveFrameworkDependencies(targetNode));
        frameworksBuilder.addAll(targetNode.getConstructorArg().getFrameworks());
        frameworksBuilder.addAll(targetNode.getConstructorArg().getLibraries());

        xcodeNativeTargetAttributesBuilder.setSystemFrameworks(frameworksBuilder.build());
      }

      if (sharedLibraryToBundle.isPresent()) {
        // Replace target nodes of libraries which are actually constituents of embedded
        // frameworks to the bundle representing the embedded framework.
        // This will be converted to a reference to the xcode build product for the embedded
        // framework rather than the dylib
        depTargetNodes = swapSharedLibrariesForBundles(depTargetNodes, sharedLibraryToBundle.get());
      }
    }

    FluentIterable<TargetNode<?>> swiftDepTargets =
        filterRecursiveLibraryDepTargetsWithSwiftSources(depTargetNodes);

    if (includeFrameworks
        && !swiftDepTargets.isEmpty()
        && shouldEmbedSwiftRuntimeInBundleTarget(bundle)
        && swiftBuckConfig.getProjectEmbedRuntime()) {
      // This is a binary that transitively depends on a library that uses Swift. We must ensure
      // that the Swift runtime is bundled.
      swiftDepsSettingsBuilder.put("ALWAYS_EMBED_SWIFT_STANDARD_LIBRARIES", "YES");
    }

    // Assume the BUCK file path is at the the base path of this target
    Path buckFilePath =
        buildTarget
            .getCellRelativeBasePath()
            .getPath()
            .toPath(projectFilesystem.getFileSystem())
            .resolve(buildFileName);
    xcodeNativeTargetAttributesBuilder.setBuckFilePath(Optional.of(buckFilePath));

    Optional<TargetNode<AppleNativeTargetDescriptionArg>> appleTargetNode =
        TargetNodes.castArg(targetNode, AppleNativeTargetDescriptionArg.class);
    if (appleTargetNode.isPresent()) {
      // Use Core Data models from immediate dependencies only.

      ImmutableList.Builder<CoreDataResource> coreDataFileBuilder = ImmutableList.builder();
      for (AppleWrapperResourceArg appleWrapperResourceArg : coreDataResources) {
        coreDataFileBuilder.add(
            CoreDataResource.fromResourceArgs(appleWrapperResourceArg, projectFilesystem));
      }
      xcodeNativeTargetAttributesBuilder.setCoreDataResources(coreDataFileBuilder.build());
    }

    ImmutableList.Builder<BuildTarget> dependencies = ImmutableList.builder();

    extraSettingsBuilder.putAll(swiftDepsSettingsBuilder.build());

    ImmutableSortedMap<Path, SourcePath> publicCxxHeaders =
        headerSearchPathAttributes.publicCxxHeaders();
    publicCxxHeaders.values().stream()
        .map(Utils::sourcePathTryIntoBuildTargetSourcePath)
        .filter(Optional::isPresent)
        .forEach(
            sourcePath ->
                Utils.addRequiredBuildTargetFromSourcePath(
                    sourcePath.get(),
                    requiredBuildTargetsBuilder,
                    targetGraph,
                    actionGraphBuilderForNode));

    if (NodeHelper.isModularAppleLibrary(targetNode) && isFrameworkProductType(productType)) {
      // Modular frameworks should not include Buck-generated hmaps as they break the VFS overlay
      // that's generated by Xcode and consequently, all headers part of a framework's umbrella
      // header fail the modularity test, as they're expected to be mapped by the VFS layer under
      // $BUILT_PRODUCTS_DIR/Module.framework/Versions/A/Headers.
      publicCxxHeaders = ImmutableSortedMap.of();
    }

    // Watch dependencies need to have explicit target dependencies setup in order for Xcode to
    // build them properly within the IDE.  It is unable to match the implicit dependency because
    // of the different in flavor between the targets (iphoneos vs watchos).
    if (bundle.isPresent()) {
      for (TargetNode<?> watchTargetNode : targetGraph.getAll(bundle.get().getExtraDeps())) {
        String targetNodeFlavorPostfix = watchTargetNode.getBuildTarget().getFlavorPostfix();
        if (targetNodeFlavorPostfix.startsWith("#watch")
            && !targetNodeFlavorPostfix.equals(targetNode.getBuildTarget().getFlavorPostfix())
            && watchTargetNode.getDescription() instanceof AppleBundleDescription) {
          dependencies.add(watchTargetNode.getBuildTarget());
        }
      }
    }

    // -- configurations
    extraSettingsBuilder
        .put("TARGET_NAME", buildTargetName)
        .put("SRCROOT", pathRelativizer.outputPathToBuildTargetPath(buildTarget).toString());
    if (productType == ProductTypes.UI_TEST) {
      if (bundleLoaderNode.isPresent()) {
        BuildTarget testTarget = bundleLoaderNode.get().getBuildTarget();
        extraSettingsBuilder.put("TEST_TARGET_NAME", testTarget.getFullyQualifiedName());
        dependencies.add(testTarget);
      } else {
        throw new HumanReadableException(
            "The test rule '%s' is configured with 'is_ui_test' but has no test_host_app",
            buildTargetName);
      }
    } else if (bundleLoaderNode.isPresent()) {
      TargetNode<AppleBundleDescriptionArg> bundleLoader = bundleLoaderNode.get();
      String bundleLoaderProductName = getProductName(bundleLoader);
      String bundleLoaderBundleName =
          bundleLoaderProductName
              + "."
              + getExtensionString(bundleLoader.getConstructorArg().getExtension());
      // NOTE(grp): This is a hack. We need to support both deep (OS X) and flat (iOS)
      // style bundles for the bundle loader, but at this point we don't know what platform
      // the bundle loader (or current target) is going to be built for. However, we can be
      // sure that it's the same as the target (presumably a test) we're building right now.
      //
      // Using that knowledge, we can do build setting tricks to defer choosing the bundle
      // loader path until Xcode build time, when the platform is known. There's no build
      // setting that conclusively says whether the current platform uses deep bundles:
      // that would be too easy. But in the cases we care about (unit test bundles), the
      // current bundle will have a style matching the style of the bundle loader app, so
      // we can take advantage of that to do the determination.
      //
      // Unfortunately, the build setting for the bundle structure (CONTENTS_FOLDER_PATH)
      // includes the WRAPPER_NAME, so we can't just interpolate that in. Instead, we have
      // to use another trick with build setting operations and evaluation. By using the
      // $(:file) operation, we can extract the last component of the contents path: either
      // "Contents" or the current bundle name. Then, we can interpolate with that expected
      // result in the build setting name to conditionally choose a different loader path.

      // The conditional that decides which path is used. This is a complex Xcode build setting
      // expression that expands to one of two values, depending on the last path component of
      // the CONTENTS_FOLDER_PATH variable. As described above, this will be either "Contents"
      // for deep bundles or the bundle file name itself for flat bundles. Finally, to santiize
      // the potentially invalid build setting names from the bundle file name, it converts that
      // to an identifier. We rely on BUNDLE_LOADER_BUNDLE_STYLE_CONDITIONAL_<bundle file name>
      // being undefined (and thus expanding to nothing) for the path resolution to work.
      //
      // The operations on the CONTENTS_FOLDER_PATH are documented here:
      // http://codeworkshop.net/posts/xcode-build-setting-transformations
      String bundleLoaderOutputPathConditional =
          "$(BUNDLE_LOADER_BUNDLE_STYLE_CONDITIONAL_$(CONTENTS_FOLDER_PATH:file:identifier))";

      // If the $(CONTENTS_FOLDER_PATH:file:identifier) expands to this, we add the deep bundle
      // path into the bundle loader. See above for the case when it will expand to this value.
      extraSettingsBuilder.put(
          "BUNDLE_LOADER_BUNDLE_STYLE_CONDITIONAL_Contents",
          Joiner.on('/')
              .join(
                  getTargetOutputPath(bundleLoader),
                  bundleLoaderBundleName,
                  "Contents/MacOS",
                  bundleLoaderProductName));

      extraSettingsBuilder.put(
          "BUNDLE_LOADER_BUNDLE_STYLE_CONDITIONAL_"
              + getProductName(bundle.get())
              + "_"
              + getExtensionString(bundle.get().getConstructorArg().getExtension()),
          Joiner.on('/')
              .join(
                  getTargetOutputPath(bundleLoader),
                  bundleLoaderBundleName,
                  bundleLoaderProductName));

      extraSettingsBuilder
          .put("BUNDLE_LOADER", bundleLoaderOutputPathConditional)
          .put("TEST_HOST", "$(BUNDLE_LOADER)");

      dependencies.add(bundleLoader.getBuildTarget());
    }
    if (infoPlistOptional.isPresent()) {
      Path infoPlistPath = pathRelativizer.outputDirToRootRelative(infoPlistOptional.get());
      extraSettingsBuilder.put("INFOPLIST_FILE", infoPlistPath.toString());
    }
    if (arg.getBridgingHeader().isPresent()) {
      Path bridgingHeaderPath =
          pathRelativizer.outputDirToRootRelative(resolveSourcePath(arg.getBridgingHeader().get()));
      extraSettingsBuilder.put(
          "SWIFT_OBJC_BRIDGING_HEADER",
          Joiner.on('/').join("$(SRCROOT)", bridgingHeaderPath.toString()));
    }

    swiftVersion.ifPresent(s -> extraSettingsBuilder.put("SWIFT_VERSION", s));
    swiftVersion.ifPresent(
        s -> extraSettingsBuilder.put("PRODUCT_MODULE_NAME", swiftAttributes.moduleName()));

    if (hasSwiftVersionArg && containsSwiftCode) {
      extraSettingsBuilder.put(
          "SWIFT_OBJC_INTERFACE_HEADER_NAME",
          SwiftAttributeParser.getSwiftObjCGeneratedHeaderName(buildTargetNode, Optional.empty()));

      if (swiftBuckConfig.getProjectWMO()) {
        // We must disable "Index While Building" as there's a bug in the LLVM infra which
        // makes the compilation fail.
        extraSettingsBuilder.put("COMPILER_INDEX_STORE_ENABLE", "NO");

        // This is a hidden Xcode setting which is needed for two reasons:
        // - Stops Xcode adding .o files for each Swift compilation unit to dependency db
        //   which is used during linking (which will fail with WMO).
        // - Turns on WMO itself.
        //
        // Note that setting SWIFT_OPTIMIZATION_LEVEL (which is public) to '-Owholemodule'
        // ends up crashing the Swift compiler for some reason while this doesn't.
        extraSettingsBuilder.put("SWIFT_WHOLE_MODULE_OPTIMIZATION", "YES");
      }
    }

    Optional<SourcePath> prefixHeaderOptional =
        getPrefixHeaderSourcePath(targetNode.getConstructorArg());
    if (prefixHeaderOptional.isPresent()) {
      Path prefixHeaderRelative = resolveSourcePath(prefixHeaderOptional.get());
      Path prefixHeaderPath = pathRelativizer.outputDirToRootRelative(prefixHeaderRelative);
      extraSettingsBuilder.put("GCC_PREFIX_HEADER", prefixHeaderPath.toString());
      extraSettingsBuilder.put("GCC_PRECOMPILE_PREFIX_HEADER", "YES");
    }

    boolean shouldSetUseHeadermap = false;
    if (isModularAppleLibrary) {
      extraSettingsBuilder.put("CLANG_ENABLE_MODULES", "YES");
      extraSettingsBuilder.put("DEFINES_MODULE", "YES");

      if (isFrameworkProductType(productType)) {
        // Modular frameworks need to have both USE_HEADERMAP enabled so that Xcode generates
        // .framework VFS overlays, in modular libraries we handle this in buck
        shouldSetUseHeadermap = true;
      }
    }
    extraSettingsBuilder.put("USE_HEADERMAP", shouldSetUseHeadermap ? "YES" : "NO");

    AbsPath repoRoot = projectFilesystem.getRootPath().normalize();
    defaultSettingsBuilder.put("REPO_ROOT", repoRoot.toString());
    if (hasSwiftVersionArg && containsSwiftCode) {
      // We need to be able to control the directory where Xcode places the derived sources, so
      // that the Obj-C Generated Header can be included in the header map and imported through
      // a framework-style import like <Module/Module-Swift.h>
      Path derivedSourcesDir =
          com.facebook.buck.features.apple.projectV2.Utils.getDerivedSourcesDirectoryForBuildTarget(
              buildTarget, projectFilesystem);
      defaultSettingsBuilder.put(
          "DERIVED_FILE_DIR", repoRoot.resolve(derivedSourcesDir).toString());
    }

    defaultSettingsBuilder.put(PRODUCT_NAME, getProductName(buildTargetNode));
    bundle.ifPresent(
        bundleNode ->
            defaultSettingsBuilder.put(
                "WRAPPER_EXTENSION",
                getExtensionString(bundleNode.getConstructorArg().getExtension())));

    // We use BUILT_PRODUCTS_DIR as the root for the everything being built. Target-
    // specific output is placed within CONFIGURATION_BUILD_DIR, inside BUILT_PRODUCTS_DIR.
    // That allows Copy Files build phases to reference files in the CONFIGURATION_BUILD_DIR
    // of other targets by using paths relative to the target-independent BUILT_PRODUCTS_DIR.
    defaultSettingsBuilder.put(
        "BUILT_PRODUCTS_DIR",
        // $EFFECTIVE_PLATFORM_NAME starts with a dash, so this expands to something like:
        // $SYMROOT/Debug-iphonesimulator
        Joiner.on('/').join("$SYMROOT", "$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"));
    defaultSettingsBuilder.put("CONFIGURATION_BUILD_DIR", "$BUILT_PRODUCTS_DIR");
    boolean nodeIsAppleLibrary = targetNode.getDescription() instanceof AppleLibraryDescription;
    boolean nodeIsCxxLibrary = targetNode.getDescription() instanceof CxxLibraryDescription;
    if (!bundle.isPresent() && (nodeIsAppleLibrary || nodeIsCxxLibrary)) {
      defaultSettingsBuilder.put("EXECUTABLE_PREFIX", "lib");
    }

    Set<Path> recursivePublicSystemIncludeDirectories =
        headerSearchPathAttributes.recursivePublicSystemIncludeDirectories();

    Builder<String, String> appendConfigsBuilder = ImmutableMap.builder();
    appendConfigsBuilder.putAll(
        getFrameworkAndLibrarySearchPathConfigs(
            targetNode, xcodeNativeTargetAttributesBuilder, includeFrameworks));
    appendConfigsBuilder.put(
        "HEADER_SEARCH_PATHS",
        Joiner.on(' ')
            .join(
                Iterables.concat(
                    headerSearchPathAttributes.recursiveHeaderSearchPaths(),
                    recursivePublicSystemIncludeDirectories,
                    headerSearchPathAttributes.recursivePublicIncludeDirectories(),
                    headerSearchPathAttributes.includeDirectories())));
    if (hasSwiftVersionArg) {
      Stream<String> allValues =
          Streams.concat(
              Stream.of("$BUILT_PRODUCTS_DIR"),
              Streams.stream(headerSearchPathAttributes.swiftIncludePaths())
                  .map((path) -> path.toString())
                  .map(Escaper.BASH_ESCAPER));
      appendConfigsBuilder.put("SWIFT_INCLUDE_PATHS", allValues.collect(Collectors.joining(" ")));
    }

    flagParser.parseFlags(
        targetNode,
        includeFrameworks,
        swiftDepTargets,
        containsSwiftCode,
        isModularAppleLibrary,
        publicCxxHeaders.size() > 0,
        recursivePublicSystemIncludeDirectories,
        appendConfigsBuilder,
        requiredBuildTargetsBuilder);

    ImmutableMap<String, String> appendedConfig = appendConfigsBuilder.build();

    BuildConfiguration.writeBuildConfigurationsForTarget(
        targetNode,
        buildTarget,
        defaultCxxPlatform,
        defaultPathResolver,
        xcodeNativeTargetAttributesBuilder,
        extraSettingsBuilder.build(),
        defaultSettingsBuilder.build(),
        appendedConfig,
        projectFilesystem,
        options.shouldGenerateReadOnlyFiles(),
        targetConfigNamesBuilder,
        xcconfigPathsBuilder);

    if (bundle.isPresent()) {
      addEntitlementsPlistIntoTarget(bundle.get(), xcodeNativeTargetAttributesBuilder);
    }

    return dependencies.build();
  }

  /** Generate a mapping from libraries to the framework bundles that include them. */
  public static ImmutableMap<BuildTarget, TargetNode<?>> computeSharedLibrariesToBundles(
      ImmutableSet<TargetNode<?>> targetNodes, TargetGraph targetGraph)
      throws HumanReadableException {

    Map<BuildTarget, TargetNode<?>> sharedLibraryToBundle = new HashMap<>();
    for (TargetNode<?> targetNode : targetNodes) {
      Optional<TargetNode<CommonArg>> binaryNode =
          TargetNodes.castArg(targetNode, AppleBundleDescriptionArg.class)
              .flatMap(bundleNode -> bundleNode.getConstructorArg().getBinary())
              .map(target -> targetGraph.get(target))
              .flatMap(node -> TargetNodes.castArg(node, CommonArg.class));
      if (!binaryNode.isPresent()) {
        continue;
      }
      CommonArg arg = binaryNode.get().getConstructorArg();
      if (arg.getPreferredLinkage().equals(Optional.of(Linkage.SHARED))) {
        BuildTarget binaryBuildTargetWithoutFlavors =
            binaryNode.get().getBuildTarget().withoutFlavors();
        if (sharedLibraryToBundle.containsKey(binaryBuildTargetWithoutFlavors)) {
          throw new HumanReadableException(
              String.format(
                  "Library %s is declared as the 'binary' of multiple bundles:\n first bundle: %s\n second bundle: %s",
                  binaryBuildTargetWithoutFlavors,
                  sharedLibraryToBundle.get(binaryBuildTargetWithoutFlavors).getBuildTarget(),
                  targetNode.getBuildTarget()));
        } else {
          sharedLibraryToBundle.put(binaryBuildTargetWithoutFlavors, targetNode);
        }
      }
    }
    return ImmutableMap.copyOf(sharedLibraryToBundle);
  }

  @VisibleForTesting
  static FluentIterable<TargetNode<?>> swapSharedLibrariesForBundles(
      FluentIterable<TargetNode<?>> targetDeps,
      ImmutableMap<BuildTarget, TargetNode<?>> sharedLibrariesToBundles) {
    return targetDeps.transform(t -> sharedLibrariesToBundles.getOrDefault(t.getBuildTarget(), t));
  }

  @SuppressWarnings("unchecked")
  private static Optional<TargetNode<CxxLibraryDescription.CommonArg>> getAppleNativeNodeOfType(
      TargetGraph targetGraph,
      TargetNode<?> targetNode,
      Set<Class<? extends DescriptionWithTargetGraph<?>>> nodeTypes,
      Set<AppleBundleExtension> bundleExtensions) {
    Optional<TargetNode<CxxLibraryDescription.CommonArg>> nativeNode = Optional.empty();
    if (nodeTypes.contains(targetNode.getDescription().getClass())) {
      nativeNode = Optional.of((TargetNode<CxxLibraryDescription.CommonArg>) targetNode);
    } else if (targetNode.getDescription() instanceof AppleBundleDescription) {
      TargetNode<AppleBundleDescriptionArg> bundle =
          (TargetNode<AppleBundleDescriptionArg>) targetNode;
      Either<AppleBundleExtension, String> extension = bundle.getConstructorArg().getExtension();
      if (extension.isLeft() && bundleExtensions.contains(extension.getLeft())) {
        nativeNode =
            Optional.of(
                (TargetNode<CxxLibraryDescription.CommonArg>)
                    targetGraph.get(getBundleBinaryTarget(bundle)));
      }
    }
    return nativeNode;
  }

  private static Optional<TargetNode<CxxLibraryDescription.CommonArg>> getLibraryNode(
      TargetGraph targetGraph, TargetNode<?> targetNode) {
    return getAppleNativeNodeOfType(
        targetGraph,
        targetNode,
        ImmutableSet.of(AppleLibraryDescription.class, CxxLibraryDescription.class),
        ImmutableSet.of(AppleBundleExtension.FRAMEWORK));
  }

  /** List of frameworks and libraries that goes into the "Link Binary With Libraries" phase. */
  private Iterable<FrameworkPath> collectRecursiveFrameworkDependencies(TargetNode<?> targetNode) {
    return FluentIterable.from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                xcodeDescriptions,
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                targetNode,
                ImmutableSet.<Class<? extends BaseDescription<?>>>builder()
                    .addAll(xcodeDescriptions.getXCodeDescriptions())
                    .add(PrebuiltAppleFrameworkDescription.class)
                    .build()))
        .transformAndConcat(
            input -> {
              // Libraries and bundles which has system frameworks and libraries.
              Optional<TargetNode<CxxLibraryDescription.CommonArg>> library =
                  getLibraryNode(targetGraph, input);
              if (library.isPresent()
                  && !AppleLibraryDescription.isNotStaticallyLinkedLibraryNode(library.get())) {
                return Iterables.concat(
                    library.get().getConstructorArg().getFrameworks(),
                    library.get().getConstructorArg().getLibraries());
              }

              Optional<TargetNode<PrebuiltAppleFrameworkDescriptionArg>> prebuilt =
                  TargetNodes.castArg(input, PrebuiltAppleFrameworkDescriptionArg.class);
              if (prebuilt.isPresent()) {
                return Iterables.concat(
                    prebuilt.get().getConstructorArg().getFrameworks(),
                    prebuilt.get().getConstructorArg().getLibraries(),
                    ImmutableList.of(
                        FrameworkPath.ofSourcePath(
                            prebuilt.get().getConstructorArg().getFramework())));
              }
              Optional<TargetNode<PrebuiltCxxLibraryDescriptionArg>> prebuiltCxxLib =
                  TargetNodes.castArg(input, PrebuiltCxxLibraryDescriptionArg.class);
              if (prebuiltCxxLib.isPresent()) {
                Iterable<FrameworkPath> deps =
                    Iterables.concat(
                        prebuiltCxxLib.get().getConstructorArg().getFrameworks(),
                        prebuiltCxxLib.get().getConstructorArg().getLibraries());
                if (prebuiltCxxLib.get().getConstructorArg().getSharedLib().isPresent()) {
                  return Iterables.concat(
                      deps,
                      ImmutableList.of(
                          FrameworkPath.ofSourcePath(
                              prebuiltCxxLib.get().getConstructorArg().getSharedLib().get())));
                } else if (prebuiltCxxLib.get().getConstructorArg().getStaticLib().isPresent()) {
                  return Iterables.concat(
                      deps,
                      ImmutableList.of(
                          FrameworkPath.ofSourcePath(
                              prebuiltCxxLib.get().getConstructorArg().getStaticLib().get())));
                } else if (prebuiltCxxLib.get().getConstructorArg().getStaticPicLib().isPresent()) {
                  return Iterables.concat(
                      deps,
                      ImmutableList.of(
                          FrameworkPath.ofSourcePath(
                              prebuiltCxxLib.get().getConstructorArg().getStaticPicLib().get())));
                }
              }

              return ImmutableList.of();
            });
  }

  private boolean shouldEmbedSwiftRuntimeInBundleTarget(
      Optional<? extends TargetNode<? extends HasAppleBundleFields>> bundle) {
    return bundle
        .map(
            b ->
                b.getConstructorArg()
                    .getExtension()
                    .transform(
                        bundleExtension -> {
                          switch (bundleExtension) {
                            case APP:
                            case APPEX:
                            case PLUGIN:
                            case BUNDLE:
                            case XCTEST:
                            case PREFPANE:
                            case XPC:
                            case QLGENERATOR:
                              // All of the above bundles can have loaders which do not contain
                              // a Swift runtime, so it must get bundled to ensure they run.
                              return true;

                            case FRAMEWORK:
                            case DSYM:
                              return false;
                          }

                          return false;
                        },
                        stringExtension -> false))
        .orElse(false);
  }

  private boolean isFrameworkProductType(ProductType productType) {
    return productType == ProductTypes.FRAMEWORK || productType == ProductTypes.STATIC_FRAMEWORK;
  }

  private ImmutableMap<String, String> getFrameworkAndLibrarySearchPathConfigs(
      TargetNode<? extends CommonArg> node,
      ImmutableXCodeNativeTargetAttributes.Builder nativeTargetBuilder,
      boolean includeFrameworks) {
    HashSet<String> frameworkSearchPaths = new HashSet<>();
    frameworkSearchPaths.add("$BUILT_PRODUCTS_DIR");
    HashSet<String> librarySearchPaths = new HashSet<>();
    librarySearchPaths.add("$BUILT_PRODUCTS_DIR");
    List<String> iOSLdRunpathSearchPaths = Lists.newArrayList();
    List<String> macOSLdRunpathSearchPaths = Lists.newArrayList();

    FluentIterable<TargetNode<?>> depTargetNodes = collectRecursiveLibraryDepTargets(node);
    FluentIterable<TargetNode<?>> swiftDeps =
        filterRecursiveLibraryDepTargetsWithSwiftSources(depTargetNodes);
    for (TargetNode<?> swiftDep : swiftDeps) {
      addLibraryFileReferenceToTarget(swiftDep, nativeTargetBuilder);
    }

    Stream.concat(
            // Collect all the nodes that contribute to linking
            // ... Which the node includes itself
            Stream.of(node),
            // ... And recursive dependencies that gets linked in
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                xcodeDescriptions,
                targetGraph,
                Optional.of(dependenciesCache),
                RecursiveDependenciesMode.LINKING,
                node,
                ImmutableSet.of(
                    AppleLibraryDescription.class,
                    CxxLibraryDescription.class,
                    PrebuiltAppleFrameworkDescription.class,
                    PrebuiltCxxLibraryDescription.class))
                .stream())
        .map(
            castedNode -> {
              // If the item itself is a prebuilt library, add it to framework_search_paths.
              // This is needed for prebuilt framework's headers to be reference-able.
              TargetNodes.castArg(castedNode, PrebuiltCxxLibraryDescriptionArg.class)
                  .ifPresent(
                      prebuilt -> {
                        SourcePath path = null;
                        if (prebuilt.getConstructorArg().getSharedLib().isPresent()) {
                          path = prebuilt.getConstructorArg().getSharedLib().get();
                        } else if (prebuilt.getConstructorArg().getStaticLib().isPresent()) {
                          path = prebuilt.getConstructorArg().getStaticLib().get();
                        } else if (prebuilt.getConstructorArg().getStaticPicLib().isPresent()) {
                          path = prebuilt.getConstructorArg().getStaticPicLib().get();
                        }
                        if (path != null) {
                          librarySearchPaths.add(
                              "$REPO_ROOT/" + resolveSourcePath(path).getParent());
                        }
                      });
              return castedNode;
            })
        // Keep only the ones that may have frameworks and libraries fields.
        .flatMap(
            input ->
                RichStream.from(TargetNodes.castArg(input, HasSystemFrameworkAndLibraries.class)))
        // Then for each of them
        .forEach(
            castedNode -> {
              // ... Add the framework path strings.
              castedNode.getConstructorArg().getFrameworks().stream()
                  .filter(x -> !x.isSDKROOTFrameworkPath())
                  .map(
                      frameworkPath ->
                          FrameworkPath.getUnexpandedSearchPath(
                                  projectSourcePathResolver::resolveSourcePath,
                                  pathRelativizer::outputDirToRootRelative,
                                  frameworkPath)
                              .toString())
                  .forEach(frameworkSearchPaths::add);

              // ... And do the same for libraries.
              castedNode.getConstructorArg().getLibraries().stream()
                  .map(
                      libraryPath ->
                          FrameworkPath.getUnexpandedSearchPath(
                                  projectSourcePathResolver::resolveSourcePath,
                                  pathRelativizer::outputDirToRootRelative,
                                  libraryPath)
                              .toString())
                  .forEach(librarySearchPaths::add);

              // If the item itself is a prebuilt framework, add it to framework_search_paths.
              // This is needed for prebuilt framework's headers to be reference-able.
              TargetNodes.castArg(castedNode, PrebuiltAppleFrameworkDescriptionArg.class)
                  .ifPresent(
                      prebuilt -> {
                        frameworkSearchPaths.add(
                            "$REPO_ROOT/"
                                + resolveSourcePath(prebuilt.getConstructorArg().getFramework())
                                    .getParent());
                        if (prebuilt.getConstructorArg().getPreferredLinkage() != Linkage.STATIC) {
                          // Frameworks that are copied into the binary.
                          if (options.shouldLinkSystemSwift()) {
                            iOSLdRunpathSearchPaths.add("/usr/lib/swift");
                            macOSLdRunpathSearchPaths.add("/usr/lib/swift");
                          }

                          iOSLdRunpathSearchPaths.add("@loader_path/Frameworks");
                          iOSLdRunpathSearchPaths.add("@executable_path/Frameworks");

                          macOSLdRunpathSearchPaths.add("@loader_path/../Frameworks");
                          macOSLdRunpathSearchPaths.add("@executable_path/../Frameworks");
                        }
                      });
            });

    if (includeFrameworks && swiftDeps.size() > 0) {
      // When Xcode compiles static Swift libs, it will include linker commands (LC_LINKER_OPTION)
      // that will be carried over for the final binary to link to the appropriate Swift overlays
      // and libs. This means that the final binary must be able to locate the Swift libs in the
      // library search path. If an Xcode target includes Swift, Xcode will automatically append
      // the Swift lib folder when invoking the linker. Unfortunately, this will not happen if
      // we have a plain apple_binary that has Swift deps. So we're manually doing exactly what
      // Xcode does to make sure binaries link successfully if they use Swift directly or
      // transitively.

      // I'm not sure how to look for the correct folder here, so just adding both for now, if the
      // folder changes in a future release this can be revisited.
      librarySearchPaths.add("$DT_TOOLCHAIN_DIR/usr/lib/swift/$PLATFORM_NAME");
      if (options.shouldLinkSystemSwift()) {
        librarySearchPaths.add("$DT_TOOLCHAIN_DIR/usr/lib/swift-5.0/$PLATFORM_NAME");
      }
    }

    if (swiftDeps.size() > 0 || projGenerationStateCache.targetContainsSwiftSourceCode(node)) {
      if (options.shouldLinkSystemSwift()) {
        iOSLdRunpathSearchPaths.add("/usr/lib/swift");
        macOSLdRunpathSearchPaths.add("/usr/lib/swift");
      }

      iOSLdRunpathSearchPaths.add("@executable_path/Frameworks");
      iOSLdRunpathSearchPaths.add("@loader_path/Frameworks");

      macOSLdRunpathSearchPaths.add("@executable_path/../Frameworks");
      macOSLdRunpathSearchPaths.add("@loader_path/../Frameworks");
    }

    Builder<String, String> results =
        ImmutableMap.<String, String>builder()
            .put("FRAMEWORK_SEARCH_PATHS", Joiner.on(' ').join(frameworkSearchPaths))
            .put("LIBRARY_SEARCH_PATHS", Joiner.on(' ').join(librarySearchPaths));
    if (!iOSLdRunpathSearchPaths.isEmpty()) {
      results.put(
          "LD_RUNPATH_SEARCH_PATHS[sdk=iphoneos*]", Joiner.on(' ').join(iOSLdRunpathSearchPaths));
      results.put(
          "LD_RUNPATH_SEARCH_PATHS[sdk=iphonesimulator*]",
          Joiner.on(' ').join(iOSLdRunpathSearchPaths));
    }
    if (!macOSLdRunpathSearchPaths.isEmpty()) {
      results.put(
          "LD_RUNPATH_SEARCH_PATHS[sdk=macosx*]", Joiner.on(' ').join(macOSLdRunpathSearchPaths));
    }
    return results.build();
  }

  private void addEntitlementsPlistIntoTarget(
      TargetNode<? extends HasAppleBundleFields> targetNode,
      ImmutableXCodeNativeTargetAttributes.Builder nativeTargetBuilder) {
    ImmutableMap<String, String> infoPlistSubstitutions =
        targetNode.getConstructorArg().getInfoPlistSubstitutions();

    if (infoPlistSubstitutions.containsKey(AppleBundle.CODE_SIGN_ENTITLEMENTS)) {
      // Expand SOURCE_ROOT to the target base path so we can get the full proper path to the
      // entitlements file instead of a path relative to the project.
      String targetPath =
          targetNode.getBuildTarget().getCellRelativeBasePath().getPath().toString();
      String entitlementsPlistPath =
          InfoPlistSubstitution.replaceVariablesInString(
              "$(" + AppleBundle.CODE_SIGN_ENTITLEMENTS + ")",
              AppleBundle.withDefaults(
                  infoPlistSubstitutions,
                  ImmutableMap.of(
                      "SOURCE_ROOT", targetPath,
                      "SRCROOT", targetPath)));

      nativeTargetBuilder.setEntitlementsPlistPath(Optional.of(Paths.get(entitlementsPlistPath)));
    }
  }

  @VisibleForTesting
  static Iterable<SourcePath> getHeaderSourcePaths(SourceSortedSet headers) {
    if (headers.getUnnamedSources().isPresent()) {
      return headers.getUnnamedSources().get();
    } else {
      return headers.getNamedSources().get().values();
    }
  }

  private String getProductName(TargetNode<?> buildTargetNode) {
    return TargetNodes.castArg(buildTargetNode, AppleBundleDescriptionArg.class)
        .flatMap(node -> node.getConstructorArg().getProductName())
        .orElse(getProductNameForBuildTargetNode(buildTargetNode));
  }

  private String getProductNameForBuildTargetNode(TargetNode<?> targetNode) {
    Optional<TargetNode<CommonArg>> library = NodeHelper.getLibraryNode(targetGraph, targetNode);
    boolean isStaticLibrary =
        library.isPresent()
            && !AppleLibraryDescription.isNotStaticallyLinkedLibraryNode(library.get());
    if (isStaticLibrary) {
      Optional<String> basename = library.get().getConstructorArg().getStaticLibraryBasename();
      if (basename.isPresent()) {
        return basename.get();
      }
      return CxxDescriptionEnhancer.getStaticLibraryBasename(
          targetNode.getBuildTarget(), "", cxxBuckConfig.isUniqueLibraryNameEnabled());
    } else {
      return targetNode.getBuildTarget().getShortName();
    }
  }

  private String getBuiltProductsRelativeTargetOutputPath(TargetNode<?> targetNode) {
    if (targetNode.getDescription() instanceof AppleBinaryDescription
        || targetNode.getDescription() instanceof AppleTestDescription
        || (targetNode.getDescription() instanceof AppleBundleDescription
            && !isFrameworkBundle((AppleBundleDescriptionArg) targetNode.getConstructorArg()))) {
      // TODO(grp): These should be inside the path below. Right now, that causes issues with
      // bundle loader paths hardcoded in .xcconfig files that don't expect the full target path.
      // It also causes issues where Xcode doesn't know where to look for a final .app to run it.
      return ".";
    } else {
      return BaseEncoding.base32()
          .omitPadding()
          .encode(targetNode.getBuildTarget().getFullyQualifiedName().getBytes());
    }
  }

  private String getTargetOutputPath(TargetNode<?> targetNode) {
    return Joiner.on('/')
        .join("$BUILT_PRODUCTS_DIR", getBuiltProductsRelativeTargetOutputPath(targetNode));
  }

  private boolean shouldExcludeLibraryFromProject(TargetNode<?> targetNode) {
    // targets with flavor #compilation-database are not meant to be built by Xcode, they are used
    // only to generate the compile commands for a library during buck build
    return targetNode
        .getBuildTarget()
        .getFlavors()
        .contains(CxxCompilationDatabase.COMPILATION_DATABASE);
  }

  private FluentIterable<TargetNode<?>> collectRecursiveLibraryDepTargets(
      TargetNode<?> targetNode) {
    FluentIterable<TargetNode<?>> allDeps =
        FluentIterable.from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                xcodeDescriptions,
                targetGraph,
                Optional.of(dependenciesCache),
                RecursiveDependenciesMode.LINKING,
                targetNode,
                xcodeDescriptions.getXCodeDescriptions()));
    return allDeps.filter(this::isLibraryWithSourcesToCompile);
  }

  private FluentIterable<TargetNode<?>> filterRecursiveLibraryDepTargetsWithSwiftSources(
      FluentIterable<TargetNode<?>> targetNodes) {
    return targetNodes.filter(this::isLibraryWithSwiftSources);
  }

  private String getProductOutputBaseName(TargetNode<?> targetNode) {
    String productName = getProductNameForBuildTargetNode(targetNode);
    if (targetNode.getDescription() instanceof AppleBundleDescription
        || targetNode.getDescription() instanceof AppleTestDescription) {
      HasAppleBundleFields arg = (HasAppleBundleFields) targetNode.getConstructorArg();
      productName = arg.getProductName().orElse(productName);
    }
    return productName;
  }

  private String getProductOutputNameWithExtension(TargetNode<?> targetNode) {
    String productName = getProductOutputBaseName(targetNode);
    String productOutputName;

    if (targetNode.getDescription() instanceof AppleLibraryDescription
        || targetNode.getDescription() instanceof CxxLibraryDescription
        || targetNode.getDescription() instanceof HalideLibraryDescription) {
      String productOutputFormat =
          AppleBuildRules.getOutputFileNameFormatForLibrary(
              targetNode
                  .getBuildTarget()
                  .getFlavors()
                  .contains(CxxDescriptionEnhancer.SHARED_FLAVOR));
      productOutputName = String.format(productOutputFormat, productName);
    } else if (targetNode.getDescription() instanceof AppleBundleDescription
        || targetNode.getDescription() instanceof AppleTestDescription) {
      HasAppleBundleFields arg = (HasAppleBundleFields) targetNode.getConstructorArg();
      productOutputName = productName + "." + getExtensionString(arg.getExtension());
    } else if (targetNode.getDescription() instanceof AppleBinaryDescription) {
      productOutputName = productName;
    } else if (targetNode.getDescription() instanceof PrebuiltAppleFrameworkDescription) {
      PrebuiltAppleFrameworkDescriptionArg arg =
          (PrebuiltAppleFrameworkDescriptionArg) targetNode.getConstructorArg();
      productOutputName = pathRelativizer.outputPathToSourcePath(arg.getFramework()).toString();
    } else {
      throw new RuntimeException("Unexpected type: " + targetNode.getDescription().getClass());
    }
    return productOutputName;
  }

  private void addLibraryFileReferenceToTarget(
      TargetNode<?> targetNode, ImmutableXCodeNativeTargetAttributes.Builder nativeTargetBuilder) {
    String productOutputName = getProductOutputNameWithExtension(targetNode);
    PBXReference.SourceTree path = PBXReference.SourceTree.BUILT_PRODUCTS_DIR;
    if (targetNode.getDescription() instanceof PrebuiltAppleFrameworkDescription) {
      path = PBXReference.SourceTree.SOURCE_ROOT;
    }

    SourceTreePath productsPath =
        new SourceTreePath(path, Paths.get(productOutputName), Optional.empty());
    if (isWatchApplicationNode(targetNode)) {
      nativeTargetBuilder.addProducts(productsPath);
    } else if (targetNode.getDescription() instanceof AppleLibraryDescription
        || targetNode.getDescription() instanceof AppleBundleDescription
        || targetNode.getDescription() instanceof CxxLibraryDescription
        || targetNode.getDescription() instanceof HalideLibraryDescription
        || targetNode.getDescription() instanceof PrebuiltAppleFrameworkDescription) {
      nativeTargetBuilder.addFrameworks(productsPath);
    } else if (targetNode.getDescription() instanceof AppleBinaryDescription) {
      nativeTargetBuilder.addDependencies(productsPath);
    } else {
      throw new RuntimeException("Unexpected type: " + targetNode.getDescription().getClass());
    }
  }

  private static String getExtensionString(Either<AppleBundleExtension, String> extension) {
    return extension.isLeft() ? extension.getLeft().toFileExtension() : extension.getRight();
  }

  private static boolean isFrameworkBundle(HasAppleBundleFields arg) {
    return arg.getExtension().isLeft()
        && arg.getExtension().getLeft().equals(AppleBundleExtension.FRAMEWORK);
  }

  private Path resolveSourcePath(SourcePath sourcePath) {
    return projectSourcePathResolver.resolveSourcePath(sourcePath);
  }

  private boolean isLibraryWithSourcesToCompile(TargetNode<?> input) {
    if (input.getDescription() instanceof HalideLibraryDescription) {
      return true;
    }

    Optional<TargetNode<CommonArg>> library = NodeHelper.getLibraryNode(targetGraph, input);

    if (!library.isPresent()) {
      return false;
    }
    PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSources =
        library.get().getConstructorArg().getPlatformSrcs();
    int platFormSourcesSize = platformSources.getValues().size();
    return (library.get().getConstructorArg().getSrcs().size() + platFormSourcesSize != 0);
  }

  private boolean isLibraryWithSwiftSources(TargetNode<?> input) {
    Optional<TargetNode<CommonArg>> library = NodeHelper.getLibraryNode(targetGraph, input);
    return library.filter(projGenerationStateCache::targetContainsSwiftSourceCode).isPresent();
  }

  /**
   * Determines if a target node is for watchOS2 application
   *
   * @param targetNode A target node
   * @return If the given target node is for an watchOS2 application
   */
  private static boolean isWatchApplicationNode(TargetNode<?> targetNode) {
    if (targetNode.getDescription() instanceof AppleBundleDescription) {
      AppleBundleDescriptionArg arg = (AppleBundleDescriptionArg) targetNode.getConstructorArg();
      return arg.getXcodeProductType()
          .equals(Optional.of(ProductTypes.WATCH_APPLICATION.getIdentifier()));
    }
    return false;
  }

  private Optional<SourcePath> getPrefixHeaderSourcePath(CommonArg arg) {
    // The prefix header could be stored in either the `prefix_header` or the `precompiled_header`
    // field. Use either, but prefer the prefix_header.
    if (arg.getPrefixHeader().isPresent()) {
      return arg.getPrefixHeader();
    }

    if (!arg.getPrecompiledHeader().isPresent()) {
      return Optional.empty();
    }

    SourcePath pchPath = arg.getPrecompiledHeader().get();
    // `precompiled_header` requires a cxx_precompiled_header target, but we want to give Xcode the
    // path to the pch file itself. Resolve our target reference into a path
    Preconditions.checkArgument(pchPath instanceof BuildTargetSourcePath);
    BuildTargetSourcePath pchTargetSourcePath = (BuildTargetSourcePath) pchPath;
    BuildTarget pchTarget = pchTargetSourcePath.getTarget();
    TargetNode<?> node = targetGraph.get(pchTarget);
    BuildRuleResolver resolver = actionGraphBuilderForNode.apply(node);
    BuildRule rule = resolver.getRule(pchTargetSourcePath);
    Preconditions.checkArgument(rule instanceof CxxPrecompiledHeaderTemplate);
    CxxPrecompiledHeaderTemplate pch = (CxxPrecompiledHeaderTemplate) rule;
    return Optional.of(pch.getHeaderSourcePath());
  }
}
