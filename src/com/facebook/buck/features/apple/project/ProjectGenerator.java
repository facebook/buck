/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.features.apple.project;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.AppleAssetCatalogDescriptionArg;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBinaryDescriptionArg;
import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBuildRules.RecursiveDependenciesMode;
import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleDescriptionArg;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleDependenciesCache;
import com.facebook.buck.apple.AppleDescriptions;
import com.facebook.buck.apple.AppleHeaderVisibilities;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleLibraryDescriptionArg;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.AppleResourceDescriptionArg;
import com.facebook.buck.apple.AppleResources;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.AppleTestDescriptionArg;
import com.facebook.buck.apple.AppleWrapperResourceArg;
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.apple.HasAppleBundleFields;
import com.facebook.buck.apple.InfoPlistSubstitution;
import com.facebook.buck.apple.PrebuiltAppleFrameworkDescription;
import com.facebook.buck.apple.PrebuiltAppleFrameworkDescriptionArg;
import com.facebook.buck.apple.SceneKitAssetsDescription;
import com.facebook.buck.apple.XCodeDescriptions;
import com.facebook.buck.apple.XcodePostbuildScriptDescription;
import com.facebook.buck.apple.XcodePrebuildScriptDescription;
import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.apple.clang.ModuleMap;
import com.facebook.buck.apple.clang.UmbrellaHeader;
import com.facebook.buck.apple.clang.VFSOverlay;
import com.facebook.buck.apple.xcode.GidGenerator;
import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.facebook.buck.apple.xcode.xcodeproj.CopyFilePhaseDestinationSpec;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXContainerItemProxy;
import com.facebook.buck.apple.xcode.xcodeproj.PBXCopyFilesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTargetDependency;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.apple.xcode.xcodeproj.XCVersionGroup;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.NoSuchTargetException;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetGraphAndTargets;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodes;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.SingleThreadedActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.core.util.graph.GraphTraversable;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxCompilationDatabase;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxLibraryDescription.CommonArg;
import com.facebook.buck.cxx.CxxPrecompiledHeaderTemplate;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.HasSystemFrameworkAndLibraries;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable.Linkage;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.features.halide.HalideBuckConfig;
import com.facebook.buck.features.halide.HalideCompile;
import com.facebook.buck.features.halide.HalideLibraryDescription;
import com.facebook.buck.features.halide.HalideLibraryDescriptionArg;
import com.facebook.buck.features.js.JsBundleOutputsDescription;
import com.facebook.buck.io.MoreProjectFilesystems;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.rules.macros.LocationMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.shell.AbstractGenruleDescription;
import com.facebook.buck.shell.ExportFileDescriptionArg;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftCommonArg;
import com.facebook.buck.swift.SwiftLibraryDescriptionArg;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Generator for xcode project and associated files from a set of xcode/ios rules. */
public class ProjectGenerator {

  private static final Logger LOG = Logger.get(ProjectGenerator.class);
  private static final ImmutableList<String> DEFAULT_CFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CXXFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_LDFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_SWIFTFLAGS = ImmutableList.of();
  private static final String PRODUCT_NAME = "PRODUCT_NAME";

  private static final ImmutableSet<Class<? extends DescriptionWithTargetGraph<?>>>
      APPLE_NATIVE_DESCRIPTION_CLASSES =
          ImmutableSet.of(
              AppleBinaryDescription.class,
              AppleLibraryDescription.class,
              CxxLibraryDescription.class);

  private static final ImmutableSet<AppleBundleExtension> APPLE_NATIVE_BUNDLE_EXTENSIONS =
      ImmutableSet.of(AppleBundleExtension.APP, AppleBundleExtension.FRAMEWORK);

