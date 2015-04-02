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

package com.facebook.buck.apple;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.apple.xcode.GidGenerator;
import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import com.facebook.buck.apple.xcode.xcodeproj.ImmutableProductType;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXCopyFilesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.apple.xcode.xcodeproj.XCVersionGroup;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
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
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.UncheckedExecutionException;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generator for xcode project and associated files from a set of xcode/ios rules.
 */
public class ProjectGenerator {
  private static final Logger LOG = Logger.get(ProjectGenerator.class);

  public enum Option {
    /** Use short BuildTarget name instead of full name for targets */
    USE_SHORT_NAMES_FOR_TARGETS,

    /** Put targets into groups reflecting directory structure of their BUCK files */
    CREATE_DIRECTORY_STRUCTURE,

    /** Generate read-only project files */
    GENERATE_READ_ONLY_FILES,

    /** Include tests in the scheme */
    INCLUDE_TESTS,
    ;
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

  public static final String PATH_TO_ASSET_CATALOG_COMPILER = System.getProperty(
      "buck.path_to_compile_asset_catalogs_py",
      "src/com/facebook/buck/apple/compile_asset_catalogs.py");
  public static final String PATH_TO_ASSET_CATALOG_BUILD_PHASE_SCRIPT = System.getProperty(
      "buck.path_to_compile_asset_catalogs_build_phase_sh",
      "src/com/facebook/buck/apple/compile_asset_catalogs_build_phase.sh");
  public static final String PATH_OVERRIDE_FOR_ASSET_CATALOG_BUILD_PHASE_SCRIPT =
      System.getProperty(
          "buck.path_override_for_asset_catalog_build_phase",
          null);

  private static final FileAttribute<?> READ_ONLY_FILE_ATTRIBUTE =
    PosixFilePermissions.asFileAttribute(
      ImmutableSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ));

