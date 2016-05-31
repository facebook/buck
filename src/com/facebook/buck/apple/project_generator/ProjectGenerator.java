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

package com.facebook.buck.apple.project_generator;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleDescriptions;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.AppleResources;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.apple.HasAppleBundleFields;
import com.facebook.buck.apple.XcodePostbuildScriptDescription;
import com.facebook.buck.apple.XcodePrebuildScriptDescription;
import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.apple.xcode.GidGenerator;
import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.facebook.buck.apple.xcode.xcodeproj.CopyFilePhaseDestinationSpec;
import com.facebook.buck.apple.xcode.xcodeproj.PBXAggregateTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXCopyFilesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.apple.xcode.xcodeproj.XCConfigurationList;
import com.facebook.buck.apple.xcode.xcodeproj.XCVersionGroup;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.ProjectGenerationEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.graph.AcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.GraphTraversable;
import com.facebook.buck.halide.HalideBuckConfig;
import com.facebook.buck.halide.HalideCompile;
import com.facebook.buck.halide.HalideLibraryDescription;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.js.IosReactNativeLibraryDescription;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.PackagedResource;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.UncheckedExecutionException;

import org.stringtemplate.v4.ST;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generator for xcode project and associated files from a set of xcode/ios rules.
 */
public class ProjectGenerator {
  public static final String BUILD_WITH_BUCK_POSTFIX = "-Buck";

  private static final Logger LOG = Logger.get(ProjectGenerator.class);
  private static final String BUILD_WITH_BUCK_TEMPLATE = "build-with-buck.st";
  private static final String FIX_UUID_TEMPLATE = "fix-uuid.st";
  private static final String FIX_UUID_PY_RESOURCE = "fix_uuid.py";
  private static final String CODESIGN_TEMPLATE = "codesign.st";
  private static final String CODESIGN_PY_RESOURCE = "codesign.py";
  private static final ImmutableList<String> DEFAULT_CFLAGS = ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_CXXFLAGS = ImmutableList.of();
  public static final String REPORT_ABSOLUTE_PATHS = "--report-absolute-paths";
  public static final String XCODE_BUILD_SCRIPT_FLAVOR_VALUE = "#$PLATFORM_NAME-$arch";
  public static final String PRODUCT_NAME = "PRODUCT_NAME";

  public enum Option {
    /** Use short BuildTarget name instead of full name for targets */
    USE_SHORT_NAMES_FOR_TARGETS,

    /** Put targets into groups reflecting directory structure of their BUCK files */
    CREATE_DIRECTORY_STRUCTURE,

    /** Generate read-only project files */
    GENERATE_READ_ONLY_FILES,

    /** Include tests that test root targets in the scheme */
    INCLUDE_TESTS,

    /** Include dependencies tests in the scheme */
    INCLUDE_DEPENDENCIES_TESTS,

    /** Don't use header maps as header search paths */
    DISABLE_HEADER_MAPS,
  }

  /**
   * Standard options for generating a separated project
   */
  public static final ImmutableSet<Option> SEPARATED_PROJECT_OPTIONS = ImmutableSet.of(
      Option.USE_SHORT_NAMES_FOR_TARGETS);

  /**
   * Standard options for generating a combined project
   */
  public static final ImmutableSet<Option> COMBINED_PROJECT_OPTIONS = ImmutableSet.of(
      Option.CREATE_DIRECTORY_STRUCTURE,
      Option.USE_SHORT_NAMES_FOR_TARGETS);

