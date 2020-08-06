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

import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleDependenciesCache;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.PrebuiltAppleFrameworkDescription;
import com.facebook.buck.apple.XCodeDescriptions;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.targetgraph.NoSuchTargetException;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.config.registry.impl.ConfigurationRuleRegistryFactory;
import com.facebook.buck.core.rules.resolver.impl.MultiThreadedActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.PrebuiltCxxLibraryDescription;
import com.facebook.buck.cxx.PrebuiltCxxLibraryDescriptionArg;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.features.apple.common.Utils;
import com.facebook.buck.features.halide.HalideLibraryDescription;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.MacroContainer;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftCommonArg;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Helper class to parse flags for targets to apply for compilation and linking. */
class FlagParser {

  private static final ImmutableList<String> DEFAULT_CFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CXXFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CPPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CXXPPFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_LDFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_SWIFTFLAGS = ImmutableList.of();

  private final Cell projectCell;
  private final AppleConfig appleConfig;
  private final SwiftBuckConfig swiftBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final ImmutableSet<Flavor> appleCxxFlavors;
  private final XCodeDescriptions xcodeDescriptions;
  private final TargetGraph targetGraph;
  private final Function<? super TargetNode<?>, ActionGraphBuilder> actionGraphBuilderForNode;
  private final AppleDependenciesCache dependenciesCache;
  private final SourcePathResolverAdapter defaultPathResolver;
  private final HeaderSearchPaths headerSearchPaths;

  private final ImmutableMap<Flavor, CxxBuckConfig> platformCxxBuckConfigs;

  FlagParser(
      Cell projectCell,
      AppleConfig appleConfig,
      SwiftBuckConfig swiftBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      ImmutableSet<Flavor> appleCxxFlavors,
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      Function<? super TargetNode<?>, ActionGraphBuilder> actionGraphBuilderForNode,
      AppleDependenciesCache dependenciesCache,
      SourcePathResolverAdapter defaultPathResolver,
      HeaderSearchPaths headerSearchPaths) {
    this.projectCell = projectCell;
    this.appleConfig = appleConfig;
    this.swiftBuckConfig = swiftBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.appleCxxFlavors = appleCxxFlavors;
    this.xcodeDescriptions = xcodeDescriptions;
    this.targetGraph = targetGraph;
    this.actionGraphBuilderForNode = actionGraphBuilderForNode;
    this.dependenciesCache = dependenciesCache;
    this.defaultPathResolver = defaultPathResolver;
    this.headerSearchPaths = headerSearchPaths;

    this.platformCxxBuckConfigs = cxxBuckConfig.getFlavoredConfigs();
  }