  public static final Function<
      TargetNode<AppleNativeTargetDescriptionArg>,
      Iterable<String>> GET_LINKER_FLAGS =
      new Function<TargetNode<AppleNativeTargetDescriptionArg>, Iterable<String>>() {
        @Override
        public Iterable<String> apply(TargetNode<AppleNativeTargetDescriptionArg> input) {
          return input.getConstructorArg().linkerFlags.get();
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
  private final ProjectFilesystem projectFilesystem;
  private final Path outputDirectory;
  private final String projectName;
  private final ImmutableSet<BuildTarget> initialTargets;
  private final Path projectPath;
  private final Path placedAssetCatalogBuildPhaseScript;
  private final PathRelativizer pathRelativizer;

  private final ImmutableSet<Option> options;

  private ImmutableSet<TargetNode<AppleTestDescription.Arg>> testsToGenerateAsStaticLibraries =
      ImmutableSet.of();
  private ImmutableMultimap<AppleTestBundleParamsKey, TargetNode<AppleTestDescription.Arg>>
      additionalCombinedTestTargets = ImmutableMultimap.of();

  // These fields are created/filled when creating the projects.
  private final PBXProject project;
  private final LoadingCache<TargetNode<?>, Optional<PBXTarget>> targetNodeToProjectTarget;
  private boolean shouldPlaceAssetCatalogCompiler = false;
  private final ImmutableMap.Builder<TargetNode<?>, PBXTarget>
      targetNodeToGeneratedProjectTargetBuilder;
  private boolean projectGenerated;
  private List<Path> headerMaps;
  private final ImmutableSet.Builder<PBXTarget> buildableCombinedTestTargets =
      ImmutableSet.builder();
  private final ImmutableSet.Builder<BuildTarget> requiredBuildTargetsBuilder =
      ImmutableSet.builder();
  private final Function<TargetNode<?>, Path> outputPathOfNode;

  /**
   * Populated while generating project configurations, in order to collect the possible
   * project-level configurations to set.
   */
  private final ImmutableSet.Builder<String> targetConfigNamesBuilder;

  private Map<String, String> gidsToTargetNames;
  private String buildFileName;

  public ProjectGenerator(
      TargetGraph targetGraph,
      Set<BuildTarget> initialTargets,
      ProjectFilesystem projectFilesystem,
      Path outputDirectory,
      String projectName,
      String buildFileName,
      Set<Option> options,
      Function<TargetNode<?>, Path> outputPathOfNode) {
    this.sourcePathResolver = new Function<SourcePath, Path>() {
      @Override
      public Path apply(SourcePath input) {
        return resolveSourcePath(input);
      }
    };

    this.targetGraph = targetGraph;
    this.initialTargets = ImmutableSet.copyOf(initialTargets);
    this.projectFilesystem = projectFilesystem;
    this.outputDirectory = outputDirectory;
    this.projectName = projectName;
    this.buildFileName = buildFileName;
    this.options = ImmutableSet.copyOf(options);
    this.outputPathOfNode = outputPathOfNode;

    this.projectPath = outputDirectory.resolve(projectName + ".xcodeproj");
    this.pathRelativizer = new PathRelativizer(
        outputDirectory,
        sourcePathResolver);

    LOG.debug(
        "Output directory %s, profile fs root path %s, repo root relative to output dir %s",
        this.outputDirectory,
        projectFilesystem.getRootPath(),
        this.pathRelativizer.outputDirToRootRelative(Paths.get(".")));

    this.placedAssetCatalogBuildPhaseScript =
        BuckConstant.SCRATCH_PATH.resolve("xcode-scripts/compile_asset_catalogs_build_phase.sh");

    this.project = new PBXProject(projectName);
    this.headerMaps = new ArrayList<>();

    this.targetNodeToGeneratedProjectTargetBuilder = ImmutableMap.builder();
    this.targetNodeToProjectTarget = CacheBuilder.newBuilder().build(
        new CacheLoader<TargetNode<?>, Optional<PBXTarget>>() {
          @Override
          public Optional<PBXTarget> load(TargetNode<?> key) throws Exception {
            return generateProjectTarget(key);
          }
        });

    targetConfigNamesBuilder = ImmutableSet.builder();
    gidsToTargetNames = new HashMap<>();
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
  List<Path> getGeneratedHeaderMaps() {
    return headerMaps;
  }

  public Path getProjectPath() {
    return projectPath;
  }

  public ImmutableMap<BuildTarget, PBXTarget> getBuildTargetToGeneratedTargetMap() {
    Preconditions.checkState(projectGenerated, "Must have called createXcodeProjects");
    ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMap = ImmutableMap.builder();
    for (Map.Entry<TargetNode<?>, PBXTarget> entry :
        targetNodeToGeneratedProjectTargetBuilder.build().entrySet()) {
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

    try {
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

      if (shouldPlaceAssetCatalogCompiler) {
        Path placedAssetCatalogCompilerPath = projectFilesystem.getPathForRelativePath(
            BuckConstant.SCRATCH_PATH.resolve(
                "xcode-scripts/compile_asset_catalogs.py"));
        LOG.debug("Ensuring asset catalog is copied to path [%s]", placedAssetCatalogCompilerPath);
        projectFilesystem.createParentDirs(placedAssetCatalogCompilerPath);
        projectFilesystem.createParentDirs(placedAssetCatalogBuildPhaseScript);
        projectFilesystem.copyFile(
            Paths.get(PATH_TO_ASSET_CATALOG_COMPILER),
            placedAssetCatalogCompilerPath);
        projectFilesystem.copyFile(
            Paths.get(PATH_TO_ASSET_CATALOG_BUILD_PHASE_SCRIPT),
            placedAssetCatalogBuildPhaseScript);
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
              (TargetNode<AppleNativeTargetDescriptionArg>) targetNode));
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
              (TargetNode<AppleNativeTargetDescriptionArg>) Preconditions.checkNotNull(
                  targetGraph.get(bundleTargetNode.getConstructorArg().binary))));
    } else if (targetNode.getType().equals(AppleTestDescription.TYPE)) {
      TargetNode<AppleTestDescription.Arg> testTargetNode =
          (TargetNode<AppleTestDescription.Arg>) targetNode;
      if (testsToGenerateAsStaticLibraries.contains(testTargetNode)) {
        result = Optional.<PBXTarget>of(
            generateAppleLibraryTarget(project, testTargetNode));
      } else {
        result = Optional.<PBXTarget>of(
            generateAppleBundleTarget(
                project,
                testTargetNode,
                testTargetNode));
      }
    } else if (targetNode.getType().equals(AppleResourceDescription.TYPE)) {
      // Check that the resource target node is referencing valid files or directories.
      TargetNode<AppleResourceDescription.Arg> resource =
          (TargetNode<AppleResourceDescription.Arg>) targetNode;
      AppleResourceDescription.Arg arg = resource.getConstructorArg();
      for (Path dir : arg.dirs) {
        if (!projectFilesystem.isDirectory(dir)) {
          throw new HumanReadableException(
              "%s specified in the dirs parameter of %s is not a directory",
              dir.toString(), resource.toString());
        }
      }
      for (SourcePath file : arg.files) {
        if (!projectFilesystem.isFile(sourcePathResolver.apply(file))) {
          throw new HumanReadableException(
              "%s specified in the files parameter of %s is not a regular file",
              file.toString(), resource.toString());
        }
      }
    }

    return result;
  }

  PBXNativeTarget generateAppleBundleTarget(
      PBXProject project,
      TargetNode<? extends HasAppleBundleFields> targetNode,
      TargetNode<? extends AppleNativeTargetDescriptionArg> binaryNode)
      throws IOException {
    Optional<Path> infoPlistPath;
    if (targetNode.getConstructorArg().getInfoPlist().isPresent()) {
      infoPlistPath = Optional.of(
          Preconditions.checkNotNull(
              sourcePathResolver.apply(targetNode.getConstructorArg().getInfoPlist().get())));
    } else {
      infoPlistPath = Optional.absent();
    }

    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.of(targetNode),
        binaryNode,
        bundleToTargetProductType(targetNode, binaryNode),
        "%s." + getExtensionString(targetNode.getConstructorArg().getExtension()),
        infoPlistPath,
        /* includeFrameworks */ true,
        AppleResources.collectRecursiveResources(targetGraph, ImmutableList.of(targetNode)),
        collectRecursiveAssetCatalogs(ImmutableList.of(targetNode)));

    // -- copy any binary and bundle targets into this bundle
    Iterable<TargetNode<?>> copiedRules = AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
        targetGraph,
        AppleBuildRules.RecursiveDependenciesMode.COPYING,
        targetNode,
        Optional.of(AppleBuildRules.XCODE_TARGET_BUILD_RULE_TYPES));
    generateCopyFilesBuildPhases(target, copiedRules);