  private static final FileAttribute<?> READ_ONLY_FILE_ATTRIBUTE =
      PosixFilePermissions.asFileAttribute(
          ImmutableSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.OTHERS_READ));

  private final XCodeDescriptions xcodeDescriptions;
  private final TargetGraph targetGraph;
  private final AppleDependenciesCache dependenciesCache;
  private final ProjectGenerationStateCache projGenerationStateCache;
  private final Cell projectCell;
  private final ProjectFilesystem projectFilesystem;
  private final Path outputDirectory;
  private final String projectName;
  private final ImmutableSet<BuildTarget> initialTargets;
  private final Path projectPath;
  private final PathRelativizer pathRelativizer;

  private final String buildFileName;
  private final ProjectGeneratorOptions options;
  private final CxxPlatform defaultCxxPlatform;

  // These fields are created/filled when creating the projects.
  private final PBXProject project;
  private final LoadingCache<TargetNode<?>, Optional<PBXTarget>> targetNodeToProjectTarget;
  private final ImmutableMultimap.Builder<TargetNode<?>, PBXTarget>
      targetNodeToGeneratedProjectTargetBuilder;
  private boolean projectGenerated;
  private final List<Path> headerSymlinkTrees;
  private final ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder =
      ImmutableSet.builder();
  private final Function<? super TargetNode<?>, ActionGraphBuilder> actionGraphBuilderForNode;
  private final SourcePathResolver defaultPathResolver;
  private final BuckEventBus buckEventBus;
  private final RuleKeyConfiguration ruleKeyConfiguration;

  /**
   * Populated while generating project configurations, in order to collect the possible
   * project-level configurations to set.
   */
  private final ImmutableSet.Builder<String> targetConfigNamesBuilder;

  private final GidGenerator gidGenerator;
  private final ImmutableSet<Flavor> appleCxxFlavors;
  private final HalideBuckConfig halideBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final SwiftBuckConfig swiftBuckConfig;
  private final AppleConfig appleConfig;
  private final FocusedModuleTargetMatcher focusModules;
  private final boolean isMainProject;
  private final Optional<BuildTarget> workspaceTarget;
  private final ImmutableSet<BuildTarget> targetsInRequiredProjects;
  private final ImmutableSet.Builder<Path> xcconfigPathsBuilder = ImmutableSet.builder();
  private final ImmutableList.Builder<CopyInXcode> filesToCopyInXcodeBuilder =
      ImmutableList.builder();
  private final ImmutableList.Builder<SourcePath> genruleFiles = ImmutableList.builder();
  private final ImmutableSet.Builder<SourcePath> filesAddedBuilder = ImmutableSet.builder();
  private final Set<BuildTarget> generatedTargets = new HashSet<>();
  /**
   * Mapping from an apple_library target to the associated apple_bundle which names it as its
   * 'binary'
   */
  private final Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle;

  public ProjectGenerator(
      XCodeDescriptions xcodeDescriptions,
      TargetGraph targetGraph,
      AppleDependenciesCache dependenciesCache,
      ProjectGenerationStateCache projGenerationStateCache,
      Set<BuildTarget> initialTargets,
      Cell cell,
      Path outputDirectory,
      String projectName,
      String buildFileName,
      ProjectGeneratorOptions options,
      RuleKeyConfiguration ruleKeyConfiguration,
      boolean isMainProject,
      Optional<BuildTarget> workspaceTarget,
      ImmutableSet<BuildTarget> targetsInRequiredProjects,
      FocusedModuleTargetMatcher focusModules,
      CxxPlatform defaultCxxPlatform,
      ImmutableSet<Flavor> appleCxxFlavors,
      Function<? super TargetNode<?>, ActionGraphBuilder> actionGraphBuilderForNode,
      BuckEventBus buckEventBus,
      HalideBuckConfig halideBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      AppleConfig appleConfig,
      SwiftBuckConfig swiftBuckConfig,
      Optional<ImmutableMap<BuildTarget, TargetNode<?>>> sharedLibraryToBundle) {
    this.xcodeDescriptions = xcodeDescriptions;
    this.targetGraph = targetGraph;
    this.dependenciesCache = dependenciesCache;
    this.projGenerationStateCache = projGenerationStateCache;
    this.initialTargets = ImmutableSet.copyOf(initialTargets);
    this.projectCell = cell;
    this.projectFilesystem = cell.getFilesystem();
    this.outputDirectory = outputDirectory;
    this.projectName = projectName;
    this.buildFileName = buildFileName;
    this.options = options;
    this.ruleKeyConfiguration = ruleKeyConfiguration;
    this.isMainProject = isMainProject;
    this.workspaceTarget = workspaceTarget;
    this.targetsInRequiredProjects = targetsInRequiredProjects;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.appleCxxFlavors = appleCxxFlavors;
    this.actionGraphBuilderForNode = actionGraphBuilderForNode;
    this.defaultPathResolver =
        DefaultSourcePathResolver.from(
            new SourcePathRuleFinder(
                new SingleThreadedActionGraphBuilder(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer(),
                    cell.getCellProvider())));
    this.buckEventBus = buckEventBus;

    this.projectPath = outputDirectory.resolve(projectName + ".xcodeproj");
    this.pathRelativizer = new PathRelativizer(outputDirectory, this::resolveSourcePath);
    this.sharedLibraryToBundle = sharedLibraryToBundle;

    LOG.debug(
        "Output directory %s, profile fs root path %s, repo root relative to output dir %s",
        this.outputDirectory,
        projectFilesystem.getRootPath(),
        this.pathRelativizer.outputDirToRootRelative(Paths.get(".")));

    this.project = new PBXProject(projectName);
    this.headerSymlinkTrees = new ArrayList<>();

    this.targetNodeToGeneratedProjectTargetBuilder = ImmutableMultimap.builder();
    this.targetNodeToProjectTarget =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<TargetNode<?>, Optional<PBXTarget>>() {
                  @Override
                  public Optional<PBXTarget> load(TargetNode<?> key) throws Exception {
                    return generateProjectTarget(key);
                  }
                });

    targetConfigNamesBuilder = ImmutableSet.builder();
    this.halideBuckConfig = halideBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.appleConfig = appleConfig;
    this.swiftBuckConfig = swiftBuckConfig;
    this.focusModules = focusModules;

    gidGenerator = new GidGenerator();
  }

  @VisibleForTesting
  PBXProject getGeneratedProject() {
    return project;
  }

  @VisibleForTesting
  List<Path> getGeneratedHeaderSymlinkTrees() {
    return headerSymlinkTrees;
  }

  public Path getProjectPath() {
    return projectPath;
  }

  private boolean shouldMergeHeaderMaps() {
    return options.shouldMergeHeaderMaps()
        && workspaceTarget.isPresent()
        && options.shouldUseHeaderMaps();
  }

  public ImmutableMap<BuildTarget, PBXTarget> getBuildTargetToGeneratedTargetMap() {
    Preconditions.checkState(projectGenerated, "Must have called createXcodeProjects");
    ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMap = ImmutableMap.builder();
    for (Map.Entry<TargetNode<?>, PBXTarget> entry :
        targetNodeToGeneratedProjectTargetBuilder.build().entries()) {
      buildTargetToPbxTargetMap.put(entry.getKey().getBuildTarget(), entry.getValue());
    }
    return buildTargetToPbxTargetMap.build();
  }

  /** @return Map from the generated project to all contained targets. */
  public ImmutableSetMultimap<PBXProject, PBXTarget> getGeneratedProjectToGeneratedTargets() {
    Preconditions.checkState(projectGenerated, "Must have called createXcodeProjects");
    ImmutableSetMultimap.Builder<PBXProject, PBXTarget> generatedProjectToPbxTargetsBuilder =
        ImmutableSetMultimap.builder();
    generatedProjectToPbxTargetsBuilder.putAll(
        getGeneratedProject(), targetNodeToGeneratedProjectTargetBuilder.build().values());
    return generatedProjectToPbxTargetsBuilder.build();
  }

  public ImmutableSet<BuildTarget> getRequiredBuildTargets() {
    Preconditions.checkState(projectGenerated, "Must have called createXcodeProjects");
    return requiredBuildTargetsBuilder.build();
  }

  // Returns a set of generated xcconfig files.
  public ImmutableSet<Path> getXcconfigPaths() {
    return xcconfigPathsBuilder.build();
  }

  // Returns the list of infos about the files that we need to copy during Xcode build.
  public ImmutableList<CopyInXcode> getFilesToCopyInXcode() {
    return filesToCopyInXcodeBuilder.build();
  }

  // Returns true if we ran the project generation and we decided to eventually generate
  // the project.
  public boolean isProjectGenerated() {
    return projectGenerated;
  }

  public void createXcodeProjects() throws IOException {
    LOG.debug("Creating projects for targets %s", initialTargets);

    boolean hasAtLeastOneTarget = false;
    try (SimplePerfEvent.Scope scope =
        SimplePerfEvent.scope(
            buckEventBus,
            PerfEventId.of("xcode_project_generation"),
            ImmutableMap.of("Path", getProjectPath()))) {
      for (TargetNode<?> targetNode : targetGraph.getNodes()) {

        if (isBuiltByCurrentProject(targetNode.getBuildTarget())) {
          LOG.debug("Including rule %s in project", targetNode);
          // Trigger the loading cache to call the generateProjectTarget function.
          Optional<PBXTarget> target = targetNodeToProjectTarget.getUnchecked(targetNode);
          target.ifPresent(
              pbxTarget -> targetNodeToGeneratedProjectTargetBuilder.put(targetNode, pbxTarget));
          if (focusModules.isFocusedOn(targetNode.getBuildTarget())) {
            // If the target is not included, we still need to do other operations to generate
            // the required header maps.
            hasAtLeastOneTarget = true;
          }
        } else {
          LOG.verbose("Excluding rule %s (not built by current project)", targetNode);
        }
      }

      addGenruleFiles();

      if (!hasAtLeastOneTarget && focusModules.hasFocus()) {
        return;
      }

      if (shouldMergeHeaderMaps() && isMainProject) {
        createMergedHeaderMap();
      }

      for (String configName : targetConfigNamesBuilder.build()) {
        XCBuildConfiguration outputConfig =
            project
                .getBuildConfigurationList()
                .getBuildConfigurationsByName()
                .getUnchecked(configName);
        outputConfig.setBuildSettings(new NSDictionary());
      }

      if (!options.shouldGenerateHeaderSymlinkTreesOnly()) {
        writeProjectFile(project);
      }

      projectGenerated = true;
    } catch (UncheckedExecutionException e) {
      // if any code throws an exception, they tend to get wrapped in LoadingCache's
      // UncheckedExecutionException. Unwrap it if its cause is HumanReadable.
      UncheckedExecutionException originalException = e;
      while (e.getCause() instanceof UncheckedExecutionException) {
        e = (UncheckedExecutionException) e.getCause();
      }
      if (e.getCause() instanceof HumanReadableException) {
        throw (HumanReadableException) e.getCause();
      } else {
        throw originalException;
      }
    }
  }

  /** Add all source files of genrules in a group "Other". */
  private void addGenruleFiles() {
    ImmutableSet<SourcePath> filesAdded = filesAddedBuilder.build();
    PBXGroup group = project.getMainGroup();
    ImmutableList<SourcePath> files = genruleFiles.build();
    if (files.size() > 0) {
      PBXGroup otherGroup = group.getOrCreateChildGroupByName("Other");
      for (SourcePath sourcePath : files) {
        // Make sure we don't add duplicates of existing files in this section.
        if (filesAdded.contains(sourcePath)) {
          continue;
        }
        Path path = pathRelativizer.outputPathToSourcePath(sourcePath);
        ImmutableList<String> targetGroupPath = null;
        PBXGroup sourceGroup = otherGroup;
        if (path.getParent() != null) {
          targetGroupPath =
              RichStream.from(path.getParent()).map(Object::toString).toImmutableList();
          sourceGroup = otherGroup.getOrCreateDescendantGroupByPath(targetGroupPath);
        }
        sourceGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(PBXReference.SourceTree.SOURCE_ROOT, path, Optional.empty()));
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Optional<PBXTarget> generateProjectTarget(TargetNode<?> targetNode) throws IOException {
    Preconditions.checkState(
        isBuiltByCurrentProject(targetNode.getBuildTarget()),
        "should not generate rule if it shouldn't be built by current project");

    if (shouldExcludeLibraryFromProject(targetNode)) {
      return Optional.empty();
    }

    BuildTarget targetWithoutAppleCxxFlavors =
        targetNode.getBuildTarget().withoutFlavors(appleCxxFlavors);
    if (generatedTargets.contains(targetWithoutAppleCxxFlavors)) {
      return Optional.empty();
    }
    generatedTargets.add(targetWithoutAppleCxxFlavors);

    Optional<PBXTarget> result = Optional.empty();
    if (targetNode.getDescription() instanceof AppleLibraryDescription) {
      result =
          Optional.of(
              generateAppleLibraryTarget(
                  project,
                  (TargetNode<AppleNativeTargetDescriptionArg>) targetNode,
                  Optional.empty()));
    } else if (targetNode.getDescription() instanceof CxxLibraryDescription) {
      result =
          Optional.of(
              generateCxxLibraryTarget(
                  project,
                  (TargetNode<CxxLibraryDescription.CommonArg>) targetNode,
                  ImmutableSet.of(),
                  ImmutableSet.of(),
                  Optional.empty()));
    } else if (targetNode.getDescription() instanceof AppleBinaryDescription) {
      result =
          Optional.of(
              generateAppleBinaryTarget(
                  project, (TargetNode<AppleNativeTargetDescriptionArg>) targetNode));
    } else if (targetNode.getDescription() instanceof AppleBundleDescription) {
      TargetNode<AppleBundleDescriptionArg> bundleTargetNode =
          (TargetNode<AppleBundleDescriptionArg>) targetNode;
      result =
          Optional.of(
              generateAppleBundleTarget(
                  project,
                  bundleTargetNode,
                  (TargetNode<AppleNativeTargetDescriptionArg>)
                      targetGraph.get(getBundleBinaryTarget(bundleTargetNode)),
                  Optional.empty()));
    } else if (targetNode.getDescription() instanceof AppleTestDescription) {
      result =
          Optional.of(generateAppleTestTarget((TargetNode<AppleTestDescriptionArg>) targetNode));
    } else if (targetNode.getDescription() instanceof AppleResourceDescription) {
      checkAppleResourceTargetNodeReferencingValidContents(
          (TargetNode<AppleResourceDescriptionArg>) targetNode);
    } else if (targetNode.getDescription() instanceof HalideLibraryDescription) {
      TargetNode<HalideLibraryDescriptionArg> halideTargetNode =
          (TargetNode<HalideLibraryDescriptionArg>) targetNode;
      BuildTarget buildTarget = targetNode.getBuildTarget();

      // The generated target just runs a shell script that invokes the "compiler" with the
      // correct target architecture.
      result = generateHalideLibraryTarget(project, halideTargetNode);

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
      genruleFiles.addAll(genruleNode.getConstructorArg().getSrcs().getPaths());
    }
    buckEventBus.post(ProjectGenerationEvent.processed());
    return result;
  }

  private static Path getHalideOutputPath(ProjectFilesystem filesystem, BuildTarget target) {
    return filesystem
        .getBuckPaths()
        .getConfiguredBuckOut()
        .resolve("halide")
        .resolve(target.getBasePath())
        .resolve(target.getShortName());
  }

  private Optional<PBXTarget> generateHalideLibraryTarget(
      PBXProject project, TargetNode<HalideLibraryDescriptionArg> targetNode) throws IOException {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    boolean isFocusedOnTarget = focusModules.isFocusedOn(buildTarget);
    String productName = getProductNameForBuildTargetNode(targetNode);
    Path outputPath = getHalideOutputPath(targetNode.getFilesystem(), buildTarget);

    Path scriptPath = halideBuckConfig.getXcodeCompileScriptPath();
    Optional<String> script = projectFilesystem.readFileIfItExists(scriptPath);
    PBXShellScriptBuildPhase scriptPhase = new PBXShellScriptBuildPhase();
    scriptPhase.setShellScript(script.orElse(""));

    NewNativeTargetProjectMutator mutator =
        new NewNativeTargetProjectMutator(pathRelativizer, this::resolveSourcePath);
    mutator
        .setTargetName(getXcodeTargetName(buildTarget))
        .setProduct(ProductTypes.STATIC_LIBRARY, productName, outputPath)
        .setPreBuildRunScriptPhases(ImmutableList.of(scriptPhase));

    NewNativeTargetProjectMutator.Result targetBuilderResult;
    targetBuilderResult = mutator.buildTargetAndAddToProject(project, isFocusedOnTarget);

    BuildTarget compilerTarget =
        HalideLibraryDescription.createHalideCompilerBuildTarget(buildTarget);
    Path compilerPath = BuildTargetPaths.getGenPath(projectFilesystem, compilerTarget, "%s");
    ImmutableMap<String, String> appendedConfig = ImmutableMap.of();
    ImmutableMap<String, String> extraSettings = ImmutableMap.of();
    ImmutableMap.Builder<String, String> defaultSettingsBuilder = ImmutableMap.builder();
    defaultSettingsBuilder.put(
        "REPO_ROOT", projectFilesystem.getRootPath().toAbsolutePath().normalize().toString());
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

    Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs =
        getXcodeBuildConfigurationsForTargetNode(targetNode, appendedConfig);
    PBXNativeTarget target = targetBuilderResult.target;
    setTargetBuildConfigurations(
        buildTarget,
        target,
        project.getMainGroup(),
        configs.get(),
        extraSettings,
        defaultSettingsBuilder.build(),
        appendedConfig);
    return Optional.of(target);
  }

  private PBXTarget generateAppleTestTarget(TargetNode<AppleTestDescriptionArg> testTargetNode)
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
    return generateAppleBundleTarget(project, testTargetNode, testTargetNode, testHostBundle);
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

  private PBXNativeTarget generateAppleBundleTarget(
      PBXProject project,
      TargetNode<? extends HasAppleBundleFields> targetNode,
      TargetNode<? extends AppleNativeTargetDescriptionArg> binaryNode,
      Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode)
      throws IOException {
    Path infoPlistPath =
        Objects.requireNonNull(resolveSourcePath(targetNode.getConstructorArg().getInfoPlist()));

    // -- copy any binary and bundle targets into this bundle
    Iterable<TargetNode<?>> copiedRules =
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            xcodeDescriptions,
            targetGraph,
            Optional.of(dependenciesCache),
            appleConfig.shouldIncludeSharedLibraryResources()
                ? RecursiveDependenciesMode.COPYING_INCLUDE_SHARED_RESOURCES
                : AppleBuildRules.RecursiveDependenciesMode.COPYING,
            targetNode,
            Optional.of(xcodeDescriptions.getXCodeDescriptions()));
    if (bundleRequiresRemovalOfAllTransitiveFrameworks(targetNode)) {
      copiedRules = rulesWithoutFrameworkBundles(copiedRules);
    } else if (bundleRequiresAllTransitiveFrameworks(binaryNode, bundleLoaderNode)) {
      copiedRules =
          ImmutableSet.<TargetNode<?>>builder()
              .addAll(copiedRules)
              .addAll(getTransitiveFrameworkNodes(targetNode))
              .build();
    }

    if (bundleLoaderNode.isPresent()) {
      copiedRules = rulesWithoutBundleLoader(copiedRules, bundleLoaderNode.get());
    }

    ImmutableList<PBXBuildPhase> copyFilesBuildPhases = getCopyFilesBuildPhases(copiedRules);

    RecursiveDependenciesMode mode =
        appleConfig.shouldIncludeSharedLibraryResources()
            ? RecursiveDependenciesMode.COPYING_INCLUDE_SHARED_RESOURCES
            : RecursiveDependenciesMode.COPYING;
    PBXNativeTarget target =
        generateBinaryTarget(
            project,
            Optional.of(targetNode),
            binaryNode,
            bundleToTargetProductType(targetNode, binaryNode),
            "%s." + getExtensionString(targetNode.getConstructorArg().getExtension()),
            Optional.of(infoPlistPath),
            /* includeFrameworks */ true,
            AppleResources.collectRecursiveResources(
                xcodeDescriptions, targetGraph, Optional.of(dependenciesCache), targetNode, mode),
            AppleResources.collectDirectResources(targetGraph, targetNode),
            AppleBuildRules.collectRecursiveAssetCatalogs(
                xcodeDescriptions,
                targetGraph,
                Optional.of(dependenciesCache),
                ImmutableList.of(targetNode),
                mode),
            AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
            AppleBuildRules.collectRecursiveWrapperResources(
                xcodeDescriptions,
                targetGraph,
                Optional.of(dependenciesCache),
                ImmutableList.of(targetNode),
                mode),
            Optional.of(copyFilesBuildPhases),
            bundleLoaderNode);

    if (bundleLoaderNode.isPresent()) {
      LOG.debug(
          "Generated iOS bundle target %s with binarynode: %s bundleLoadernode: %s",
          targetNode.getBuildTarget().getFullyQualifiedName(),
          binaryNode.getBuildTarget().getFullyQualifiedName(),
          bundleLoaderNode.get().getBuildTarget().getFullyQualifiedName());
    } else {
      LOG.debug(
          "Generated iOS bundle target %s with binarynode: %s and without bundleloader",
          targetNode.getBuildTarget().getFullyQualifiedName(),
          binaryNode.getBuildTarget().getFullyQualifiedName());
    }
    return target;
  }

  /**
   * Traverses the graph to find all (non-system) frameworks that should be embedded into the
   * target's bundle.
   */
  private ImmutableSet<TargetNode<?>> getTransitiveFrameworkNodes(
      TargetNode<? extends HasAppleBundleFields> targetNode) {
    GraphTraversable<TargetNode<?>> graphTraversable =
        node -> {
          if (!(node.getDescription() instanceof AppleResourceDescription)) {
            Set<BuildTarget> buildDeps = node.getBuildDeps();
            if (node.getDescription() instanceof AppleBundleDescription) {
              AppleBundleDescriptionArg arg = (AppleBundleDescriptionArg) node.getConstructorArg();
              // TODO: handle platform binaries in addition to regular binaries
              if (arg.getBinary().isPresent()) {
                buildDeps = Sets.union(buildDeps, ImmutableSet.of(arg.getBinary().get()));
              }
            }
            return targetGraph.getAll(buildDeps).iterator();
          } else {
            return Collections.emptyIterator();
          }
        };

    ImmutableSet.Builder<TargetNode<?>> filteredRules = ImmutableSet.builder();
    AcyclicDepthFirstPostOrderTraversal<TargetNode<?>> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(graphTraversable);
    try {
      for (TargetNode<?> node : traversal.traverse(ImmutableList.of(targetNode))) {
        if (node != targetNode) {
          TargetNodes.castArg(node, AppleBundleDescriptionArg.class)
              .ifPresent(
                  appleBundleNode -> {
                    if (isFrameworkBundle(appleBundleNode.getConstructorArg())) {
                      filteredRules.add(node);
                    }
                  });
          TargetNodes.castArg(node, PrebuiltAppleFrameworkDescriptionArg.class)
              .ifPresent(
                  prebuiltFramework -> {
                    // Technically (see Apple Tech Notes 2435), static frameworks are lies. In case
                    // a static framework is used, they can escape the incorrect project generation
                    // by marking its preferred linkage static (what does preferred linkage even
                    // mean for a prebuilt thing? none of this makes sense anyways).
                    if (prebuiltFramework.getConstructorArg().getPreferredLinkage()
                        != NativeLinkable.Linkage.STATIC) {
                      filteredRules.add(node);
                    }
                  });
        }
      }
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new RuntimeException(e);
    }
    return filteredRules.build();
  }

  /** Returns a new list of rules which does not contain framework bundles. */
  private ImmutableList<TargetNode<?>> rulesWithoutFrameworkBundles(
      Iterable<TargetNode<?>> copiedRules) {
    return RichStream.from(copiedRules)
        .filter(
            input ->
                TargetNodes.castArg(input, AppleBundleDescriptionArg.class)
                    .map(argTargetNode -> !isFrameworkBundle(argTargetNode.getConstructorArg()))
                    .orElse(true))
        .toImmutableList();
  }

  private ImmutableList<TargetNode<?>> rulesWithoutBundleLoader(
      Iterable<TargetNode<?>> copiedRules, TargetNode<?> bundleLoader) {
    return RichStream.from(copiedRules).filter(x -> !bundleLoader.equals(x)).toImmutableList();
  }

  private PBXNativeTarget generateAppleBinaryTarget(
      PBXProject project, TargetNode<AppleNativeTargetDescriptionArg> targetNode)
      throws IOException {
    PBXNativeTarget target =
        generateBinaryTarget(
            project,
            Optional.empty(),
            targetNode,
            ProductTypes.TOOL,
            "%s",
            Optional.empty(),
            /* includeFrameworks */ true,
            ImmutableSet.of(),
            AppleResources.collectDirectResources(targetGraph, targetNode),
            ImmutableSet.of(),
            AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
            ImmutableSet.of(),
            Optional.empty(),
            Optional.empty());
    LOG.debug(
        "Generated Apple binary target %s", targetNode.getBuildTarget().getFullyQualifiedName());
    return target;
  }

  private PBXNativeTarget generateAppleLibraryTarget(
      PBXProject project,
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode,
      Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode)
      throws IOException {
    PBXNativeTarget target =
        generateCxxLibraryTarget(
            project,
            targetNode,
            AppleResources.collectDirectResources(targetGraph, targetNode),
            AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
            bundleLoaderNode);
    LOG.debug(
        "Generated iOS library target %s", targetNode.getBuildTarget().getFullyQualifiedName());
    return target;
  }

  private PBXNativeTarget generateCxxLibraryTarget(
      PBXProject project,
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode,
      ImmutableSet<AppleResourceDescriptionArg> directResources,
      ImmutableSet<AppleAssetCatalogDescriptionArg> directAssetCatalogs,
      Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode)
      throws IOException {
    boolean isShared =
        targetNode.getBuildTarget().getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR);

    ProductType productType = isShared ? ProductTypes.DYNAMIC_LIBRARY : ProductTypes.STATIC_LIBRARY;
    PBXNativeTarget target =
        generateBinaryTarget(
            project,
            Optional.empty(),
            targetNode,
            productType,
            AppleBuildRules.getOutputFileNameFormatForLibrary(isShared),
            Optional.empty(),
            /* includeFrameworks */ isShared,
            ImmutableSet.of(),
            directResources,
            ImmutableSet.of(),
            directAssetCatalogs,
            ImmutableSet.of(),
            Optional.empty(),
            bundleLoaderNode);
    LOG.debug(
        "Generated Cxx library target %s", targetNode.getBuildTarget().getFullyQualifiedName());
    return target;
  }

  private ImmutableList<String> convertStringWithMacros(
      TargetNode<?> node, Iterable<StringWithMacros> flags) {

    // TODO(cjhopman): This seems really broken, it's totally inconsistent about what graphBuilder
    // is
    // provided. This should either just do rule resolution like normal or maybe do its own custom
    // MacroReplacer<>.
    LocationMacroExpander locationMacroExpander =
        new LocationMacroExpander() {
          @Override
          public Arg expandFrom(
              BuildTarget target,
              CellPathResolver cellNames,
              ActionGraphBuilder graphBuilder,
              LocationMacro input)
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
                    super.expandFrom(target, cellNames, builderFromNode, input),
                    DefaultSourcePathResolver.from(new SourcePathRuleFinder(builderFromNode))));
          }
        };

    ActionGraphBuilder emptyGraphBuilder =
        new SingleThreadedActionGraphBuilder(
            TargetGraph.EMPTY,
            new DefaultTargetNodeToBuildRuleTransformer(),
            projectCell.getCellProvider());
    ImmutableList.Builder<String> result = new ImmutableList.Builder<>();
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            node.getBuildTarget(), node.getCellNames(), ImmutableList.of(locationMacroExpander));
    for (StringWithMacros flag : flags) {
      macrosConverter
          .convert(flag, emptyGraphBuilder)
          .appendToCommandLine(result::add, defaultPathResolver);
    }
    return result.build();
  }

  private ImmutableMultimap<String, ImmutableList<String>> convertPlatformFlags(
      TargetNode<?> node,
      Iterable<PatternMatchedCollection<ImmutableList<StringWithMacros>>> matchers) {
    ImmutableMultimap.Builder<String, ImmutableList<String>> flagsBuilder =
        ImmutableMultimap.builder();

    for (PatternMatchedCollection<ImmutableList<StringWithMacros>> matcher : matchers) {
      for (Flavor flavor : appleCxxFlavors) {
        String platform = flavor.toString();
        for (ImmutableList<StringWithMacros> flags : matcher.getMatchingValues(platform)) {
          flagsBuilder.put(platform, convertStringWithMacros(node, flags));
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

  private Optional<String> getSwiftVersionForTargetNode(TargetNode<?> targetNode) {
    Optional<TargetNode<SwiftCommonArg>> targetNodeWithSwiftArgs =
        TargetNodes.castArg(targetNode, SwiftCommonArg.class);
    return targetNodeWithSwiftArgs.flatMap(t -> t.getConstructorArg().getSwiftVersion());
  }

  private static String sourceNameRelativeToOutput(
      SourcePath source, SourcePathResolver pathResolver, Path outputDirectory) {
    Path pathRelativeToCell = pathResolver.getRelativePath(source);
    Path pathRelativeToOutput = outputDirectory.relativize(pathRelativeToCell);
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
      ImmutableSet<String> applePlatforms,
      ImmutableList<Pair<Pattern, ImmutableSortedSet<SourceWithFlags>>> platformSources,
      ImmutableList<Pair<Pattern, Iterable<SourcePath>>> platformHeaders,
      Path outputDirectory,
      SourcePathResolver pathResolver) {
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
            allPlatformSpecificSources
                .stream()
                .map(s -> "'" + s + "'")
                .collect(Collectors.toSet())));
    // We need to convert the regex to a glob that Xcode will recognize so we match the regex
    // against the name of a known sdk with the matcher, then glob that.
    for (String platformMatcher : includedSourcesByPlatform.keySet()) {
      for (String flavor : applePlatforms) {
        Pattern pattern = Pattern.compile(platformMatcher);
        Matcher matcher = pattern.matcher(flavor);
        if (matcher.lookingAt()) {
          String key = "INCLUDED_SOURCE_FILE_NAMES[sdk=" + flavor + "*]";
          Set<String> sourcesMatchingPlatform = includedSourcesByPlatform.get(platformMatcher);
          if (sourcesMatchingPlatform != null) {
            Set<String> quotedSources =
                sourcesMatchingPlatform
                    .stream()
                    .map(s -> "'" + s + "'")
                    .collect(Collectors.toSet());
            // They may have different matchers for similar things in which case the key will
            // already
            // be included
            if (result.get(key) != null) {
              result.get(key).addAll(quotedSources);
            } else {
              result.put(
                  "INCLUDED_SOURCE_FILE_NAMES[sdk=" + flavor + "*]", new TreeSet<>(quotedSources));
            }
          }
        }
      }
    }

    ImmutableMap.Builder<String, ImmutableSortedSet<String>> finalResultBuilder =
        ImmutableMap.builder();

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

  /** @return a map of all exported platform headers without matching a specific platform. */
  public static ImmutableMap<Path, SourcePath> parseAllPlatformHeaders(
      BuildTarget buildTarget,
      SourcePathResolver sourcePathResolver,
      PatternMatchedCollection<SourceSortedSet> platformHeaders,
      boolean export,
      CxxLibraryDescription.CommonArg args) {
    ImmutableMap.Builder<String, SourcePath> parsed = ImmutableMap.builder();

    String parameterName = (export) ? "exported_platform_headers" : "platform_headers";

    // Include all platform specific headers.
    for (SourceSortedSet sourceList : platformHeaders.getValues()) {
      parsed.putAll(
          sourceList.toNameMap(
              buildTarget, sourcePathResolver, parameterName, path -> true, path -> path));
    }
    return CxxPreprocessables.resolveHeaderMap(
        args.getHeaderNamespace().map(Paths::get).orElse(buildTarget.getBasePath()),
        parsed.build());
  }

  private PBXNativeTarget generateBinaryTarget(
      PBXProject project,
      Optional<? extends TargetNode<? extends HasAppleBundleFields>> bundle,
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode,
      ProductType productType,
      String productOutputFormat,
      Optional<Path> infoPlistOptional,
      boolean includeFrameworks,
      ImmutableSet<AppleResourceDescriptionArg> recursiveResources,
      ImmutableSet<AppleResourceDescriptionArg> directResources,
      ImmutableSet<AppleAssetCatalogDescriptionArg> recursiveAssetCatalogs,
      ImmutableSet<AppleAssetCatalogDescriptionArg> directAssetCatalogs,
      ImmutableSet<AppleWrapperResourceArg> wrapperResources,
      Optional<Iterable<PBXBuildPhase>> copyFilesPhases,
      Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode)
      throws IOException {

    LOG.debug("Generating binary target for node %s", targetNode);

    TargetNode<?> buildTargetNode = bundle.isPresent() ? bundle.get() : targetNode;
    BuildTarget buildTarget = buildTargetNode.getBuildTarget();
    boolean containsSwiftCode = projGenerationStateCache.targetContainsSwiftSourceCode(targetNode);

    String buildTargetName = getProductNameForBuildTargetNode(buildTargetNode);
    CxxLibraryDescription.CommonArg arg = targetNode.getConstructorArg();
    NewNativeTargetProjectMutator mutator =
        new NewNativeTargetProjectMutator(pathRelativizer, this::resolveSourcePath);

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
    boolean isFocusedOnTarget = focusModules.isFocusedOn(buildTarget);

    Optional<String> swiftVersion = getSwiftVersionForTargetNode(targetNode);
    boolean hasSwiftVersionArg = swiftVersion.isPresent();
    if (!swiftVersion.isPresent()) {
      swiftVersion = swiftBuckConfig.getVersion();
    }

    mutator
        .setTargetName(getXcodeTargetName(buildTarget))
        .setProduct(
            productType,
            buildTargetName,
            Paths.get(String.format(productOutputFormat, buildTargetName)));

    boolean isModularAppleLibrary = isModularAppleLibrary(targetNode) && isFocusedOnTarget;
    mutator.setFrameworkHeadersEnabled(isModularAppleLibrary);

    ImmutableMap.Builder<String, String> swiftDepsSettingsBuilder = ImmutableMap.builder();
    ImmutableList.Builder<String> swiftDebugLinkerFlagsBuilder = ImmutableList.builder();

    ImmutableMap.Builder<String, String> extraSettingsBuilder = ImmutableMap.builder();
    ImmutableMap.Builder<String, String> defaultSettingsBuilder = ImmutableMap.builder();

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
        ProjectGenerator.gatherExcludedSources(
            appleCxxFlavors
                .stream()
                .map(f -> applePlatformAndArchitecture(f).getFirst())
                .collect(ImmutableSet.toImmutableSet()),
            platformSources,
            platformHeadersIterable,
            outputDirectory,
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

    if (!options.shouldGenerateHeaderSymlinkTreesOnly()) {
      if (isFocusedOnTarget) {
        filesAddedBuilder.addAll(
            allSrcs.stream().map(s -> s.getSourcePath()).collect(ImmutableList.toImmutableList()));
        mutator
            .setLangPreprocessorFlags(
                ImmutableMap.copyOf(
                    Maps.transformValues(
                        langPreprocessorFlags, f -> convertStringWithMacros(targetNode, f))))
            .setPublicHeaders(exportedHeaders)
            .setPrefixHeader(getPrefixHeaderSourcePath(arg))
            .setSourcesWithFlags(ImmutableSet.copyOf(allSrcs))
            .setPrivateHeaders(headers)
            .setRecursiveResources(recursiveResources)
            .setDirectResources(directResources)
            .setWrapperResources(wrapperResources)
            .setExtraXcodeSources(ImmutableSet.copyOf(arg.getExtraXcodeSources()))
            .setExtraXcodeFiles(ImmutableSet.copyOf(arg.getExtraXcodeFiles()));
      }

      if (bundle.isPresent() && isFocusedOnTarget) {
        HasAppleBundleFields bundleArg = bundle.get().getConstructorArg();
        mutator.setInfoPlist(Optional.of(bundleArg.getInfoPlist()));
      }

      mutator.setBridgingHeader(arg.getBridgingHeader());

      if (options.shouldCreateDirectoryStructure() && isFocusedOnTarget) {
        mutator.setTargetGroupPath(
            RichStream.from(buildTarget.getBasePath()).map(Object::toString).toImmutableList());
      }

      if (!recursiveAssetCatalogs.isEmpty() && isFocusedOnTarget) {
        mutator.setRecursiveAssetCatalogs(recursiveAssetCatalogs);
      }

      if (!directAssetCatalogs.isEmpty() && isFocusedOnTarget) {
        mutator.setDirectAssetCatalogs(directAssetCatalogs);
      }

      FluentIterable<TargetNode<?>> depTargetNodes = collectRecursiveLibraryDepTargets(targetNode);

      if (includeFrameworks && isFocusedOnTarget) {

        if (!options.shouldAddLinkedLibrariesAsFlags()) {
          mutator.setFrameworks(getSytemFrameworksLibsForTargetNode(targetNode));
        }

        if (sharedLibraryToBundle.isPresent()) {
          // Replace target nodes of libraries which are actually constituents of embedded
          // frameworks to the bundle representing the embedded framework.
          // This will be converted to a reference to the xcode build product for the embedded
          // framework rather than the dylib
          depTargetNodes =
              swapSharedLibrariesForBundles(depTargetNodes, sharedLibraryToBundle.get());
        }

        ImmutableSet<PBXFileReference> targetNodeDeps =
            filterRecursiveLibraryDependenciesForLinkerPhase(depTargetNodes);

        if (isTargetNodeApplicationTestTarget(targetNode, bundleLoaderNode)) {
          ImmutableSet<PBXFileReference> bundleLoaderDeps =
              bundleLoaderNode.isPresent()
                  ? collectRecursiveLibraryDependencies(bundleLoaderNode.get())
                  : ImmutableSet.of();
          mutator.setArchives(Sets.difference(targetNodeDeps, bundleLoaderDeps));
        } else {
          mutator.setArchives(targetNodeDeps);
        }
      }

      if (isFocusedOnTarget) {
        ImmutableSet<TargetNode<?>> swiftDepTargets =
            filterRecursiveLibraryDepTargetsWithSwiftSources(depTargetNodes);

        if (!includeFrameworks && !swiftDepTargets.isEmpty()) {
          // If the current target, which is non-shared (e.g., static lib), depends on other focused
          // targets which include Swift code, we must ensure those are treated as dependencies so
          // that Xcode builds the targets in the correct order. Unfortunately, those deps can be
          // part of other projects which would require cross-project references.
          //
          // Thankfully, there's an easy workaround because we can just create a phony copy phase
          // which depends on the outputs of the deps (i.e., the static libs). The copy phase
          // will effectively say "Copy libX.a from Products Dir into Products Dir" which is a nop.
          // To be on the safe side, we're explicitly marking the copy phase as only running for
          // deployment postprocessing (i.e., "Copy only when installing") and disabling
          // deployment postprocessing (it's enabled by default for release builds).
          CopyFilePhaseDestinationSpec.Builder destSpecBuilder =
              CopyFilePhaseDestinationSpec.builder();
          destSpecBuilder.setDestination(PBXCopyFilesBuildPhase.Destination.PRODUCTS);
          PBXCopyFilesBuildPhase copyFiles = new PBXCopyFilesBuildPhase(destSpecBuilder.build());
          copyFiles.setRunOnlyForDeploymentPostprocessing(Optional.of(Boolean.TRUE));
          copyFiles.setName(Optional.of("Fake Swift Dependencies (Copy Files Phase)"));

          ImmutableSet<PBXFileReference> swiftDepsFileRefs =
              targetNodesSetToPBXFileReference(swiftDepTargets);
          for (PBXFileReference fileRef : swiftDepsFileRefs) {
            PBXBuildFile buildFile = new PBXBuildFile(fileRef);
            copyFiles.getFiles().add(buildFile);
          }

          swiftDepsSettingsBuilder.put("DEPLOYMENT_POSTPROCESSING", "NO");

          mutator.setSwiftDependenciesBuildPhase(copyFiles);
        }

        if (includeFrameworks
            && !swiftDepTargets.isEmpty()
            && shouldEmbedSwiftRuntimeInBundleTarget(bundle)
            && swiftBuckConfig.getProjectEmbedRuntime()) {
          // This is a binary that transitively depends on a library that uses Swift. We must ensure
          // that the Swift runtime is bundled.
          swiftDepsSettingsBuilder.put("ALWAYS_EMBED_SWIFT_STANDARD_LIBRARIES", "YES");
        }

        if (includeFrameworks
            && !swiftDepTargets.isEmpty()
            && swiftBuckConfig.getProjectAddASTPaths()) {
          for (TargetNode<?> swiftNode : swiftDepTargets) {
            String swiftModulePath =
                String.format(
                    "${BUILT_PRODUCTS_DIR}/%s.swiftmodule/${CURRENT_ARCH}.swiftmodule",
                    getModuleName(swiftNode));
            swiftDebugLinkerFlagsBuilder.add("-Xlinker");
            swiftDebugLinkerFlagsBuilder.add("-add_ast_path");
            swiftDebugLinkerFlagsBuilder.add("-Xlinker");
            swiftDebugLinkerFlagsBuilder.add(swiftModulePath);
          }
        }
      }

      // TODO(Task #3772930): Go through all dependencies of the rule
      // and add any shell script rules here
      ImmutableList.Builder<TargetNode<?>> preScriptPhasesBuilder = ImmutableList.builder();
      ImmutableList.Builder<TargetNode<?>> postScriptPhasesBuilder = ImmutableList.builder();
      if (bundle.isPresent() && targetNode != bundle.get() && isFocusedOnTarget) {
        collectBuildScriptDependencies(
            targetGraph.getAll(bundle.get().getDeclaredDeps()),
            preScriptPhasesBuilder,
            postScriptPhasesBuilder);
      }
      collectBuildScriptDependencies(
          targetGraph.getAll(targetNode.getDeclaredDeps()),
          preScriptPhasesBuilder,
          postScriptPhasesBuilder);
      if (isFocusedOnTarget) {
        ImmutableList<TargetNode<?>> preScriptPhases = preScriptPhasesBuilder.build();
        ImmutableList<TargetNode<?>> postScriptPhases = postScriptPhasesBuilder.build();

        mutator.setPreBuildRunScriptPhasesFromTargetNodes(
            preScriptPhases, actionGraphBuilderForNode::apply);
        if (copyFilesPhases.isPresent()) {
          mutator.setCopyFilesPhases(copyFilesPhases.get());
        }
        mutator.setPostBuildRunScriptPhasesFromTargetNodes(
            postScriptPhases, actionGraphBuilderForNode::apply);

        ImmutableList<TargetNode<?>> scriptPhases =
            Stream.concat(preScriptPhases.stream(), postScriptPhases.stream())
                .collect(ImmutableList.toImmutableList());
        mutator.collectFilesToCopyInXcode(
            filesToCopyInXcodeBuilder, scriptPhases, projectCell, actionGraphBuilderForNode::apply);
      }
    }

    NewNativeTargetProjectMutator.Result targetBuilderResult =
        mutator.buildTargetAndAddToProject(project, isFocusedOnTarget);
    PBXNativeTarget target = targetBuilderResult.target;
    Optional<PBXGroup> targetGroup = targetBuilderResult.targetGroup;

    extraSettingsBuilder.putAll(swiftDepsSettingsBuilder.build());

    setAppIconSettings(
        recursiveAssetCatalogs, directAssetCatalogs, buildTarget, defaultSettingsBuilder);
    setLaunchImageSettings(
        recursiveAssetCatalogs, directAssetCatalogs, buildTarget, defaultSettingsBuilder);

    ImmutableSortedMap<Path, SourcePath> publicCxxHeaders = getPublicCxxHeaders(targetNode);
    if (isModularAppleLibrary(targetNode) && isFrameworkProductType(productType)) {
      // Modular frameworks should not include Buck-generated hmaps as they break the VFS overlay
      // that's generated by Xcode and consequently, all headers part of a framework's umbrella
      // header fail the modularity test, as they're expected to be mapped by the VFS layer under
      // $BUILT_PRODUCTS_DIR/Module.framework/Versions/A/Headers.
      publicCxxHeaders = ImmutableSortedMap.of();
    }

    if (!options.shouldGenerateHeaderSymlinkTreesOnly()) {
      if (isFocusedOnTarget) {
        SourceTreePath buckFilePath =
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativizer.outputPathToBuildTargetPath(buildTarget).resolve(buildFileName),
                Optional.empty());
        PBXFileReference buckReference =
            targetGroup.get().getOrCreateFileReferenceBySourceTreePath(buckFilePath);
        buckReference.setExplicitFileType(Optional.of("text.script.python"));
      }

      // Watch dependencies need to have explicit target dependencies setup in order for Xcode to
      // build them properly within the IDE.  It is unable to match the implicit dependency because
      // of the different in flavor between the targets (iphoneos vs watchos).
      if (bundle.isPresent() && isFocusedOnTarget) {
        collectProjectTargetWatchDependencies(
            targetNode.getBuildTarget().getFlavorPostfix(),
            target,
            targetGraph.getAll(bundle.get().getExtraDeps()));
      }

      // -- configurations
      extraSettingsBuilder
          .put("TARGET_NAME", buildTargetName)
          .put("SRCROOT", pathRelativizer.outputPathToBuildTargetPath(buildTarget).toString());
      if (productType == ProductTypes.UI_TEST && isFocusedOnTarget) {
        if (bundleLoaderNode.isPresent()) {
          BuildTarget testTarget = bundleLoaderNode.get().getBuildTarget();
          extraSettingsBuilder.put("TEST_TARGET_NAME", getXcodeTargetName(testTarget));
          addPBXTargetDependency(target, testTarget);
        } else {
          throw new HumanReadableException(
              "The test rule '%s' is configured with 'is_ui_test' but has no test_host_app",
              buildTargetName);
        }
      } else if (bundleLoaderNode.isPresent() && isFocusedOnTarget) {
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

        addPBXTargetDependency(target, bundleLoader.getBuildTarget());
      }
      if (infoPlistOptional.isPresent()) {
        Path infoPlistPath = pathRelativizer.outputDirToRootRelative(infoPlistOptional.get());
        extraSettingsBuilder.put("INFOPLIST_FILE", infoPlistPath.toString());
      }
      if (arg.getBridgingHeader().isPresent()) {
        Path bridgingHeaderPath =
            pathRelativizer.outputDirToRootRelative(
                resolveSourcePath(arg.getBridgingHeader().get()));
        extraSettingsBuilder.put(
            "SWIFT_OBJC_BRIDGING_HEADER",
            Joiner.on('/').join("$(SRCROOT)", bridgingHeaderPath.toString()));
      }

      swiftVersion.ifPresent(s -> extraSettingsBuilder.put("SWIFT_VERSION", s));
      swiftVersion.ifPresent(
          s -> extraSettingsBuilder.put("PRODUCT_MODULE_NAME", getModuleName(targetNode)));

      if (hasSwiftVersionArg && containsSwiftCode && isFocusedOnTarget) {
        extraSettingsBuilder.put(
            "SWIFT_OBJC_INTERFACE_HEADER_NAME", getSwiftObjCGeneratedHeaderName(buildTargetNode));

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

      defaultSettingsBuilder.put(
          "REPO_ROOT", projectFilesystem.getRootPath().toAbsolutePath().normalize().toString());
      if (hasSwiftVersionArg && containsSwiftCode && isFocusedOnTarget) {
        // We need to be able to control the directory where Xcode places the derived sources, so
        // that the Obj-C Generated Header can be included in the header map and imported through
        // a framework-style import like <Module/Module-Swift.h>
        Path derivedSourcesDir =
            getDerivedSourcesDirectoryForBuildTarget(buildTarget, projectFilesystem);
        Path derivedSourceDirRelativeToProjectRoot =
            pathRelativizer.outputDirToRootRelative(derivedSourcesDir);

        defaultSettingsBuilder.put(
            "DERIVED_FILE_DIR", derivedSourceDirRelativeToProjectRoot.toString());
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

      if (isFocusedOnTarget) {
        ImmutableSet<Path> recursiveHeaderSearchPaths =
            collectRecursiveHeaderSearchPaths(targetNode);
        ImmutableSet<Path> headerMapBases = collectRecursiveHeaderMapBases(targetNode);

        ImmutableMap.Builder<String, String> appendConfigsBuilder = ImmutableMap.builder();
        appendConfigsBuilder.putAll(
            getFrameworkAndLibrarySearchPathConfigs(targetNode, includeFrameworks));
        appendConfigsBuilder.put(
            "HEADER_SEARCH_PATHS",
            Joiner.on(' ').join(Iterables.concat(recursiveHeaderSearchPaths, headerMapBases)));
        if (hasSwiftVersionArg && containsSwiftCode && isFocusedOnTarget) {
          appendConfigsBuilder.put("SWIFT_INCLUDE_PATHS", "$BUILT_PRODUCTS_DIR");
        }

        ImmutableList.Builder<String> targetSpecificSwiftFlags = ImmutableList.builder();
        Optional<TargetNode<SwiftCommonArg>> swiftTargetNode =
            TargetNodes.castArg(targetNode, SwiftCommonArg.class);
        targetSpecificSwiftFlags.addAll(
            swiftTargetNode
                .map(
                    x ->
                        convertStringWithMacros(
                            targetNode, x.getConstructorArg().getSwiftCompilerFlags()))
                .orElse(ImmutableList.of()));

        if (containsSwiftCode && isModularAppleLibrary && publicCxxHeaders.size() > 0) {
          targetSpecificSwiftFlags.addAll(collectModularTargetSpecificSwiftFlags(targetNode));
        }

        ImmutableList<String> testingOverlay = getFlagsForExcludesForModulesUnderTests(targetNode);
        Iterable<String> otherSwiftFlags =
            Iterables.concat(
                swiftBuckConfig.getCompilerFlags().orElse(DEFAULT_SWIFTFLAGS),
                targetSpecificSwiftFlags.build());

        Iterable<String> otherCFlags =
            ImmutableList.<String>builder()
                .addAll(cxxBuckConfig.getCflags().orElse(DEFAULT_CFLAGS))
                .addAll(
                    convertStringWithMacros(
                        targetNode, collectRecursiveExportedPreprocessorFlags(targetNode)))
                .addAll(
                    convertStringWithMacros(
                        targetNode, targetNode.getConstructorArg().getCompilerFlags()))
                .addAll(
                    convertStringWithMacros(
                        targetNode, targetNode.getConstructorArg().getPreprocessorFlags()))
                .addAll(testingOverlay)
                .build();
        Iterable<String> otherCxxFlags =
            ImmutableList.<String>builder()
                .addAll(cxxBuckConfig.getCxxflags().orElse(DEFAULT_CXXFLAGS))
                .addAll(
                    convertStringWithMacros(
                        targetNode, collectRecursiveExportedPreprocessorFlags(targetNode)))
                .addAll(
                    convertStringWithMacros(
                        targetNode, targetNode.getConstructorArg().getCompilerFlags()))
                .addAll(
                    convertStringWithMacros(
                        targetNode, targetNode.getConstructorArg().getPreprocessorFlags()))
                .addAll(testingOverlay)
                .build();

        appendConfigsBuilder
            .put(
                "OTHER_SWIFT_FLAGS",
                Streams.stream(otherSwiftFlags)
                    .map(Escaper.BASH_ESCAPER)
                    .collect(Collectors.joining(" ")))
            .put(
                "OTHER_CFLAGS",
                Streams.stream(otherCFlags)
                    .map(Escaper.BASH_ESCAPER)
                    .collect(Collectors.joining(" ")))
            .put(
                "OTHER_CPLUSPLUSFLAGS",
                Streams.stream(otherCxxFlags)
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
                            collectRecursiveExportedLinkerFlags(targetNode))))
                .addAll(swiftDebugLinkerFlagsBuilder.build())
                .build();

        updateOtherLinkerFlagsForOptions(
            targetNode, bundleLoaderNode, appendConfigsBuilder, otherLdFlags);

        ImmutableMultimap<String, ImmutableList<String>> platformFlags =
            convertPlatformFlags(
                targetNode,
                Iterables.concat(
                    ImmutableList.of(targetNode.getConstructorArg().getPlatformCompilerFlags()),
                    ImmutableList.of(targetNode.getConstructorArg().getPlatformPreprocessorFlags()),
                    collectRecursiveExportedPlatformPreprocessorFlags(targetNode)));
        for (String platform : platformFlags.keySet()) {
          appendConfigsBuilder
              .put(
                  generateConfigKey("OTHER_CFLAGS", platform),
                  Streams.stream(
                          Iterables.transform(
                              Iterables.concat(
                                  otherCFlags, Iterables.concat(platformFlags.get(platform))),
                              Escaper.BASH_ESCAPER::apply))
                      .collect(Collectors.joining(" ")))
              .put(
                  generateConfigKey("OTHER_CPLUSPLUSFLAGS", platform),
                  Streams.stream(
                          Iterables.transform(
                              Iterables.concat(
                                  otherCxxFlags, Iterables.concat(platformFlags.get(platform))),
                              Escaper.BASH_ESCAPER::apply))
                      .collect(Collectors.joining(" ")));
        }

        ImmutableMultimap<String, ImmutableList<String>> platformLinkerFlags =
            convertPlatformFlags(
                targetNode,
                Iterables.concat(
                    ImmutableList.of(targetNode.getConstructorArg().getPlatformLinkerFlags()),
                    collectRecursiveExportedPlatformLinkerFlags(targetNode)));
        for (String platform : platformLinkerFlags.keySet()) {
          appendConfigsBuilder.put(
              generateConfigKey("OTHER_LDFLAGS", platform),
              Streams.stream(
                      Iterables.transform(
                          Iterables.concat(
                              otherLdFlags, Iterables.concat(platformLinkerFlags.get(platform))),
                          Escaper.BASH_ESCAPER::apply))
                  .collect(Collectors.joining(" ")));
        }

        ImmutableMap<String, String> appendedConfig = appendConfigsBuilder.build();

        Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs =
            getXcodeBuildConfigurationsForTargetNode(targetNode, appendedConfig);
        setTargetBuildConfigurations(
            buildTarget,
            target,
            project.getMainGroup(),
            configs.get(),
            extraSettingsBuilder.build(),
            defaultSettingsBuilder.build(),
            appendedConfig);
      }
    }

    Optional<String> moduleName =
        isModularAppleLibrary ? Optional.of(getModuleName(targetNode)) : Optional.empty();
    // -- phases
    createHeaderSymlinkTree(
        publicCxxHeaders,
        getSwiftPublicHeaderMapEntriesForTarget(targetNode),
        moduleName,
        getPathToHeaderSymlinkTree(targetNode, HeaderVisibility.PUBLIC),
        arg.getXcodePublicHeadersSymlinks().orElse(cxxBuckConfig.getPublicHeadersSymlinksEnabled())
            || !options.shouldUseHeaderMaps(),
        !shouldMergeHeaderMaps(),
        options.shouldGenerateMissingUmbrellaHeader());
    if (isFocusedOnTarget) {
      createHeaderSymlinkTree(
          getPrivateCxxHeaders(targetNode),
          ImmutableMap.of(), // private interfaces never have a modulemap
          Optional.empty(),
          getPathToHeaderSymlinkTree(targetNode, HeaderVisibility.PRIVATE),
          arg.getXcodePrivateHeadersSymlinks()
                  .orElse(cxxBuckConfig.getPrivateHeadersSymlinksEnabled())
              || !options.shouldUseHeaderMaps(),
          options.shouldUseHeaderMaps(),
          options.shouldGenerateMissingUmbrellaHeader());
    }

    Optional<TargetNode<AppleNativeTargetDescriptionArg>> appleTargetNode =
        TargetNodes.castArg(targetNode, AppleNativeTargetDescriptionArg.class);
    if (appleTargetNode.isPresent()
        && isFocusedOnTarget
        && !options.shouldGenerateHeaderSymlinkTreesOnly()) {
      // Use Core Data models from immediate dependencies only.
      addCoreDataModelsIntoTarget(appleTargetNode.get(), targetGroup.get());
      addSceneKitAssetsIntoTarget(appleTargetNode.get(), targetGroup.get());
    }

    if (bundle.isPresent()
        && isFocusedOnTarget
        && !options.shouldGenerateHeaderSymlinkTreesOnly()) {
      addEntitlementsPlistIntoTarget(bundle.get(), targetGroup.get());
    }

    return target;
  }

  /** Generate a mapping from libraries to the framework bundles that include them. */
  public static ImmutableMap<BuildTarget, TargetNode<?>> computeSharedLibrariesToBundles(
      ImmutableSet<TargetNode<?>> targetNodes, TargetGraphAndTargets targetGraphAndTargets)
      throws HumanReadableException {

    Map<BuildTarget, TargetNode<?>> sharedLibraryToBundle = new HashMap<>();
    for (TargetNode<?> targetNode : targetNodes) {
      if (targetNode.getBuildTarget().isFlavored()) {
        continue;
      }
      Optional<TargetNode<CxxLibraryDescription.CommonArg>> binaryNode =
          TargetNodes.castArg(targetNode, AppleBundleDescriptionArg.class)
              .flatMap(bundleNode -> bundleNode.getConstructorArg().getBinary())
              .map(target -> targetGraphAndTargets.getTargetGraph().get(target))
              .flatMap(node -> TargetNodes.castArg(node, CxxLibraryDescription.CommonArg.class));
      if (!binaryNode.isPresent()) {
        continue;
      }
      CxxLibraryDescription.CommonArg arg = binaryNode.get().getConstructorArg();
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

  private ImmutableSet<FrameworkPath> getSytemFrameworksLibsForTargetNode(
      TargetNode<? extends CommonArg> targetNode) {
    ImmutableSet.Builder<FrameworkPath> frameworksBuilder = ImmutableSet.builder();
    frameworksBuilder.addAll(collectRecursiveFrameworkDependencies(targetNode));
    frameworksBuilder.addAll(targetNode.getConstructorArg().getFrameworks());
    frameworksBuilder.addAll(targetNode.getConstructorArg().getLibraries());
    return frameworksBuilder.build();
  }

  /**
   * Subdivide the various deps and write out to the xcconfig file for scripts to post process if
   * needed*
   */
  private void updateOtherLinkerFlagsForOptions(
      TargetNode<? extends CommonArg> targetNode,
      Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode,
      Builder<String, String> appendConfigsBuilder,
      Iterable<String> otherLdFlags) {

    // Local: Local to the current project and built by Xcode.
    // Focused: Included in the workspace and built by Xcode but not in current project.
    // Other: Not included in the workspace to be built by Xcode.
    FluentIterable<TargetNode<?>> depTargetNodes = collectRecursiveLibraryDepTargets(targetNode);

    // Don't duplicate linker flags for the bundle loader.
    FluentIterable<TargetNode<?>> filteredDeps =
        collectRecursiveLibraryDepsMinusBundleLoaderDeps(
            targetNode, depTargetNodes, bundleLoaderNode);

    ImmutableSet<FrameworkPath> systemFwkOrLibs = getSytemFrameworksLibsForTargetNode(targetNode);
    ImmutableList<String> systemFwkOrLibFlags =
        collectSystemLibraryAndFrameworkLinkerFlags(systemFwkOrLibs);

    if (options.shouldForceLoadLinkWholeLibraries() || options.shouldAddLinkedLibrariesAsFlags()) {
      ImmutableList<String> forceLoadLocal =
          collectForceLoadLinkerFlags(
              filterRecursiveLibraryDepsIterable(
                  filteredDeps, FilterFlags.LIBRARY_CURRENT_PROJECT_WITH_FORCE_LOAD));
      ImmutableList<String> forceLoadFocused =
          collectForceLoadLinkerFlags(
              filterRecursiveLibraryDepsIterable(
                  filteredDeps, FilterFlags.LIBRARY_FOCUSED_WITH_FORCE_LOAD));
      ImmutableList<String> forceLoadOther =
          collectForceLoadLinkerFlags(
              filterRecursiveLibraryDepsIterable(
                  filteredDeps, FilterFlags.LIBRARY_OTHER_WITH_FORCE_LOAD));

      appendConfigsBuilder.put(
          "BUCK_LINKER_FLAGS_LIBRARY_FORCE_LOAD_LOCAL",
          Streams.stream(forceLoadLocal)
              .map(Escaper.BASH_ESCAPER)
              .collect(Collectors.joining(" ")));

      appendConfigsBuilder.put(
          "BUCK_LINKER_FLAGS_LIBRARY_FORCE_LOAD_FOCUSED",
          Streams.stream(forceLoadFocused)
              .map(Escaper.BASH_ESCAPER)
              .collect(Collectors.joining(" ")));

      appendConfigsBuilder.put(
          "BUCK_LINKER_FLAGS_LIBRARY_FORCE_LOAD_OTHER",
          Streams.stream(forceLoadOther)
              .map(Escaper.BASH_ESCAPER)
              .collect(Collectors.joining(" ")));
    }

    if (options.shouldAddLinkedLibrariesAsFlags()) {
      // If force load enabled, then don't duplicate the flags in the OTHER flags, otherwise they
      // will just be included like normal dependencies.
      boolean shouldLimitByForceLoad = options.shouldForceLoadLinkWholeLibraries();
      Iterable<String> localLibraryFlags =
          collectLibraryLinkerFlags(
              filterRecursiveLibraryDepsIterable(
                  filteredDeps,
                  shouldLimitByForceLoad
                      ? FilterFlags.LIBRARY_CURRENT_PROJECT_WITHOUT_FORCE_LOAD
                      : FilterFlags.LIBRARY_CURRENT_PROJECT));
      Iterable<String> focusedLibraryFlags =
          collectLibraryLinkerFlags(
              filterRecursiveLibraryDepsIterable(
                  filteredDeps,
                  shouldLimitByForceLoad
                      ? FilterFlags.LIBRARY_FOCUSED_WITHOUT_FORCE_LOAD
                      : FilterFlags.LIBRARY_FOCUSED));
      Iterable<String> otherLibraryFlags =
          collectLibraryLinkerFlags(
              filterRecursiveLibraryDepsIterable(
                  filteredDeps,
                  shouldLimitByForceLoad
                      ? FilterFlags.LIBRARY_OTHER_WITHOUT_FORCE_LOAD
                      : FilterFlags.LIBRARY_OTHER));

      Iterable<String> localFrameworkFlags =
          collectFrameworkLinkerFlags(
              filterRecursiveLibraryDepsIterable(
                  depTargetNodes, FilterFlags.FRAMEWORK_CURRENT_PROJECT));

      Iterable<String> focusedFrameworkFlags =
          collectFrameworkLinkerFlags(
              filterRecursiveLibraryDepsIterable(depTargetNodes, FilterFlags.FRAMEWORK_FOCUSED));
      Iterable<String> otherFrameworkFlags =
          collectFrameworkLinkerFlags(
              filterRecursiveLibraryDepsIterable(depTargetNodes, FilterFlags.FRAMEWORK_OTHER));

      appendConfigsBuilder
          .put(
              "BUCK_LINKER_FLAGS_LIBRARY_LOCAL",
              Streams.stream(localLibraryFlags).collect(Collectors.joining(" ")))
          .put(
              "BUCK_LINKER_FLAGS_LIBRARY_FOCUSED",
              Streams.stream(focusedLibraryFlags).collect(Collectors.joining(" ")))
          .put(
              "BUCK_LINKER_FLAGS_LIBRARY_OTHER",
              Streams.stream(otherLibraryFlags).collect(Collectors.joining(" ")))
          .put(
              "BUCK_LINKER_FLAGS_FRAMEWORK_LOCAL",
              Streams.stream(localFrameworkFlags).collect(Collectors.joining(" ")))
          .put(
              "BUCK_LINKER_FLAGS_FRAMEWORK_FOCUSED",
              Streams.stream(focusedFrameworkFlags).collect(Collectors.joining(" ")))
          .put(
              "BUCK_LINKER_FLAGS_FRAMEWORK_OTHER",
              Streams.stream(otherFrameworkFlags).collect(Collectors.joining(" ")))
          .put(
              "BUCK_LINKER_FLAGS_SYSTEM",
              Streams.stream(systemFwkOrLibFlags).collect(Collectors.joining(" ")));
    }

    Stream<String> otherLdFlagsStream = Streams.stream(otherLdFlags).map(Escaper.BASH_ESCAPER);

    if (options.shouldForceLoadLinkWholeLibraries() && options.shouldAddLinkedLibrariesAsFlags()) {
      appendConfigsBuilder.put(
          "OTHER_LDFLAGS",
          Streams.concat(
                  otherLdFlagsStream,
                  Stream.of(
                      "$BUCK_LINKER_FLAGS_SYSTEM",
                      "$BUCK_LINKER_FLAGS_FRAMEWORK_LOCAL",
                      "$BUCK_LINKER_FLAGS_FRAMEWORK_FOCUSED",
                      "$BUCK_LINKER_FLAGS_FRAMEWORK_OTHER",
                      "$BUCK_LINKER_FLAGS_LIBRARY_FORCE_LOAD_LOCAL",
                      "$BUCK_LINKER_FLAGS_LIBRARY_FORCE_LOAD_FOCUSED",
                      "$BUCK_LINKER_FLAGS_LIBRARY_FORCE_LOAD_OTHER",
                      "$BUCK_LINKER_FLAGS_LIBRARY_LOCAL",
                      "$BUCK_LINKER_FLAGS_LIBRARY_FOCUSED",
                      "$BUCK_LINKER_FLAGS_LIBRARY_OTHER"))
              .collect(Collectors.joining(" ")));
    } else if (options.shouldForceLoadLinkWholeLibraries()
        && !options.shouldAddLinkedLibrariesAsFlags()) {
      appendConfigsBuilder.put(
          "OTHER_LDFLAGS",
          Streams.concat(
                  otherLdFlagsStream,
                  Stream.of(
                      "$BUCK_LINKER_FLAGS_LIBRARY_FORCE_LOAD_LOCAL",
                      "$BUCK_LINKER_FLAGS_LIBRARY_FORCE_LOAD_FOCUSED",
                      "$BUCK_LINKER_FLAGS_LIBRARY_FORCE_LOAD_OTHER"))
              .collect(Collectors.joining(" ")));
    } else if (options.shouldAddLinkedLibrariesAsFlags()) {
      appendConfigsBuilder.put(
          "OTHER_LDFLAGS",
          Streams.concat(
                  otherLdFlagsStream,
                  Stream.of(
                      "$BUCK_LINKER_FLAGS_SYSTEM",
                      "$BUCK_LINKER_FLAGS_FRAMEWORK_LOCAL",
                      "$BUCK_LINKER_FLAGS_FRAMEWORK_FOCUSED",
                      "$BUCK_LINKER_FLAGS_FRAMEWORK_OTHER",
                      "$BUCK_LINKER_FLAGS_LIBRARY_LOCAL",
                      "$BUCK_LINKER_FLAGS_LIBRARY_FOCUSED",
                      "$BUCK_LINKER_FLAGS_LIBRARY_OTHER"))
              .collect(Collectors.joining(" ")));
    } else {
      appendConfigsBuilder.put(
          "OTHER_LDFLAGS", otherLdFlagsStream.collect(Collectors.joining(" ")));
    }
  }

  private void setAppIconSettings(
      ImmutableSet<AppleAssetCatalogDescriptionArg> recursiveAssetCatalogs,
      ImmutableSet<AppleAssetCatalogDescriptionArg> directAssetCatalogs,
      BuildTarget buildTarget,
      ImmutableMap.Builder<String, String> defaultSettingsBuilder) {
    ImmutableList<String> appIcon =
        Stream.concat(directAssetCatalogs.stream(), recursiveAssetCatalogs.stream())
            .map(x -> x.getAppIcon())
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(ImmutableList.toImmutableList());
    if (appIcon.size() > 1) {
      throw new HumanReadableException(
          "At most one asset catalog in the dependencies of %s " + "can have a app_icon",
          buildTarget);
    } else if (appIcon.size() == 1) {
      defaultSettingsBuilder.put("ASSETCATALOG_COMPILER_APPICON_NAME", appIcon.get(0));
    }
  }

  private void setLaunchImageSettings(
      ImmutableSet<AppleAssetCatalogDescriptionArg> recursiveAssetCatalogs,
      ImmutableSet<AppleAssetCatalogDescriptionArg> directAssetCatalogs,
      BuildTarget buildTarget,
      ImmutableMap.Builder<String, String> defaultSettingsBuilder) {
    ImmutableList<String> launchImage =
        Stream.concat(directAssetCatalogs.stream(), recursiveAssetCatalogs.stream())
            .map(x -> x.getLaunchImage())
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(ImmutableList.toImmutableList());
    if (launchImage.size() > 1) {
      throw new HumanReadableException(
          "At most one asset catalog in the dependencies of %s " + "can have a launch_image",
          buildTarget);
    } else if (launchImage.size() == 1) {
      defaultSettingsBuilder.put("ASSETCATALOG_COMPILER_LAUNCHIMAGE_NAME", launchImage.get(0));
    }
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

  private ImmutableList<String> getFlagsForExcludesForModulesUnderTests(
      TargetNode<? extends CxxLibraryDescription.CommonArg> testingTarget) {
    ImmutableList.Builder<String> testingOverlayBuilder = new ImmutableList.Builder<>();
    visitRecursivePrivateHeaderSymlinkTreesForTests(
        testingTarget,
        (targetUnderTest, headerVisibility) -> {
          // If we are testing a modular apple_library, we expose it non-modular. This allows the
          // testing target to see both the public and private interfaces of the tested target
          // without triggering header errors related to modules. We hide the module definition by
          // using a filesystem overlay that overrides the module.modulemap with an empty file.
          if (isModularAppleLibrary(targetUnderTest)) {
            testingOverlayBuilder.add("-ivfsoverlay");
            Path vfsOverlay =
                getTestingModulemapVFSOverlayLocationFromSymlinkTreeRoot(
                    getPathToHeaderSymlinkTree(targetUnderTest, HeaderVisibility.PUBLIC));
            testingOverlayBuilder.add("$REPO_ROOT/" + vfsOverlay);
          }
        });
    return testingOverlayBuilder.build();
  }

  private boolean isFrameworkProductType(ProductType productType) {
    return productType == ProductTypes.FRAMEWORK || productType == ProductTypes.STATIC_FRAMEWORK;
  }

  private void addPBXTargetDependency(PBXNativeTarget pbxTarget, BuildTarget dependency) {
    // Xcode appears to only support target dependencies if both targets are within the same
    // project.
    // If the desired target dependency is not in the same project, then just ignore it.
    if (!isBuiltByCurrentProject(dependency)) {
      return;
    }

    targetNodeToProjectTarget
        .getUnchecked(targetGraph.get(dependency))
        .ifPresent(
            dependencyPBXTarget -> {
              if (project.getGlobalID() == null) {
                project.setGlobalID(project.generateGid(gidGenerator));
              }
              if (dependencyPBXTarget.getGlobalID() == null) {
                dependencyPBXTarget.setGlobalID(dependencyPBXTarget.generateGid(gidGenerator));
              }
              PBXContainerItemProxy dependencyProxy =
                  new PBXContainerItemProxy(
                      project,
                      dependencyPBXTarget.getGlobalID(),
                      PBXContainerItemProxy.ProxyType.TARGET_REFERENCE);

              pbxTarget.getDependencies().add(new PBXTargetDependency(dependencyProxy));
            });
  }

  private ImmutableMap<String, String> getFrameworkAndLibrarySearchPathConfigs(
      TargetNode<?> node, boolean includeFrameworks) {
    HashSet<String> frameworkSearchPaths = new HashSet<>();
    frameworkSearchPaths.add("$BUILT_PRODUCTS_DIR");
    HashSet<String> librarySearchPaths = new HashSet<>();
    librarySearchPaths.add("$BUILT_PRODUCTS_DIR");
    HashSet<String> iOSLdRunpathSearchPaths = new HashSet<>();
    HashSet<String> macOSLdRunpathSearchPaths = new HashSet<>();

    FluentIterable<TargetNode<?>> depTargetNodes = collectRecursiveLibraryDepTargets(node);
    ImmutableSet<PBXFileReference> swiftDeps =
        filterRecursiveLibraryDependenciesWithSwiftSources(depTargetNodes);

    Stream.concat(
            // Collect all the nodes that contribute to linking
            // ... Which the node includes itself
            Stream.of(node),
            // ... And recursive dependencies that gets linked in
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                    xcodeDescriptions,
                    targetGraph,
                    Optional.of(dependenciesCache),
                    AppleBuildRules.RecursiveDependenciesMode.LINKING,
                    node,
                    ImmutableSet.of(
                        AppleLibraryDescription.class,
                        CxxLibraryDescription.class,
                        PrebuiltAppleFrameworkDescription.class))
                .stream())
        // Keep only the ones that may have frameworks and libraries fields.
        .flatMap(
            input ->
                RichStream.from(TargetNodes.castArg(input, HasSystemFrameworkAndLibraries.class)))
        // Then for each of them
        .forEach(
            castedNode -> {
              // ... Add the framework path strings.
              castedNode
                  .getConstructorArg()
                  .getFrameworks()
                  .stream()
                  .filter(x -> !x.isSDKROOTFrameworkPath())
                  .map(
                      frameworkPath ->
                          FrameworkPath.getUnexpandedSearchPath(
                                  this::resolveSourcePath,
                                  pathRelativizer::outputDirToRootRelative,
                                  frameworkPath)
                              .toString())
                  .forEach(frameworkSearchPaths::add);

              // ... And do the same for libraries.
              castedNode
                  .getConstructorArg()
                  .getLibraries()
                  .stream()
                  .map(
                      libraryPath ->
                          FrameworkPath.getUnexpandedSearchPath(
                                  this::resolveSourcePath,
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
                        if (prebuilt.getConstructorArg().getPreferredLinkage()
                            != NativeLinkable.Linkage.STATIC) {
                          // Frameworks that are copied into the binary.
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
      librarySearchPaths.add("$DT_TOOLCHAIN_DIR/usr/lib/swift/$PLATFORM_NAME");
    }

    if (swiftDeps.size() > 0) {
      iOSLdRunpathSearchPaths.add("@executable_path/Frameworks");
      iOSLdRunpathSearchPaths.add("@loader_path/Frameworks");
      macOSLdRunpathSearchPaths.add("@executable_path/../Frameworks");
      macOSLdRunpathSearchPaths.add("@loader_path/../Frameworks");
    }

    ImmutableMap.Builder<String, String> results =
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

  private ImmutableSortedMap<Path, SourcePath> getPublicCxxHeaders(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode) {
    CxxLibraryDescription.CommonArg arg = targetNode.getConstructorArg();
    if (arg instanceof AppleNativeTargetDescriptionArg) {
      Path headerPathPrefix =
          AppleDescriptions.getHeaderPathPrefix(
              (AppleNativeTargetDescriptionArg) arg, targetNode.getBuildTarget());

      ImmutableSortedMap.Builder<String, SourcePath> exportedHeadersBuilder =
          ImmutableSortedMap.naturalOrder();
      exportedHeadersBuilder.putAll(
          AppleDescriptions.convertHeadersToPublicCxxHeaders(
              targetNode.getBuildTarget(),
              this::resolveSourcePath,
              headerPathPrefix,
              arg.getExportedHeaders()));

      for (Pair<Pattern, SourceSortedSet> patternMatchedHeader :
          arg.getExportedPlatformHeaders().getPatternsAndValues()) {
        exportedHeadersBuilder.putAll(
            AppleDescriptions.convertHeadersToPublicCxxHeaders(
                targetNode.getBuildTarget(),
                this::resolveSourcePath,
                headerPathPrefix,
                patternMatchedHeader.getSecond()));
      }

      ImmutableSortedMap<String, SourcePath> fullExportedHeaders = exportedHeadersBuilder.build();
      return convertMapKeysToPaths(fullExportedHeaders);
    } else {
      ActionGraphBuilder graphBuilder = actionGraphBuilderForNode.apply(targetNode);
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
      ImmutableSortedMap.Builder<Path, SourcePath> allHeadersBuilder =
          ImmutableSortedMap.naturalOrder();
      return allHeadersBuilder
          .putAll(
              CxxDescriptionEnhancer.parseExportedHeaders(
                  targetNode.getBuildTarget(),
                  graphBuilder,
                  ruleFinder,
                  pathResolver,
                  Optional.empty(),
                  arg))
          .putAll(
              ProjectGenerator.parseAllPlatformHeaders(
                  targetNode.getBuildTarget(),
                  pathResolver,
                  arg.getExportedPlatformHeaders(),
                  true,
                  arg))
          .build();
    }
  }

  private ImmutableSortedMap<Path, SourcePath> getPrivateCxxHeaders(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode) {
    CxxLibraryDescription.CommonArg arg = targetNode.getConstructorArg();
    if (arg instanceof AppleNativeTargetDescriptionArg) {
      Path headerPathPrefix =
          AppleDescriptions.getHeaderPathPrefix(
              (AppleNativeTargetDescriptionArg) arg, targetNode.getBuildTarget());

      ImmutableSortedMap.Builder<String, SourcePath> fullHeadersBuilder =
          ImmutableSortedMap.naturalOrder();
      fullHeadersBuilder.putAll(
          AppleDescriptions.convertHeadersToPrivateCxxHeaders(
              targetNode.getBuildTarget(),
              this::resolveSourcePath,
              headerPathPrefix,
              arg.getHeaders(),
              arg.getExportedHeaders()));

      for (Pair<Pattern, SourceSortedSet> patternMatchedHeader :
          arg.getExportedPlatformHeaders().getPatternsAndValues()) {
        fullHeadersBuilder.putAll(
            AppleDescriptions.convertHeadersToPrivateCxxHeaders(
                targetNode.getBuildTarget(),
                this::resolveSourcePath,
                headerPathPrefix,
                SourceSortedSet.ofNamedSources(ImmutableSortedMap.of()),
                patternMatchedHeader.getSecond()));
      }

      for (Pair<Pattern, SourceSortedSet> patternMatchedHeader :
          arg.getPlatformHeaders().getPatternsAndValues()) {
        fullHeadersBuilder.putAll(
            AppleDescriptions.convertHeadersToPrivateCxxHeaders(
                targetNode.getBuildTarget(),
                this::resolveSourcePath,
                headerPathPrefix,
                patternMatchedHeader.getSecond(),
                SourceSortedSet.ofNamedSources(ImmutableSortedMap.of())));
      }

      ImmutableSortedMap<String, SourcePath> fullHeaders = fullHeadersBuilder.build();
      return convertMapKeysToPaths(fullHeaders);
    } else {
      ActionGraphBuilder graphBuilder = actionGraphBuilderForNode.apply(targetNode);
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
      ImmutableSortedMap.Builder<Path, SourcePath> allHeadersBuilder =
          ImmutableSortedMap.naturalOrder();
      return allHeadersBuilder
          .putAll(
              CxxDescriptionEnhancer.parseHeaders(
                  targetNode.getBuildTarget(),
                  graphBuilder,
                  ruleFinder,
                  pathResolver,
                  Optional.empty(),
                  arg))
          .putAll(
              ProjectGenerator.parseAllPlatformHeaders(
                  targetNode.getBuildTarget(), pathResolver, arg.getPlatformHeaders(), false, arg))
          .build();
    }
  }

  private ImmutableSortedMap<Path, SourcePath> convertMapKeysToPaths(
      ImmutableSortedMap<String, SourcePath> input) {
    ImmutableSortedMap.Builder<Path, SourcePath> output = ImmutableSortedMap.naturalOrder();
    for (Map.Entry<String, SourcePath> entry : input.entrySet()) {
      output.put(Paths.get(entry.getKey()), entry.getValue());
    }
    return output.build();
  }

  private Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>>
      getXcodeBuildConfigurationsForTargetNode(
          TargetNode<?> targetNode, ImmutableMap<String, String> appendedConfig) {
    Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs = Optional.empty();
    Optional<TargetNode<AppleNativeTargetDescriptionArg>> appleTargetNode =
        TargetNodes.castArg(targetNode, AppleNativeTargetDescriptionArg.class);
    Optional<TargetNode<HalideLibraryDescriptionArg>> halideTargetNode =
        TargetNodes.castArg(targetNode, HalideLibraryDescriptionArg.class);
    if (appleTargetNode.isPresent()) {
      configs = Optional.of(appleTargetNode.get().getConstructorArg().getConfigs());
    } else if (halideTargetNode.isPresent()) {
      configs = Optional.of(halideTargetNode.get().getConstructorArg().getConfigs());
    }
    if (!configs.isPresent()
        || (configs.isPresent() && configs.get().isEmpty())
        || targetNode.getDescription() instanceof CxxLibraryDescription) {
      ImmutableMap<String, ImmutableMap<String, String>> defaultConfig =
          CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
              defaultCxxPlatform, appendedConfig);
      configs = Optional.of(ImmutableSortedMap.copyOf(defaultConfig));
    }
    return configs;
  }

  private void addEntitlementsPlistIntoTarget(
      TargetNode<? extends HasAppleBundleFields> targetNode, PBXGroup targetGroup) {
    ImmutableMap<String, String> infoPlistSubstitutions =
        targetNode.getConstructorArg().getInfoPlistSubstitutions();

    if (infoPlistSubstitutions.containsKey(AppleBundle.CODE_SIGN_ENTITLEMENTS)) {
      String entitlementsPlistPath =
          InfoPlistSubstitution.replaceVariablesInString(
              "$(" + AppleBundle.CODE_SIGN_ENTITLEMENTS + ")",
              AppleBundle.withDefaults(
                  infoPlistSubstitutions,
                  ImmutableMap.of(
                      "SOURCE_ROOT", ".",
                      "SRCROOT", ".")));

      targetGroup.getOrCreateFileReferenceBySourceTreePath(
          new SourceTreePath(
              PBXReference.SourceTree.SOURCE_ROOT,
              Paths.get(entitlementsPlistPath),
              Optional.empty()));
    }
  }

  private void addCoreDataModelsIntoTarget(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode, PBXGroup targetGroup)
      throws IOException {
    addCoreDataModelBuildPhase(
        targetGroup,
        AppleBuildRules.collectTransitiveBuildRules(
            xcodeDescriptions,
            targetGraph,
            Optional.of(dependenciesCache),
            AppleBuildRules.CORE_DATA_MODEL_DESCRIPTION_CLASSES,
            ImmutableList.of(targetNode),
            RecursiveDependenciesMode.COPYING));
  }

  private void addSceneKitAssetsIntoTarget(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode, PBXGroup targetGroup) {
    ImmutableSet<AppleWrapperResourceArg> allSceneKitAssets =
        AppleBuildRules.collectTransitiveBuildRules(
            xcodeDescriptions,
            targetGraph,
            Optional.of(dependenciesCache),
            AppleBuildRules.SCENEKIT_ASSETS_DESCRIPTION_CLASSES,
            ImmutableList.of(targetNode),
            RecursiveDependenciesMode.COPYING);

    for (AppleWrapperResourceArg sceneKitAssets : allSceneKitAssets) {
      PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

      resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
          new SourceTreePath(
              PBXReference.SourceTree.SOURCE_ROOT,
              pathRelativizer.outputDirToRootRelative(sceneKitAssets.getPath()),
              Optional.empty()));
    }
  }

  private Path getConfigurationXcconfigPath(BuildTarget buildTarget, String input) {
    return BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s-" + input + ".xcconfig");
  }

  @VisibleForTesting
  static Iterable<SourcePath> getHeaderSourcePaths(SourceSortedSet headers) {
    if (headers.getUnnamedSources().isPresent()) {
      return headers.getUnnamedSources().get();
    } else {
      return headers.getNamedSources().get().values();
    }
  }

  /**
   * Create target level configuration entries.
   *
   * @param target Xcode target for which the configurations will be set.
   * @param targetGroup Xcode group in which the configuration file references will be placed.
   * @param configurations Configurations as extracted from the BUCK file.
   * @param overrideBuildSettings Build settings that will override ones defined elsewhere.
   * @param defaultBuildSettings Target-inline level build settings that will be set if not already
   *     defined.
   * @param appendBuildSettings Target-inline level build settings that will incorporate the
   *     existing value or values at a higher level.
   */
  private void setTargetBuildConfigurations(
      BuildTarget buildTarget,
      PBXTarget target,
      PBXGroup targetGroup,
      ImmutableMap<String, ImmutableMap<String, String>> configurations,
      ImmutableMap<String, String> overrideBuildSettings,
      ImmutableMap<String, String> defaultBuildSettings,
      ImmutableMap<String, String> appendBuildSettings)
      throws IOException {
    if (options.shouldGenerateHeaderSymlinkTreesOnly()) {
      return;
    }

    for (Map.Entry<String, ImmutableMap<String, String>> configurationEntry :
        configurations.entrySet()) {
      targetConfigNamesBuilder.add(configurationEntry.getKey());

      ImmutableMap<String, String> targetLevelInlineSettings = configurationEntry.getValue();

      XCBuildConfiguration outputConfiguration =
          target
              .getBuildConfigurationList()
              .getBuildConfigurationsByName()
              .getUnchecked(configurationEntry.getKey());

      HashMap<String, String> combinedOverrideConfigs = new HashMap<>(overrideBuildSettings);
      for (Map.Entry<String, String> entry : defaultBuildSettings.entrySet()) {
        String existingSetting = targetLevelInlineSettings.get(entry.getKey());
        if (existingSetting == null) {
          combinedOverrideConfigs.put(entry.getKey(), entry.getValue());
        }
      }

      for (Map.Entry<String, String> entry : appendBuildSettings.entrySet()) {
        String existingSetting = targetLevelInlineSettings.get(entry.getKey());
        String settingPrefix = existingSetting != null ? existingSetting : "$(inherited)";
        combinedOverrideConfigs.put(entry.getKey(), settingPrefix + " " + entry.getValue());
      }

      ImmutableSortedMap<String, String> mergedSettings =
          MoreMaps.mergeSorted(targetLevelInlineSettings, combinedOverrideConfigs);
      Path xcconfigPath = getConfigurationXcconfigPath(buildTarget, configurationEntry.getKey());
      projectFilesystem.mkdirs(Objects.requireNonNull(xcconfigPath).getParent());

      StringBuilder stringBuilder = new StringBuilder();
      for (Map.Entry<String, String> entry : mergedSettings.entrySet()) {
        stringBuilder.append(entry.getKey());
        stringBuilder.append(" = ");
        stringBuilder.append(entry.getValue());
        stringBuilder.append('\n');
      }
      String xcconfigContents = stringBuilder.toString();

      if (MoreProjectFilesystems.fileContentsDiffer(
          new ByteArrayInputStream(xcconfigContents.getBytes(Charsets.UTF_8)),
          xcconfigPath,
          projectFilesystem)) {
        if (options.shouldGenerateReadOnlyFiles()) {
          projectFilesystem.writeContentsToPath(
              xcconfigContents, xcconfigPath, READ_ONLY_FILE_ATTRIBUTE);
        } else {
          projectFilesystem.writeContentsToPath(xcconfigContents, xcconfigPath);
        }
      }

      PBXFileReference fileReference = getConfigurationFileReference(targetGroup, xcconfigPath);
      outputConfiguration.setBaseConfigurationReference(fileReference);

      xcconfigPathsBuilder.add(
          projectFilesystem.getPathForRelativePath(xcconfigPath).toAbsolutePath());
    }
  }

  private PBXFileReference getConfigurationFileReference(PBXGroup targetGroup, Path xcconfigPath) {
    return targetGroup
        .getOrCreateChildGroupByName("Configurations")
        .getOrCreateChildGroupByName("Buck (Do Not Modify)")
        .getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativizer.outputDirToRootRelative(xcconfigPath),
                Optional.empty()));
  }

  private void collectProjectTargetWatchDependencies(
      String targetFlavorPostfix, PBXNativeTarget target, Iterable<TargetNode<?>> targetNodes) {
    for (TargetNode<?> targetNode : targetNodes) {
      String targetNodeFlavorPostfix = targetNode.getBuildTarget().getFlavorPostfix();
      if (targetNodeFlavorPostfix.startsWith("#watch")
          && !targetNodeFlavorPostfix.equals(targetFlavorPostfix)
          && targetNode.getDescription() instanceof AppleBundleDescription) {
        addPBXTargetDependency(target, targetNode.getBuildTarget());
      }
    }
  }

  private void collectBuildScriptDependencies(
      Iterable<TargetNode<?>> targetNodes,
      ImmutableList.Builder<TargetNode<?>> preRules,
      ImmutableList.Builder<TargetNode<?>> postRules) {
    for (TargetNode<?> targetNode : targetNodes) {
      if (targetNode.getDescription() instanceof JsBundleOutputsDescription) {
        postRules.add(targetNode);
        requiredBuildTargetsBuilder.add(targetNode.getBuildTarget());
      } else if (targetNode.getDescription() instanceof XcodePostbuildScriptDescription) {
        postRules.add(targetNode);
      } else if (targetNode.getDescription() instanceof XcodePrebuildScriptDescription) {
        preRules.add(targetNode);
      }
    }
  }

  /** Adds the set of headers defined by headerVisibility to the merged header maps. */
  private void addToMergedHeaderMap(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode,
      HeaderMap.Builder headerMapBuilder) {
    CxxLibraryDescription.CommonArg arg = targetNode.getConstructorArg();
    boolean shouldCreateHeadersSymlinks =
        arg.getXcodePublicHeadersSymlinks().orElse(cxxBuckConfig.getPublicHeadersSymlinksEnabled());
    Path headerSymlinkTreeRoot = getPathToHeaderSymlinkTree(targetNode, HeaderVisibility.PUBLIC);

    Path basePath;
    if (shouldCreateHeadersSymlinks) {
      basePath =
          projectFilesystem
              .getRootPath()
              .resolve(targetNode.getBuildTarget().getCellPath())
              .resolve(headerSymlinkTreeRoot);
    } else {
      basePath = projectFilesystem.getRootPath();
    }
    for (Map.Entry<Path, SourcePath> entry : getPublicCxxHeaders(targetNode).entrySet()) {
      Path path;
      if (shouldCreateHeadersSymlinks) {
        path = basePath.resolve(entry.getKey());
      } else {
        path = basePath.resolve(resolveSourcePath(entry.getValue()));
      }
      headerMapBuilder.add(entry.getKey().toString(), path);
    }

    ImmutableMap<Path, Path> swiftHeaderMapEntries =
        getSwiftPublicHeaderMapEntriesForTarget(targetNode);
    for (Map.Entry<Path, Path> entry : swiftHeaderMapEntries.entrySet()) {
      headerMapBuilder.add(entry.getKey().toString(), entry.getValue());
    }
  }

  /** Generates the merged header maps and write it to the public header symlink tree location. */
  private void createMergedHeaderMap() throws IOException {
    HeaderMap.Builder headerMapBuilder = new HeaderMap.Builder();

    Set<TargetNode<? extends CxxLibraryDescription.CommonArg>> processedNodes = new HashSet<>();

    for (TargetNode<?> targetNode : targetGraph.getAll(targetsInRequiredProjects)) {
      // Includes the public headers of the dependencies in the merged header map.
      getAppleNativeNode(targetGraph, targetNode)
          .ifPresent(
              argTargetNode ->
                  visitRecursiveHeaderSymlinkTrees(
                      argTargetNode,
                      (depNativeNode, headerVisibility) -> {
                        if (processedNodes.contains(depNativeNode)) {
                          return;
                        }
                        if (headerVisibility == HeaderVisibility.PUBLIC) {
                          addToMergedHeaderMap(depNativeNode, headerMapBuilder);
                          processedNodes.add(depNativeNode);
                        }
                      }));
    }

    // Writes the resulting header map.
    Path mergedHeaderMapRoot = getPathToMergedHeaderMap();
    Path headerMapLocation = getHeaderMapLocationFromSymlinkTreeRoot(mergedHeaderMapRoot);
    projectFilesystem.mkdirs(mergedHeaderMapRoot);
    projectFilesystem.writeBytesToPath(headerMapBuilder.build().getBytes(), headerMapLocation);
  }

  private void createHeaderSymlinkTree(
      Map<Path, SourcePath> contents,
      ImmutableMap<Path, Path> nonSourcePaths,
      Optional<String> moduleName,
      Path headerSymlinkTreeRoot,
      boolean shouldCreateHeadersSymlinks,
      boolean shouldCreateHeaderMap,
      boolean shouldGenerateUmbrellaHeaderIfMissing)
      throws IOException {
    if (!shouldCreateHeaderMap && !shouldCreateHeadersSymlinks) {
      return;
    }
    LOG.verbose(
        "Building header symlink tree at %s with contents %s", headerSymlinkTreeRoot, contents);
    ImmutableSortedMap.Builder<Path, Path> resolvedContentsBuilder =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<Path, SourcePath> entry : contents.entrySet()) {
      Path link = headerSymlinkTreeRoot.resolve(entry.getKey());
      Path existing = projectFilesystem.resolve(resolveSourcePath(entry.getValue()));
      resolvedContentsBuilder.put(link, existing);
    }
    for (Map.Entry<Path, Path> entry : nonSourcePaths.entrySet()) {
      Path link = headerSymlinkTreeRoot.resolve(entry.getKey());
      resolvedContentsBuilder.put(link, entry.getValue());
    }
    ImmutableSortedMap<Path, Path> resolvedContents = resolvedContentsBuilder.build();

    Path headerMapLocation = getHeaderMapLocationFromSymlinkTreeRoot(headerSymlinkTreeRoot);

    Path hashCodeFilePath = headerSymlinkTreeRoot.resolve(".contents-hash");
    Optional<String> currentHashCode = projectFilesystem.readFileIfItExists(hashCodeFilePath);
    String newHashCode =
        getHeaderSymlinkTreeHashCode(
                resolvedContents, moduleName, shouldCreateHeadersSymlinks, shouldCreateHeaderMap)
            .toString();
    if (Optional.of(newHashCode).equals(currentHashCode)) {
      LOG.debug(
          "Symlink tree at %s is up to date, not regenerating (key %s).",
          headerSymlinkTreeRoot, newHashCode);
    } else {
      LOG.debug(
          "Updating symlink tree at %s (old key %s, new key %s).",
          headerSymlinkTreeRoot, currentHashCode, newHashCode);
      projectFilesystem.deleteRecursivelyIfExists(headerSymlinkTreeRoot);
      projectFilesystem.mkdirs(headerSymlinkTreeRoot);
      if (shouldCreateHeadersSymlinks) {
        for (Map.Entry<Path, Path> entry : resolvedContents.entrySet()) {
          Path link = entry.getKey();
          Path existing = entry.getValue();
          projectFilesystem.createParentDirs(link);
          projectFilesystem.createSymLink(link, existing, /* force */ false);
        }
      }
      projectFilesystem.writeContentsToPath(newHashCode, hashCodeFilePath);

      if (shouldCreateHeaderMap) {
        HeaderMap.Builder headerMapBuilder = new HeaderMap.Builder();
        for (Map.Entry<Path, SourcePath> entry : contents.entrySet()) {
          if (shouldCreateHeadersSymlinks) {
            headerMapBuilder.add(
                entry.getKey().toString(),
                getHeaderMapRelativeSymlinkPathForEntry(entry, headerSymlinkTreeRoot));
          } else {
            headerMapBuilder.add(
                entry.getKey().toString(),
                projectFilesystem.resolve(resolveSourcePath(entry.getValue())));
          }
        }

        for (Map.Entry<Path, Path> entry : nonSourcePaths.entrySet()) {
          if (shouldCreateHeadersSymlinks) {
            headerMapBuilder.add(
                entry.getKey().toString(),
                getHeaderMapRelativeSymlinkPathForEntry(entry, headerSymlinkTreeRoot));
          } else {
            headerMapBuilder.add(entry.getKey().toString(), entry.getValue());
          }
        }

        projectFilesystem.writeBytesToPath(headerMapBuilder.build().getBytes(), headerMapLocation);
      }
      if (moduleName.isPresent() && resolvedContents.size() > 0) {
        if (shouldGenerateUmbrellaHeaderIfMissing) {
          writeUmbrellaHeaderIfNeeded(
              moduleName.get(), resolvedContents.keySet(), headerSymlinkTreeRoot);
        }
        boolean containsSwift = !nonSourcePaths.isEmpty();
        if (containsSwift) {
          projectFilesystem.writeContentsToPath(
              new ModuleMap(moduleName.get(), ModuleMap.SwiftMode.INCLUDE_SWIFT_HEADER).render(),
              headerSymlinkTreeRoot.resolve(moduleName.get()).resolve("module.modulemap"));
          projectFilesystem.writeContentsToPath(
              new ModuleMap(moduleName.get(), ModuleMap.SwiftMode.EXCLUDE_SWIFT_HEADER).render(),
              headerSymlinkTreeRoot.resolve(moduleName.get()).resolve("objc.modulemap"));

          Path absoluteModuleRoot =
              projectFilesystem
                  .getRootPath()
                  .resolve(headerSymlinkTreeRoot.resolve(moduleName.get()));
          VFSOverlay vfsOverlay =
              new VFSOverlay(
                  ImmutableSortedMap.of(
                      absoluteModuleRoot.resolve("module.modulemap"),
                      absoluteModuleRoot.resolve("objc.modulemap")));

          projectFilesystem.writeContentsToPath(
              vfsOverlay.render(),
              getObjcModulemapVFSOverlayLocationFromSymlinkTreeRoot(headerSymlinkTreeRoot));
        } else {
          projectFilesystem.writeContentsToPath(
              new ModuleMap(moduleName.get(), ModuleMap.SwiftMode.NO_SWIFT).render(),
              headerSymlinkTreeRoot.resolve(moduleName.get()).resolve("module.modulemap"));
        }
        Path absoluteModuleRoot =
            projectFilesystem
                .getRootPath()
                .resolve(headerSymlinkTreeRoot.resolve(moduleName.get()));
        VFSOverlay vfsOverlay =
            new VFSOverlay(
                ImmutableSortedMap.of(
                    absoluteModuleRoot.resolve("module.modulemap"),
                    absoluteModuleRoot.resolve("testing.modulemap")));

        projectFilesystem.writeContentsToPath(
            vfsOverlay.render(),
            getTestingModulemapVFSOverlayLocationFromSymlinkTreeRoot(headerSymlinkTreeRoot));
        projectFilesystem.writeContentsToPath(
            "", // empty modulemap to allow non-modular imports for testing
            headerSymlinkTreeRoot.resolve(moduleName.get()).resolve("testing.modulemap"));
      }
    }
    headerSymlinkTrees.add(headerSymlinkTreeRoot);
  }

  private void writeUmbrellaHeaderIfNeeded(
      String moduleName, ImmutableSortedSet<Path> headerPaths, Path headerSymlinkTreeRoot)
      throws IOException {
    ImmutableList<String> headerPathStrings =
        headerPaths
            .stream()
            .map(Path::getFileName)
            .map(Path::toString)
            .collect(ImmutableList.toImmutableList());
    if (!headerPathStrings.contains(moduleName + ".h")) {
      Path umbrellaPath = headerSymlinkTreeRoot.resolve(Paths.get(moduleName, moduleName + ".h"));
      Preconditions.checkState(!projectFilesystem.exists(umbrellaPath));
      projectFilesystem.writeContentsToPath(
          new UmbrellaHeader(moduleName, headerPathStrings).render(), umbrellaPath);
    }
  }

  private Path getHeaderMapRelativeSymlinkPathForEntry(
      Map.Entry<Path, ?> entry, Path headerSymlinkTreeRoot) {
    return projectCell
        .getFilesystem()
        .resolve(projectCell.getFilesystem().getBuckPaths().getConfiguredBuckOut())
        .normalize()
        .relativize(
            projectCell
                .getFilesystem()
                .resolve(headerSymlinkTreeRoot)
                .resolve(entry.getKey())
                .normalize());
  }

  private HashCode getHeaderSymlinkTreeHashCode(
      ImmutableSortedMap<Path, Path> contents,
      Optional<String> moduleName,
      boolean shouldCreateHeadersSymlinks,
      boolean shouldCreateHeaderMap) {
    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putBytes(ruleKeyConfiguration.getCoreKey().getBytes(Charsets.UTF_8));
    String symlinkState = shouldCreateHeadersSymlinks ? "symlinks-enabled" : "symlinks-disabled";
    byte[] symlinkStateValue = symlinkState.getBytes(Charsets.UTF_8);
    hasher.putInt(symlinkStateValue.length);
    hasher.putBytes(symlinkStateValue);
    String hmapState = shouldCreateHeaderMap ? "hmap-enabled" : "hmap-disabled";
    byte[] hmapStateValue = hmapState.getBytes(Charsets.UTF_8);
    hasher.putInt(hmapStateValue.length);
    hasher.putBytes(hmapStateValue);
    if (moduleName.isPresent()) {
      byte[] moduleNameValue = moduleName.get().getBytes(Charsets.UTF_8);
      hasher.putInt(moduleNameValue.length);
      hasher.putBytes(moduleNameValue);
    }
    hasher.putInt(0);
    for (Map.Entry<Path, Path> entry : contents.entrySet()) {
      byte[] key = entry.getKey().toString().getBytes(Charsets.UTF_8);
      byte[] value = entry.getValue().toString().getBytes(Charsets.UTF_8);
      hasher.putInt(key.length);
      hasher.putBytes(key);
      hasher.putInt(value.length);
      hasher.putBytes(value);
    }
    return hasher.hash();
  }

  private void addCoreDataModelBuildPhase(
      PBXGroup targetGroup, Iterable<AppleWrapperResourceArg> dataModels) throws IOException {
    // TODO(coneko): actually add a build phase

    for (AppleWrapperResourceArg dataModel : dataModels) {
      // Core data models go in the resources group also.
      PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

      if (CoreDataModelDescription.isVersionedDataModel(dataModel)) {
        // It's safe to do I/O here to figure out the current version because we're returning all
        // the versions and the file pointing to the current version from
        // getInputsToCompareToOutput(), so the rule will be correctly detected as stale if any of
        // them change.
        String currentVersionFileName = ".xccurrentversion";
        String currentVersionKey = "_XCCurrentVersionName";

        XCVersionGroup versionGroup =
            resourcesGroup.getOrCreateChildVersionGroupsBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SOURCE_ROOT,
                    pathRelativizer.outputDirToRootRelative(dataModel.getPath()),
                    Optional.empty()));

        projectFilesystem.walkRelativeFileTree(
            dataModel.getPath(),
            new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(dataModel.getPath())) {
                  return FileVisitResult.CONTINUE;
                }
                versionGroup.getOrCreateFileReferenceBySourceTreePath(
                    new SourceTreePath(
                        PBXReference.SourceTree.SOURCE_ROOT,
                        pathRelativizer.outputDirToRootRelative(dir),
                        Optional.empty()));
                return FileVisitResult.SKIP_SUBTREE;
              }
            });

        Path currentVersionPath = dataModel.getPath().resolve(currentVersionFileName);
        try (InputStream in = projectFilesystem.newFileInputStream(currentVersionPath)) {
          NSObject rootObject;
          try {
            rootObject = PropertyListParser.parse(in);
          } catch (IOException e) {
            throw e;
          } catch (Exception e) {
            rootObject = null;
          }
          if (!(rootObject instanceof NSDictionary)) {
            throw new HumanReadableException("Malformed %s file.", currentVersionFileName);
          }
          NSDictionary rootDictionary = (NSDictionary) rootObject;
          NSObject currentVersionName = rootDictionary.objectForKey(currentVersionKey);
          if (!(currentVersionName instanceof NSString)) {
            throw new HumanReadableException("Malformed %s file.", currentVersionFileName);
          }
          PBXFileReference ref =
              versionGroup.getOrCreateFileReferenceBySourceTreePath(
                  new SourceTreePath(
                      PBXReference.SourceTree.SOURCE_ROOT,
                      pathRelativizer.outputDirToRootRelative(
                          dataModel.getPath().resolve(currentVersionName.toString())),
                      Optional.empty()));
          versionGroup.setCurrentVersion(ref);
        } catch (NoSuchFileException e) {
          if (versionGroup.getChildren().size() == 1) {
            versionGroup.setCurrentVersion(Iterables.get(versionGroup.getChildren(), 0));
          }
        }
      } else {
        resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativizer.outputDirToRootRelative(dataModel.getPath()),
                Optional.empty()));
      }
    }
  }

  private Optional<CopyFilePhaseDestinationSpec> getDestinationSpec(TargetNode<?> targetNode) {
    if (targetNode.getDescription() instanceof AppleBundleDescription) {
      AppleBundleDescriptionArg arg = (AppleBundleDescriptionArg) targetNode.getConstructorArg();
      AppleBundleExtension extension =
          arg.getExtension().isLeft() ? arg.getExtension().getLeft() : AppleBundleExtension.BUNDLE;
      switch (extension) {
        case FRAMEWORK:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS));
        case APPEX:
        case PLUGIN:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.PLUGINS));
        case PREFPANE:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.RESOURCES));
        case APP:
          if (isWatchApplicationNode(targetNode)) {
            return Optional.of(
                CopyFilePhaseDestinationSpec.builder()
                    .setDestination(PBXCopyFilesBuildPhase.Destination.PRODUCTS)
                    .setPath("$(CONTENTS_FOLDER_PATH)/Watch")
                    .build());
          } else {
            return Optional.of(
                CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.EXECUTABLES));
          }
        case QLGENERATOR:
          return Optional.of(
              CopyFilePhaseDestinationSpec.builder()
                  .setDestination(PBXCopyFilesBuildPhase.Destination.QLGENERATOR)
                  .setPath("$(CONTENTS_FOLDER_PATH)/Library/QuickLook")
                  .build());
        case BUNDLE:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.PLUGINS));
        case XPC:
          return Optional.of(
              CopyFilePhaseDestinationSpec.builder()
                  .setDestination(PBXCopyFilesBuildPhase.Destination.XPC)
                  .setPath("$(CONTENTS_FOLDER_PATH)/XPCServices")
                  .build());
          // $CASES-OMITTED$
        default:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.PRODUCTS));
      }
    } else if (targetNode.getDescription() instanceof AppleLibraryDescription
        || targetNode.getDescription() instanceof CxxLibraryDescription) {
      if (targetNode.getBuildTarget().getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR)) {
        return Optional.of(
            CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS));
      } else {
        return Optional.empty();
      }
    } else if (targetNode.getDescription() instanceof AppleBinaryDescription) {
      return Optional.of(
          CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.EXECUTABLES));
    } else if (targetNode.getDescription() instanceof HalideLibraryDescription) {
      return Optional.empty();
    } else if (targetNode.getDescription() instanceof CoreDataModelDescription
        || targetNode.getDescription() instanceof SceneKitAssetsDescription) {
      return Optional.of(
          CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.RESOURCES));
    } else if (targetNode.getDescription() instanceof PrebuiltAppleFrameworkDescription) {
      return Optional.of(
          CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS));
    } else {
      String message =
          "Unexpected type: "
              + targetNode.getDescription().getClass()
              + " for target "
              + targetNode.getBuildTarget()
              + "\n"
              + "It means that it's been added as a dependency of another target but it's not a supported type of dependency.";
      throw new RuntimeException(message);
    }
  }

  /**
   * Convert a list of rules that should be somehow included into the bundle, into build phases
   * which copies them into the bundle. The parameters of these copy phases are divined by
   * scrutinizing the type of node we want to include.
   */
  private ImmutableList<PBXBuildPhase> getCopyFilesBuildPhases(
      Iterable<TargetNode<?>> copiedNodes) {

    // Bucket build rules into bins by their destinations
    ImmutableSetMultimap.Builder<CopyFilePhaseDestinationSpec, TargetNode<?>>
        ruleByDestinationSpecBuilder = ImmutableSetMultimap.builder();
    for (TargetNode<?> copiedNode : copiedNodes) {
      getDestinationSpec(copiedNode)
          .ifPresent(
              copyFilePhaseDestinationSpec ->
                  ruleByDestinationSpecBuilder.put(copyFilePhaseDestinationSpec, copiedNode));
    }

    ImmutableList.Builder<PBXBuildPhase> phases = ImmutableList.builder();

    ImmutableSetMultimap<CopyFilePhaseDestinationSpec, TargetNode<?>> ruleByDestinationSpec =
        ruleByDestinationSpecBuilder.build();

    // Emit a copy files phase for each destination.
    for (CopyFilePhaseDestinationSpec destinationSpec : ruleByDestinationSpec.keySet()) {
      Iterable<TargetNode<?>> targetNodes = ruleByDestinationSpec.get(destinationSpec);
      phases.add(getSingleCopyFilesBuildPhase(destinationSpec, targetNodes));
    }

    return phases.build();
  }

  private PBXCopyFilesBuildPhase getSingleCopyFilesBuildPhase(
      CopyFilePhaseDestinationSpec destinationSpec, Iterable<TargetNode<?>> targetNodes) {
    PBXCopyFilesBuildPhase copyFilesBuildPhase = new PBXCopyFilesBuildPhase(destinationSpec);
    HashSet<UnflavoredBuildTarget> frameworkTargets = new HashSet<UnflavoredBuildTarget>();

    for (TargetNode<?> targetNode : targetNodes) {
      /*
      Shared libraries with no sources will not produce an output file, so do not add
      them to the copy phase since the Xcode build will fail for a missing file.
       */
      boolean shouldCopy = shouldCopyOutputFile(targetNode);

      PBXFileReference fileReference = getLibraryFileReference(targetNode);
      PBXBuildFile buildFile = new PBXBuildFile(fileReference);
      if (fileReference.getExplicitFileType().equals(Optional.of("wrapper.framework"))) {
        UnflavoredBuildTarget buildTarget = targetNode.getBuildTarget().getUnflavoredBuildTarget();
        if (frameworkTargets.contains(buildTarget)) {
          continue;
        }
        frameworkTargets.add(buildTarget);

        NSDictionary settings = new NSDictionary();
        settings.put("ATTRIBUTES", new String[] {"CodeSignOnCopy", "RemoveHeadersOnCopy"});
        buildFile.setSettings(Optional.of(settings));
      }

      if (shouldCopy) {
        copyFilesBuildPhase.getFiles().add(buildFile);
      }
    }
    return copyFilesBuildPhase;
  }

  /** Create the project bundle structure and write {@code project.pbxproj}. */
  private void writeProjectFile(PBXProject project) throws IOException {
    XcodeprojSerializer serializer = new XcodeprojSerializer(gidGenerator, project);
    NSDictionary rootObject = serializer.toPlist();
    Path xcodeprojDir = outputDirectory.resolve(projectName + ".xcodeproj");
    projectFilesystem.mkdirs(xcodeprojDir);
    Path serializedProject = xcodeprojDir.resolve("project.pbxproj");
    String contentsToWrite = rootObject.toXMLPropertyList();
    // Before we write any files, check if the file contents have changed.
    if (MoreProjectFilesystems.fileContentsDiffer(
        new ByteArrayInputStream(contentsToWrite.getBytes(Charsets.UTF_8)),
        serializedProject,
        projectFilesystem)) {
      LOG.debug("Regenerating project at %s", serializedProject);
      if (options.shouldGenerateReadOnlyFiles()) {
        projectFilesystem.writeContentsToPath(
            contentsToWrite, serializedProject, READ_ONLY_FILE_ATTRIBUTE);
      } else {
        projectFilesystem.writeContentsToPath(contentsToWrite, serializedProject);
      }
    } else {
      LOG.debug("Not regenerating project at %s (contents have not changed)", serializedProject);
    }
  }

  private String getModuleName(TargetNode<?> buildTargetNode) {
    Optional<String> swiftName =
        TargetNodes.castArg(buildTargetNode, SwiftLibraryDescriptionArg.class)
            .flatMap(node -> node.getConstructorArg().getModuleName());
    if (swiftName.isPresent()) {
      return swiftName.get();
    }

    return TargetNodes.castArg(buildTargetNode, CommonArg.class)
        .flatMap(node -> node.getConstructorArg().getModuleName())
        .orElse(buildTargetNode.getBuildTarget().getShortName());
  }

  private String getProductName(TargetNode<?> buildTargetNode) {
    return TargetNodes.castArg(buildTargetNode, AppleBundleDescriptionArg.class)
        .flatMap(node -> node.getConstructorArg().getProductName())
        .orElse(getProductNameForBuildTargetNode(buildTargetNode));
  }

  private String getProductNameForBuildTargetNode(TargetNode<?> targetNode) {
    Optional<TargetNode<CxxLibraryDescription.CommonArg>> library =
        getLibraryNode(targetGraph, targetNode);
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

  private static Path getDerivedSourcesDirectoryForBuildTarget(
      BuildTarget buildTarget, ProjectFilesystem fs) {
    String fullTargetName = buildTarget.getFullyQualifiedName();
    byte[] utf8Bytes = fullTargetName.getBytes(Charset.forName("UTF-8"));

    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putBytes(utf8Bytes);

    String targetSha1Hash = hasher.hash().toString();
    String targetFolderName = buildTarget.getShortName() + "-" + targetSha1Hash;

    Path xcodeDir = fs.getBuckPaths().getXcodeDir();
    Path derivedSourcesDir = xcodeDir.resolve("derived-sources").resolve(targetFolderName);

    return derivedSourcesDir;
  }

  private String getSwiftObjCGeneratedHeaderName(TargetNode<?> node) {
    return getModuleName(node) + "-Swift.h";
  }

  private Path getSwiftObjCGeneratedHeaderPath(TargetNode<?> node, ProjectFilesystem fs) {
    Path derivedSourcesDir = getDerivedSourcesDirectoryForBuildTarget(node.getBuildTarget(), fs);
    return derivedSourcesDir.resolve(getSwiftObjCGeneratedHeaderName(node));
  }

  private ImmutableMap<Path, Path> getSwiftPublicHeaderMapEntriesForTarget(
      TargetNode<? extends CxxLibraryDescription.CommonArg> node) {
    boolean hasSwiftVersionArg = getSwiftVersionForTargetNode(node).isPresent();
    if (!hasSwiftVersionArg) {
      return ImmutableMap.of();
    }

    Optional<TargetNode<AppleNativeTargetDescriptionArg>> maybeAppleNode =
        TargetNodes.castArg(node, AppleNativeTargetDescriptionArg.class);
    if (!maybeAppleNode.isPresent()) {
      return ImmutableMap.of();
    }

    TargetNode<? extends AppleNativeTargetDescriptionArg> appleNode = maybeAppleNode.get();
    if (!projGenerationStateCache.targetContainsSwiftSourceCode(appleNode)) {
      return ImmutableMap.of();
    }

    BuildTarget buildTarget = appleNode.getBuildTarget();
    Path headerPrefix =
        AppleDescriptions.getHeaderPathPrefix(appleNode.getConstructorArg(), buildTarget);
    Path relativePath = headerPrefix.resolve(getSwiftObjCGeneratedHeaderName(appleNode));

    ImmutableSortedMap.Builder<Path, Path> builder = ImmutableSortedMap.naturalOrder();
    builder.put(
        relativePath,
        getSwiftObjCGeneratedHeaderPath(appleNode, projectFilesystem).toAbsolutePath());

    return builder.build();
  }

  /** @param targetNode Must have a header symlink tree or an exception will be thrown. */
  private Path getHeaderSymlinkTreePath(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode,
      HeaderVisibility headerVisibility) {
    Path treeRoot = getAbsolutePathToHeaderSymlinkTree(targetNode, headerVisibility);
    if (options.shouldUseAbsoluteHeaderMapPaths()) {
      return treeRoot;
    } else {
      return projectFilesystem.resolve(outputDirectory).relativize(treeRoot);
    }
  }

  private Path getObjcModulemapVFSOverlayLocationFromSymlinkTreeRoot(Path headerSymlinkTreeRoot) {
    return headerSymlinkTreeRoot.resolve("objc-module-overlay.yaml");
  }

  private Path getTestingModulemapVFSOverlayLocationFromSymlinkTreeRoot(
      Path headerSymlinkTreeRoot) {
    return headerSymlinkTreeRoot.resolve("testing-overlay.yaml");
  }

  private Path getHeaderMapLocationFromSymlinkTreeRoot(Path headerSymlinkTreeRoot) {
    return headerSymlinkTreeRoot.resolve(".hmap");
  }

  private Path getHeaderSearchPathFromSymlinkTreeRoot(Path headerSymlinkTreeRoot) {
    if (!options.shouldUseHeaderMaps()) {
      return headerSymlinkTreeRoot;
    } else {
      return getHeaderMapLocationFromSymlinkTreeRoot(headerSymlinkTreeRoot);
    }
  }

  private Path getRelativePathToMergedHeaderMap() {
    Path treeRoot = getPathToMergedHeaderMap();
    Path cellRoot =
        MorePaths.relativize(projectFilesystem.getRootPath(), workspaceTarget.get().getCellPath());
    return pathRelativizer.outputDirToRootRelative(cellRoot.resolve(treeRoot));
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

  private static BuildTarget getBundleBinaryTarget(TargetNode<AppleBundleDescriptionArg> bundle) {
    return bundle
        .getConstructorArg()
        .getBinary()
        .orElseThrow(
            () ->
                new HumanReadableException(
                    "apple_bundle rules without binary attribute are not supported."));
  }

  private static Optional<TargetNode<CxxLibraryDescription.CommonArg>> getAppleNativeNode(
      TargetGraph targetGraph, TargetNode<?> targetNode) {
    return getAppleNativeNodeOfType(
        targetGraph, targetNode, APPLE_NATIVE_DESCRIPTION_CLASSES, APPLE_NATIVE_BUNDLE_EXTENSIONS);
  }

  private static Optional<TargetNode<CxxLibraryDescription.CommonArg>> getLibraryNode(
      TargetGraph targetGraph, TargetNode<?> targetNode) {
    return getAppleNativeNodeOfType(
        targetGraph,
        targetNode,
        ImmutableSet.of(AppleLibraryDescription.class, CxxLibraryDescription.class),
        ImmutableSet.of(AppleBundleExtension.FRAMEWORK));
  }

  private ImmutableSet<Path> collectRecursiveHeaderSearchPaths(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();

    if (shouldMergeHeaderMaps()) {
      builder.add(
          getHeaderSearchPathFromSymlinkTreeRoot(
              getHeaderSymlinkTreePath(targetNode, HeaderVisibility.PRIVATE)));
      if (options.shouldUseAbsoluteHeaderMapPaths()) {
        builder.add(
            getHeaderSearchPathFromSymlinkTreeRoot(getPathToMergedHeaderMap().toAbsolutePath()));
      } else {
        builder.add(getHeaderSearchPathFromSymlinkTreeRoot(getRelativePathToMergedHeaderMap()));
      }
      visitRecursivePrivateHeaderSymlinkTreesForTests(
          targetNode,
          (nativeNode, headerVisibility) -> {
            builder.add(
                getHeaderSearchPathFromSymlinkTreeRoot(
                    getHeaderSymlinkTreePath(nativeNode, headerVisibility)));
          });
    } else {
      for (Path headerSymlinkTreePath : collectRecursiveHeaderSymlinkTrees(targetNode)) {
        builder.add(getHeaderSearchPathFromSymlinkTreeRoot(headerSymlinkTreePath));
      }
    }

    for (Path halideHeaderPath : collectRecursiveHalideLibraryHeaderPaths(targetNode)) {
      builder.add(halideHeaderPath);
    }

    return builder.build();
  }

  private ImmutableSet<Path> collectRecursiveHeaderMapBases(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    if (shouldMergeHeaderMaps()) {
      return ImmutableSet.of();
    } else {
      visitRecursiveHeaderSymlinkTrees(
          targetNode,
          (nativeNode, headerVisibility) -> {
            if (options.shouldUseAbsoluteHeaderMapPaths()) {
              builder.add(
                  nativeNode
                      .getFilesystem()
                      .getBuckPaths()
                      .getConfiguredBuckOut()
                      .toAbsolutePath()
                      .normalize());
            } else {
              builder.add(
                  targetNode
                      .getFilesystem()
                      .resolve(outputDirectory)
                      .relativize(
                          nativeNode
                              .getFilesystem()
                              .resolve(
                                  nativeNode
                                      .getFilesystem()
                                      .getBuckPaths()
                                      .getConfiguredBuckOut())));
            }
          });
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private ImmutableSet<Path> collectRecursiveHalideLibraryHeaderPaths(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    for (TargetNode<?> input :
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            xcodeDescriptions,
            targetGraph,
            Optional.of(dependenciesCache),
            AppleBuildRules.RecursiveDependenciesMode.BUILDING,
            targetNode,
            Optional.of(ImmutableSet.of(HalideLibraryDescription.class)))) {
      TargetNode<HalideLibraryDescriptionArg> halideNode =
          (TargetNode<HalideLibraryDescriptionArg>) input;
      BuildTarget buildTarget = halideNode.getBuildTarget();
      builder.add(
          pathRelativizer.outputDirToRootRelative(
              HalideCompile.headerOutputPath(
                      buildTarget.withFlavors(
                          HalideLibraryDescription.HALIDE_COMPILE_FLAVOR,
                          defaultCxxPlatform.getFlavor()),
                      projectFilesystem,
                      halideNode.getConstructorArg().getFunctionName())
                  .getParent()));
    }
    return builder.build();
  }

  private void visitRecursiveHeaderSymlinkTrees(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode,
      BiConsumer<TargetNode<? extends CxxLibraryDescription.CommonArg>, HeaderVisibility> visitor) {
    // Visits public and private headers from current target.
    visitor.accept(targetNode, HeaderVisibility.PRIVATE);
    visitor.accept(targetNode, HeaderVisibility.PUBLIC);

    // Visits public headers from dependencies.
    for (TargetNode<?> input :
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            xcodeDescriptions,
            targetGraph,
            Optional.of(dependenciesCache),
            AppleBuildRules.RecursiveDependenciesMode.BUILDING,
            targetNode,
            Optional.of(xcodeDescriptions.getXCodeDescriptions()))) {
      getAppleNativeNode(targetGraph, input)
          .ifPresent(argTargetNode -> visitor.accept(argTargetNode, HeaderVisibility.PUBLIC));
    }

    visitRecursivePrivateHeaderSymlinkTreesForTests(targetNode, visitor);
  }

  private void visitRecursivePrivateHeaderSymlinkTreesForTests(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode,
      BiConsumer<TargetNode<? extends CxxLibraryDescription.CommonArg>, HeaderVisibility> visitor) {
    // Visits headers of source under tests.
    ImmutableSet<TargetNode<?>> directDependencies =
        ImmutableSet.copyOf(targetGraph.getAll(targetNode.getBuildDeps()));
    for (TargetNode<?> dependency : directDependencies) {
      Optional<TargetNode<CxxLibraryDescription.CommonArg>> nativeNode =
          getAppleNativeNode(targetGraph, dependency);
      if (nativeNode.isPresent() && isSourceUnderTest(dependency, nativeNode.get(), targetNode)) {
        visitor.accept(nativeNode.get(), HeaderVisibility.PRIVATE);
      }
    }
  }

  private ImmutableSet<Path> collectRecursiveHeaderSymlinkTrees(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    visitRecursiveHeaderSymlinkTrees(
        targetNode,
        (nativeNode, headerVisibility) -> {
          builder.add(getHeaderSymlinkTreePath(nativeNode, headerVisibility));
        });
    return builder.build();
  }

  private boolean isSourceUnderTest(
      TargetNode<?> dependencyNode,
      TargetNode<CxxLibraryDescription.CommonArg> nativeNode,
      TargetNode<?> testNode) {
    boolean isSourceUnderTest =
        nativeNode.getConstructorArg().getTests().contains(testNode.getBuildTarget());

    if (dependencyNode != nativeNode && dependencyNode.getConstructorArg() instanceof HasTests) {
      ImmutableSortedSet<BuildTarget> tests =
          ((HasTests) dependencyNode.getConstructorArg()).getTests();
      if (tests.contains(testNode.getBuildTarget())) {
        isSourceUnderTest = true;
      }
    }

    return isSourceUnderTest;
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

              return ImmutableList.of();
            });
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
                ImmutableSet.of(AppleLibraryDescription.class, CxxLibraryDescription.class)))
        .append(targetNode)
        .transformAndConcat(
            input ->
                TargetNodes.castArg(input, CxxLibraryDescription.CommonArg.class)
                    .map(
                        input1 ->
                            ImmutableList.of(
                                input1.getConstructorArg().getExportedPlatformPreprocessorFlags()))
                    .orElse(ImmutableList.of()));
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
                    HalideLibraryDescription.class)))
        .append(targetNode)
        .transformAndConcat(
            input ->
                TargetNodes.castArg(input, CxxLibraryDescription.CommonArg.class)
                    .map(input1 -> input1.getConstructorArg().getExportedLinkerFlags())
                    .orElse(ImmutableList.of()))
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
        getObjcModulemapVFSOverlayLocationFromSymlinkTreeRoot(
            getPathToHeaderSymlinkTree(targetNode, HeaderVisibility.PUBLIC));
    targetSpecificSwiftFlags.add("-Xcc");
    targetSpecificSwiftFlags.add("-ivfsoverlay");
    targetSpecificSwiftFlags.add("-Xcc");
    targetSpecificSwiftFlags.add("$REPO_ROOT/" + vfsOverlay);
    return targetSpecificSwiftFlags.build();
  }

  private boolean isTargetNodeApplicationTestTarget(
      TargetNode<?> targetNode, Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode) {
    // This is an application test if it is not a UI test and has a bundle loader.
    Optional<TargetNode<AppleTestDescriptionArg>> testNode =
        TargetNodes.castArg(targetNode, AppleTestDescriptionArg.class);
    if (testNode.isPresent() && bundleLoaderNode.isPresent()) {
      AppleTestDescriptionArg testArg = testNode.get().getConstructorArg();
      return !testArg.getIsUiTest();
    } else {
      return false;
    }
  }

  private boolean isLibraryWithForceLoad(TargetNode<?> input) {
    Optional<TargetNode<CxxLibraryDescription.CommonArg>> library =
        getLibraryNode(targetGraph, input);

    if (!library.isPresent()) {
      return false;
    } else {

      TargetNode<CxxLibraryDescription.CommonArg> target = library.get();
      CxxLibraryDescription.CommonArg arg = target.getConstructorArg();
      return arg.getLinkWhole().orElse(false);
    }
  }

  private boolean shouldExcludeLibraryFromProject(TargetNode<?> targetNode) {
    // targets with flavor #compilation-database are not meant to be built by Xcode, they are used
    // only to generate the compile commands for a library during buck build
    return targetNode
        .getBuildTarget()
        .getFlavors()
        .contains(CxxCompilationDatabase.COMPILATION_DATABASE);
  }

  private boolean isLibraryBuiltByCurrentProject(TargetNode<?> targetNode) {
    return isBuiltByCurrentProject(targetNode.getBuildTarget());
  }

  private ImmutableSet<PBXFileReference> targetNodesSetToPBXFileReference(
      ImmutableSet<TargetNode<?>> targetNodes) {
    return FluentIterable.from(targetNodes).transform(this::getLibraryFileReference).toSet();
  }

  private ImmutableSet<PBXFileReference> targetNodesIterableToPBXFileReference(
      FluentIterable<TargetNode<?>> targetNodes) {
    return targetNodes.transform(this::getLibraryFileReference).toSet();
  }

  private FluentIterable<TargetNode<?>> collectRecursiveLibraryDepTargets(
      TargetNode<?> targetNode) {
    FluentIterable<TargetNode<?>> allDeps =
        FluentIterable.from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                xcodeDescriptions,
                targetGraph,
                Optional.of(dependenciesCache),
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                targetNode,
                xcodeDescriptions.getXCodeDescriptions()));
    return allDeps.filter(this::isLibraryWithSourcesToCompile);
  }

  private ImmutableSet<PBXFileReference> collectRecursiveLibraryDependencies(
      TargetNode<?> targetNode) {
    return targetNodesIterableToPBXFileReference(collectRecursiveLibraryDepTargets(targetNode));
  }

  private ImmutableSet<TargetNode<?>> filterRecursiveLibraryDepTargetsWithSwiftSources(
      FluentIterable<TargetNode<?>> targetNodes) {
    return targetNodes.filter(this::isLibraryWithSwiftSources).toSet();
  }

  private ImmutableSet<PBXFileReference> filterRecursiveLibraryDependenciesWithSwiftSources(
      FluentIterable<TargetNode<?>> targetNodes) {
    return targetNodesSetToPBXFileReference(
        filterRecursiveLibraryDepTargetsWithSwiftSources(targetNodes));
  }

  private boolean isLibraryFocused(TargetNode<?> targetNode) {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    return focusModules.isFocusedOn(buildTarget);
  }

  boolean isFrameworkTarget(TargetNode<?> targetNode) {
    if (targetNode.getDescription() instanceof AppleBundleDescription
        || targetNode.getDescription() instanceof AppleTestDescription) {
      HasAppleBundleFields arg = (HasAppleBundleFields) targetNode.getConstructorArg();
      return isFrameworkBundle(arg);
    }
    return false;
  }

  /** Filter Flags for subdividing dependencies */
  public enum FilterFlags {
    CURRENT_PROJECT, // Refers to deps that are built within the current project.
    OTHER_FOCUSED, // Refers to deps that are built within the workspace but not by the current
    // project.
    OTHER_NON_FOCUSED, // Refers to deps that not built by the workspace.
    FRAMEWORK, // Dependency built as a framework.
    LIBRARY, // Dependency built as a library.
    WITH_FORCE_LOAD, // Dependency with link_whole = True
    WITHOUT_FORCE_LOAD; // Dependency with link_whole = False

    /** Filter deps for the current project * */
    public static final EnumSet<FilterFlags> LIBRARY_CURRENT_PROJECT =
        EnumSet.of(LIBRARY, CURRENT_PROJECT);

    public static final EnumSet<FilterFlags> LIBRARY_CURRENT_PROJECT_WITH_FORCE_LOAD =
        EnumSet.of(LIBRARY, CURRENT_PROJECT, WITH_FORCE_LOAD);
    public static final EnumSet<FilterFlags> LIBRARY_CURRENT_PROJECT_WITHOUT_FORCE_LOAD =
        EnumSet.of(LIBRARY, CURRENT_PROJECT, WITHOUT_FORCE_LOAD);

    /** Filter deps for within the workspace and built by Xcode but not in the current project * */
    public static final EnumSet<FilterFlags> LIBRARY_FOCUSED = EnumSet.of(LIBRARY, OTHER_FOCUSED);

    public static final EnumSet<FilterFlags> LIBRARY_FOCUSED_WITH_FORCE_LOAD =
        EnumSet.of(LIBRARY, OTHER_FOCUSED, WITH_FORCE_LOAD);
    public static final EnumSet<FilterFlags> LIBRARY_FOCUSED_WITHOUT_FORCE_LOAD =
        EnumSet.of(LIBRARY, OTHER_FOCUSED, WITHOUT_FORCE_LOAD);

    /** Filter deps not included within the workspace * */
    public static final EnumSet<FilterFlags> LIBRARY_OTHER = EnumSet.of(LIBRARY, OTHER_NON_FOCUSED);

    public static final EnumSet<FilterFlags> LIBRARY_OTHER_WITH_FORCE_LOAD =
        EnumSet.of(LIBRARY, OTHER_NON_FOCUSED, WITH_FORCE_LOAD);
    public static final EnumSet<FilterFlags> LIBRARY_OTHER_WITHOUT_FORCE_LOAD =
        EnumSet.of(LIBRARY, OTHER_NON_FOCUSED, WITHOUT_FORCE_LOAD);

    /** Filter framework deps * */
    public static final EnumSet<FilterFlags> FRAMEWORK_CURRENT_PROJECT =
        EnumSet.of(FRAMEWORK, CURRENT_PROJECT);

    public static final EnumSet<FilterFlags> FRAMEWORK_FOCUSED =
        EnumSet.of(FRAMEWORK, OTHER_FOCUSED);
    public static final EnumSet<FilterFlags> FRAMEWORK_OTHER =
        EnumSet.of(FRAMEWORK, OTHER_NON_FOCUSED);
  }

  private FluentIterable<TargetNode<?>> filterRecursiveDeps(
      FluentIterable<TargetNode<?>> targetNodes, EnumSet<FilterFlags> filters) {

    FluentIterable<TargetNode<?>> filtered = targetNodes;
    boolean shouldBeFramework = filters.contains(FilterFlags.FRAMEWORK);
    filtered = targetNodes.filter(dep -> shouldBeFramework == isFrameworkTarget(dep));

    if (filters.contains(FilterFlags.CURRENT_PROJECT)) {
      filtered = filtered.filter(dep -> isLibraryBuiltByCurrentProject(dep));
    } else if (filters.contains(FilterFlags.OTHER_FOCUSED)) {
      filtered =
          filtered
              .filter(dep -> !isLibraryBuiltByCurrentProject(dep))
              .filter(dep -> isLibraryFocused(dep));
    } else if (filters.contains(FilterFlags.OTHER_NON_FOCUSED)) {
      filtered =
          filtered
              .filter(dep -> !isLibraryBuiltByCurrentProject(dep))
              .filter(dep -> !isLibraryFocused(dep));
    }

    // If neither is specified then do not limit by force_load at all.
    if (filters.contains(FilterFlags.WITH_FORCE_LOAD)
        || filters.contains(FilterFlags.WITHOUT_FORCE_LOAD)) {
      boolean shouldBeForceLoad = filters.contains(FilterFlags.WITH_FORCE_LOAD);
      filtered = filtered.filter(dep -> shouldBeForceLoad == isLibraryWithForceLoad(dep));
    }

    return filtered;
  }

  private FluentIterable<TargetNode<?>> filterRecursiveLibraryDepsIterable(
      FluentIterable<TargetNode<?>> targetNodes, EnumSet<FilterFlags> filters) {
    return filterRecursiveDeps(targetNodes, filters);
  }

  private ImmutableSet<PBXFileReference> filterRecursiveLibraryDeps(
      FluentIterable<TargetNode<?>> targetNodes, EnumSet<FilterFlags> filters) {
    return targetNodesIterableToPBXFileReference(
        filterRecursiveLibraryDepsIterable(targetNodes, filters));
  }

  /** Dependencies to be included in the Xcode Linker Phase Step* */
  private ImmutableSet<PBXFileReference> filterRecursiveLibraryDependenciesForLinkerPhase(
      FluentIterable<TargetNode<?>> targetNodes) {
    /** Local -- local to the current project and built by Xcode * */
    ImmutableSet<PBXFileReference> librariesLocal =
        filterRecursiveLibraryDeps(targetNodes, FilterFlags.LIBRARY_CURRENT_PROJECT);

    /** Focused -- included in the workspace and built by Xcode but not in current project * */
    ImmutableSet<PBXFileReference> librariesOtherFocused =
        filterRecursiveLibraryDeps(targetNodes, FilterFlags.LIBRARY_FOCUSED);

    /** Other -- not included in the workspace to be built by Xcode * */
    ImmutableSet<PBXFileReference> librariesOther =
        filterRecursiveLibraryDeps(targetNodes, FilterFlags.LIBRARY_OTHER);

    ImmutableSet<PBXFileReference> frameworksLocal =
        filterRecursiveLibraryDeps(targetNodes, FilterFlags.FRAMEWORK_CURRENT_PROJECT);

    ImmutableSet<PBXFileReference> frameworksOther =
        filterRecursiveLibraryDeps(targetNodes, FilterFlags.FRAMEWORK_OTHER);

    ImmutableSet<PBXFileReference> frameworksOtherFocused =
        filterRecursiveLibraryDeps(targetNodes, FilterFlags.FRAMEWORK_FOCUSED);

    if (options.shouldAddLinkedLibrariesAsFlags()) {
      // only include the local and focused so they can be picked up by xcode as implicit deps
      // exclude other libraries since they will be linked using the flags only
      ImmutableSet.Builder<PBXFileReference> builder = ImmutableSet.builder();
      return builder
          .addAll(frameworksLocal)
          .addAll(frameworksOtherFocused)
          .addAll(librariesLocal)
          .addAll(librariesOtherFocused)
          .build();
    } else {
      ImmutableSet.Builder<PBXFileReference> builder = ImmutableSet.builder();
      return builder
          .addAll(frameworksLocal)
          .addAll(frameworksOtherFocused)
          .addAll(frameworksOther)
          .addAll(librariesLocal)
          .addAll(librariesOtherFocused)
          .addAll(librariesOther)
          .build();
    }
  }

  private ImmutableList<String> collectSystemLibraryAndFrameworkLinkerFlags(
      ImmutableSet<FrameworkPath> paths) {
    FluentIterable<FrameworkPath> pathList = FluentIterable.from(paths);
    return pathList
        .transform(fwkOrLib -> getSystemFrameworkOrLibraryLinkerFlag(fwkOrLib))
        .filter(Optional::isPresent)
        .transform(input -> input.get())
        .toList();
  }

  private ImmutableList<String> collectLibraryLinkerFlags(
      FluentIterable<TargetNode<?>> targetNodes) {
    return targetNodes
        .transform(dep -> getLibraryLinkerFlag(dep))
        .filter(Optional::isPresent)
        .transform(input -> input.get())
        .toList();
  }

  private ImmutableList<String> collectFrameworkLinkerFlags(
      FluentIterable<TargetNode<?>> targetNodes) {
    return targetNodes
        .transform(
            input ->
                TargetNodes.castArg(input, AppleBundleDescriptionArg.class)
                    .flatMap(this::getFrameworkLinkerFlag))
        .filter(Optional::isPresent)
        .transform(input -> input.get())
        .toList();
  }

  private FluentIterable<TargetNode<?>> collectRecursiveLibraryDepsMinusBundleLoaderDeps(
      TargetNode<?> targetNode,
      FluentIterable<TargetNode<?>> targetNodes,
      Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode) {

    // Don't duplicate force_load params from the test host app if this is an app test.
    if (isTargetNodeApplicationTestTarget(targetNode, bundleLoaderNode)
        && bundleLoaderNode.isPresent()) {
      FluentIterable<TargetNode<?>> bundleLoaderDeps =
          collectRecursiveLibraryDepTargets(bundleLoaderNode.get());

      Set<TargetNode<?>> directDeps =
          Sets.difference(targetNodes.toSet(), bundleLoaderDeps.toSet());
      return FluentIterable.from(ImmutableSet.copyOf(directDeps));
    }
    return targetNodes;
  }

  private ImmutableList<String> collectForceLoadLinkerFlags(
      FluentIterable<TargetNode<?>> targetNodes) {
    return targetNodes
        .transform(
            input ->
                TargetNodes.castArg(input, CxxLibraryDescription.CommonArg.class)
                    .flatMap(this::getForceLoadLinkerFlag))
        .filter(Optional::isPresent)
        .transform(input -> input.get())
        .toList();
  }

  private Optional<String> getFrameworkLinkerFlag(
      TargetNode<? extends AppleBundleDescriptionArg> targetNode) {
    if (isFrameworkBundle(targetNode.getConstructorArg())) {
      return Optional.of("-framework " + getProductOutputBaseName(targetNode));
    } else {
      return Optional.empty();
    }
  }

  private Optional<String> getSystemFrameworkOrLibraryLinkerFlag(FrameworkPath framework) {

    SourceTreePath sourceTreePath;
    if (framework.getSourceTreePath().isPresent()) {
      sourceTreePath = framework.getSourceTreePath().get();
    } else if (framework.getSourcePath().isPresent()) {
      sourceTreePath =
          new SourceTreePath(
              PBXReference.SourceTree.SOURCE_ROOT,
              pathRelativizer.outputPathToSourcePath(framework.getSourcePath().get()),
              Optional.empty());
    } else {
      return Optional.empty();
    }

    String nameWithoutExtension = MorePaths.getNameWithoutExtension(sourceTreePath.getPath());

    if (nameWithoutExtension.length() > 0) {
      String libraryPrefix = "lib";
      if (nameWithoutExtension.startsWith(libraryPrefix)) {
        return Optional.of("-l" + nameWithoutExtension.substring(libraryPrefix.length()));
      } else {
        return Optional.of("-framework " + nameWithoutExtension);
      }
    }
    return Optional.empty();
  }

  private Optional<String> getLibraryLinkerFlag(TargetNode<?> targetNode) {
    return Optional.of("-l" + getProductOutputBaseName(targetNode));
  }

  private Optional<String> getForceLoadLinkerFlag(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode) {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    boolean isFocusedOnTarget = focusModules.isFocusedOn(buildTarget);
    CxxLibraryDescription.CommonArg arg = targetNode.getConstructorArg();
    if (arg.getLinkWhole().orElse(false)) {
      String flag =
          "-Wl,-force_load,"
              + appleConfig.getForceLoadLibraryPath(isFocusedOnTarget)
              + "/"
              + getProductOutputNameWithExtension(targetNode);
      return Optional.of(flag);
    } else {
      return Optional.empty();
    }
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

  private SourceTreePath getProductsSourceTreePath(TargetNode<?> targetNode) {
    String productOutputName = getProductOutputNameWithExtension(targetNode);
    PBXReference.SourceTree path = PBXReference.SourceTree.BUILT_PRODUCTS_DIR;
    if (targetNode.getDescription() instanceof PrebuiltAppleFrameworkDescription) {
      path = PBXReference.SourceTree.SOURCE_ROOT;
    }
    return new SourceTreePath(path, Paths.get(productOutputName), Optional.empty());
  }

  private PBXFileReference getLibraryFileReference(TargetNode<?> targetNode) {
    // Don't re-use the productReference from other targets in this project.
    // File references set as a productReference don't work with custom paths.
    SourceTreePath productsPath = getProductsSourceTreePath(targetNode);

    if (isWatchApplicationNode(targetNode)) {
      return project
          .getMainGroup()
          .getOrCreateChildGroupByName("Products")
          .getOrCreateFileReferenceBySourceTreePath(productsPath);
    } else if (targetNode.getDescription() instanceof AppleLibraryDescription
        || targetNode.getDescription() instanceof AppleBundleDescription
        || targetNode.getDescription() instanceof CxxLibraryDescription
        || targetNode.getDescription() instanceof HalideLibraryDescription
        || targetNode.getDescription() instanceof PrebuiltAppleFrameworkDescription) {
      return project
          .getMainGroup()
          .getOrCreateChildGroupByName("Frameworks")
          .getOrCreateFileReferenceBySourceTreePath(productsPath);
    } else if (targetNode.getDescription() instanceof AppleBinaryDescription) {
      return project
          .getMainGroup()
          .getOrCreateChildGroupByName("Dependencies")
          .getOrCreateFileReferenceBySourceTreePath(productsPath);
    } else {
      throw new RuntimeException("Unexpected type: " + targetNode.getDescription().getClass());
    }
  }

  /**
   * Whether a given build target is built by the project being generated, or being build elsewhere.
   */
  private boolean isBuiltByCurrentProject(BuildTarget buildTarget) {
    return initialTargets.contains(buildTarget);
  }

  private String getXcodeTargetName(BuildTarget target) {
    return options.shouldUseShortNamesForTargets()
        ? target.getShortNameAndFlavorPostfix() // make sure Xcode UI shows unique names by flavor
        : target.getFullyQualifiedName();
  }

  private ProductType bundleToTargetProductType(
      TargetNode<? extends HasAppleBundleFields> targetNode,
      TargetNode<? extends AppleNativeTargetDescriptionArg> binaryNode) {
    if (targetNode.getConstructorArg().getXcodeProductType().isPresent()) {
      return ProductType.of(targetNode.getConstructorArg().getXcodeProductType().get());
    } else if (targetNode.getConstructorArg().getExtension().isLeft()) {
      AppleBundleExtension extension = targetNode.getConstructorArg().getExtension().getLeft();

      boolean nodeIsAppleLibrary = binaryNode.getDescription() instanceof AppleLibraryDescription;
      boolean nodeIsCxxLibrary =
          ((DescriptionWithTargetGraph<?>) binaryNode.getDescription())
              instanceof CxxLibraryDescription;
      if (nodeIsAppleLibrary || nodeIsCxxLibrary) {
        if (binaryNode
            .getBuildTarget()
            .getFlavors()
            .contains(CxxDescriptionEnhancer.SHARED_FLAVOR)) {
          Optional<ProductType> productType = dylibProductTypeByBundleExtension(extension);
          if (productType.isPresent()) {
            return productType.get();
          }
        } else if (extension == AppleBundleExtension.FRAMEWORK) {
          return ProductTypes.STATIC_FRAMEWORK;
        }
      } else if (binaryNode.getDescription() instanceof AppleBinaryDescription) {
        if (extension == AppleBundleExtension.APP) {
          return ProductTypes.APPLICATION;
        }
      } else if (binaryNode.getDescription() instanceof AppleTestDescription) {
        TargetNode<AppleTestDescriptionArg> testNode =
            TargetNodes.castArg(binaryNode, AppleTestDescriptionArg.class).get();
        if (testNode.getConstructorArg().getIsUiTest()) {
          return ProductTypes.UI_TEST;
        } else {
          return ProductTypes.UNIT_TEST;
        }
      }
    }

    return ProductTypes.BUNDLE;
  }

  private static String getExtensionString(Either<AppleBundleExtension, String> extension) {
    return extension.isLeft() ? extension.getLeft().toFileExtension() : extension.getRight();
  }

  private static boolean isFrameworkBundle(HasAppleBundleFields arg) {
    return arg.getExtension().isLeft()
        && arg.getExtension().getLeft().equals(AppleBundleExtension.FRAMEWORK);
  }

  private static boolean isModularAppleLibrary(TargetNode<?> libraryNode) {
    Optional<TargetNode<AppleLibraryDescriptionArg>> appleLibNode =
        TargetNodes.castArg(libraryNode, AppleLibraryDescriptionArg.class);
    if (appleLibNode.isPresent()) {
      AppleLibraryDescriptionArg constructorArg = appleLibNode.get().getConstructorArg();
      return constructorArg.isModular();
    }

    return false;
  }

  private static boolean bundleRequiresRemovalOfAllTransitiveFrameworks(
      TargetNode<? extends HasAppleBundleFields> targetNode) {
    return isFrameworkBundle(targetNode.getConstructorArg());
  }

  private static boolean bundleRequiresAllTransitiveFrameworks(
      TargetNode<? extends AppleNativeTargetDescriptionArg> binaryNode,
      Optional<TargetNode<AppleBundleDescriptionArg>> bundleLoaderNode) {
    return TargetNodes.castArg(binaryNode, AppleBinaryDescriptionArg.class).isPresent()
        || (!bundleLoaderNode.isPresent()
            && TargetNodes.castArg(binaryNode, AppleTestDescriptionArg.class).isPresent());
  }

  private Path resolveSourcePath(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return projectFilesystem.relativize(defaultPathResolver.getAbsolutePath(sourcePath));
    }
    Preconditions.checkArgument(sourcePath instanceof BuildTargetSourcePath);
    BuildTargetSourcePath buildTargetSourcePath = (BuildTargetSourcePath) sourcePath;
    BuildTarget buildTarget = buildTargetSourcePath.getTarget();
    TargetNode<?> node = targetGraph.get(buildTarget);
    Optional<TargetNode<ExportFileDescriptionArg>> exportFileNode =
        TargetNodes.castArg(node, ExportFileDescriptionArg.class);
    if (!exportFileNode.isPresent()) {
      BuildRuleResolver resolver = actionGraphBuilderForNode.apply(node);
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
      Path output = pathResolver.getAbsolutePath(sourcePath);
      if (output == null) {
        throw new HumanReadableException(
            "The target '%s' does not have an output.", node.getBuildTarget());
      }
      requiredBuildTargetsBuilder.add(buildTarget);
      return projectFilesystem.relativize(output);
    }

    Optional<SourcePath> src = exportFileNode.get().getConstructorArg().getSrc();
    if (!src.isPresent()) {
      Path output =
          buildTarget
              .getCellPath()
              .resolve(buildTarget.getBasePath())
              .resolve(buildTarget.getShortNameAndFlavorPostfix());
      return projectFilesystem.relativize(output);
    }

    return resolveSourcePath(src.get());
  }

  private boolean shouldCopyOutputFile(TargetNode<?> input) {
    if (input.getDescription() instanceof CxxLibraryDescription
        || input.getDescription() instanceof AppleLibraryDescription) {
      return isLibraryWithSourcesToCompile(input);
    }
    return true;
  }

  private boolean isLibraryWithSourcesToCompile(TargetNode<?> input) {
    if (input.getDescription() instanceof HalideLibraryDescription) {
      return true;
    }

    Optional<TargetNode<CxxLibraryDescription.CommonArg>> library =
        getLibraryNode(targetGraph, input);

    if (!library.isPresent()) {
      return false;
    }
    PatternMatchedCollection<ImmutableSortedSet<SourceWithFlags>> platformSources =
        library.get().getConstructorArg().getPlatformSrcs();
    int platFormSourcesSize = platformSources.getValues().size();
    return (library.get().getConstructorArg().getSrcs().size() + platFormSourcesSize != 0);
  }

  private boolean isLibraryWithSwiftSources(TargetNode<?> input) {
    Optional<TargetNode<CxxLibraryDescription.CommonArg>> library =
        getLibraryNode(targetGraph, input);
    if (!library.isPresent()) {
      return false;
    }

    return projGenerationStateCache.targetContainsSwiftSourceCode(library.get());
  }

  /** @return product type of a bundle containing a dylib. */
  private static Optional<ProductType> dylibProductTypeByBundleExtension(
      AppleBundleExtension extension) {
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

  private Optional<SourcePath> getPrefixHeaderSourcePath(CxxLibraryDescription.CommonArg arg) {
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
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    BuildRule rule = ruleFinder.getRule(pchTargetSourcePath);
    Preconditions.checkArgument(rule instanceof CxxPrecompiledHeaderTemplate);
    CxxPrecompiledHeaderTemplate pch = (CxxPrecompiledHeaderTemplate) rule;
    return Optional.of(pch.getHeaderSourcePath());
  }

  private ProjectFilesystem getFilesystemForTarget(Optional<BuildTarget> target) {
    if (target.isPresent()) {
      Path cellPath = target.get().getCellPath();
      Cell cell = projectCell.getCellProvider().getCellByPath(cellPath);
      return cell.getFilesystem();
    } else {
      return projectFilesystem;
    }
  }

  private Path getPathToHeaderMapsRoot(Optional<BuildTarget> target) {
    ProjectFilesystem filesystem = getFilesystemForTarget(target);
    return filesystem.getBuckPaths().getGenDir().resolve("_p");
  }

  private Path getFilenameToHeadersPath(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode, String suffix) {
    String hashedPath =
        BaseEncoding.base64Url()
            .omitPadding()
            .encode(
                Hashing.sha1()
                    .hashString(
                        targetNode
                            .getBuildTarget()
                            .getUnflavoredBuildTarget()
                            .getFullyQualifiedName(),
                        Charsets.UTF_8)
                    .asBytes())
            .substring(0, 10);
    return Paths.get(hashedPath + suffix);
  }

  private Path getPathToHeadersPath(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode, String suffix) {
    return getPathToHeaderMapsRoot(Optional.of(targetNode.getBuildTarget()))
        .resolve(getFilenameToHeadersPath(targetNode, suffix));
  }

  private Path getAbsolutePathToHeaderSymlinkTree(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode,
      HeaderVisibility headerVisibility) {
    ProjectFilesystem filesystem = getFilesystemForTarget(Optional.of(targetNode.getBuildTarget()));
    return filesystem.resolve(getPathToHeaderSymlinkTree(targetNode, headerVisibility));
  }

  private Path getPathToHeaderSymlinkTree(
      TargetNode<? extends CxxLibraryDescription.CommonArg> targetNode,
      HeaderVisibility headerVisibility) {
    return getPathToHeadersPath(
        targetNode, AppleHeaderVisibilities.getHeaderSymlinkTreeSuffix(headerVisibility));
  }

  private Path getPathToMergedHeaderMap() {
    return getPathToHeaderMapsRoot(Optional.of(workspaceTarget.get())).resolve("pub-hmap");
  }
}