  /**
   * Parse flags for {@code targetNode}, adding them to the {@code flagsBuilder}. Populates {@code
   * requiredBuildTargetsBuilder} for targets that need to be built.
   */
  void parseFlags(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode,
      boolean includeFrameworks,
      FluentIterable<TargetNode<?>> swiftDepTargets,
      boolean containsSwiftCode,
      boolean isModularAppleLibrary,
      boolean hasPublicCxxHeaders,
      Set<Path> recursivePublicSystemIncludeDirectories,
      ImmutableMap.Builder<String, String> flagsBuilder,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder) {

    ImmutableList.Builder<String> swiftDebugLinkerFlagsBuilder = ImmutableList.builder();

    if (includeFrameworks
        && !swiftDepTargets.isEmpty()
        && swiftBuckConfig.getProjectAddASTPaths()) {
      for (TargetNode<?> swiftNode : swiftDepTargets) {
        String swiftModulePath =
            String.format(
                "${BUILT_PRODUCTS_DIR}/%s.swiftmodule/${CURRENT_ARCH}.swiftmodule",
                com.facebook.buck.features.apple.projectV2.Utils.getModuleName(swiftNode));
        swiftDebugLinkerFlagsBuilder.add("-Xlinker");
        swiftDebugLinkerFlagsBuilder.add("-add_ast_path");
        swiftDebugLinkerFlagsBuilder.add("-Xlinker");
        swiftDebugLinkerFlagsBuilder.add(swiftModulePath);
      }
    }

    ImmutableList.Builder<String> targetSpecificSwiftFlags = ImmutableList.builder();
    Optional<TargetNode<SwiftCommonArg>> swiftTargetNode =
        TargetNodes.castArg(targetNode, SwiftCommonArg.class);
    targetSpecificSwiftFlags.addAll(
        swiftTargetNode
            .map(
                x ->
                    convertStringWithMacros(
                        targetNode,
                        x.getConstructorArg().getSwiftCompilerFlags(),
                        requiredBuildTargetsBuilder))
            .orElse(ImmutableList.of()));

    if (containsSwiftCode && isModularAppleLibrary && hasPublicCxxHeaders) {
      targetSpecificSwiftFlags.addAll(collectModularTargetSpecificSwiftFlags(targetNode));
    }

    // Explicitly add system include directories to compile flags to mute warnings,
    // XCode seems to not support system include directories directly.
    // But even if headers dirs are passed as flags, we still need to add
    // them to `HEADER_SEARCH_PATH` otherwise header generation for Swift interop
    // won't work (it doesn't use `OTHER_XXX_FLAGS`).
    Iterable<String> systemIncludeDirectoryFlags =
        StreamSupport.stream(recursivePublicSystemIncludeDirectories.spliterator(), false)
            .map(path -> "-isystem" + path)
            .collect(Collectors.toList());

    ImmutableList<String> testingOverlay = getFlagsForExcludesForModulesUnderTests(targetNode);
    Iterable<String> otherSwiftFlags =
        Utils.distinctUntilChanged(
            Iterables.concat(
                swiftBuckConfig.getCompilerFlags().orElse(DEFAULT_SWIFTFLAGS),
                targetSpecificSwiftFlags.build()));

    Iterable<String> targetCFlags =
        Utils.distinctUntilChanged(
            ImmutableList.<String>builder()
                .addAll(
                    convertStringWithMacros(
                        targetNode,
                        collectRecursiveExportedPreprocessorFlags(targetNode),
                        requiredBuildTargetsBuilder))
                .addAll(
                    convertStringWithMacros(
                        targetNode,
                        targetNode.getConstructorArg().getCompilerFlags(),
                        requiredBuildTargetsBuilder))
                .addAll(
                    convertStringWithMacros(
                        targetNode,
                        targetNode.getConstructorArg().getPreprocessorFlags(),
                        requiredBuildTargetsBuilder))
                .addAll(
                    convertStringWithMacros(
                        targetNode,
                        collectRecursiveSystemPreprocessorFlags(targetNode),
                        requiredBuildTargetsBuilder))
                .addAll(systemIncludeDirectoryFlags)
                .addAll(testingOverlay)
                .build());

    Iterable<String> targetCxxFlags =
        Utils.distinctUntilChanged(
            ImmutableList.<String>builder()
                .addAll(
                    convertStringWithMacros(
                        targetNode,
                        collectRecursiveExportedPreprocessorFlags(targetNode),
                        requiredBuildTargetsBuilder))
                .addAll(
                    convertStringWithMacros(
                        targetNode,
                        targetNode.getConstructorArg().getCompilerFlags(),
                        requiredBuildTargetsBuilder))
                .addAll(
                    convertStringWithMacros(
                        targetNode,
                        targetNode.getConstructorArg().getPreprocessorFlags(),
                        requiredBuildTargetsBuilder))
                .addAll(
                    convertStringWithMacros(
                        targetNode,
                        collectRecursiveSystemPreprocessorFlags(targetNode),
                        requiredBuildTargetsBuilder))
                .addAll(systemIncludeDirectoryFlags)
                .addAll(testingOverlay)
                .build());

    flagsBuilder
        .put(
            "OTHER_SWIFT_FLAGS",
            Streams.stream(otherSwiftFlags)
                .map(Escaper.BASH_ESCAPER)
                .collect(Collectors.joining(" ")))
        .put(
            "OTHER_CFLAGS",
            Streams.stream(
                    Iterables.concat(
                        cxxBuckConfig.getCflags().orElse(DEFAULT_CFLAGS),
                        cxxBuckConfig.getCppflags().orElse(DEFAULT_CPPFLAGS),
                        targetCFlags))
                .map(Escaper.BASH_ESCAPER)
                .collect(Collectors.joining(" ")))
        .put(
            "OTHER_CPLUSPLUSFLAGS",
            Streams.stream(
                    Iterables.concat(
                        cxxBuckConfig.getCxxflags().orElse(DEFAULT_CXXFLAGS),
                        cxxBuckConfig.getCxxppflags().orElse(DEFAULT_CXXPPFLAGS),
                        targetCxxFlags))
                .map(Escaper.BASH_ESCAPER)
                .collect(Collectors.joining(" ")));

    Iterable<String> otherLdFlags =
        ImmutableList.<String>builder()
            .addAll(cxxBuckConfig.getLdflags().orElse(DEFAULT_LDFLAGS))
            .addAll(appleConfig.linkAllObjC() ? ImmutableList.of("-ObjC") : ImmutableList.of())
            .addAll(
                convertStringWithMacros(
                    targetNode,
                    Iterables.concat(
                        targetNode.getConstructorArg().getLinkerFlags(),
                        collectRecursiveExportedLinkerFlags(targetNode)),
                    requiredBuildTargetsBuilder))
            .addAll(swiftDebugLinkerFlagsBuilder.build())
            .build();

    Stream<String> otherLdFlagsStream = Streams.stream(otherLdFlags).map(Escaper.BASH_ESCAPER);

    flagsBuilder.put("OTHER_LDFLAGS", otherLdFlagsStream.collect(Collectors.joining(" ")));

    ImmutableMultimap<String, ImmutableList<String>> platformFlags =
        convertPlatformFlags(
            targetNode,
            Iterables.concat(
                ImmutableList.of(targetNode.getConstructorArg().getPlatformCompilerFlags()),
                ImmutableList.of(targetNode.getConstructorArg().getPlatformPreprocessorFlags()),
                collectRecursiveExportedPlatformPreprocessorFlags(targetNode)),
            requiredBuildTargetsBuilder);

    for (Flavor platformFlavor : appleCxxFlavors) {
      Optional<CxxBuckConfig> platformConfig =
          Optional.ofNullable(platformCxxBuckConfigs.get(platformFlavor));
      String platform = platformFlavor.getName();

      // The behavior below matches the CxxPlatform behavior where it adds the cxx flags,
      // then the cxx#platform flags, then the flags for the target
      flagsBuilder
          .put(
              generateConfigKey("OTHER_CFLAGS", platform),
              Streams.stream(
                      Utils.distinctUntilChanged(
                          Iterables.transform(
                              Iterables.concat(
                                  cxxBuckConfig.getCflags().orElse(DEFAULT_CFLAGS),
                                  platformConfig
                                      .flatMap(CxxBuckConfig::getCflags)
                                      .orElse(DEFAULT_CFLAGS),
                                  cxxBuckConfig.getCppflags().orElse(DEFAULT_CPPFLAGS),
                                  platformConfig
                                      .flatMap(CxxBuckConfig::getCppflags)
                                      .orElse(DEFAULT_CPPFLAGS),
                                  targetCFlags,
                                  Iterables.concat(platformFlags.get(platform))),
                              Escaper.BASH_ESCAPER::apply)))
                  .collect(Collectors.joining(" ")))
          .put(
              generateConfigKey("OTHER_CPLUSPLUSFLAGS", platform),
              Streams.stream(
                      Utils.distinctUntilChanged(
                          Iterables.transform(
                              Iterables.concat(
                                  cxxBuckConfig.getCxxflags().orElse(DEFAULT_CPPFLAGS),
                                  platformConfig
                                      .flatMap(CxxBuckConfig::getCxxflags)
                                      .orElse(DEFAULT_CXXFLAGS),
                                  cxxBuckConfig.getCxxppflags().orElse(DEFAULT_CXXPPFLAGS),
                                  platformConfig
                                      .flatMap(CxxBuckConfig::getCxxppflags)
                                      .orElse(DEFAULT_CXXPPFLAGS),
                                  targetCxxFlags,
                                  Iterables.concat(platformFlags.get(platform))),
                              Escaper.BASH_ESCAPER::apply)))
                  .collect(Collectors.joining(" ")));
    }

    ImmutableMultimap<String, ImmutableList<String>> platformLinkerFlags =
        convertPlatformFlags(
            targetNode,
            Iterables.concat(
                ImmutableList.of(targetNode.getConstructorArg().getPlatformLinkerFlags()),
                collectRecursiveExportedPlatformLinkerFlags(targetNode)),
            requiredBuildTargetsBuilder);
    for (String platform : platformLinkerFlags.keySet()) {
      flagsBuilder.put(
          generateConfigKey("OTHER_LDFLAGS", platform),
          Streams.stream(
                  Iterables.transform(
                      Iterables.concat(
                          otherLdFlags, Iterables.concat(platformLinkerFlags.get(platform))),
                      Escaper.BASH_ESCAPER::apply))
              .collect(Collectors.joining(" ")));
    }
  }