    LOG.debug("Generated iOS bundle target %s", target);
    return target;
  }

  private PBXNativeTarget generateAppleBinaryTarget(
      PBXProject project,
      TargetNode<AppleNativeTargetDescriptionArg> targetNode)
      throws IOException {
    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.<TargetNode<AppleBundleDescription.Arg>>absent(),
        targetNode,
        PBXTarget.ProductType.TOOL,
        "%s",
        Optional.<Path>absent(),
        /* includeFrameworks */ true,
        ImmutableSet.<AppleResourceDescription.Arg>of(),
        ImmutableSet.<AppleAssetCatalogDescription.Arg>of());
    LOG.debug("Generated Apple binary target %s", target);
    return target;
  }

  private PBXNativeTarget generateAppleLibraryTarget(
      PBXProject project,
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode)
      throws IOException {
    boolean isShared = targetNode
        .getBuildTarget()
        .getFlavors()
        .contains(CxxDescriptionEnhancer.SHARED_FLAVOR);
    PBXTarget.ProductType productType = isShared ?
        PBXTarget.ProductType.DYNAMIC_LIBRARY :
        PBXTarget.ProductType.STATIC_LIBRARY;
    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.<TargetNode<AppleBundleDescription.Arg>>absent(),
        targetNode,
        productType,
        AppleBuildRules.getOutputFileNameFormatForLibrary(isShared),
        Optional.<Path>absent(),
        /* includeFrameworks */ isShared,
        ImmutableSet.<AppleResourceDescription.Arg>of(),
        ImmutableSet.<AppleAssetCatalogDescription.Arg>of());
    LOG.debug("Generated iOS library target %s", target);
    return target;
  }

  private void writeHeaderMap(
      HeaderMap headerMap,
      Path headerMapFile)
      throws IOException {
    if (headerMap.getNumEntries() == 0) {
      return;
    }
    headerMaps.add(headerMapFile);
    projectFilesystem.mkdirs(headerMapFile.getParent());
    if (shouldGenerateReadOnlyFiles()) {
      projectFilesystem.writeBytesToPath(
          headerMap.getBytes(),
          headerMapFile,
          READ_ONLY_FILE_ATTRIBUTE);
    } else {
      projectFilesystem.writeBytesToPath(
          headerMap.getBytes(),
          headerMapFile);
    }
  }

  private PBXNativeTarget generateBinaryTarget(
      PBXProject project,
      Optional<? extends TargetNode<? extends HasAppleBundleFields>> bundle,
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode,
      PBXTarget.ProductType productType,
      String productOutputFormat,
      Optional<Path> infoPlistOptional,
      boolean includeFrameworks,
      ImmutableSet<AppleResourceDescription.Arg> resources,
      ImmutableSet<AppleAssetCatalogDescription.Arg> assetCatalogs)
      throws IOException {
    Optional<String> targetGid = targetNode.getConstructorArg().gid;
    LOG.debug("Generating binary target for node %s (GID %s)", targetNode, targetGid);
    if (targetGid.isPresent()) {
      // Check if we have used this hardcoded GID before.
      // If not, remember it so we don't use it again.
      String thisTargetName = targetNode.getBuildTarget().getFullyQualifiedName();
      String conflictingTargetName = gidsToTargetNames.get(targetGid.get());
      if (conflictingTargetName != null) {
        throw new HumanReadableException(
            "Targets %s have the same hardcoded GID (%s)",
            ImmutableSortedSet.of(thisTargetName, conflictingTargetName),
            targetGid.get());
      }
      gidsToTargetNames.put(targetGid.get(), thisTargetName);
    }

    TargetNode<?> buildTargetNode = bundle.isPresent() ? bundle.get() : targetNode;
    final BuildTarget buildTarget = buildTargetNode.getBuildTarget();

    String productName = getProductName(buildTarget);
    AppleNativeTargetDescriptionArg arg = targetNode.getConstructorArg();
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        pathRelativizer,
        sourcePathResolver);
    ImmutableSet<SourcePath> exportedHeaders =
        ImmutableSet.copyOf(getHeaderSourcePaths(arg.exportedHeaders));
    ImmutableSet<SourcePath> headers = ImmutableSet.copyOf(getHeaderSourcePaths(arg.headers));
    mutator
        .setTargetName(getXcodeTargetName(buildTarget))
        .setProduct(
            productType,
            productName,
            Paths.get(String.format(productOutputFormat, productName)))
        .setGid(targetGid)
        .setShouldGenerateCopyHeadersPhase(
            !targetNode.getConstructorArg().getUseBuckHeaderMaps())
        .setSourcesWithFlags(ImmutableSet.copyOf(arg.srcs.get()))
        .setExtraXcodeSources(ImmutableSet.copyOf(arg.extraXcodeSources.get()))
        .setPublicHeaders(exportedHeaders)
        .setPrivateHeaders(headers)
        .setResources(resources);

    if (options.contains(Option.CREATE_DIRECTORY_STRUCTURE)) {
      ImmutableList.Builder<String> targetGroupPathBuilder = ImmutableList.builder();
      for (Path pathPart : buildTarget.getBasePath()) {
        targetGroupPathBuilder.add(pathPart.toString());
      }
      mutator.setTargetGroupPath(targetGroupPathBuilder.build());
    }

    if (!assetCatalogs.isEmpty()) {
      mutator.setAssetCatalogs(getAndMarkAssetCatalogBuildScript(), assetCatalogs);
    }

    if (includeFrameworks) {
      ImmutableSet.Builder<FrameworkPath> frameworksBuilder = ImmutableSet.builder();
      frameworksBuilder.addAll(targetNode.getConstructorArg().frameworks.get());
      frameworksBuilder.addAll(collectRecursiveFrameworkDependencies(ImmutableList.of(targetNode)));
      mutator.setFrameworks(frameworksBuilder.build());
      mutator.setArchives(
          collectRecursiveLibraryDependencies(ImmutableList.of(targetNode)));
    }

    // TODO(Task #3772930): Go through all dependencies of the rule
    // and add any shell script rules here
    ImmutableList.Builder<TargetNode<?>> preScriptPhases = ImmutableList.builder();
    ImmutableList.Builder<TargetNode<?>> postScriptPhases = ImmutableList.builder();
    if (bundle.isPresent() && targetNode != bundle.get()) {
      collectBuildScriptDependencies(
          targetGraph.getAll(bundle.get().getDeclaredDeps()),
          preScriptPhases,
          postScriptPhases);
    }
    collectBuildScriptDependencies(
        targetGraph.getAll(targetNode.getDeclaredDeps()),
        preScriptPhases,
        postScriptPhases);
    mutator.setPreBuildRunScriptPhases(preScriptPhases.build());
    mutator.setPostBuildRunScriptPhases(postScriptPhases.build());

    NewNativeTargetProjectMutator.Result targetBuilderResult;
    try {
      targetBuilderResult = mutator.buildTargetAndAddToProject(project);
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e);
    }
    PBXNativeTarget target = targetBuilderResult.target;
    PBXGroup targetGroup = targetBuilderResult.targetGroup;

    SourceTreePath buckFilePath = new SourceTreePath(
        PBXReference.SourceTree.SOURCE_ROOT,
        pathRelativizer.outputPathToBuildTargetPath(buildTarget).resolve(buildFileName));
    PBXFileReference buckReference =
        targetGroup.getOrCreateFileReferenceBySourceTreePath(buckFilePath);
    buckReference.setExplicitFileType(Optional.of("text.script.python"));

    // -- configurations
    ImmutableMap.Builder<String, String> extraSettingsBuilder = ImmutableMap.builder();
    extraSettingsBuilder
        .put("TARGET_NAME", getProductName(buildTarget))
        .put("SRCROOT", pathRelativizer.outputPathToBuildTargetPath(buildTarget).toString());
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
    if (targetNode.getConstructorArg().getUseBuckHeaderMaps()) {
      extraSettingsBuilder.put("USE_HEADERMAP", "NO");
    }

    ImmutableMap.Builder<String, String> defaultSettingsBuilder = ImmutableMap.builder();
    defaultSettingsBuilder.put(
        "REPO_ROOT",
        projectFilesystem.getRootPath().toAbsolutePath().normalize().toString());
    defaultSettingsBuilder.put("PRODUCT_NAME", getProductName(buildTarget));
    if (bundle.isPresent()) {
      defaultSettingsBuilder.put(
          "WRAPPER_EXTENSION",
          getExtensionString(bundle.get().getConstructorArg().getExtension()));
    }
    defaultSettingsBuilder.put(
        "PUBLIC_HEADERS_FOLDER_PATH",
        getHeaderOutputPath(targetNode.getConstructorArg().headerPathPrefix));
    // We use BUILT_PRODUCTS_DIR as the root for the everything being built. Target-
    // specific output is placed within CONFIGURATION_BUILD_DIR, inside BUILT_PRODUCTS_DIR.
    // That allows Copy Files build phases to reference files in the CONFIGURATION_BUILD_DIR
    // of other targets by using paths relative to the target-independent BUILT_PRODUCTS_DIR.
    defaultSettingsBuilder.put(
        "BUILT_PRODUCTS_DIR",
        // $EFFECTIVE_PLATFORM_NAME starts with a dash, so this expands to something like:
        // $SYMROOT/Debug-iphonesimulator
        Joiner.on('/').join("$SYMROOT", "$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"));
    defaultSettingsBuilder.put("CONFIGURATION_BUILD_DIR", getTargetOutputPath(buildTargetNode));
    if (!bundle.isPresent() && targetNode.getType().equals(AppleLibraryDescription.TYPE)) {
      defaultSettingsBuilder.put("EXECUTABLE_PREFIX", "lib");
    }

    ImmutableMap.Builder<String, String> appendConfigsBuilder = ImmutableMap.builder();
    appendConfigsBuilder
        .put(
            "HEADER_SEARCH_PATHS",
            Joiner.on(' ').join(
                Iterators.concat(
                    collectRecursiveHeaderSearchPaths(targetNode).iterator(),
                    collectRecursiveHeaderMaps(targetNode).iterator())))
        .put(
            "LIBRARY_SEARCH_PATHS",
            Joiner.on(' ').join(
                collectRecursiveLibrarySearchPaths(ImmutableSet.of(targetNode))))
        .put(
            "FRAMEWORK_SEARCH_PATHS",
            Joiner.on(' ').join(
                collectRecursiveFrameworkSearchPaths(ImmutableList.of(targetNode))))
        .put(
            "OTHER_CFLAGS",
            Joiner
                .on(' ')
                .join(
                    Iterables.concat(
                        targetNode.getConstructorArg().compilerFlags.get(),
                        targetNode.getConstructorArg().preprocessorFlags.get(),
                        collectRecursiveExportedPreprocessorFlags(ImmutableList.of(targetNode)))))
        .put(
            "OTHER_LDFLAGS",
            Joiner.on(' ').join(collectRecursiveLinkerFlags(ImmutableList.of(targetNode))));

    setTargetBuildConfigurations(
        new Function<String, Path>() {
          @Override
          public Path apply(String input) {
            return BuildTargets.getGenPath(buildTarget, "%s-" + input + ".xcconfig");
          }
        },
        target,
        targetGroup,
        targetNode.getConstructorArg().configs.get(),
        extraSettingsBuilder.build(),
        defaultSettingsBuilder.build(),
        appendConfigsBuilder.build());

    // -- phases
    if (targetNode.getConstructorArg().getUseBuckHeaderMaps()) {
      Path headerPathPrefix =
          AppleDescriptions.getHeaderPathPrefix(arg, targetNode.getBuildTarget());
      createHeaderMap(
          sourcePathResolver,
          AppleDescriptions.convertAppleHeadersToPublicCxxHeaders(
              sourcePathResolver,
              headerPathPrefix,
              arg),
          AppleDescriptions.getPathToHeaderMap(targetNode, HeaderMapType.PUBLIC_HEADER_MAP).get());
      createHeaderMap(
          sourcePathResolver,
          AppleDescriptions.convertAppleHeadersToPrivateCxxHeaders(
              sourcePathResolver,
              headerPathPrefix,
              arg),
          AppleDescriptions.getPathToHeaderMap(targetNode, HeaderMapType.TARGET_HEADER_MAP).get());
    }

    // Use Core Data models from immediate dependencies only.
    addCoreDataModelBuildPhase(
        targetGroup,
        FluentIterable
            .from(targetNode.getDeps())
            .transform(
                new Function<BuildTarget, TargetNode<?>>() {
                  @Override
                  public TargetNode<?> apply(BuildTarget input) {
                    return Preconditions.checkNotNull(targetGraph.get(input));
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

    return target;
  }

  private Iterable<SourcePath> getHeaderSourcePaths(
      Optional<Either<ImmutableSortedSet<SourcePath>, ImmutableMap<String, SourcePath>>> headers) {
    if (!headers.isPresent()) {
      return ImmutableList.of();
    } else if (headers.get().isLeft()) {
      return headers.get().getLeft();
    } else {
      return headers.get().getRight().values();
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
            dylibProductTypeByBundleExtension(key.getExtension().getLeft()).get(),
            productName,
            Paths.get(productName + "." + getExtensionString(key.getExtension())))
        .setShouldGenerateCopyHeadersPhase(false)
        .setSourcesWithFlags(
            ImmutableSet.of(
                SourceWithFlags.of(
                    new PathSourcePath(projectFilesystem, emptyFileWithExtension("c")))))
        .setArchives(Sets.union(collectRecursiveLibraryDependencies(tests), testLibs.build()))
        .setResources(AppleResources.collectRecursiveResources(targetGraph, tests))
        .setAssetCatalogs(
            getAndMarkAssetCatalogBuildScript(),
            collectRecursiveAssetCatalogs(tests));

    ImmutableSet.Builder<FrameworkPath> frameworksBuilder = ImmutableSet.builder();
    frameworksBuilder.addAll(collectRecursiveFrameworkDependencies(tests));
    for (TargetNode<AppleTestDescription.Arg> test : tests) {
      frameworksBuilder.addAll(test.getConstructorArg().frameworks.get());
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
        result.targetGroup,
        key.getConfigs().get(),
        overrideBuildSettingsBuilder.build(),
        ImmutableMap.of(
            "PRODUCT_NAME", productName,
            "WRAPPER_EXTENSION", getExtensionString(key.getExtension())),
        ImmutableMap.of(
            "FRAMEWORK_SEARCH_PATHS", Joiner.on(' ').join(
                collectRecursiveFrameworkSearchPaths(tests)),
            "LIBRARY_SEARCH_PATHS", Joiner.on(' ').join(
                collectRecursiveLibrarySearchPaths(tests))));
    buildableCombinedTestTargets.add(result.target);
  }

  private String deriveCombinedTestTargetNameFromKey(
      AppleTestBundleParamsKey key,
      int combinedTestIndex) {
    return Joiner.on("-").join(
        "_BuckCombinedTest",
        getExtensionString(key.getExtension()),
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

    PBXGroup configurationsGroup = targetGroup.getOrCreateChildGroupByName("Configurations");

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

      PBXFileReference fileReference =
          configurationsGroup.getOrCreateFileReferenceBySourceTreePath(
              new SourceTreePath(
                  PBXReference.SourceTree.SOURCE_ROOT,
                  pathRelativizer.outputDirToRootRelative(xcconfigPath)));
      outputConfiguration.setBaseConfigurationReference(fileReference);
    }
  }

  private void collectBuildScriptDependencies(
      Iterable<TargetNode<?>> targetNodes,
      ImmutableList.Builder<TargetNode<?>> preRules,
      ImmutableList.Builder<TargetNode<?>> postRules) {
    for (TargetNode<?> targetNode : targetNodes) {
      if (targetNode.getType().equals(IosPostprocessResourcesDescription.TYPE)) {
        postRules.add(targetNode);
      } else if (targetNode.getType().equals(GenruleDescription.TYPE)) {
        preRules.add(targetNode);
      }
    }
  }

  private void createHeaderMap(
      Function<SourcePath, Path> pathResolver,
      Map<String, SourcePath> contents,
      Path headerMapFile) throws IOException {
    LOG.verbose(
        "Writing header map file to %s with contents %s",
        headerMapFile,
        contents);
    HeaderMap.Builder builder = HeaderMap.builder();
    for (Map.Entry<String, SourcePath> entry : contents.entrySet()) {
      builder.add(entry.getKey(), projectFilesystem.resolve(pathResolver.apply(entry.getValue())));
    }
    writeHeaderMap(builder.build(), headerMapFile);
  }

  private void addCoreDataModelBuildPhase(
      PBXGroup targetGroup,
      Iterable<CoreDataModelDescription.Arg> dataModels) throws IOException {
    // TODO(user): actually add a build phase

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
                    pathRelativizer.outputDirToRootRelative(dataModel.path)));

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
                        pathRelativizer.outputDirToRootRelative(dir)));
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
                      dataModel.path.resolve(currentVersionName.toString()))));
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
                pathRelativizer.outputDirToRootRelative(dataModel.path)));
      }
    }
  }

  private Optional<PBXCopyFilesBuildPhase.Destination> getDestination(TargetNode<?> targetNode) {
    if (targetNode.getType().equals(AppleBundleDescription.TYPE)) {
      AppleBundleDescription.Arg arg = (AppleBundleDescription.Arg) targetNode.getConstructorArg();
      AppleBundleExtension extension = arg.extension.isLeft() ?
          arg.extension.getLeft() :
          AppleBundleExtension.BUNDLE;
      switch (extension) {
        case FRAMEWORK:
          return Optional.of(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS);
        case APPEX:
        case PLUGIN:
          return Optional.of(PBXCopyFilesBuildPhase.Destination.PLUGINS);
        case APP:
          return Optional.of(PBXCopyFilesBuildPhase.Destination.EXECUTABLES);
        //$CASES-OMITTED$
      default:
          return Optional.of(PBXCopyFilesBuildPhase.Destination.PRODUCTS);
      }
    } else if (targetNode.getType().equals(AppleLibraryDescription.TYPE)) {
      if (targetNode
          .getBuildTarget()
          .getFlavors()
          .contains(CxxDescriptionEnhancer.SHARED_FLAVOR)) {
        return Optional.of(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS);
      } else {
        return Optional.absent();
      }
    } else if (targetNode.getType().equals(AppleBinaryDescription.TYPE)) {
      return Optional.of(PBXCopyFilesBuildPhase.Destination.EXECUTABLES);
    } else {
      throw new RuntimeException("Unexpected type: " + targetNode.getType());
    }
  }

  private void generateCopyFilesBuildPhases(
      PBXNativeTarget target,
      Iterable<TargetNode<?>> copiedNodes) {

    // Bucket build rules into bins by their destinations
    ImmutableSetMultimap.Builder<PBXCopyFilesBuildPhase.Destination, TargetNode<?>>
        ruleByDestinationBuilder = ImmutableSetMultimap.builder();
    for (TargetNode<?> copiedNode : copiedNodes) {
      Optional<PBXCopyFilesBuildPhase.Destination> optionalDestination =
          getDestination(copiedNode);
      if (optionalDestination.isPresent()) {
        ruleByDestinationBuilder.put(optionalDestination.get(), copiedNode);
      }
    }
    ImmutableSetMultimap<PBXCopyFilesBuildPhase.Destination, TargetNode<?>> ruleByDestination =
        ruleByDestinationBuilder.build();

    // Emit a copy files phase for each destination.
    for (PBXCopyFilesBuildPhase.Destination destination : ruleByDestination.keySet()) {
      PBXCopyFilesBuildPhase copyFilesBuildPhase = new PBXCopyFilesBuildPhase(destination, "");
      target.getBuildPhases().add(copyFilesBuildPhase);
      for (TargetNode<?> targetNode : ruleByDestination.get(destination)) {
        PBXFileReference fileReference = getLibraryFileReference(targetNode);
        copyFilesBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
      }
    }
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

  private static String getProductName(BuildTarget buildTarget) {
    return buildTarget.getShortName();
  }

  private String getHeaderOutputPath(Optional<String> headerPathPrefix) {
    // This is automatically appended to $CONFIGURATION_BUILD_DIR.
    return Joiner.on('/').join(
        "Headers",
        headerPathPrefix.or("$TARGET_NAME"));
  }

  /**
   * @param targetNode Must have a header map or an exception will be thrown.
   */
  private String getHeaderMapRelativePath(
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode,
      HeaderMapType headerMapType) {
    Optional<Path> filePath = AppleDescriptions.getPathToHeaderMap(
        targetNode,
        headerMapType);
    Preconditions.checkState(filePath.isPresent(), "%s does not have a header map.", targetNode);
    return pathRelativizer.outputDirToRootRelative(filePath.get()).toString();
  }

  private String getHeaderSearchPath(TargetNode<?> targetNode) {
    return Joiner.on('/').join(
        getTargetOutputPath(targetNode),
        "Headers");
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
  private static Optional<TargetNode<AppleNativeTargetDescriptionArg>> getAppleNativeNodeOfType(
      TargetGraph targetGraph,
      TargetNode<?> targetNode,
      Set<BuildRuleType> nodeTypes,
      Set<AppleBundleExtension> bundleExtensions) {
    Optional<TargetNode<AppleNativeTargetDescriptionArg>> nativeNode = Optional.absent();
    if (nodeTypes.contains(targetNode.getType())) {
      nativeNode = Optional.of((TargetNode<AppleNativeTargetDescriptionArg>) targetNode);
    } else if (targetNode.getType().equals(AppleBundleDescription.TYPE)) {
      TargetNode<AppleBundleDescription.Arg> bundle =
          (TargetNode<AppleBundleDescription.Arg>) targetNode;
      Either<AppleBundleExtension, String> extension = bundle.getConstructorArg().getExtension();
      if (extension.isLeft() && bundleExtensions.contains(extension.getLeft())) {
        nativeNode = Optional.of(
            Preconditions.checkNotNull(
                (TargetNode<AppleNativeTargetDescriptionArg>) targetGraph.get(
                    bundle.getConstructorArg().binary)));
      }
    }
    return nativeNode;
  }

  private static Optional<TargetNode<AppleNativeTargetDescriptionArg>> getAppleNativeNode(
      TargetGraph targetGraph,
      TargetNode<?> targetNode) {
    return getAppleNativeNodeOfType(
        targetGraph,
        targetNode,
        ImmutableSet.of(
            AppleBinaryDescription.TYPE,
            AppleLibraryDescription.TYPE),
        ImmutableSet.of(
            AppleBundleExtension.APP,
            AppleBundleExtension.FRAMEWORK));
  }

  private static Optional<TargetNode<AppleNativeTargetDescriptionArg>> getLibraryNode(
      TargetGraph targetGraph,
      TargetNode<?> targetNode) {
    return getAppleNativeNodeOfType(
        targetGraph,
        targetNode,
        ImmutableSet.of(
            AppleLibraryDescription.TYPE),
        ImmutableSet.of(
            AppleBundleExtension.FRAMEWORK));
  }

  private ImmutableSet<String> collectRecursiveHeaderSearchPaths(
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode) {
    return FluentIterable
        .from(
            AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.BUILDING,
                targetNode,
                Optional.of(AppleBuildRules.XCODE_TARGET_BUILD_RULE_TYPES)))
        .filter(
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> input) {
                Optional<TargetNode<AppleNativeTargetDescriptionArg>> nativeNode =
                    getAppleNativeNode(targetGraph, input);
                return nativeNode.isPresent() &&
                    !nativeNode.get().getConstructorArg().getUseBuckHeaderMaps();
              }
            })
        .transform(
            new Function<TargetNode<?>, String>() {
              @Override
              public String apply(TargetNode<?> input) {
                return getHeaderSearchPath(input);
              }
            })
        .toSet();
  }

  private ImmutableSet<String> collectRecursiveHeaderMaps(
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode) {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    if (targetNode.getConstructorArg().getUseBuckHeaderMaps()) {
      builder.add(getHeaderMapRelativePath(targetNode, HeaderMapType.TARGET_HEADER_MAP));
      builder.add(getHeaderMapRelativePath(targetNode, HeaderMapType.PUBLIC_HEADER_MAP));
    }

    for (TargetNode<?> input :
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            AppleBuildRules.RecursiveDependenciesMode.BUILDING,
            targetNode,
            Optional.of(AppleBuildRules.XCODE_TARGET_BUILD_RULE_TYPES))) {
      Optional<TargetNode<AppleNativeTargetDescriptionArg>> nativeNode =
          getAppleNativeNode(targetGraph, input);
      if (nativeNode.isPresent() && nativeNode.get().getConstructorArg().getUseBuckHeaderMaps()) {
        builder.add(getHeaderMapRelativePath(nativeNode.get(), HeaderMapType.PUBLIC_HEADER_MAP));
      }
    }

    addHeaderMapsForSourceUnderTest(targetNode, builder, HeaderMapType.TARGET_HEADER_MAP);

    return builder.build();
  }

  private void addHeaderMapsForSourceUnderTest(
      TargetNode<? extends AppleNativeTargetDescriptionArg> targetNode,
      ImmutableSet.Builder<String> headerMapsBuilder,
      HeaderMapType headerMapType) {
    ImmutableSet<TargetNode<?>> directDependencies = ImmutableSet.copyOf(
        targetGraph.getAll(targetNode.getDeps()));
    for (TargetNode<?> dependency : directDependencies) {
      Optional<TargetNode<AppleNativeTargetDescriptionArg>> nativeNode =
          getAppleNativeNode(targetGraph, dependency);
      if (nativeNode.isPresent() &&
          isSourceUnderTest(dependency, nativeNode.get(), targetNode) &&
          nativeNode.get().getConstructorArg().getUseBuckHeaderMaps()) {
        headerMapsBuilder.add(
            getHeaderMapRelativePath(
                nativeNode.get(),
                headerMapType));
      }
    }
  }

  private boolean isSourceUnderTest(
      TargetNode<?> dependencyNode,
      TargetNode<AppleNativeTargetDescriptionArg> nativeNode,
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
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                ImmutableSet.of(AppleLibraryDescription.TYPE)))
        .transform(
            new Function<TargetNode<?>, String>() {
              @Override
              public String apply(TargetNode<?> input) {
                return getTargetOutputPath(input);
              }
            })
        .append(
            collectRecursiveSearchPathsForFrameworkPaths(
                targetNodes,
                FrameworkPath.FrameworkType.LIBRARY))
        .toSet();
  }

  private <T> ImmutableSet<String> collectRecursiveFrameworkSearchPaths(
      Iterable<TargetNode<T>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                ImmutableSet.of(AppleBundleDescription.TYPE)))
        .filter(
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> input) {
                return getLibraryNode(targetGraph, input).isPresent();
              }
            })
        .transform(
            new Function<TargetNode<?>, String>() {
              @Override
              public String apply(TargetNode<?> input) {
                return getTargetOutputPath(input);
              }
            })
        .append(
            collectRecursiveSearchPathsForFrameworkPaths(
                targetNodes,
                FrameworkPath.FrameworkType.FRAMEWORK))
        .toSet();
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
                Optional<TargetNode<AppleNativeTargetDescriptionArg>> library =
                    getLibraryNode(targetGraph, input);
                if (library.isPresent() &&
                    !AppleLibraryDescription.isSharedLibraryTarget(
                        library.get().getBuildTarget())) {
                  return library.get().getConstructorArg().frameworks.get();
                } else {
                  return ImmutableList.of();
                }
              }
            });
  }

  private <T> Iterable<String> collectRecursiveSearchPathsForFrameworkPaths(
      Iterable<TargetNode<T>> targetNodes,
      final FrameworkPath.FrameworkType type) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                ImmutableSet.of(AppleLibraryDescription.TYPE)))
        .append(targetNodes)
        .transformAndConcat(
            new Function<TargetNode<?>, Iterable<String>>() {
              @Override
              public Iterable<String> apply(TargetNode<?> input) {
                return input
                    .castArg(AppleNativeTargetDescriptionArg.class)
                    .transform(getTargetFrameworkSearchPaths(type))
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
                ImmutableSet.of(AppleLibraryDescription.TYPE)))
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

  private <T> Iterable<String> collectRecursiveLinkerFlags(Iterable<TargetNode<T>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                ImmutableSet.of(AppleLibraryDescription.TYPE)))
        .append(targetNodes)
        .transformAndConcat(
            new Function<TargetNode<?>, Iterable<? extends String>>() {
              @Override
              public Iterable<String> apply(TargetNode<?> input) {
                return input
                    .castArg(AppleNativeTargetDescriptionArg.class)
                    .transform(GET_LINKER_FLAGS)
                    .or(ImmutableSet.<String>of());
              }
            });
  }

  private <T> ImmutableSet<PBXFileReference> collectRecursiveLibraryDependencies(
      Iterable<TargetNode<T>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                AppleBuildRules.XCODE_TARGET_BUILD_RULE_TYPES))
        .filter(
            new Predicate<TargetNode<?>>() {
              @Override
              public boolean apply(TargetNode<?> input) {
                return getLibraryNode(targetGraph, input).isPresent();
              }
            })
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
      Iterable<String>> getTargetFrameworkSearchPaths(final FrameworkPath.FrameworkType type) {

    final Predicate<FrameworkPath> byType = Predicates.compose(
        Predicates.equalTo(type),
        FrameworkPath.getFrameworkTypeFunction(sourcePathResolver));

    final Function<FrameworkPath, Path> toSearchPath = FrameworkPath
        .getUnexpandedSearchPathFunction(
            sourcePathResolver,
            pathRelativizer.outputDirToRootRelative());

    return new Function<TargetNode<AppleNativeTargetDescriptionArg>, Iterable<String>>() {
      @Override
      public Iterable<String> apply(TargetNode<AppleNativeTargetDescriptionArg> input) {
        return FluentIterable
            .from(input.getConstructorArg().frameworks.get())
            .filter(byType)
            .transform(toSearchPath)
            .transform(Functions.toStringFunction());
      }
    };
  }

  private SourceTreePath getProductsSourceTreePath(TargetNode<?> targetNode) {
    String productName = getProductName(targetNode.getBuildTarget());
    String productOutputName;

    if (targetNode.getType().equals(AppleLibraryDescription.TYPE)) {
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

    String productOutputRelativePath = Joiner.on('/')
        .join(getBuiltProductsRelativeTargetOutputPath(targetNode), productOutputName);

    return new SourceTreePath(
        PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
        Paths.get(productOutputRelativePath));
  }

  private PBXFileReference getLibraryFileReference(TargetNode<?> targetNode) {
    // Don't re-use the productReference from other targets in this project.
    // File references set as a productReference don't work with custom paths.
    SourceTreePath productsPath = getProductsSourceTreePath(targetNode);

    if (targetNode.getType().equals(AppleLibraryDescription.TYPE) ||
        targetNode.getType().equals(AppleBundleDescription.TYPE)) {
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
                getProductName(test.getBuildTarget()))));
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

  /**
   * Collect asset catalogs from recursive dependencies.
   */
  private <T> ImmutableSet<AppleAssetCatalogDescription.Arg> collectRecursiveAssetCatalogs(
      Iterable<TargetNode<T>> targetNodes) {
    return FluentIterable
        .from(targetNodes)
        .transformAndConcat(
            AppleBuildRules.newRecursiveRuleDependencyTransformer(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.COPYING,
                ImmutableSet.of(AppleAssetCatalogDescription.TYPE)))
        .transform(
            new Function<TargetNode<?>, AppleAssetCatalogDescription.Arg>() {
              @Override
              public AppleAssetCatalogDescription.Arg apply(TargetNode<?> input) {
                return (AppleAssetCatalogDescription.Arg) input.getConstructorArg();
              }
            })
        .toSet();
  }

  @SuppressWarnings("incomplete-switch")
  PBXTarget.ProductType bundleToTargetProductType(
      TargetNode<? extends HasAppleBundleFields> targetNode,
      TargetNode<? extends AppleNativeTargetDescriptionArg> binaryNode) {
    if (targetNode.getConstructorArg().getXcodeProductType().isPresent()) {
      return ImmutableProductType.of(targetNode.getConstructorArg().getXcodeProductType().get());
    } else if (targetNode.getConstructorArg().getExtension().isLeft()) {
      AppleBundleExtension extension = targetNode.getConstructorArg().getExtension().getLeft();

      if (binaryNode.getType().equals(AppleLibraryDescription.TYPE)) {
        if (binaryNode.getBuildTarget().getFlavors().contains(
            CxxDescriptionEnhancer.SHARED_FLAVOR)) {
          Optional<PBXTarget.ProductType> productType =
              dylibProductTypeByBundleExtension(extension);
          if (productType.isPresent()) {
            return productType.get();
          }
        } else {
          switch (extension) {
            case FRAMEWORK:
              return PBXTarget.ProductType.STATIC_FRAMEWORK;
          }
        }
      } else if (binaryNode.getType().equals(AppleBinaryDescription.TYPE)) {
        switch (extension) {
          case APP:
            return PBXTarget.ProductType.APPLICATION;
        }
      } else if (binaryNode.getType().equals(AppleTestDescription.TYPE)) {
        switch (extension) {
          case OCTEST:
            return PBXTarget.ProductType.BUNDLE;
          case XCTEST:
            return PBXTarget.ProductType.UNIT_TEST;
        }
      }
    }

    return PBXTarget.ProductType.BUNDLE;
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

  /**
   * Retrieve the location of the asset catalog build script.
   *
   * If the file is provided by buck and needs to be copied, mark it as such in the project.
   */
  private Path getAndMarkAssetCatalogBuildScript() {
    if (PATH_OVERRIDE_FOR_ASSET_CATALOG_BUILD_PHASE_SCRIPT != null) {
      return Paths.get(PATH_OVERRIDE_FOR_ASSET_CATALOG_BUILD_PHASE_SCRIPT);
    } else {
      // In order for the script to run, it must be accessible by Xcode and
      // deserves to be part of the generated output.
      shouldPlaceAssetCatalogCompiler = true;
      return placedAssetCatalogBuildPhaseScript;
    }
  }

  private Path emptyFileWithExtension(String extension) {
    Path path = BuckConstant.GEN_PATH.resolve("xcode-scripts/emptyFile." + extension);
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
    TargetNode<?> node = Preconditions.checkNotNull(targetGraph.get(buildTarget));
    Optional<TargetNode<ExportFileDescription.Arg>> exportFileNode = node.castArg(
        ExportFileDescription.Arg.class);
    if (!exportFileNode.isPresent()) {
      Path output = outputPathOfNode.apply(node);
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

  /**
   * @return product type of a bundle containing a dylib.
   */
  private static Optional<PBXTarget.ProductType> dylibProductTypeByBundleExtension(
      AppleBundleExtension extension) {
    switch (extension) {
      case FRAMEWORK:
        return Optional.of(PBXTarget.ProductType.FRAMEWORK);
      case APPEX:
        return Optional.of(PBXTarget.ProductType.APP_EXTENSION);
      case BUNDLE:
        return Optional.of(PBXTarget.ProductType.BUNDLE);
      case OCTEST:
        return Optional.of(PBXTarget.ProductType.BUNDLE);
      case XCTEST:
        return Optional.of(PBXTarget.ProductType.UNIT_TEST);
      // $CASES-OMITTED$
      default:
        return Optional.absent();
    }
  }
}