  private static final FileAttribute<?> READ_ONLY_FILE_ATTRIBUTE =
      PosixFilePermissions.asFileAttribute(
          ImmutableSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.OTHERS_READ));

  public static final Function<
      TargetNode<AppleNativeTargetDescriptionArg>,
      Iterable<String>> GET_EXPORTED_LINKER_FLAGS =
      new Function<TargetNode<AppleNativeTargetDescriptionArg>, Iterable<String>>() {
        @Override
        public Iterable<String> apply(TargetNode<AppleNativeTargetDescriptionArg> input) {
          return input.getConstructorArg().exportedLinkerFlags.get();
        }
      };

  public static final Function<
      TargetNode<AppleNativeTargetDescriptionArg>,
      Iterable<String>> GET_EXPORTED_PREPROCESSOR_FLAGS =
      new Function<TargetNode<AppleNativeTargetDescriptionArg>, Iterable<String>>() {
        @Override
        public Iterable<String> apply(TargetNode<AppleNativeTargetDescriptionArg> input) {
          return input.getConstructorArg().exportedPreprocessorFlags.get();
        }
      };

  private final Function<SourcePath, Path> sourcePathResolver;
  private final TargetGraph targetGraph;
  private final Cell projectCell;
  private final ProjectFilesystem projectFilesystem;
  private final Path outputDirectory;
  private final String projectName;
  private final ImmutableSet<BuildTarget> initialTargets;
  private final Path projectPath;
  private final PathRelativizer pathRelativizer;

  private final String buildFileName;
  private final ImmutableSet<Option> options;
  private final Optional<BuildTarget> targetToBuildWithBuck;
  private final ImmutableList<String> buildWithBuckFlags;
  private final ExecutableFinder executableFinder;
  private final ImmutableMap<String, String> environment;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;

  private ImmutableSet<TargetNode<AppleTestDescription.Arg>> testsToGenerateAsStaticLibraries =
      ImmutableSet.of();
  private ImmutableMultimap<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
      additionalCombinedTestTargets = ImmutableMultimap.of();

  // These fields are created/filled when creating the projects.
  private final PBXProject project;
  private final LoadingCache<TargetNode<?>, Optional<PBXTarget>> targetNodeToProjectTarget;
  private final ImmutableMultimap.Builder<TargetNode<?>, PBXTarget>
      targetNodeToGeneratedProjectTargetBuilder;
  private boolean projectGenerated;
  private final List<Path> headerSymlinkTrees;
  private final ImmutableSet.Builder<PBXTarget> buildableCombinedTestTargets =
      ImmutableSet.builder();
  private final ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder =
      ImmutableSet.builder();
  private final Function<? super TargetNode<?>, SourcePathResolver> sourcePathResolverForNode;
  private final BuckEventBus buckEventBus;
  private boolean attemptToDetermineBestCxxPlatform;

  /**
   * Populated while generating project configurations, in order to collect the possible
   * project-level configurations to set.
   */
  private final ImmutableSet.Builder<String> targetConfigNamesBuilder;

  private final Map<String, String> gidsToTargetNames;
  private final HalideBuckConfig halideBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final ImmutableList<BuildTarget> focusModules;

  public ProjectGenerator(
      TargetGraph targetGraph,
      Set<BuildTarget> initialTargets,
      Cell cell,
      Path outputDirectory,
      String projectName,
      String buildFileName,
      Set<Option> options,
      Optional<BuildTarget> targetToBuildWithBuck,
      ImmutableList<String> buildWithBuckFlags,
      ImmutableList<BuildTarget> focusModules,
      ExecutableFinder executableFinder,
      ImmutableMap<String, String> environment,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxPlatform defaultCxxPlatform,
      Function<? super TargetNode<?>, SourcePathResolver> sourcePathResolverForNode,
      BuckEventBus buckEventBus,
      boolean attemptToDetermineBestCxxPlatform,
      HalideBuckConfig halideBuckConfig,
      CxxBuckConfig cxxBuckConfig) {
    this.sourcePathResolver = new Function<SourcePath, Path>() {
      @Override
      public Path apply(SourcePath input) {
        return resolveSourcePath(input);
      }
    };

    this.targetGraph = targetGraph;
    this.initialTargets = ImmutableSet.copyOf(initialTargets);
    this.projectCell = cell;
    this.projectFilesystem = cell.getFilesystem();
    this.outputDirectory = outputDirectory;
    this.projectName = projectName;
    this.buildFileName = buildFileName;
    this.options = ImmutableSet.copyOf(options);
    this.targetToBuildWithBuck = targetToBuildWithBuck;
    this.buildWithBuckFlags = buildWithBuckFlags;
    this.executableFinder = executableFinder;
    this.environment = environment;
    this.cxxPlatforms = cxxPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.sourcePathResolverForNode = sourcePathResolverForNode;
    this.buckEventBus = buckEventBus;
    this.attemptToDetermineBestCxxPlatform = attemptToDetermineBestCxxPlatform;

    this.projectPath = outputDirectory.resolve(projectName + ".xcodeproj");
    this.pathRelativizer = new PathRelativizer(
        outputDirectory,
        sourcePathResolver);

    LOG.debug(
        "Output directory %s, profile fs root path %s, repo root relative to output dir %s",
        this.outputDirectory,
        projectFilesystem.getRootPath(),
        this.pathRelativizer.outputDirToRootRelative(Paths.get(".")));

    this.project = new PBXProject(projectName);
    this.headerSymlinkTrees = new ArrayList<>();

    this.targetNodeToGeneratedProjectTargetBuilder = ImmutableMultimap.builder();
    this.targetNodeToProjectTarget = CacheBuilder.newBuilder().build(
        new CacheLoader<TargetNode<?>, Optional<PBXTarget>>() {
          @Override
          public Optional<PBXTarget> load(TargetNode<?> key) throws Exception {
            return generateProjectTarget(key);
          }
        });

    targetConfigNamesBuilder = ImmutableSet.builder();
    gidsToTargetNames = new HashMap<>();
    this.halideBuckConfig = halideBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.focusModules = focusModules;

    for (BuildTarget focusedTarget : focusModules) {
      Preconditions.checkArgument(
          targetGraph.getOptional(focusedTarget).isPresent(),
          "Cannot find build target %s in target graph", focusedTarget);
    }
  }

  /**
   * Sets the set of tests which should be generated as static libraries instead of test bundles.
   */
  public ProjectGenerator setTestsToGenerateAsStaticLibraries(
      Set<TargetNode<AppleTestDescription.Arg>> set) {
    Preconditions.checkState(!projectGenerated);
    this.testsToGenerateAsStaticLibraries = ImmutableSet.copyOf(set);
    return this;
  }

  /**
   * Sets combined test targets which should be generated in this project.
   */
  public ProjectGenerator setAdditionalCombinedTestTargets(
      Multimap<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>> targets) {
    Preconditions.checkState(!projectGenerated);
    this.additionalCombinedTestTargets = ImmutableMultimap.copyOf(targets);
    return this;
  }

  @VisibleForTesting
  PBXProject getGeneratedProject() {
    return project;
  }

  @VisibleForTesting
  List<Path> getGeneratedHeaderSymlinkTrees() {
    return headerSymlinkTrees;
  }

  static PackagedResource getPackagedResourceNamed(ProjectFilesystem filesystem, String name) {
    return new PackagedResource(filesystem, ProjectGenerator.class, name);
  }

  static Path getPathToBuck(
      ExecutableFinder executableFinder,
      ImmutableMap<String, String> environment) {
    return executableFinder.getExecutable(Paths.get("buck"), environment);
  }

  @VisibleForTesting
  static Path getFixUUIDScriptPath(ProjectFilesystem filesystem) {
    return getPackagedResourceNamed(filesystem, FIX_UUID_PY_RESOURCE).get();
  }

  @VisibleForTesting
  static Path getCodesignScriptPath(ProjectFilesystem filesystem) {
    return getPackagedResourceNamed(filesystem, CODESIGN_PY_RESOURCE).get();
  }

  public Path getProjectPath() {
    return projectPath;
  }

  public ImmutableMultimap<BuildTarget, PBXTarget> getBuildTargetToGeneratedTargetMap() {
    Preconditions.checkState(projectGenerated, "Must have called createXcodeProjects");
    ImmutableMultimap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMap =
        ImmutableMultimap.builder();
    for (Map.Entry<TargetNode<?>, PBXTarget> entry :
        targetNodeToGeneratedProjectTargetBuilder.build().entries()) {
      buildTargetToPbxTargetMap.put(entry.getKey().getBuildTarget(), entry.getValue());
    }
    return buildTargetToPbxTargetMap.build();
  }

  public ImmutableSet<PBXTarget> getBuildableCombinedTestTargets() {
    Preconditions.checkState(projectGenerated, "Must have called createXcodeProjects");
    return buildableCombinedTestTargets.build();
  }

  public ImmutableSet<BuildTarget> getRequiredBuildTargets() {
    Preconditions.checkState(projectGenerated, "Must have called createXcodeProjects");
    return requiredBuildTargetsBuilder.build();
  }

  public void createXcodeProjects() throws IOException {
    LOG.debug("Creating projects for targets %s", initialTargets);

    try (
        SimplePerfEvent.Scope scope = SimplePerfEvent.scope(
            buckEventBus,
            PerfEventId.of("xcode_project_generation"),
            ImmutableMap.<String, Object>of("Path", getProjectPath()))) {
      for (TargetNode<?> targetNode : targetGraph.getNodes()) {
        if (isBuiltByCurrentProject(targetNode.getBuildTarget())) {
          LOG.debug("Including rule %s in project", targetNode);
          // Trigger the loading cache to call the generateProjectTarget function.
          Optional<PBXTarget> target = targetNodeToProjectTarget.getUnchecked(targetNode);
          if (target.isPresent()) {
            targetNodeToGeneratedProjectTargetBuilder.put(targetNode, target.get());
          }
        } else {
          LOG.verbose("Excluding rule %s (not built by current project)", targetNode);
        }
      }

      if (targetToBuildWithBuck.isPresent()) {
        generateBuildWithBuckTarget(targetGraph.get(targetToBuildWithBuck.get()));
      }

      int combinedTestIndex = 0;
      for (AppleTestBundleParamsKey key : additionalCombinedTestTargets.keySet()) {
        generateCombinedTestTarget(
            deriveCombinedTestTargetNameFromKey(key, combinedTestIndex++),
            key,
            additionalCombinedTestTargets.get(key));
      }

      for (String configName : targetConfigNamesBuilder.build()) {
        XCBuildConfiguration outputConfig = project
            .getBuildConfigurationList()
            .getBuildConfigurationsByName()
            .getUnchecked(configName);
        outputConfig.setBuildSettings(new NSDictionary());
      }

      writeProjectFile(project);

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

  private void generateBuildWithBuckTarget(TargetNode<?> targetNode) throws IOException {
    final BuildTarget buildTarget = targetNode.getBuildTarget();

    String buckTargetProductName = getXcodeTargetName(buildTarget) + BUILD_WITH_BUCK_POSTFIX;

    PBXAggregateTarget buildWithBuckTarget = new PBXAggregateTarget(buckTargetProductName);
    buildWithBuckTarget.setProductName(buckTargetProductName);

    PBXShellScriptBuildPhase buildShellScriptBuildPhase = new PBXShellScriptBuildPhase();
    buildShellScriptBuildPhase.setShellScript(getBuildWithBuckShellScript(targetNode));
    buildWithBuckTarget.getBuildPhases().add(buildShellScriptBuildPhase);

    // Only add a shell script for fixing UUIDs if it is an AppleBundle
    if (targetNode.getType().equals(AppleBundleDescription.TYPE)) {
      PBXShellScriptBuildPhase fixUUIDShellScriptBuildPhase = new PBXShellScriptBuildPhase();
      fixUUIDShellScriptBuildPhase.setShellScript(getFixUUIDShellScript(targetNode));
      buildWithBuckTarget.getBuildPhases().add(fixUUIDShellScriptBuildPhase);

      PBXShellScriptBuildPhase codesignPhase = new PBXShellScriptBuildPhase();
      codesignPhase.setShellScript(getCodesignShellScript(targetNode));
      buildWithBuckTarget.getBuildPhases().add(codesignPhase);
    }

    TargetNode<CxxLibraryDescription.Arg> node = getAppleNativeNode(targetGraph, targetNode).get();
    ImmutableMap<String, ImmutableMap<String, String>> configs =
        getXcodeBuildConfigurationsForTargetNode(node, ImmutableMap.<String, String>of()).get();

    XCConfigurationList configurationList = new XCConfigurationList();
    PBXGroup group = project
        .getMainGroup()
        .getOrCreateDescendantGroupByPath(
            FluentIterable
                .from(buildTarget.getBasePath())
                .transform(Functions.toStringFunction())
                .toList())
        .getOrCreateChildGroupByName(getXcodeTargetName(buildTarget));
    for (String configurationName : configs.keySet()) {
      XCBuildConfiguration configuration = configurationList
          .getBuildConfigurationsByName()
          .getUnchecked(configurationName);
      configuration.setBaseConfigurationReference(
          getConfigurationFileReference(
              group,
              getConfigurationNameToXcconfigPath(buildTarget).apply(configurationName)));

      NSDictionary inlineSettings = new NSDictionary();
      inlineSettings.put("HEADER_SEARCH_PATHS", "");
      inlineSettings.put("LIBRARY_SEARCH_PATHS", "");
      inlineSettings.put("FRAMEWORK_SEARCH_PATHS", "");
      configuration.setBuildSettings(inlineSettings);
    }

    buildWithBuckTarget.setBuildConfigurationList(configurationList);
    project.getTargets().add(buildWithBuckTarget);

    targetNodeToGeneratedProjectTargetBuilder.put(targetNode, buildWithBuckTarget);
  }

  private static Optional<String> getProductNameForTargetNode(TargetNode<?> targetNode) {
    Optional<String> productName = Optional.absent();
    Optional<TargetNode<AppleBundleDescription.Arg>> appleBundleNode =
        targetNode.castArg(AppleBundleDescription.Arg.class);
    if (appleBundleNode.isPresent()) {
      productName = appleBundleNode.get().getConstructorArg().productName;
    }
    return productName;
  }

  private String getBuildFlags() {
    ArrayList<String> flags = new ArrayList<>(buildWithBuckFlags);
    if (!flags.contains(REPORT_ABSOLUTE_PATHS)) {
      flags.add(0, REPORT_ABSOLUTE_PATHS);
    }
    flags = new ArrayList<String>(
        FluentIterable.<String>from(flags).transform(Escaper.BASH_ESCAPER).toList());
    return Joiner.on(' ').join(flags);
  }

  private String getBuildWithBuckShellScript(TargetNode<?> targetNode) {
    ST template;
    try {
      template = new ST(Resources.toString(
          Resources.getResource(ProjectGenerator.class, BUILD_WITH_BUCK_TEMPLATE),
          Charsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(
          "There was an error loading '" + BUILD_WITH_BUCK_TEMPLATE + "' template", e);
    }

    String buildFlags = getBuildFlags();
    String escapedBuildTarget = Escaper.escapeAsBashString(
        targetNode.getBuildTarget().getFullyQualifiedName());
    if (attemptToDetermineBestCxxPlatform) {
      escapedBuildTarget += XCODE_BUILD_SCRIPT_FLAVOR_VALUE;
    }

    template.add("repo_root", projectFilesystem.getRootPath());
    template.add("path_to_buck", getPathToBuck(executableFinder, environment));
    template.add("build_flags", buildFlags);
    template.add("escaped_build_target", escapedBuildTarget);

    return template.render();
  }

  private String getFixUUIDShellScript(TargetNode<?> targetNode) {
    ST template;
    try {
      template = new ST(Resources.toString(
          Resources.getResource(ProjectGenerator.class, FIX_UUID_TEMPLATE), Charsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(
          "There was an error loading '" + FIX_UUID_TEMPLATE + "' template", e);
    }

    ImmutableSet<Flavor> flavors = ImmutableSet.copyOf(targetNode.getBuildTarget().getFlavors());
    CxxPlatform cxxPlatform = cxxPlatforms.getValue(flavors).or(defaultCxxPlatform);
    String compDir = cxxPlatform.getDebugPathSanitizer().getCompilationDirectory();
    // Use the hostname for padding instead of the directory, this way the directory matches without
    // having to resolve it.
    String sourceDir = Strings.padStart(
        ":" + projectFilesystem.getRootPath().toString(),
        compDir.length(),
        'f');

    Optional<String> productName = getProductNameForTargetNode(targetNode);
    String binaryName = AppleBundle.getBinaryName(targetNode.getBuildTarget(), productName);
    Path bundleDestination =
        getScratchPathForAppBundle(projectFilesystem, targetNode.getBuildTarget(), binaryName);
    Path dsymDestination =
        getScratchPathForDsymBundle(projectFilesystem, targetNode.getBuildTarget(), binaryName);
    Path resolvedBundleDestination = projectFilesystem.resolve(bundleDestination);
    Path resolvedDsymDestination = projectFilesystem.resolve(dsymDestination);
    Path fixUUIDScriptPath = getFixUUIDScriptPath(projectFilesystem);

    if (attemptToDetermineBestCxxPlatform) {
      template.add("buck_flavor", XCODE_BUILD_SCRIPT_FLAVOR_VALUE);
    } else {
      template.add("buck_flavor", "");
    }
    template.add("path_to_buck", getPathToBuck(executableFinder, environment));
    template.add("buck_target", targetNode.getBuildTarget().getFullyQualifiedName());
    template.add("root_path", projectFilesystem.getRootPath());

    template.add("comp_dir", compDir);
    template.add("source_dir", sourceDir);

    template.add("resolved_bundle_destination", resolvedBundleDestination);
    template.add("resolved_bundle_destination_parent", resolvedBundleDestination.getParent());
    template.add("resolved_dsym_destination", resolvedDsymDestination);
    template.add("binary_name", binaryName);
    template.add("path_to_fix_uuid_script", fixUUIDScriptPath);

    return template.render();
  }

  private String getCodesignShellScript(TargetNode<?> targetNode) {
    ST template;
    try {
      template = new ST(Resources.toString(
          Resources.getResource(ProjectGenerator.class, CODESIGN_TEMPLATE), Charsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException(
          "There was an error loading '" + CODESIGN_TEMPLATE + "' template", e);
    }

    Optional<String> productName = getProductNameForTargetNode(targetNode);
    String binaryName = AppleBundle.getBinaryName(targetNode.getBuildTarget(), productName);
    Path bundleDestination =
        getScratchPathForAppBundle(projectFilesystem, targetNode.getBuildTarget(), binaryName);
    Path resolvedBundleDestination = projectFilesystem.resolve(bundleDestination);

    template.add("root_path", projectFilesystem.getRootPath());
    template.add("path_to_codesign_script", getCodesignScriptPath(projectFilesystem));
    template.add("app_bundle_path", resolvedBundleDestination);

    return template.render();
  }

  static Path getScratchPathForAppBundle(
      ProjectFilesystem filesystem,
      BuildTarget targetToBuildWithBuck,
      String binaryName) {
    return BuildTargets
        .getScratchPath(filesystem, targetToBuildWithBuck, "/%s-unsanitised")
        .resolve(binaryName + ".app");
  }

  static Path getScratchPathForDsymBundle(
      ProjectFilesystem filesystem,
      BuildTarget targetToBuildWithBuck,
      String binaryName) {
    return BuildTargets
        .getScratchPath(filesystem, targetToBuildWithBuck, "/%s-unsanitised")
        .resolve(binaryName + ".dSYM");
  }

  @SuppressWarnings("unchecked")
  private Optional<PBXTarget> generateProjectTarget(TargetNode<?> targetNode)
      throws IOException {
    Preconditions.checkState(
        isBuiltByCurrentProject(targetNode.getBuildTarget()),
        "should not generate rule if it shouldn't be built by current project");
    Optional<PBXTarget> result = Optional.absent();
    if (targetNode.getType().equals(AppleLibraryDescription.TYPE)) {
      result = Optional.<PBXTarget>of(
          generateAppleLibraryTarget(
              project,
              (TargetNode<AppleNativeTargetDescriptionArg>) targetNode,
              Optional.<TargetNode<AppleBundleDescription.Arg>>absent()));
    } else if (targetNode.getType().equals(CxxLibraryDescription.TYPE)) {
      result = Optional.<PBXTarget>of(
          generateCxxLibraryTarget(
              project,
              (TargetNode<CxxLibraryDescription.Arg>) targetNode,
              ImmutableSet.<AppleResourceDescription.Arg>of(),
              ImmutableSet.<AppleAssetCatalogDescription.Arg>of(),
              Optional.<TargetNode<AppleBundleDescription.Arg>>absent()));
    } else if (targetNode.getType().equals(AppleBinaryDescription.TYPE)) {
      result = Optional.<PBXTarget>of(
          generateAppleBinaryTarget(
              project,
              (TargetNode<AppleNativeTargetDescriptionArg>) targetNode));
    } else if (targetNode.getType().equals(AppleBundleDescription.TYPE)) {
      TargetNode<AppleBundleDescription.Arg> bundleTargetNode =
          (TargetNode<AppleBundleDescription.Arg>) targetNode;
      result = Optional.<PBXTarget>of(
          generateAppleBundleTarget(
              project,
              bundleTargetNode,
              (TargetNode<AppleNativeTargetDescriptionArg>)
                  targetGraph.get(bundleTargetNode.getConstructorArg().binary),
              Optional.<TargetNode<AppleBundleDescription.Arg>>absent()));
    } else if (targetNode.getType().equals(AppleTestDescription.TYPE)) {
      result = generateAppleTestTarget((TargetNode<AppleTestDescription.Arg>) targetNode);
    } else if (targetNode.getType().equals(AppleResourceDescription.TYPE)) {
      checkAppleResourceTargetNodeReferencingValidContents(
          (TargetNode<AppleResourceDescription.Arg>) targetNode);
    } else if (targetNode.getType().equals(HalideLibraryDescription.TYPE)) {
      TargetNode<HalideLibraryDescription.Arg> halideTargetNode =
          (TargetNode<HalideLibraryDescription.Arg>) targetNode;
      BuildTarget buildTarget = targetNode.getBuildTarget();

      // The generated target just runs a shell script that invokes the "compiler" with the
      // correct target architecture.
      result = generateHalideLibraryTarget(project, halideTargetNode);

      // Make sure the compiler gets built at project time, since we'll need
      // it to generate the shader code during the Xcode build.
      requiredBuildTargetsBuilder.add(
          HalideLibraryDescription.createHalideCompilerBuildTarget(buildTarget));

      // Also, run the compiler once at project time to generate the header
      // file needed for compilation.
      requiredBuildTargetsBuilder.add(
          buildTarget.withFlavors(
              HalideLibraryDescription.HALIDE_COMPILE_FLAVOR,
              defaultCxxPlatform.getFlavor()));
    }
    buckEventBus.post(ProjectGenerationEvent.processed());
    return result;
  }

  private static Path getHalideOutputPath(ProjectFilesystem filesystem, BuildTarget target) {
    return filesystem.getBuckPaths().getBuckOut()
        .resolve("halide")
        .resolve(target.getBasePath())
        .resolve(target.getShortName());
  }

  private Optional<PBXTarget> generateHalideLibraryTarget(
      PBXProject project,
      TargetNode<HalideLibraryDescription.Arg> targetNode) throws IOException {
    final BuildTarget buildTarget = targetNode.getBuildTarget();
    String productName = getProductNameForBuildTarget(buildTarget);
    Path outputPath =
        getHalideOutputPath(targetNode.getRuleFactoryParams().getProjectFilesystem(), buildTarget);

    Path scriptPath = halideBuckConfig.getXcodeCompileScriptPath();
    Optional<String> script = projectFilesystem.readFileIfItExists(scriptPath);
    PBXShellScriptBuildPhase scriptPhase = new PBXShellScriptBuildPhase();
    scriptPhase.setShellScript(script.or(""));

    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        pathRelativizer,
        sourcePathResolver);
    mutator
        .setTargetName(getXcodeTargetName(buildTarget))
        .setProduct(ProductType.STATIC_LIBRARY, productName, outputPath)
        .setPreBuildRunScriptPhases(ImmutableList.of(scriptPhase));

    NewNativeTargetProjectMutator.Result targetBuilderResult;
    try {
      targetBuilderResult = mutator.buildTargetAndAddToProject(project);
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e);
    }

    BuildTarget compilerTarget =
        HalideLibraryDescription.createHalideCompilerBuildTarget(buildTarget);
    Path compilerPath = BuildTargets.getGenPath(projectFilesystem, compilerTarget, "%s");
    ImmutableMap<String, String> appendedConfig = ImmutableMap.<String, String>of();
    ImmutableMap<String, String> extraSettings = ImmutableMap.<String, String>of();
    ImmutableMap.Builder<String, String> defaultSettingsBuilder =
        ImmutableMap.builder();
    defaultSettingsBuilder.put(
        "REPO_ROOT",
        projectFilesystem.getRootPath().toAbsolutePath().normalize().toString());
    defaultSettingsBuilder.put("HALIDE_COMPILER_PATH", compilerPath.toString());

    // pass the source list to the xcode script
    String halideCompilerSrcs = "";
    if (targetNode.getConstructorArg().srcs.isPresent()) {
      Iterable<Path> compilerSrcFiles =
          Iterables.transform(
              targetNode.getConstructorArg().srcs.get(),
              new Function<SourceWithFlags, Path>() {
                @Override
                public Path apply(SourceWithFlags input) {
                  return sourcePathResolver.apply(input.getSourcePath());
                }
              }
          );
      halideCompilerSrcs = Joiner.on(" ").join(compilerSrcFiles);
    }
    defaultSettingsBuilder.put("HALIDE_COMPILER_SRCS", halideCompilerSrcs);
    String halideCompilerFlags = "";
    if (targetNode.getConstructorArg().compilerFlags.isPresent()) {
      halideCompilerFlags = Joiner.on(" ").join(targetNode.getConstructorArg().compilerFlags.get());
    }
    defaultSettingsBuilder.put("HALIDE_COMPILER_FLAGS", halideCompilerFlags);

    defaultSettingsBuilder.put("HALIDE_OUTPUT_PATH", outputPath.toString());
    defaultSettingsBuilder.put("HALIDE_FUNC_NAME", buildTarget.getShortName());
    defaultSettingsBuilder.put(PRODUCT_NAME, productName);

    Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs =
        getXcodeBuildConfigurationsForTargetNode(
            targetNode,
            appendedConfig);
    PBXNativeTarget target = targetBuilderResult.target;
    setTargetBuildConfigurations(
        getConfigurationNameToXcconfigPath(buildTarget),
        target,
        project.getMainGroup(),
        configs.get(),
        extraSettings,
        defaultSettingsBuilder.build(),
        appendedConfig);
    return Optional.<PBXTarget>of(target);
  }

  @SuppressWarnings("unchecked")
  private Optional<PBXTarget> generateAppleTestTarget(
      TargetNode<AppleTestDescription.Arg> testTargetNode) throws IOException {
    Optional<TargetNode<AppleBundleDescription.Arg>> testHostBundle;
    if (testTargetNode.getConstructorArg().testHostApp.isPresent()) {
      BuildTarget testHostBundleTarget =
          testTargetNode.getConstructorArg().testHostApp.get();
      TargetNode<?> testHostBundleNode = targetGraph.get(testHostBundleTarget);
      if (testHostBundleNode.getType() != AppleBundleDescription.TYPE) {
        throw new HumanReadableException(
            "The test host target '%s' has the wrong type (%s), must be apple_bundle",
            testHostBundleTarget,
            testHostBundleNode.getType());
      }
      testHostBundle = Optional.of((TargetNode<AppleBundleDescription.Arg>) testHostBundleNode);
    } else {
      testHostBundle = Optional.absent();
    }
    if (testsToGenerateAsStaticLibraries.contains(testTargetNode)) {
      return Optional.<PBXTarget>of(
          generateAppleLibraryTarget(
              project,
              testTargetNode,
              testHostBundle));
    } else {
      return Optional.<PBXTarget>of(
          generateAppleBundleTarget(
              project,
              testTargetNode,
              testTargetNode,
              testHostBundle));
    }
  }

  private void checkAppleResourceTargetNodeReferencingValidContents(
      TargetNode<AppleResourceDescription.Arg> resource) {
    // Check that the resource target node is referencing valid files or directories.
    // If a SourcePath is a BuildTargetSourcePath (or some hypothetical future implementation of
    // AbstractSourcePath), just assume it's the right type; we have no way of checking now as it
    // may not exist yet.
    AppleResourceDescription.Arg arg = resource.getConstructorArg();
    for (SourcePath dir : arg.dirs) {
      if (dir instanceof PathSourcePath &&
          !projectFilesystem.isDirectory(sourcePathResolver.apply(dir))) {
        throw new HumanReadableException(
            "%s specified in the dirs parameter of %s is not a directory",
            dir.toString(), resource.toString());
      }
    }
    for (SourcePath file : arg.files) {
      if (file instanceof PathSourcePath &&
          !projectFilesystem.isFile(sourcePathResolver.apply(file))) {
        throw new HumanReadableException(
            "%s specified in the files parameter of %s is not a regular file",
            file.toString(), resource.toString());
      }
    }
  }

  PBXNativeTarget generateAppleBundleTarget(
      PBXProject project,
      TargetNode<? extends HasAppleBundleFields> targetNode,
      TargetNode<? extends AppleNativeTargetDescriptionArg> binaryNode,
      Optional<TargetNode<AppleBundleDescription.Arg>> bundleLoaderNode)
      throws IOException {
    Path infoPlistPath =
        Preconditions.checkNotNull(
            sourcePathResolver.apply(targetNode.getConstructorArg().getInfoPlist()));

    // -- copy any binary and bundle targets into this bundle
    Iterable<TargetNode<?>> copiedRules = AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
        targetGraph,
        AppleBuildRules.RecursiveDependenciesMode.COPYING,
        targetNode,
        Optional.of(AppleBuildRules.XCODE_TARGET_BUILD_RULE_TYPES));
    if (bundleRequiresRemovalOfAllTransitiveFrameworks(targetNode)) {
      copiedRules = rulesWithoutFrameworkBundles(copiedRules);
    } else if (bundleRequiresAllTransitiveFrameworks(binaryNode)) {
      copiedRules = ImmutableSet.<TargetNode<?>>builder()
          .addAll(copiedRules)
          .addAll(getTransitiveFrameworkNodes(targetNode))
          .build();
    }

    if (bundleLoaderNode.isPresent()) {
      copiedRules = rulesWithoutBundleLoader(copiedRules, bundleLoaderNode.get());
    }

    ImmutableList<PBXBuildPhase> copyFilesBuildPhases = getCopyFilesBuildPhases(copiedRules);

    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.of(targetNode),
        binaryNode,
        bundleToTargetProductType(targetNode, binaryNode),
        "%s." + getExtensionString(targetNode.getConstructorArg().getExtension()),
        Optional.of(infoPlistPath),
        /* includeFrameworks */ true,
        AppleResources.collectRecursiveResources(targetGraph, ImmutableList.of(targetNode)),
        AppleResources.collectDirectResources(targetGraph, targetNode),
        AppleBuildRules.collectRecursiveAssetCatalogs(targetGraph, ImmutableList.of(targetNode)),
        AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
        Optional.<Iterable<PBXBuildPhase>>of(copyFilesBuildPhases), bundleLoaderNode);

    LOG.debug("Generated iOS bundle target %s", target);
    return target;
  }

  private ImmutableSet<TargetNode<?>> getTransitiveFrameworkNodes(
      TargetNode<? extends HasAppleBundleFields> targetNode) {
    GraphTraversable<TargetNode<?>> graphTraversable = new GraphTraversable<TargetNode<?>>() {
      @Override
      public Iterator<TargetNode<?>> findChildren(TargetNode<?> node) {
        if (node.getType() != AppleResourceDescription.TYPE) {
          return targetGraph.getAll(node.getDeps()).iterator();
        } else {
          return Collections.emptyIterator();
        }
      }
    };

    final ImmutableSet.Builder<TargetNode<?>> filteredRules = ImmutableSet.builder();
    AcyclicDepthFirstPostOrderTraversal<TargetNode<?>> traversal =
        new AcyclicDepthFirstPostOrderTraversal<>(graphTraversable);
    try {
      for (TargetNode<?> node : traversal.traverse(ImmutableList.of(targetNode))) {
        if (node != targetNode) {
          Optional<TargetNode<AppleBundleDescription.Arg>> appleBundleNode =
              node.castArg(AppleBundleDescription.Arg.class);
          if (appleBundleNode.isPresent() &&
              isFrameworkBundle(appleBundleNode.get().getConstructorArg())) {
            filteredRules.add(node);
          }
        }
      }
    } catch (AcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new RuntimeException(e);
    }
    return filteredRules.build();
  }

  /**
   * Returns a new list of rules which does not contain framework bundles.
   */
  private ImmutableList<TargetNode<?>> rulesWithoutFrameworkBundles(
      Iterable<TargetNode<?>> copiedRules) {
    return FluentIterable.from(copiedRules).filter(new Predicate<TargetNode<?>>() {
      @Override
      public boolean apply(TargetNode<?> input) {
        Optional<TargetNode<AppleBundleDescription.Arg>> appleBundleNode =
            input.castArg(AppleBundleDescription.Arg.class);
        if (appleBundleNode.isPresent()) {
          return !isFrameworkBundle(appleBundleNode.get().getConstructorArg());
        }
        return true;
      }
    }).toList();
  }

  private ImmutableList<TargetNode<?>> rulesWithoutBundleLoader(
      Iterable<TargetNode<?>> copiedRules,
      TargetNode<?> bundleLoader) {
    return FluentIterable
        .from(copiedRules)
        .filter(Predicates.not(Predicates.<TargetNode<?>>equalTo(bundleLoader)))
        .toList();
  }

  private PBXNativeTarget generateAppleBinaryTarget(
      PBXProject project,
      TargetNode<AppleNativeTargetDescriptionArg> targetNode)
      throws IOException {
    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.<TargetNode<AppleBundleDescription.Arg>>absent(),
        targetNode,
        ProductType.TOOL,
        "%s",
        Optional.<Path>absent(),
        /* includeFrameworks */ true,
        ImmutableSet.<AppleResourceDescription.Arg>of(),
        AppleResources.collectDirectResources(targetGraph, targetNode),
        ImmutableSet.<AppleAssetCatalogDescription.Arg>of(),
        AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
        Optional.<Iterable<PBXBuildPhase>>absent(),
        Optional.<TargetNode<AppleBundleDescription.Arg>>absent());
    LOG.debug("Generated Apple binary target %s", target);
    return target;
  }

  private PBXNativeTarget generateAppleLibraryTarget(
      PBXProject project,
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode,
      Optional<TargetNode<AppleBundleDescription.Arg>> bundleLoaderNode)
      throws IOException {
    PBXNativeTarget target = generateCxxLibraryTarget(
        project,
        targetNode,
        AppleResources.collectDirectResources(targetGraph, targetNode),
        AppleBuildRules.collectDirectAssetCatalogs(targetGraph, targetNode),
        bundleLoaderNode);
    LOG.debug("Generated iOS library target %s", target);
    return target;
  }

  private PBXNativeTarget generateCxxLibraryTarget(
      PBXProject project,
      TargetNode<? extends CxxLibraryDescription.Arg> targetNode,
      ImmutableSet<AppleResourceDescription.Arg> directResources,
      ImmutableSet<AppleAssetCatalogDescription.Arg> directAssetCatalogs,
      Optional<TargetNode<AppleBundleDescription.Arg>> bundleLoaderNode)
      throws IOException {
    boolean isShared = targetNode
        .getBuildTarget()
        .getFlavors()
        .contains(CxxDescriptionEnhancer.SHARED_FLAVOR);
    ProductType productType = isShared ?
        ProductType.DYNAMIC_LIBRARY :
        ProductType.STATIC_LIBRARY;
    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.<TargetNode<AppleBundleDescription.Arg>>absent(),
        targetNode,
        productType,
        AppleBuildRules.getOutputFileNameFormatForLibrary(isShared),
        Optional.<Path>absent(),
        /* includeFrameworks */ isShared,
        ImmutableSet.<AppleResourceDescription.Arg>of(),
        directResources,
        ImmutableSet.<AppleAssetCatalogDescription.Arg>of(),
        directAssetCatalogs,
        Optional.<Iterable<PBXBuildPhase>>absent(),
        bundleLoaderNode);
    LOG.debug("Generated Cxx library target %s", target);
    return target;
  }

  private PBXNativeTarget generateBinaryTarget(
      PBXProject project,
      Optional<? extends TargetNode<? extends HasAppleBundleFields>> bundle,
      TargetNode<? extends CxxLibraryDescription.Arg> targetNode,
      ProductType productType,
      String productOutputFormat,
      Optional<Path> infoPlistOptional,
      boolean includeFrameworks,
      ImmutableSet<AppleResourceDescription.Arg> recursiveResources,
      ImmutableSet<AppleResourceDescription.Arg> directResources,
      ImmutableSet<AppleAssetCatalogDescription.Arg> recursiveAssetCatalogs,
      ImmutableSet<AppleAssetCatalogDescription.Arg> directAssetCatalogs,
      Optional<Iterable<PBXBuildPhase>> copyFilesPhases,
      Optional<TargetNode<AppleBundleDescription.Arg>> bundleLoaderNode)
      throws IOException {

    LOG.debug("Generating binary target for node %s", targetNode);

    TargetNode<?> buildTargetNode = bundle.isPresent() ? bundle.get() : targetNode;
    final BuildTarget buildTarget = buildTargetNode.getBuildTarget();

    String buildTargetName = getProductNameForBuildTarget(buildTarget);
    CxxLibraryDescription.Arg arg = targetNode.getConstructorArg();
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        pathRelativizer,
        sourcePathResolver);
    ImmutableSet<SourcePath> exportedHeaders =
        ImmutableSet.copyOf(getHeaderSourcePaths(arg.exportedHeaders));
    ImmutableSet<SourcePath> headers = ImmutableSet.copyOf(getHeaderSourcePaths(arg.headers));
    ImmutableMap<CxxSource.Type, ImmutableList<String>> langPreprocessorFlags =
        targetNode.getConstructorArg().langPreprocessorFlags.get();

    mutator
        .setTargetName(getXcodeTargetName(buildTarget))
        .setProduct(
            productType,
            buildTargetName,
            Paths.get(String.format(productOutputFormat, buildTargetName)));

    boolean isFocusedOnTarget = shouldIncludeBuildTargetIntoFocusedProject(buildTarget);
    if (isFocusedOnTarget) {
      mutator
          .setLangPreprocessorFlags(langPreprocessorFlags)
          .setPublicHeaders(exportedHeaders)
          .setPrefixHeader(arg.prefixHeader)
          .setSourcesWithFlags(ImmutableSet.copyOf(arg.srcs.get()))
          .setPrivateHeaders(headers)
          .setRecursiveResources(recursiveResources)
          .setDirectResources(directResources);
    }

    if (bundle.isPresent() && isFocusedOnTarget) {
      HasAppleBundleFields bundleArg = bundle.get().getConstructorArg();
      mutator.setInfoPlist(Optional.of(bundleArg.getInfoPlist()));
    }

    Optional<TargetNode<AppleNativeTargetDescriptionArg>> appleTargetNode =
        targetNode.castArg(AppleNativeTargetDescriptionArg.class);
    if (appleTargetNode.isPresent() && isFocusedOnTarget) {
      AppleNativeTargetDescriptionArg appleArg = appleTargetNode.get().getConstructorArg();
      mutator = mutator
          .setExtraXcodeSources(ImmutableSet.copyOf(appleArg.extraXcodeSources.get()));
    }

    if (options.contains(Option.CREATE_DIRECTORY_STRUCTURE) && isFocusedOnTarget) {
      mutator.setTargetGroupPath(
          FluentIterable
              .from(buildTarget.getBasePath())
              .transform(Functions.toStringFunction())
              .toList());
    }

    if (!recursiveAssetCatalogs.isEmpty() && isFocusedOnTarget) {
      mutator.setRecursiveAssetCatalogs(recursiveAssetCatalogs);
    }

    if (!directAssetCatalogs.isEmpty() && isFocusedOnTarget) {
      mutator.setDirectAssetCatalogs(directAssetCatalogs);
    }

    if (includeFrameworks && isFocusedOnTarget) {
      ImmutableSet.Builder<FrameworkPath> frameworksBuilder = ImmutableSet.builder();
      frameworksBuilder.addAll(targetNode.getConstructorArg().frameworks.get());
      frameworksBuilder.addAll(targetNode.getConstructorArg().libraries.get());
      frameworksBuilder.addAll(collectRecursiveFrameworkDependencies(ImmutableList.of(targetNode)));
      mutator.setFrameworks(frameworksBuilder.build());

      mutator.setArchives(
          collectRecursiveLibraryDependencies(ImmutableList.of(targetNode)));
    }

    // TODO(Task #3772930): Go through all dependencies of the rule
    // and add any shell script rules here
    ImmutableList.Builder<TargetNode<?>> preScriptPhases = ImmutableList.builder();
    ImmutableList.Builder<TargetNode<?>> postScriptPhases = ImmutableList.builder();
    if (bundle.isPresent() && targetNode != bundle.get() && isFocusedOnTarget) {
      collectBuildScriptDependencies(
          targetGraph.getAll(bundle.get().getDeclaredDeps()),
          preScriptPhases,
          postScriptPhases);
    }
    collectBuildScriptDependencies(
        targetGraph.getAll(targetNode.getDeclaredDeps()),
        preScriptPhases,
        postScriptPhases);
    if (isFocusedOnTarget) {
      mutator.setPreBuildRunScriptPhasesFromTargetNodes(preScriptPhases.build());
      if (copyFilesPhases.isPresent()) {
        mutator.setCopyFilesPhases(copyFilesPhases.get());
      }
      mutator.setPostBuildRunScriptPhasesFromTargetNodes(postScriptPhases.build());
    }

    NewNativeTargetProjectMutator.Result targetBuilderResult;
    try {
      targetBuilderResult = mutator.buildTargetAndAddToProject(project);
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e);
    }
    PBXGroup targetGroup = targetBuilderResult.targetGroup;

    SourceTreePath buckFilePath = new SourceTreePath(
        PBXReference.SourceTree.SOURCE_ROOT,
        pathRelativizer.outputPathToBuildTargetPath(buildTarget).resolve(buildFileName),
        Optional.<String>absent());
    PBXFileReference buckReference =
        targetGroup.getOrCreateFileReferenceBySourceTreePath(buckFilePath);
    buckReference.setExplicitFileType(Optional.of("text.script.python"));

    // -- configurations
    ImmutableMap.Builder<String, String> extraSettingsBuilder = ImmutableMap.builder();
    extraSettingsBuilder
        .put("TARGET_NAME", buildTargetName)
        .put("SRCROOT", pathRelativizer.outputPathToBuildTargetPath(buildTarget).toString());
    if (bundleLoaderNode.isPresent() && isFocusedOnTarget) {
      TargetNode<AppleBundleDescription.Arg> bundleLoader = bundleLoaderNode.get();
      String bundleLoaderProductName = getProductNameForBuildTarget(bundleLoader.getBuildTarget());
      String bundleLoaderBundleName = bundleLoaderProductName + "." +
          getExtensionString(bundleLoader.getConstructorArg().getExtension());
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

      // The conditional that decdies which path is used. This is a complex Xcode build setting
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
      String bundleLoaderOutputPathDeepSetting = "BUNDLE_LOADER_BUNDLE_STYLE_CONDITIONAL_Contents";
      String bundleLoaderOutputPathDeepValue = "Contents/MacOS/";

      String bundleLoaderOutputPathValue = Joiner.on('/').join(
          getTargetOutputPath(bundleLoader),
          bundleLoaderBundleName,
          bundleLoaderOutputPathConditional,
          bundleLoaderProductName);

      extraSettingsBuilder
          .put(bundleLoaderOutputPathDeepSetting, bundleLoaderOutputPathDeepValue)
          .put("BUNDLE_LOADER", bundleLoaderOutputPathValue)
          .put("TEST_HOST", "$(BUNDLE_LOADER)");
    }
    if (infoPlistOptional.isPresent()) {
      Path infoPlistPath = pathRelativizer.outputDirToRootRelative(infoPlistOptional.get());
      extraSettingsBuilder.put("INFOPLIST_FILE", infoPlistPath.toString());
    }
    Optional<SourcePath> prefixHeaderOptional = targetNode.getConstructorArg().prefixHeader;
    if (prefixHeaderOptional.isPresent()) {
      Path prefixHeaderRelative = sourcePathResolver.apply(prefixHeaderOptional.get());
      Path prefixHeaderPath = pathRelativizer.outputDirToRootRelative(prefixHeaderRelative);
      extraSettingsBuilder.put("GCC_PREFIX_HEADER", prefixHeaderPath.toString());
      extraSettingsBuilder.put("GCC_PRECOMPILE_PREFIX_HEADER", "YES");
    }
    extraSettingsBuilder.put("USE_HEADERMAP", "NO");

    ImmutableMap.Builder<String, String> defaultSettingsBuilder = ImmutableMap.builder();
    defaultSettingsBuilder.put(
        "REPO_ROOT",
        projectFilesystem.getRootPath().toAbsolutePath().normalize().toString());
    defaultSettingsBuilder.put(PRODUCT_NAME, getProductName(buildTargetNode, buildTarget));
    if (bundle.isPresent()) {
      defaultSettingsBuilder.put(
          "WRAPPER_EXTENSION",
          getExtensionString(bundle.get().getConstructorArg().getExtension()));
    }

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
    if (!bundle.isPresent() &&
        (targetNode.getType().equals(AppleLibraryDescription.TYPE) ||
            targetNode.getType().equals(CxxLibraryDescription.TYPE))) {
      defaultSettingsBuilder.put("EXECUTABLE_PREFIX", "lib");
    }

    ImmutableMap.Builder<String, String> appendConfigsBuilder = ImmutableMap.builder();

    ImmutableSet<Path> recursiveHeaderSearchPaths = collectRecursiveHeaderSearchPaths(targetNode);
    ImmutableSet<Path> headerMapBases = recursiveHeaderSearchPaths.isEmpty() ?
        ImmutableSet.<Path>of() :
        ImmutableSet.of(
            pathRelativizer.outputDirToRootRelative(
                buildTargetNode.getRuleFactoryParams().getProjectFilesystem()
                    .getBuckPaths().getBuckOut()));

    appendConfigsBuilder
        .put(
            "HEADER_SEARCH_PATHS",
            Joiner.on(' ').join(Iterables.concat(recursiveHeaderSearchPaths, headerMapBases)))
        .put(
            "LIBRARY_SEARCH_PATHS",
            Joiner.on(' ').join(collectRecursiveLibrarySearchPaths(ImmutableSet.of(targetNode))))
        .put(
            "FRAMEWORK_SEARCH_PATHS",
            Joiner.on(' ').join(
                collectRecursiveFrameworkSearchPaths(ImmutableList.of(targetNode))));
    if (isFocusedOnTarget) {
      appendConfigsBuilder
          .put(
              "OTHER_CFLAGS",
              Joiner
                  .on(' ')
                  .join(
                      Iterables.transform(
                          Iterables.concat(
                              cxxBuckConfig.getFlags("cflags").or(DEFAULT_CFLAGS),
                              collectRecursiveExportedPreprocessorFlags(
                                  ImmutableList.of(targetNode)),
                              targetNode.getConstructorArg().compilerFlags.get(),
                              targetNode.getConstructorArg().preprocessorFlags.get()),
                          Escaper.BASH_ESCAPER)))
          .put(
              "OTHER_CPLUSPLUSFLAGS",
              Joiner
                  .on(' ')
                  .join(
                      Iterables.transform(
                          Iterables.concat(
                              cxxBuckConfig.getFlags("cxxflags").or(DEFAULT_CXXFLAGS),
                              collectRecursiveExportedPreprocessorFlags(
                                  ImmutableList.of(targetNode)),
                              targetNode.getConstructorArg().compilerFlags.get(),
                              targetNode.getConstructorArg().preprocessorFlags.get()),
                          Escaper.BASH_ESCAPER)))
          .put(
              "OTHER_LDFLAGS",
              Joiner
                  .on(' ')
                  .join(
                      Iterables.transform(
                          Iterables.concat(
                              targetNode.getConstructorArg().linkerFlags.get(),
                              collectRecursiveExportedLinkerFlags(
                                  ImmutableList.of(targetNode))),
                          Escaper.BASH_ESCAPER)));
    }

    ImmutableMap<String, String> appendedConfig = appendConfigsBuilder.build();

    PBXNativeTarget target = targetBuilderResult.target;

    if (isFocusedOnTarget) {
      Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs =
          getXcodeBuildConfigurationsForTargetNode(
              targetNode,
              appendedConfig);
      setTargetBuildConfigurations(
          getConfigurationNameToXcconfigPath(buildTarget),
          target,
          project.getMainGroup(),
          configs.get(),
          extraSettingsBuilder.build(),
          defaultSettingsBuilder.build(),
          appendedConfig);
    }

    // -- phases
    createHeaderSymlinkTree(
        sourcePathResolver,
        getPublicCxxHeaders(targetNode),
        AppleDescriptions.getPathToHeaderSymlinkTree(targetNode,
            HeaderVisibility.PUBLIC));
    createHeaderSymlinkTree(
        sourcePathResolver,
        getPrivateCxxHeaders(targetNode),
        AppleDescriptions.getPathToHeaderSymlinkTree(targetNode,
            HeaderVisibility.PRIVATE));

    if (appleTargetNode.isPresent()) {
      // Use Core Data models from immediate dependencies only.
      addCoreDataModelsIntoTarget(appleTargetNode.get(), targetGroup);
    }

    return target;
  }

  private boolean shouldIncludeBuildTargetIntoFocusedProject(BuildTarget buildTarget) {
    if (focusModules.isEmpty()) {
      return true;
    }

    if (targetToBuildWithBuck.isPresent() && buildTarget.equals(targetToBuildWithBuck.get())) {
      return true;
    }

    for (BuildTarget target : focusModules) {
      if (buildTarget.equals(target)) {
        return true;
      }
    }
    return false;
  }

  public static String getProductName(TargetNode<?> buildTargetNode, BuildTarget buildTarget) {
    return getProductNameForTargetNode(buildTargetNode).or(
        getProductNameForBuildTarget(buildTarget));
  }

  private ImmutableSortedMap<Path, SourcePath> getPublicCxxHeaders(
      TargetNode<? extends CxxLibraryDescription.Arg> targetNode) {
    CxxLibraryDescription.Arg arg = targetNode.getConstructorArg();
    if (arg instanceof AppleNativeTargetDescriptionArg) {
      Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(
          (AppleNativeTargetDescriptionArg) arg,
          targetNode.getBuildTarget());
      ImmutableSortedMap<String, SourcePath> cxxHeaders =
          AppleDescriptions.convertAppleHeadersToPublicCxxHeaders(
              sourcePathResolver,
              headerPathPrefix,
              arg);
      return convertMapKeysToPaths(cxxHeaders);
    } else {
      SourcePathResolver sourcePathResolver = sourcePathResolverForNode.apply(targetNode);
      return ImmutableSortedMap.copyOf(
          CxxDescriptionEnhancer.parseExportedHeaders(
              targetNode.getBuildTarget(),
              sourcePathResolver,
              Optional.<CxxPlatform>absent(),
              arg));
    }
  }

  private ImmutableSortedMap<Path, SourcePath> getPrivateCxxHeaders(
      TargetNode<? extends CxxLibraryDescription.Arg> targetNode) {
    CxxLibraryDescription.Arg arg = targetNode.getConstructorArg();
    if (arg instanceof AppleNativeTargetDescriptionArg) {
      Path headerPathPrefix = AppleDescriptions.getHeaderPathPrefix(
          (AppleNativeTargetDescriptionArg) arg,
          targetNode.getBuildTarget());
      ImmutableSortedMap<String, SourcePath> cxxHeaders =
          AppleDescriptions.convertAppleHeadersToPrivateCxxHeaders(
              sourcePathResolver,
              headerPathPrefix,
              arg);
      return convertMapKeysToPaths(cxxHeaders);
    } else {
      SourcePathResolver sourcePathResolver = sourcePathResolverForNode.apply(targetNode);
      return ImmutableSortedMap.copyOf(
          CxxDescriptionEnhancer.parseHeaders(
              targetNode.getBuildTarget(),
              sourcePathResolver,
              Optional.<CxxPlatform>absent(),
              arg));
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
      TargetNode<?> targetNode,
      ImmutableMap<String, String> appendedConfig) {
    Optional<ImmutableSortedMap<String, ImmutableMap<String, String>>> configs = Optional.absent();
    Optional<TargetNode<AppleNativeTargetDescriptionArg>> appleTargetNode =
        targetNode.castArg(AppleNativeTargetDescriptionArg.class);
    Optional<TargetNode<HalideLibraryDescription.Arg>> halideTargetNode =
        targetNode.castArg(HalideLibraryDescription.Arg.class);
    if (appleTargetNode.isPresent()) {
      configs = appleTargetNode.get().getConstructorArg().configs;
    } else if (halideTargetNode.isPresent()) {
      configs = halideTargetNode.get().getConstructorArg().configs;
    }
    if (!configs.isPresent() ||
        (configs.isPresent() && configs.get().isEmpty()) ||
        targetNode.getType().equals(CxxLibraryDescription.TYPE)) {
      ImmutableMap<String, ImmutableMap<String, String>> defaultConfig =
          CxxPlatformXcodeConfigGenerator.getDefaultXcodeBuildConfigurationsFromCxxPlatform(
              defaultCxxPlatform,
              appendedConfig);
      configs = Optional.of(ImmutableSortedMap.copyOf(defaultConfig));
    }
    return configs;
  }

  private void addCoreDataModelsIntoTarget(
      TargetNode<? extends CxxLibraryDescription.Arg> targetNode,
      PBXGroup targetGroup) throws IOException {
    addCoreDataModelBuildPhase(
        targetGroup,
        FluentIterable
            .from(targetNode.getDeps())
            .transform(
                new Function<BuildTarget, TargetNode<?>>() {
                  @Override
                  public TargetNode<?> apply(BuildTarget input) {
                    return targetGraph.get(input);
                  }
                })
            .filter(
                new Predicate<TargetNode<?>>() {
                  @Override
                  public boolean apply(TargetNode<?> input) {
                    return CoreDataModelDescription.TYPE.equals(input.getType());
                  }
                })
            .transform(
                new Function<TargetNode<?>, CoreDataModelDescription.Arg>() {
                  @Override
                  public CoreDataModelDescription.Arg apply(TargetNode<?> input) {
                    return (CoreDataModelDescription.Arg) input.getConstructorArg();
                  }
                })
            .toSet());
  }

  private Function<String, Path> getConfigurationNameToXcconfigPath(final BuildTarget buildTarget) {
    return new Function<String, Path>() {
      @Override
      public Path apply(String input) {
        return BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s-" + input + ".xcconfig");
      }
    };
  }

  private Iterable<SourcePath> getHeaderSourcePaths(
      Optional<SourceList> headers) {
    if (!headers.isPresent()) {
      return ImmutableList.of();
    } else if (headers.get().getUnnamedSources().isPresent()) {
      return headers.get().getUnnamedSources().get();
    } else {
      return headers.get().getNamedSources().get().values();
    }
  }

  private void generateCombinedTestTarget(
      final String productName,
      AppleTestBundleParamsKey key,
      ImmutableCollection<TargetNode<AppleTestDescription.Arg>> tests)
      throws IOException {
    ImmutableSet.Builder<PBXFileReference> testLibs = ImmutableSet.builder();
    for (TargetNode<AppleTestDescription.Arg> test : tests) {
      testLibs.add(getOrCreateTestLibraryFileReference(test));
    }
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        pathRelativizer,
        sourcePathResolver)
        .setTargetName(productName)
        .setProduct(
            dylibProductTypeByBundleExtension(key.getExtension()).get(),
            productName,
            Paths.get(productName + "." + key.getExtension().toFileExtension()))
        .setSourcesWithFlags(
            ImmutableSet.of(
                SourceWithFlags.of(
                    new PathSourcePath(projectFilesystem, emptyFileWithExtension("c")))))
        .setArchives(Sets.union(collectRecursiveLibraryDependencies(tests), testLibs.build()))
        .setRecursiveResources(AppleResources.collectRecursiveResources(targetGraph, tests))
        .setRecursiveAssetCatalogs(
            AppleBuildRules.collectRecursiveAssetCatalogs(targetGraph, tests));

    ImmutableSet.Builder<FrameworkPath> frameworksBuilder = ImmutableSet.builder();
    frameworksBuilder.addAll(collectRecursiveFrameworkDependencies(tests));
    for (TargetNode<AppleTestDescription.Arg> test : tests) {
      frameworksBuilder.addAll(test.getConstructorArg().frameworks.get());
      frameworksBuilder.addAll(test.getConstructorArg().libraries.get());
    }
    mutator.setFrameworks(frameworksBuilder.build());

    NewNativeTargetProjectMutator.Result result;
    try {
      result = mutator.buildTargetAndAddToProject(project);
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e);
    }

    ImmutableMap.Builder<String, String> overrideBuildSettingsBuilder =
        ImmutableMap.<String, String>builder()
            .put("GCC_PREFIX_HEADER", "")
            .put("USE_HEADERMAP", "NO");
    if (key.getInfoPlist().isPresent()) {
      overrideBuildSettingsBuilder.put(
          "INFOPLIST_FILE",
          pathRelativizer.outputDirToRootRelative(
              sourcePathResolver.apply(key.getInfoPlist().get())).toString());
    }
    setTargetBuildConfigurations(
        new Function<String, Path>() {
          @Override
          public Path apply(String input) {
            return outputDirectory.resolve(
                String.format("xcconfigs/%s-%s.xcconfig", productName, input));
          }
        },
        result.target,
        project.getMainGroup(),
        key.getConfigs().get(),
        overrideBuildSettingsBuilder.build(),
        ImmutableMap.of(
            PRODUCT_NAME, productName,
            "WRAPPER_EXTENSION", key.getExtension().toFileExtension()),
        ImmutableMap.of(
            "FRAMEWORK_SEARCH_PATHS",
            Joiner.on(' ').join(collectRecursiveFrameworkSearchPaths(tests)),
            "LIBRARY_SEARCH_PATHS",
            Joiner.on(' ').join(collectRecursiveLibrarySearchPaths(tests)),
            "OTHER_LDFLAGS",
            Joiner.on(' ').join(
                MoreIterables.zipAndConcat(
                    Iterables.cycle("-Xlinker"),
                    Iterables.concat(
                        key.getLinkerFlags(),
                        collectRecursiveExportedLinkerFlags(tests))))));
    buildableCombinedTestTargets.add(result.target);
  }

  private String deriveCombinedTestTargetNameFromKey(
      AppleTestBundleParamsKey key,
      int combinedTestIndex) {
    return Joiner.on("-").join(
        "_BuckCombinedTest",
        key.getExtension().toFileExtension(),
        combinedTestIndex);

  }

  /**
   * Create target level configuration entries.
   *
   * @param target      Xcode target for which the configurations will be set.
   * @param targetGroup Xcode group in which the configuration file references will be placed.
   * @param configurations  Configurations as extracted from the BUCK file.
   * @param overrideBuildSettings Build settings that will override ones defined elsewhere.
   * @param defaultBuildSettings  Target-inline level build settings that will be set if not already
   *                              defined.
   * @param appendBuildSettings   Target-inline level build settings that will incorporate the
   *                              existing value or values at a higher level.
   */
  private void setTargetBuildConfigurations(
      Function<String, Path> configurationNameToXcconfigPath,
      PBXTarget target,
      PBXGroup targetGroup,
      ImmutableMap<String, ImmutableMap<String, String>> configurations,
      ImmutableMap<String, String> overrideBuildSettings,
      ImmutableMap<String, String> defaultBuildSettings,
      ImmutableMap<String, String> appendBuildSettings)
      throws IOException {

    for (Map.Entry<String, ImmutableMap<String, String>> configurationEntry :
        configurations.entrySet()) {
      targetConfigNamesBuilder.add(configurationEntry.getKey());

      ImmutableMap<String, String> targetLevelInlineSettings =
          configurationEntry.getValue();

      XCBuildConfiguration outputConfiguration = target
          .getBuildConfigurationList()
          .getBuildConfigurationsByName()
          .getUnchecked(configurationEntry.getKey());

      HashMap<String, String> combinedOverrideConfigs = Maps.newHashMap(overrideBuildSettings);
      for (Map.Entry<String, String> entry: defaultBuildSettings.entrySet()) {
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

      Iterable<Map.Entry<String, String>> entries = Iterables.concat(
          targetLevelInlineSettings.entrySet(),
          combinedOverrideConfigs.entrySet());

      Path xcconfigPath = configurationNameToXcconfigPath.apply(configurationEntry.getKey());
      projectFilesystem.mkdirs(Preconditions.checkNotNull(xcconfigPath).getParent());

      StringBuilder stringBuilder = new StringBuilder();
      for (Map.Entry<String, String> entry : entries) {
        stringBuilder.append(entry.getKey());
        stringBuilder.append(" = ");
        stringBuilder.append(entry.getValue());
        stringBuilder.append('\n');
      }
      String xcconfigContents = stringBuilder.toString();

      if (MorePaths.fileContentsDiffer(
          new ByteArrayInputStream(xcconfigContents.getBytes(Charsets.UTF_8)),
          xcconfigPath,
          projectFilesystem)) {
        if (shouldGenerateReadOnlyFiles()) {
          projectFilesystem.writeContentsToPath(
              xcconfigContents,
              xcconfigPath,
              READ_ONLY_FILE_ATTRIBUTE);
        } else {
          projectFilesystem.writeContentsToPath(
              xcconfigContents,
              xcconfigPath);
        }
      }

      PBXFileReference fileReference = getConfigurationFileReference(targetGroup, xcconfigPath);
      outputConfiguration.setBaseConfigurationReference(fileReference);
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
                Optional.<String>absent()));
  }

  private void collectBuildScriptDependencies(
      Iterable<TargetNode<?>> targetNodes,
      ImmutableList.Builder<TargetNode<?>> preRules,
      ImmutableList.Builder<TargetNode<?>> postRules) {
    for (TargetNode<?> targetNode : targetNodes) {
      BuildRuleType type = targetNode.getType();
      if (type.equals(IosReactNativeLibraryDescription.TYPE)) {
        postRules.add(targetNode);
        requiredBuildTargetsBuilder.add(targetNode.getBuildTarget());
      } else if (type.equals(XcodePostbuildScriptDescription.TYPE)) {
        postRules.add(targetNode);
      } else if (type.equals(XcodePrebuildScriptDescription.TYPE)) {
        preRules.add(targetNode);
      }
    }
  }

  private void createHeaderSymlinkTree(
      Function<SourcePath, Path> pathResolver,
      Map<Path, SourcePath> contents,
      Path headerSymlinkTreeRoot) throws IOException {
    LOG.verbose(
        "Building header symlink tree at %s with contents %s",
        headerSymlinkTreeRoot,
        contents);
    ImmutableSortedMap.Builder<Path, Path> resolvedContentsBuilder =
        ImmutableSortedMap.naturalOrder();
    for (Map.Entry<Path, SourcePath> entry : contents.entrySet()) {
      Path link = headerSymlinkTreeRoot.resolve(entry.getKey());
      Path existing = projectFilesystem.resolve(pathResolver.apply(entry.getValue()));
      resolvedContentsBuilder.put(link, existing);
    }
    ImmutableSortedMap<Path, Path> resolvedContents = resolvedContentsBuilder.build();

    Path headerMapLocation = getHeaderMapLocationFromSymlinkTreeRoot(headerSymlinkTreeRoot);

    Path hashCodeFilePath = headerSymlinkTreeRoot.resolve(".contents-hash");
    Optional<String> currentHashCode = projectFilesystem.readFileIfItExists(hashCodeFilePath);
    String newHashCode = getHeaderSymlinkTreeHashCode(resolvedContents).toString();
    if (Optional.of(newHashCode).equals(currentHashCode)) {
      LOG.debug(
          "Symlink tree at %s is up to date, not regenerating (key %s).",
          headerSymlinkTreeRoot,
          newHashCode);
    } else {
      LOG.debug(
          "Updating symlink tree at %s (old key %s, new key %s).",
          headerSymlinkTreeRoot,
          currentHashCode,
          newHashCode);
      projectFilesystem.deleteRecursivelyIfExists(headerSymlinkTreeRoot);
      projectFilesystem.mkdirs(headerSymlinkTreeRoot);
      for (Map.Entry<Path, Path> entry : resolvedContents.entrySet()) {
        Path link = entry.getKey();
        Path existing = entry.getValue();
        projectFilesystem.createParentDirs(link);
        projectFilesystem.createSymLink(link, existing, /* force */ false);
      }
      projectFilesystem.writeContentsToPath(newHashCode, hashCodeFilePath);

      HeaderMap.Builder headerMapBuilder = new HeaderMap.Builder();
      for (Map.Entry<Path, SourcePath> entry : contents.entrySet()) {
        headerMapBuilder.add(
            entry.getKey().toString(),
            Paths.get("../../")
                .resolve(projectCell.getRoot().getFileName())
                .resolve(headerSymlinkTreeRoot)
                .resolve(entry.getKey()));
      }
      projectFilesystem.writeBytesToPath(headerMapBuilder.build().getBytes(), headerMapLocation);
    }
    headerSymlinkTrees.add(headerSymlinkTreeRoot);
  }

  private HashCode getHeaderSymlinkTreeHashCode(ImmutableSortedMap<Path, Path> contents) {
    Hasher hasher = Hashing.sha1().newHasher();
    hasher.putBytes(BuckVersion.getVersion().getBytes(Charsets.UTF_8));
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
      PBXGroup targetGroup,
      Iterable<CoreDataModelDescription.Arg> dataModels) throws IOException {
    // TODO(Coneko): actually add a build phase

    for (final CoreDataModelDescription.Arg dataModel : dataModels) {
      // Core data models go in the resources group also.
      PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

      if (CoreDataModelDescription.isVersionedDataModel(dataModel)) {
        // It's safe to do I/O here to figure out the current version because we're returning all
        // the versions and the file pointing to the current version from
        // getInputsToCompareToOutput(), so the rule will be correctly detected as stale if any of
        // them change.
        final String currentVersionFileName = ".xccurrentversion";
        final String currentVersionKey = "_XCCurrentVersionName";

        final XCVersionGroup versionGroup =
            resourcesGroup.getOrCreateChildVersionGroupsBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SOURCE_ROOT,
                    pathRelativizer.outputDirToRootRelative(dataModel.path),
                    Optional.<String>absent()));

        projectFilesystem.walkRelativeFileTree(
            dataModel.path,
            new SimpleFileVisitor<Path>() {
              @Override
              public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(dataModel.path)) {
                  return FileVisitResult.CONTINUE;
                }
                versionGroup.getOrCreateFileReferenceBySourceTreePath(
                    new SourceTreePath(
                        PBXReference.SourceTree.SOURCE_ROOT,
                        pathRelativizer.outputDirToRootRelative(dir),
                        Optional.<String>absent()));
                return FileVisitResult.SKIP_SUBTREE;
              }
            });

        Path currentVersionPath = dataModel.path.resolve(currentVersionFileName);
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
          PBXFileReference ref = versionGroup.getOrCreateFileReferenceBySourceTreePath(
              new SourceTreePath(
                  PBXReference.SourceTree.SOURCE_ROOT,
                  pathRelativizer.outputDirToRootRelative(
                      dataModel.path.resolve(currentVersionName.toString())),
                  Optional.<String>absent()));
          versionGroup.setCurrentVersion(Optional.of(ref));
        } catch (NoSuchFileException e) {
          if (versionGroup.getChildren().size() == 1) {
            versionGroup.setCurrentVersion(Optional.of(Iterables.get(
                versionGroup.getChildren(),
                0)));
          }
        }
      } else {
        resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativizer.outputDirToRootRelative(dataModel.path),
                Optional.<String>absent()));
      }
    }
  }

  private Optional<CopyFilePhaseDestinationSpec> getDestinationSpec(TargetNode<?> targetNode) {
    if (targetNode.getType().equals(AppleBundleDescription.TYPE)) {
      AppleBundleDescription.Arg arg = (AppleBundleDescription.Arg) targetNode.getConstructorArg();
      AppleBundleExtension extension = arg.extension.isLeft() ?
          arg.extension.getLeft() :
          AppleBundleExtension.BUNDLE;
      switch (extension) {
        case FRAMEWORK:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS)
          );
        case APPEX:
        case PLUGIN:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.PLUGINS)
          );
        case APP:
          if (isWatchApplicationNode(targetNode)) {
            return Optional.of(
                CopyFilePhaseDestinationSpec.builder()
                    .setDestination(PBXCopyFilesBuildPhase.Destination.PRODUCTS)
                    .setPath("$(CONTENTS_FOLDER_PATH)/Watch")
                    .build()
            );
          } else {
            return Optional.of(
                CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.EXECUTABLES)
            );
          }
        case BUNDLE:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.PLUGINS)
          );
        //$CASES-OMITTED$
        default:
          return Optional.of(
              CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.PRODUCTS)
          );
      }
    } else if (targetNode.getType().equals(AppleLibraryDescription.TYPE) ||
        targetNode.getType().equals(CxxLibraryDescription.TYPE)) {
      if (targetNode
          .getBuildTarget()
          .getFlavors()
          .contains(CxxDescriptionEnhancer.SHARED_FLAVOR)) {
        return Optional.of(
            CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS)
        );
      } else {
        return Optional.absent();
      }
    } else if (targetNode.getType().equals(AppleBinaryDescription.TYPE)) {
      return Optional.of(
          CopyFilePhaseDestinationSpec.of(PBXCopyFilesBuildPhase.Destination.EXECUTABLES)
      );
    } else if (targetNode.getType().equals(HalideLibraryDescription.TYPE)) {
      return Optional.absent();
    } else {
      throw new RuntimeException("Unexpected type: " + targetNode.getType());
    }
  }

  private ImmutableList<PBXBuildPhase> getCopyFilesBuildPhases(
      Iterable<TargetNode<?>> copiedNodes) {

    // Bucket build rules into bins by their destinations
    ImmutableSetMultimap.Builder<CopyFilePhaseDestinationSpec, TargetNode<?>>
        ruleByDestinationSpecBuilder = ImmutableSetMultimap.builder();
    for (TargetNode<?> copiedNode : copiedNodes) {
      Optional<CopyFilePhaseDestinationSpec> optionalDestinationSpec =
          getDestinationSpec(copiedNode);
      if (optionalDestinationSpec.isPresent()) {
        ruleByDestinationSpecBuilder.put(optionalDestinationSpec.get(), copiedNode);
      }
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
      CopyFilePhaseDestinationSpec destinationSpec,
      Iterable<TargetNode<?>> targetNodes) {
    PBXCopyFilesBuildPhase copyFilesBuildPhase = new PBXCopyFilesBuildPhase(destinationSpec);
    for (TargetNode<?> targetNode : targetNodes) {
      PBXFileReference fileReference = getLibraryFileReference(targetNode);
      copyFilesBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
    }
    return copyFilesBuildPhase;
  }

  /**
   * Create the project bundle structure and write {@code project.pbxproj}.
   */
  private Path writeProjectFile(PBXProject project) throws IOException {
    XcodeprojSerializer serializer = new XcodeprojSerializer(
        new GidGenerator(ImmutableSet.copyOf(gidsToTargetNames.keySet())),
        project);
    NSDictionary rootObject = serializer.toPlist();
    Path xcodeprojDir = outputDirectory.resolve(projectName + ".xcodeproj");
    projectFilesystem.mkdirs(xcodeprojDir);
    Path serializedProject = xcodeprojDir.resolve("project.pbxproj");
    String contentsToWrite = rootObject.toXMLPropertyList();
    // Before we write any files, check if the file contents have changed.
    if (MorePaths.fileContentsDiffer(
        new ByteArrayInputStream(contentsToWrite.getBytes(Charsets.UTF_8)),
        serializedProject,
        projectFilesystem)) {
      LOG.debug("Regenerating project at %s", serializedProject);
      if (shouldGenerateReadOnlyFiles()) {
        projectFilesystem.writeContentsToPath(
            contentsToWrite,
            serializedProject,
            READ_ONLY_FILE_ATTRIBUTE);
      } else {
        projectFilesystem.writeContentsToPath(
            contentsToWrite,
            serializedProject);
      }
    } else {
      LOG.debug("Not regenerating project at %s (contents have not changed)", serializedProject);
    }
    return xcodeprojDir;
  }

  private static String getProductNameForBuildTarget(BuildTarget buildTarget) {
    return buildTarget.getShortName();
  }

  /**
   * @param targetNode Must have a header symlink tree or an exception will be thrown.
   */
  private Path getHeaderSymlinkTreeRelativePath(
      TargetNode<? extends CxxLibraryDescription.Arg> targetNode,
      HeaderVisibility headerVisibility) {
    Path treeRoot = AppleDescriptions.getPathToHeaderSymlinkTree(
        targetNode,
        headerVisibility);
    Path cellRoot = MorePaths.relativize(
        projectFilesystem.getRootPath(),
        targetNode.getBuildTarget().getCellPath());
    return pathRelativizer.outputDirToRootRelative(cellRoot.resolve(treeRoot));
  }

  private Path getHeaderMapLocationFromSymlinkTreeRoot(Path headerSymlinkTreeRoot) {
    return headerSymlinkTreeRoot.resolve(".tree.hmap");
  }

  private Path getHeaderSearchPathFromSymlinkTreeRoot(Path headerSymlinkTreeRoot) {
    if (options.contains(Option.DISABLE_HEADER_MAPS)) {
      return headerSymlinkTreeRoot;
    } else {
      return getHeaderMapLocationFromSymlinkTreeRoot(headerSymlinkTreeRoot);
    }
  }

  private String getBuiltProductsRelativeTargetOutputPath(TargetNode<?> targetNode) {
    if (targetNode.getType().equals(AppleBinaryDescription.TYPE) ||
        targetNode.getType().equals(AppleTestDescription.TYPE) ||
        (targetNode.getType().equals(AppleBundleDescription.TYPE) &&
            !isFrameworkBundle((AppleBundleDescription.Arg) targetNode.getConstructorArg()))) {
      // TODO(grp): These should be inside the path below. Right now, that causes issues with
      // bundle loader paths hardcoded in .xcconfig files that don't expect the full target path.
      // It also causes issues where Xcode doesn't know where to look for a final .app to run it.
      return ".";
    } else {
      return BaseEncoding
          .base32()
          .omitPadding()
          .encode(targetNode.getBuildTarget().getFullyQualifiedName().getBytes());
    }
  }

  private String getTargetOutputPath(TargetNode<?> targetNode) {
    return Joiner.on('/').join(
        "$BUILT_PRODUCTS_DIR",
        getBuiltProductsRelativeTargetOutputPath(targetNode));
  }

  @SuppressWarnings("unchecked")
  private static Optional<TargetNode<CxxLibraryDescription.Arg>> getAppleNativeNodeOfType(
      TargetGraph targetGraph,
      TargetNode<?> targetNode,
      Set<BuildRuleType> nodeTypes,
      Set<AppleBundleExtension> bundleExtensions) {
    Optional<TargetNode<CxxLibraryDescription.Arg>> nativeNode = Optional.absent();
    if (nodeTypes.contains(targetNode.getType())) {
      nativeNode = Optional.of((TargetNode<CxxLibraryDescription.Arg>) targetNode);
    } else if (targetNode.getType().equals(AppleBundleDescription.TYPE)) {
      TargetNode<AppleBundleDescription.Arg> bundle =
          (TargetNode<AppleBundleDescription.Arg>) targetNode;
      Either<AppleBundleExtension, String> extension = bundle.getConstructorArg().getExtension();
      if (extension.isLeft() && bundleExtensions.contains(extension.getLeft())) {
        nativeNode = Optional.of(
            (TargetNode<CxxLibraryDescription.Arg>) targetGraph.get(
                bundle.getConstructorArg().binary));
      }
    }
    return nativeNode;
  }

  private static Optional<TargetNode<CxxLibraryDescription.Arg>> getAppleNativeNode(
      TargetGraph targetGraph,
      TargetNode<?> targetNode) {
    return getAppleNativeNodeOfType(
        targetGraph,
        targetNode,
        ImmutableSet.of(
            AppleBinaryDescription.TYPE,
            AppleLibraryDescription.TYPE,
            CxxLibraryDescription.TYPE),
        ImmutableSet.of(
            AppleBundleExtension.APP,
            AppleBundleExtension.FRAMEWORK));
  }

  private static Optional<TargetNode<CxxLibraryDescription.Arg>> getLibraryNode(
      TargetGraph targetGraph,
      TargetNode<?> targetNode) {
    return getAppleNativeNodeOfType(
        targetGraph,
        targetNode,
        ImmutableSet.of(
            AppleLibraryDescription.TYPE,
            CxxLibraryDescription.TYPE),
        ImmutableSet.of(
            AppleBundleExtension.FRAMEWORK));
  }

  private ImmutableSet<Path> collectRecursiveHeaderSearchPaths(
      TargetNode<? extends CxxLibraryDescription.Arg> targetNode) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();

    for (Path headerSymlinkTreePath : collectRecursiveHeaderSymlinkTrees(targetNode)) {
      builder.add(getHeaderSearchPathFromSymlinkTreeRoot(headerSymlinkTreePath));
    }

    for (Path halideHeaderPath : collectRecursiveHalideLibraryHeaderPaths(targetNode)) {
      builder.add(halideHeaderPath);
    }

    return builder.build();
  }

  private ImmutableSet<Path> collectRecursiveHalideLibraryHeaderPaths(
      TargetNode<? extends CxxLibraryDescription.Arg> targetNode) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    for (TargetNode<?> input :
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            AppleBuildRules.RecursiveDependenciesMode.BUILDING,
            targetNode,
            Optional.of(
                ImmutableSet.<BuildRuleType>of(
                    HalideLibraryDescription.TYPE)))) {
      BuildTarget buildTarget = input.getBuildTarget();
      builder.add(
          pathRelativizer.outputDirToRootRelative(
              HalideCompile
                  .headerOutputPath(
                      buildTarget.withFlavors(
                          HalideLibraryDescription.HALIDE_COMPILE_FLAVOR,
                          defaultCxxPlatform.getFlavor()),
                      projectFilesystem)
                  .getParent()));
    }
    return builder.build();
  }

  private ImmutableSet<Path> collectRecursiveHeaderSymlinkTrees(
      TargetNode<? extends CxxLibraryDescription.Arg> targetNode) {
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();

    builder.add(getHeaderSymlinkTreeRelativePath(targetNode, HeaderVisibility.PRIVATE));
    builder.add(getHeaderSymlinkTreeRelativePath(targetNode, HeaderVisibility.PUBLIC));

    for (TargetNode<?> input :
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            AppleBuildRules.RecursiveDependenciesMode.BUILDING,
            targetNode,
            Optional.of(AppleBuildRules.XCODE_TARGET_BUILD_RULE_TYPES))) {
      Optional<TargetNode<CxxLibraryDescription.Arg>> nativeNode =
          getAppleNativeNode(targetGraph, input);
      if (nativeNode.isPresent()) {
        builder.add(
            getHeaderSymlinkTreeRelativePath(
                nativeNode.get(),
                HeaderVisibility.PUBLIC));
      }
    }

    addHeaderSymlinkTreesForSourceUnderTest(targetNode, builder, HeaderVisibility.PRIVATE);

    return builder.build();
  }

  private void addHeaderSymlinkTreesForSourceUnderTest(
      TargetNode<? extends CxxLibraryDescription.Arg> targetNode,
      ImmutableSet.Builder<Path> headerSymlinkTreesBuilder,
      HeaderVisibility headerVisibility) {
    ImmutableSet<TargetNode<?>> directDependencies = ImmutableSet.copyOf(
        targetGraph.getAll(targetNode.getDeps()));
    for (TargetNode<?> dependency : directDependencies) {
      Optional<TargetNode<CxxLibraryDescription.Arg>> nativeNode =
          getAppleNativeNode(targetGraph, dependency);
      if (nativeNode.isPresent() && isSourceUnderTest(dependency, nativeNode.get(), targetNode)) {
        headerSymlinkTreesBuilder.add(
            getHeaderSymlinkTreeRelativePath(
                nativeNode.get(),
                headerVisibility));
      }
    }
  }

  private boolean isSourceUnderTest(
      TargetNode<?> dependencyNode,
      TargetNode<CxxLibraryDescription.Arg> nativeNode,
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

  private <T> ImmutableSet<String> collectRecursiveLibrarySearchPaths(
      Iterable<TargetNode<T>> targetNodes) {
    return new ImmutableSet.Builder<String>()
        .add("$BUILT_PRODUCTS_DIR")
        .addAll(
            collectRecursiveSearchPathsForFrameworkPaths(
                targetNodes,
                new Function<
                    AppleNativeTargetDescriptionArg,
                    ImmutableSortedSet<FrameworkPath>>() {
                  @Override
                  public ImmutableSortedSet<FrameworkPath> apply(
                      AppleNativeTargetDescriptionArg input) {
                    return input.libraries.or(ImmutableSortedSet.<FrameworkPath>of());
                  }
                })).build();
  }

  private <T> ImmutableSet<String> collectRecursiveFrameworkSearchPaths(
      Iterable<TargetNode<T>> targetNodes) {
    return new ImmutableSet.Builder<String>()
        .add("$BUILT_PRODUCTS_DIR")
        .addAll(
            collectRecursiveSearchPathsForFrameworkPaths(
                targetNodes,
                new Function<
                    AppleNativeTargetDescriptionArg,
                    ImmutableSortedSet<FrameworkPath>>() {
                  @Override
                  public ImmutableSortedSet<FrameworkPath> apply(
                      AppleNativeTargetDescriptionArg input) {
                    return input.frameworks.or(ImmutableSortedSet.<FrameworkPath>of());
                  }
                })).build();
  }

  private <T> Iterable<FrameworkPath> collectRecursiveFrameworkDependencies(
      Iterable<TargetNode<T>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                AppleBuildRules.XCODE_TARGET_BUILD_RULE_TYPES))
        .transformAndConcat(
            new Function<TargetNode<?>, Iterable<FrameworkPath>>() {
              @Override
              public Iterable<FrameworkPath> apply(TargetNode<?> input) {
                Optional<TargetNode<CxxLibraryDescription.Arg>> library =
                    getLibraryNode(targetGraph, input);
                if (library.isPresent() &&
                    !AppleLibraryDescription.isSharedLibraryTarget(
                        library.get().getBuildTarget())) {
                  return Iterables.concat(
                      library.get().getConstructorArg().frameworks.get(),
                      library.get().getConstructorArg().libraries.get());
                } else {
                  return ImmutableList.of();
                }
              }
            });
  }

  private <T> Iterable<String> collectRecursiveSearchPathsForFrameworkPaths(
      Iterable<TargetNode<T>> targetNodes,
      final Function<
          AppleNativeTargetDescriptionArg,
          ImmutableSortedSet<FrameworkPath>> pathSetExtractor) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                ImmutableSet.of(
                    AppleLibraryDescription.TYPE,
                    CxxLibraryDescription.TYPE)))
        .append(targetNodes)
        .transformAndConcat(
            new Function<TargetNode<?>, Iterable<String>>() {
              @Override
              public Iterable<String> apply(TargetNode<?> input) {
                return input
                    .castArg(AppleNativeTargetDescriptionArg.class)
                    .transform(getTargetFrameworkSearchPaths(pathSetExtractor))
                    .or(ImmutableSet.<String>of());
              }
            });
  }

  private <T> Iterable<String> collectRecursiveExportedPreprocessorFlags(
      Iterable<TargetNode<T>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.BUILDING,
                ImmutableSet.of(
                    AppleLibraryDescription.TYPE,
                    CxxLibraryDescription.TYPE)))
        .append(targetNodes)
        .transformAndConcat(
            new Function<TargetNode<?>, Iterable<? extends String>>() {
              @Override
              public Iterable<? extends String> apply(TargetNode<?> input) {
                return input
                    .castArg(AppleNativeTargetDescriptionArg.class)
                    .transform(GET_EXPORTED_PREPROCESSOR_FLAGS)
                    .or(ImmutableSet.<String>of());
              }
            });
  }

  private <T> Iterable<String> collectRecursiveExportedLinkerFlags(
      Iterable<TargetNode<T>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                ImmutableSet.of(
                    AppleLibraryDescription.TYPE,
                    CxxLibraryDescription.TYPE,
                    HalideLibraryDescription.TYPE)))
        .append(targetNodes)
        .transformAndConcat(
            new Function<TargetNode<?>, Iterable<? extends String>>() {
              @Override
              public Iterable<String> apply(TargetNode<?> input) {
                return input
                    .castArg(AppleNativeTargetDescriptionArg.class)
                    .transform(GET_EXPORTED_LINKER_FLAGS)
                    .or(ImmutableSet.<String>of());
              }
            });
  }

  private <T> ImmutableSet<PBXFileReference>
  collectRecursiveLibraryDependencies(Iterable<TargetNode<T>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                AppleBuildRules.XCODE_TARGET_BUILD_RULE_TYPES))
        .filter(getLibraryWithSourcesToCompilePredicate())
        .transform(
            new Function<TargetNode<?>, PBXFileReference>() {
              @Override
              public PBXFileReference apply(TargetNode<?> input) {
                return getLibraryFileReference(input);
              }
            }).toSet();
  }

  private Function<
      TargetNode<AppleNativeTargetDescriptionArg>,
      Iterable<String>> getTargetFrameworkSearchPaths(
      final Function<
          AppleNativeTargetDescriptionArg,
          ImmutableSortedSet<FrameworkPath>> pathSetExtractor) {

    final Function<FrameworkPath, Path> toSearchPath = FrameworkPath
        .getUnexpandedSearchPathFunction(
            sourcePathResolver,
            pathRelativizer.outputDirToRootRelative());

    return new Function<TargetNode<AppleNativeTargetDescriptionArg>, Iterable<String>>() {
      @Override
      public Iterable<String> apply(TargetNode<AppleNativeTargetDescriptionArg> input) {
        return FluentIterable
            .from(pathSetExtractor.apply(input.getConstructorArg()))
            .transform(toSearchPath)
            .transform(Functions.toStringFunction());
      }
    };
  }

  private SourceTreePath getProductsSourceTreePath(TargetNode<?> targetNode) {
    String productName = getProductNameForBuildTarget(targetNode.getBuildTarget());
    String productOutputName;

    if (targetNode.getType().equals(AppleLibraryDescription.TYPE) ||
        targetNode.getType().equals(CxxLibraryDescription.TYPE) ||
        targetNode.getType().equals(HalideLibraryDescription.TYPE)) {
      String productOutputFormat = AppleBuildRules.getOutputFileNameFormatForLibrary(
          targetNode
              .getBuildTarget()
              .getFlavors()
              .contains(CxxDescriptionEnhancer.SHARED_FLAVOR));
      productOutputName = String.format(productOutputFormat, productName);
    } else if (targetNode.getType().equals(AppleBundleDescription.TYPE) ||
        targetNode.getType().equals(AppleTestDescription.TYPE)) {
      HasAppleBundleFields arg = (HasAppleBundleFields) targetNode.getConstructorArg();
      productOutputName = productName + "." + getExtensionString(arg.getExtension());
    } else if (targetNode.getType().equals(AppleBinaryDescription.TYPE)) {
      productOutputName = productName;
    } else {
      throw new RuntimeException("Unexpected type: " + targetNode.getType());
    }

    return new SourceTreePath(
        PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
        Paths.get(productOutputName),
        Optional.<String>absent());
  }

  private PBXFileReference getLibraryFileReference(TargetNode<?> targetNode) {
    // Don't re-use the productReference from other targets in this project.
    // File references set as a productReference don't work with custom paths.
    SourceTreePath productsPath = getProductsSourceTreePath(targetNode);

    if (isWatchApplicationNode(targetNode)) {
      return project.getMainGroup()
          .getOrCreateChildGroupByName("Products")
          .getOrCreateFileReferenceBySourceTreePath(productsPath);
    } else if (targetNode.getType().equals(AppleLibraryDescription.TYPE) ||
        targetNode.getType().equals(AppleBundleDescription.TYPE) ||
        targetNode.getType().equals(CxxLibraryDescription.TYPE) ||
        targetNode.getType().equals(HalideLibraryDescription.TYPE)) {
      return project.getMainGroup()
          .getOrCreateChildGroupByName("Frameworks")
          .getOrCreateFileReferenceBySourceTreePath(productsPath);
    } else if (targetNode.getType().equals(AppleBinaryDescription.TYPE)) {
      return project.getMainGroup()
          .getOrCreateChildGroupByName("Dependencies")
          .getOrCreateFileReferenceBySourceTreePath(productsPath);
    } else {
      throw new RuntimeException("Unexpected type: " + targetNode.getType());
    }
  }

  /**
   * Return a file reference to a test assuming it's built as a static library.
   */
  private PBXFileReference getOrCreateTestLibraryFileReference(
      TargetNode<AppleTestDescription.Arg> test) {
    SourceTreePath path = new SourceTreePath(
        PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
        Paths.get(getBuiltProductsRelativeTargetOutputPath(test)).resolve(
            String.format(
                AppleBuildRules.getOutputFileNameFormatForLibrary(false),
                getProductNameForBuildTarget(test.getBuildTarget()))),
        Optional.<String>absent());
    return project.getMainGroup()
        .getOrCreateChildGroupByName("Test Libraries")
        .getOrCreateFileReferenceBySourceTreePath(path);
  }

  /**
   * Whether a given build target is built by the project being generated, or being build elsewhere.
   */
  private boolean isBuiltByCurrentProject(BuildTarget buildTarget) {
    return initialTargets.contains(buildTarget);
  }

  private String getXcodeTargetName(BuildTarget target) {
    return options.contains(Option.USE_SHORT_NAMES_FOR_TARGETS)
        ? target.getShortName()
        : target.getFullyQualifiedName();
  }

  @SuppressWarnings("incomplete-switch")
  ProductType bundleToTargetProductType(
      TargetNode<? extends HasAppleBundleFields> targetNode,
      TargetNode<? extends AppleNativeTargetDescriptionArg> binaryNode) {
    if (targetNode.getConstructorArg().getXcodeProductType().isPresent()) {
      return ProductType.of(targetNode.getConstructorArg().getXcodeProductType().get());
    } else if (targetNode.getConstructorArg().getExtension().isLeft()) {
      AppleBundleExtension extension = targetNode.getConstructorArg().getExtension().getLeft();

      if (binaryNode.getType().equals(AppleLibraryDescription.TYPE) ||
          binaryNode.getType().equals(CxxLibraryDescription.TYPE)) {
        if (binaryNode.getBuildTarget().getFlavors().contains(
            CxxDescriptionEnhancer.SHARED_FLAVOR)) {
          Optional<ProductType> productType =
              dylibProductTypeByBundleExtension(extension);
          if (productType.isPresent()) {
            return productType.get();
          }
        } else {
          switch (extension) {
            case FRAMEWORK:
              return ProductType.STATIC_FRAMEWORK;
          }
        }
      } else if (binaryNode.getType().equals(AppleBinaryDescription.TYPE)) {
        switch (extension) {
          case APP:
            return ProductType.APPLICATION;
        }
      } else if (binaryNode.getType().equals(AppleTestDescription.TYPE)) {
        switch (extension) {
          case OCTEST:
            return ProductType.BUNDLE;
          case XCTEST:
            return ProductType.UNIT_TEST;
        }
      }
    }

    return ProductType.BUNDLE;
  }

  private boolean shouldGenerateReadOnlyFiles() {
    return options.contains(Option.GENERATE_READ_ONLY_FILES);
  }

  private static String getExtensionString(Either<AppleBundleExtension, String> extension) {
    return extension.isLeft() ? extension.getLeft().toFileExtension() : extension.getRight();
  }

  private static boolean isFrameworkBundle(HasAppleBundleFields arg) {
    return arg.getExtension().isLeft() &&
        arg.getExtension().getLeft().equals(AppleBundleExtension.FRAMEWORK);
  }

  private static boolean bundleRequiresRemovalOfAllTransitiveFrameworks(
      TargetNode<? extends HasAppleBundleFields> targetNode) {
    return isFrameworkBundle(targetNode.getConstructorArg());
  }

  private static boolean bundleRequiresAllTransitiveFrameworks(
      TargetNode<? extends AppleNativeTargetDescriptionArg> binaryNode) {
    return binaryNode.castArg(AppleBinaryDescription.Arg.class).isPresent();
  }

  private Path emptyFileWithExtension(String extension) {
    Path path =
        projectFilesystem.getBuckPaths().getGenDir()
            .resolve("xcode-scripts/emptyFile." + extension);
    if (!projectFilesystem.exists(path)) {
      try {
        projectFilesystem.createParentDirs(path);
        projectFilesystem.newFileOutputStream(path).close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return path;
  }

  private Path resolveSourcePath(SourcePath sourcePath) {
    if (sourcePath instanceof PathSourcePath) {
      return ((PathSourcePath) sourcePath).getRelativePath();
    }
    Preconditions.checkArgument(sourcePath instanceof BuildTargetSourcePath);
    BuildTargetSourcePath buildTargetSourcePath = (BuildTargetSourcePath) sourcePath;
    BuildTarget buildTarget = buildTargetSourcePath.getTarget();
    TargetNode<?> node = targetGraph.get(buildTarget);
    Optional<TargetNode<ExportFileDescription.Arg>> exportFileNode = node.castArg(
        ExportFileDescription.Arg.class);
    if (!exportFileNode.isPresent()) {
      SourcePathResolver sourcePathResolver = sourcePathResolverForNode.apply(node);
      Path output = sourcePathResolver.getRelativePath(sourcePath);
      if (output == null) {
        throw new HumanReadableException(
            "The target '%s' does not have an output.",
            node.getBuildTarget());
      }
      requiredBuildTargetsBuilder.add(buildTarget);
      return output;
    }

    Optional<SourcePath> src = exportFileNode.get().getConstructorArg().src;
    if (!src.isPresent()) {
      return buildTarget.getBasePath().resolve(buildTarget.getShortNameAndFlavorPostfix());
    }

    return resolveSourcePath(src.get());
  }

  private Predicate<TargetNode<?>> getLibraryWithSourcesToCompilePredicate() {
    return new Predicate<TargetNode<?>>() {
      @Override
      public boolean apply(TargetNode<?> input) {
        if (input.getType() == HalideLibraryDescription.TYPE) {
          return true;
        }

        Optional<TargetNode<CxxLibraryDescription.Arg>> library =
            getLibraryNode(targetGraph, input);
        if (!library.isPresent()) {
          return false;
        }
        return (library.get().getConstructorArg().srcs.get().size() != 0);
      }
    };
  }

  /**
   * @return product type of a bundle containing a dylib.
   */
  private static Optional<ProductType> dylibProductTypeByBundleExtension(
      AppleBundleExtension extension) {
    switch (extension) {
      case FRAMEWORK:
        return Optional.of(ProductType.FRAMEWORK);
      case APPEX:
        return Optional.of(ProductType.APP_EXTENSION);
      case BUNDLE:
        return Optional.of(ProductType.BUNDLE);
      case OCTEST:
        return Optional.of(ProductType.BUNDLE);
      case XCTEST:
        return Optional.of(ProductType.UNIT_TEST);
      // $CASES-OMITTED$
      default:
        return Optional.absent();
    }
  }

  /**
   * Determines if a target node is for watchOS2 application
   * @param targetNode A target node
   * @return If the given target node is for an watchOS2 application
   */
  private static boolean isWatchApplicationNode(TargetNode<?> targetNode) {
    if (targetNode.getType().equals(AppleBundleDescription.TYPE)) {
      AppleBundleDescription.Arg arg = (AppleBundleDescription.Arg) targetNode.getConstructorArg();
      return arg.getXcodeProductType().equals(
          Optional.of(ProductType.WATCH_APPLICATION.getIdentifier())
      );
    }
    return false;
  }
}