  /** Convert a list of {@link StringWithMacros} to a list of flags for the {@code node}. */
  ImmutableList<String> convertStringWithMacros(
      TargetNode<?> node,
      Iterable<StringWithMacros> flags,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder) {

    // TODO(cjhopman): This seems really broken, it's totally inconsistent about what graphBuilder
    // is provided. This should either just do rule resolution like normal or maybe do its own
    // custom MacroReplacer<>.
    LocationMacroExpander locationMacroExpander =
        new LocationMacroExpander() {
          @Override
          public Arg expandFrom(
              BuildTarget target, ActionGraphBuilder graphBuilder, LocationMacro input)
              throws MacroException {
            BuildTarget locationMacroTarget = input.getTarget();

            ActionGraphBuilder builderFromNode =
                actionGraphBuilderForNode.apply(targetGraph.get(locationMacroTarget));
            try {
              builderFromNode.requireRule(locationMacroTarget);
            } catch (NoSuchTargetException e) {
              throw new MacroException(
                  String.format(
                      "couldn't find rule referenced by location macro: %s", e.getMessage()),
                  e);
            }

            requiredBuildTargetsBuilder.add(locationMacroTarget);
            return StringArg.of(
                Arg.stringify(
                    super.expandFrom(target, builderFromNode, input),
                    builderFromNode.getSourcePathResolver()));
          }
        };

    ActionGraphBuilder emptyGraphBuilder =
        new MultiThreadedActionGraphBuilder(
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
            TargetGraph.EMPTY,
            ConfigurationRuleRegistryFactory.createRegistry(TargetGraph.EMPTY),
            new DefaultTargetNodeToBuildRuleTransformer(),
            projectCell.getCellProvider());
    ImmutableList.Builder<String> result = new ImmutableList.Builder<>();
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            node.getBuildTarget(),
            projectCell.getCellPathResolver().getCellNameResolver(),
            emptyGraphBuilder,
            ImmutableList.of(locationMacroExpander));
    for (StringWithMacros flag : flags) {
      macrosConverter.convert(flag).appendToCommandLine(result::add, defaultPathResolver);
    }
    return result.build();
  }

  private ImmutableList<String> getFlagsForExcludesForModulesUnderTests(
      TargetNode<? extends CxxLibraryDescription.CommonArg> testingTarget) {
    ImmutableList.Builder<String> testingOverlayBuilder = new ImmutableList.Builder<>();
    headerSearchPaths.visitRecursivePrivateHeaderSymlinkTreesForTests(
        testingTarget,
        (targetUnderTest, headerVisibility) -> {
          // If we are testing a modular apple_library, we expose it non-modular. This allows the
          // testing target to see both the public and private interfaces of the tested target
          // without triggering header errors related to modules. We hide the module definition by
          // using a filesystem overlay that overrides the module.modulemap with an empty file.
          if (NodeHelper.isModularAppleLibrary(targetUnderTest)) {
            testingOverlayBuilder.add("-ivfsoverlay");
            Path vfsOverlay =
                HeaderSearchPaths.getTestingModulemapVFSOverlayLocationFromSymlinkTreeRoot(
                    headerSearchPaths.getPathToHeaderSymlinkTree(
                        targetUnderTest, HeaderVisibility.PUBLIC));
            testingOverlayBuilder.add("$REPO_ROOT/" + vfsOverlay);
          }
        });
    return testingOverlayBuilder.build();
  }

  private Iterable<StringWithMacros> collectRecursiveExportedPreprocessorFlags(
      TargetNode<?> targetNode) {
    return FluentIterable.from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                xcodeDescriptions,
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.BUILDING,
                targetNode,
                ImmutableSet.of(AppleLibraryDescription.class, CxxLibraryDescription.class)))
        .append(targetNode)
        .transformAndConcat(
            input ->
                TargetNodes.castArg(input, CxxLibraryDescription.CommonArg.class)
                    .map(input1 -> input1.getConstructorArg().getExportedPreprocessorFlags())
                    .orElse(ImmutableList.of()));
  }

  private Iterable<PatternMatchedCollection<ImmutableList<StringWithMacros>>>
      collectRecursiveExportedPlatformPreprocessorFlags(TargetNode<?> targetNode) {
    return FluentIterable.from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                xcodeDescriptions,
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.BUILDING,
                targetNode,
                ImmutableSet.of(
                    AppleLibraryDescription.class,
                    CxxLibraryDescription.class,
                    PrebuiltCxxLibraryDescription.class,
                    PrebuiltAppleFrameworkDescription.class)))
        .append(targetNode)
        .transformAndConcat(
            input -> {
              Optional<Iterable<PatternMatchedCollection<ImmutableList<StringWithMacros>>>> result;
              result =
                  TargetNodes.castArg(input, CxxLibraryDescription.CommonArg.class)
                      .map(
                          input1 ->
                              ImmutableList.of(
                                  input1
                                      .getConstructorArg()
                                      .getExportedPlatformPreprocessorFlags()));
              if (result.isPresent()) {
                return result.get();
              }
              result =
                  TargetNodes.castArg(input, PrebuiltCxxLibraryDescriptionArg.class)
                      .map(
                          input1 ->
                              ImmutableList.of(
                                  input1
                                      .getConstructorArg()
                                      .getExportedPlatformPreprocessorFlags()));
              if (result.isPresent()) {
                return result.get();
              }
              return ImmutableList.of();
            });
  }

  private Iterable<StringWithMacros> collectRecursiveSystemPreprocessorFlags(
      TargetNode<?> targetNode) {
    return FluentIterable.from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                xcodeDescriptions,
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.BUILDING,
                targetNode,
                ImmutableSet.of(PrebuiltCxxLibraryDescription.class)))
        .append(targetNode)
        .transformAndConcat(
            input -> {
              Optional<ImmutableList<SourcePath>> result;
              result =
                  TargetNodes.castArg(input, PrebuiltCxxLibraryDescriptionArg.class)
                      .map(
                          input1 ->
                              input1
                                  .getConstructorArg()
                                  .getHeaderDirs()
                                  .orElse(ImmutableList.of()));
              if (result.isPresent()) {
                return result.get();
              }
              return ImmutableList.of();
            })
        .transform(
            headerDir -> {
              if (headerDir instanceof BuildTargetSourcePath) {
                BuildTargetSourcePath targetSourcePath = (BuildTargetSourcePath) headerDir;
                return StringWithMacros.of(
                    ImmutableList.of(
                        Either.ofLeft("-isystem"),
                        Either.ofRight(
                            MacroContainer.of(
                                LocationMacro.of(targetSourcePath.getTarget()), false))));
              }
              return StringWithMacros.ofConstantString("-isystem" + headerDir);
            });
  }

  private ImmutableList<StringWithMacros> collectRecursiveExportedLinkerFlags(
      TargetNode<?> targetNode) {
    return FluentIterable.from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                xcodeDescriptions,
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                targetNode,
                ImmutableSet.of(
                    AppleLibraryDescription.class,
                    CxxLibraryDescription.class,
                    HalideLibraryDescription.class,
                    PrebuiltCxxLibraryDescription.class,
                    PrebuiltAppleFrameworkDescription.class)))
        .append(targetNode)
        .transformAndConcat(
            input -> {
              Optional<ImmutableList<StringWithMacros>> result;
              result =
                  TargetNodes.castArg(input, CxxLibraryDescription.CommonArg.class)
                      .map(input1 -> input1.getConstructorArg().getExportedLinkerFlags());
              if (result.isPresent()) {
                return result.get();
              }
              result =
                  TargetNodes.castArg(input, PrebuiltCxxLibraryDescriptionArg.class)
                      .map(input1 -> input1.getConstructorArg().getExportedLinkerFlags());
              if (result.isPresent()) {
                return result.get();
              }
              return ImmutableList.of();
            })
        .toList();
  }

  private Iterable<PatternMatchedCollection<ImmutableList<StringWithMacros>>>
      collectRecursiveExportedPlatformLinkerFlags(TargetNode<?> targetNode) {
    return FluentIterable.from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                xcodeDescriptions,
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                targetNode,
                ImmutableSet.of(
                    AppleLibraryDescription.class,
                    CxxLibraryDescription.class,
                    HalideLibraryDescription.class)))
        .append(targetNode)
        .transformAndConcat(
            input ->
                TargetNodes.castArg(input, CxxLibraryDescription.CommonArg.class)
                    .map(
                        input1 ->
                            ImmutableList.of(
                                input1.getConstructorArg().getExportedPlatformLinkerFlags()))
                    .orElse(ImmutableList.of()));
  }

  private Iterable<String> collectModularTargetSpecificSwiftFlags(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode) {
    ImmutableList.Builder<String> targetSpecificSwiftFlags = ImmutableList.builder();
    targetSpecificSwiftFlags.add("-import-underlying-module");
    Path vfsOverlay =
        HeaderSearchPaths.getObjcModulemapVFSOverlayLocationFromSymlinkTreeRoot(
            headerSearchPaths.getPathToHeaderSymlinkTree(targetNode, HeaderVisibility.PUBLIC));
    targetSpecificSwiftFlags.add("-Xcc");
    targetSpecificSwiftFlags.add("-ivfsoverlay");
    targetSpecificSwiftFlags.add("-Xcc");
    targetSpecificSwiftFlags.add("$REPO_ROOT/" + vfsOverlay);
    return targetSpecificSwiftFlags.build();
  }

  private ImmutableMultimap<String, ImmutableList<String>> convertPlatformFlags(
      TargetNode<?> node,
      Iterable<PatternMatchedCollection<ImmutableList<StringWithMacros>>> matchers,
      ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder) {
    ImmutableMultimap.Builder<String, ImmutableList<String>> flagsBuilder =
        ImmutableMultimap.builder();

    for (PatternMatchedCollection<ImmutableList<StringWithMacros>> matcher : matchers) {
      for (Flavor flavor : appleCxxFlavors) {
        String platform = flavor.toString();
        for (ImmutableList<StringWithMacros> flags : matcher.getMatchingValues(platform)) {
          flagsBuilder.put(
              platform, convertStringWithMacros(node, flags, requiredBuildTargetsBuilder));
        }
      }
    }
    return flagsBuilder.build();
  }

  private String generateConfigKey(String key, String platform) {
    int index = platform.lastIndexOf('-');
    String sdk = platform.substring(0, index);
    String arch = platform.substring(index + 1);
    return String.format("%s[sdk=%s*][arch=%s]", key, sdk, arch);
  }
}
