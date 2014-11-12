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

package com.facebook.buck.apple.xcode;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.AbstractAppleNativeTargetBuildRule.HeaderMapType;
import com.facebook.buck.apple.AbstractAppleNativeTargetBuildRuleDescriptions;
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibrary;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.apple.FileExtensions;
import com.facebook.buck.apple.GroupedSource;
import com.facebook.buck.apple.HeaderVisibility;
import com.facebook.buck.apple.IosPostprocessResourcesDescription;
import com.facebook.buck.apple.TargetSources;
import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.apple.xcode.xcconfig.XcconfigStack;
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
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.XcodeRuleConfiguration;
import com.facebook.buck.rules.coercer.XcodeRuleConfigurationLayer;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
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
    /**
     * Attempt to generate projects with configurations in the standard xcode configuration layout.
     *
     * Checks that the rules declare their configurations in either
     * - 4 layers: file-project inline-project file-target inline-target
     * - 2 layers: file-project file-target
     *
     * Additionally, all project-level layers should be identical amongst all targets in the
     * project.
     */
    REFERENCE_EXISTING_XCCONFIGS,

    /** Use short BuildTarget name instead of full name for targets */
    USE_SHORT_NAMES_FOR_TARGETS,

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
      Option.REFERENCE_EXISTING_XCCONFIGS,
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

  private final SourcePathResolver resolver;
  private final TargetGraph targetGraph;
  private final ProjectFilesystem projectFilesystem;
  private final ExecutionContext executionContext;
  private final Path outputDirectory;
  private final String projectName;
  private final ImmutableSet<BuildTarget> initialTargets;
  private final Path projectPath;
  private final Path placedAssetCatalogBuildPhaseScript;
  private final PathRelativizer pathRelativizer;

  private final ImmutableSet<Option> options;

  // These fields are created/filled when creating the projects.
  private final PBXProject project;
  private final LoadingCache<TargetNode<?>, Optional<PBXTarget>> targetNodeToProjectTarget;
  private boolean shouldPlaceAssetCatalogCompiler = false;
  private final ImmutableMap.Builder<TargetNode<?>, PBXTarget>
      targetNodeToGeneratedProjectTargetBuilder;
  private boolean projectGenerated;
  private List<Path> headerMaps;

  /**
   * Populated while generating project configurations, in order to collect the possible
   * project-level configurations to set when operation with
   * {@link Option#REFERENCE_EXISTING_XCCONFIGS}.
   */
  private final ImmutableMultimap.Builder<String, ConfigInXcodeLayout>
    xcodeConfigurationLayersMultimapBuilder;

  private Map<String, String> gidsToTargetNames;

  public ProjectGenerator(
      TargetGraph targetGraph,
      Set<BuildTarget> initialTargets,
      ProjectFilesystem projectFilesystem,
      ExecutionContext executionContext,
      Path outputDirectory,
      String projectName,
      Set<Option> options) {
    this.resolver = new SourcePathResolver(
        new BuildRuleResolver(
            ImmutableSet.copyOf(
                targetGraph.getActionGraph(executionContext.getBuckEventBus()).getNodes())));
    this.targetGraph = targetGraph;
    this.initialTargets = ImmutableSet.copyOf(initialTargets);
    this.projectFilesystem = projectFilesystem;
    this.executionContext = executionContext;
    this.outputDirectory = outputDirectory;
    this.projectName = projectName;
    this.options = ImmutableSet.copyOf(options);

    this.projectPath = outputDirectory.resolve(projectName + ".xcodeproj");
    this.pathRelativizer = new PathRelativizer(
        projectFilesystem.getRootPath(),
        outputDirectory,
        resolver);

    LOG.debug(
        "Output directory %s, profile fs root path %s, repo root relative to output dir %s",
        this.outputDirectory,
        projectFilesystem.getRootPath(),
        this.pathRelativizer.outputDirToRootRelative(Paths.get(".")));

    this.placedAssetCatalogBuildPhaseScript =
        BuckConstant.BIN_PATH.resolve("xcode-scripts/compile_asset_catalogs_build_phase.sh");

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

    xcodeConfigurationLayersMultimapBuilder = ImmutableMultimap.builder();
    gidsToTargetNames = new HashMap<>();
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

  public void createXcodeProjects() throws IOException {
    LOG.debug("Creating projects for targets %s", initialTargets);

    try {
      for (TargetNode<?> targetNode : targetGraph.getNodes()) {
        if (isBuiltByCurrentProject(targetNode.getBuildTarget())) {
          LOG.debug("Including rule %s in project", targetNode);
          // Trigger the loading cache to call the generateProjectTarget function.
          Optional<PBXTarget> target = targetNodeToProjectTarget.getUnchecked(targetNode);
          if (target.isPresent()) {
            // TODO(grp, t4964329): SchemeGenerator should look for the AppleTest rule itself.
            if (targetNode.getType().equals(AppleTestDescription.TYPE)) {
              AppleTestDescription.Arg arg =
                  (AppleTestDescription.Arg) targetNode.getConstructorArg();
              targetNode = targetGraph.get(arg.testBundle);
            }
            targetNodeToGeneratedProjectTargetBuilder.put(targetNode, target.get());
          }
        } else {
          LOG.verbose("Excluding rule %s (not built by current project)", targetNode);
        }
      }

      if (options.contains(Option.REFERENCE_EXISTING_XCCONFIGS)) {
        setProjectLevelConfigs(
            resolver,
            project,
            collectProjectLevelConfigsIfIdenticalOrFail(
                xcodeConfigurationLayersMultimapBuilder.build()));
      }

      writeProjectFile(project);

      if (shouldPlaceAssetCatalogCompiler) {
        Path placedAssetCatalogCompilerPath = projectFilesystem.getPathForRelativePath(
            BuckConstant.BIN_PATH.resolve(
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
      result = Optional.<PBXTarget>of(
          generateAppleBundleTarget(
              project,
              (TargetNode<AppleBundleDescription.Arg>) targetNode));
    } else if (targetNode.getType().equals(AppleTestDescription.TYPE)) {
      AppleTestDescription.Arg arg = (AppleTestDescription.Arg) targetNode.getConstructorArg();
      TargetNode<AppleBundleDescription.Arg> bundle =
          (TargetNode<AppleBundleDescription.Arg>) Preconditions.checkNotNull(
              targetGraph.get(arg.testBundle));
      if (bundle.getType().equals(AppleBundleDescription.TYPE)) {
        if (bundle.getConstructorArg().isTestBundle()) {
          result = Optional.<PBXTarget>of(generateAppleBundleTarget(project, bundle));
        } else {
          throw new HumanReadableException(
              "Incorrect extension: " + bundle.getConstructorArg().extension.getRight());
        }
      } else {
        throw new HumanReadableException("Test bundle should be a bundle: " + bundle);
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
        if (!projectFilesystem.isFile(resolver.getPath(file))) {
          throw new HumanReadableException(
              "%s specified in the files parameter of %s is not a regular file",
              file.toString(), resource.toString());
        }
      }
    }

    return result;
  }

  @SuppressWarnings("unchecked")
  private PBXNativeTarget generateAppleBundleTarget(
      PBXProject project,
      TargetNode<AppleBundleDescription.Arg> targetNode)
      throws IOException {
    Optional<Path> infoPlistPath;
    if (targetNode.getConstructorArg().infoPlist.isPresent()) {
      infoPlistPath = Optional.of(resolver.getPath(targetNode.getConstructorArg().infoPlist.get()));
    } else {
      infoPlistPath = Optional.absent();
    }

    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.of(targetNode),
        (TargetNode<AppleNativeTargetDescriptionArg>) Preconditions.checkNotNull(
            targetGraph.get(targetNode.getConstructorArg().binary)),
        bundleToTargetProductType(targetNode),
        "%s." + targetNode.getConstructorArg().getExtensionString(),
        infoPlistPath,
        /* includeFrameworks */ true,
        collectRecursiveResources(targetNode),
        collectRecursiveAssetCatalogs(targetNode));

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
      TargetNode<AppleNativeTargetDescriptionArg> targetNode)
      throws IOException {
    boolean isDynamic = targetNode
        .getBuildTarget()
        .getFlavors()
        .contains(AppleLibraryDescription.DYNAMIC_LIBRARY);
    PBXTarget.ProductType productType = isDynamic ?
        PBXTarget.ProductType.DYNAMIC_LIBRARY :
        PBXTarget.ProductType.STATIC_LIBRARY;
    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.<TargetNode<AppleBundleDescription.Arg>>absent(),
        targetNode,
        productType,
        AppleLibrary.getOutputFileNameFormat(isDynamic),
        Optional.<Path>absent(),
        /* includeFrameworks */ isDynamic,
        ImmutableSet.<AppleResourceDescription.Arg>of(),
        ImmutableSet.<AppleAssetCatalogDescription.Arg>of());
    LOG.debug("Generated iOS library target %s", target);
    return target;
  }

  private void writeHeaderMap(
      HeaderMap headerMap,
      TargetNode<AppleNativeTargetDescriptionArg> targetNode,
      HeaderMapType headerMapType)
      throws IOException {
    if (headerMap.getNumEntries() == 0) {
      return;
    }
    Path headerMapFile = AbstractAppleNativeTargetBuildRuleDescriptions
        .getPathToHeaderMap(targetNode, headerMapType)
        .get();
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
      Optional<TargetNode<AppleBundleDescription.Arg>> bundle,
      TargetNode<AppleNativeTargetDescriptionArg> targetNode,
      PBXTarget.ProductType productType,
      String productOutputFormat,
      Optional<Path> infoPlistOptional,
      boolean includeFrameworks,
      ImmutableSet<AppleResourceDescription.Arg> resources,
      ImmutableSet<AppleAssetCatalogDescription.Arg> assetCatalogs)
      throws IOException {
    Optional<String> targetGid = targetNode.getConstructorArg().gid;
    if (targetGid.isPresent()) {
      // Check if we have used this hardcoded GID before.
      // If not, remember it so we don't use it again.
      String thisTargetName = targetNode.getBuildTarget().getFullyQualifiedName();
      String conflictingTargetName = gidsToTargetNames.get(targetGid.get());
      if (conflictingTargetName != null) {
        throw new HumanReadableException(
            "Targets %s and %s have the same hardcoded GID (%s)",
            thisTargetName, conflictingTargetName, targetGid.get());
      }
      gidsToTargetNames.put(targetGid.get(), thisTargetName);
    }

    TargetNode<?> buildTargetNode = bundle.isPresent() ? bundle.get() : targetNode;
    BuildTarget buildTarget = buildTargetNode.getBuildTarget();

    String productName = getProductName(buildTarget);
    TargetSources sources = TargetSources.ofAppleSources(
        resolver,
        targetNode.getConstructorArg().srcs.get());
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        targetGraph,
        executionContext,
        pathRelativizer,
        resolver,
        buildTarget);
    mutator
        .setTargetName(getXcodeTargetName(buildTarget))
        .setProduct(
            productType,
            productName,
            Paths.get(String.format(productOutputFormat, productName)))
        .setGid(targetGid)
        .setShouldGenerateCopyHeadersPhase(
            !targetNode.getConstructorArg().getUseBuckHeaderMaps())
        .setSources(sources.srcs, sources.perFileFlags)
        .setResources(resources);

    Path assetCatalogBuildPhaseScript;
    if (!assetCatalogs.isEmpty()) {
      if (PATH_OVERRIDE_FOR_ASSET_CATALOG_BUILD_PHASE_SCRIPT != null) {
        assetCatalogBuildPhaseScript =
            Paths.get(PATH_OVERRIDE_FOR_ASSET_CATALOG_BUILD_PHASE_SCRIPT);
      } else {
        // In order for the script to run, it must be accessible by Xcode and
        // deserves to be part of the generated output.
        shouldPlaceAssetCatalogCompiler = true;
        assetCatalogBuildPhaseScript = placedAssetCatalogBuildPhaseScript;
      }
      mutator.setAssetCatalogs(assetCatalogBuildPhaseScript, assetCatalogs);
    }

    if (includeFrameworks) {
      ImmutableSet.Builder<String> frameworksBuilder = ImmutableSet.builder();
      frameworksBuilder.addAll(targetNode.getConstructorArg().frameworks.get());
      collectRecursiveFrameworkDependencies(targetNode, frameworksBuilder);
      mutator.setFrameworks(frameworksBuilder.build());
      mutator.setArchives(collectRecursiveLibraryDependencies(targetNode));
    }

    // TODO(Task #3772930): Go through all dependencies of the rule
    // and add any shell script rules here
    ImmutableList.Builder<TargetNode<?>> preScriptPhases = ImmutableList.builder();
    ImmutableList.Builder<TargetNode<?>> postScriptPhases = ImmutableList.builder();
    if (bundle.isPresent()) {
      collectBuildScriptDependencies(
          targetGraph.getAll(bundle.get().getDeps()),
          preScriptPhases,
          postScriptPhases);
    }
    collectBuildScriptDependencies(
        targetGraph.getAll(targetNode.getDeps()),
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
        Path prefixHeaderRelative = resolver.getPath(prefixHeaderOptional.get());
        Path prefixHeaderPath = pathRelativizer.outputDirToRootRelative(prefixHeaderRelative);
        extraSettingsBuilder.put("GCC_PREFIX_HEADER", prefixHeaderPath.toString());
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
          bundle.get().getConstructorArg().getExtensionString());
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
            "USER_HEADER_SEARCH_PATHS",
            Joiner.on(' ').join(collectUserHeaderMaps(targetNode)))
        .put(
            "LIBRARY_SEARCH_PATHS",
            Joiner.on(' ').join(collectRecursiveLibrarySearchPaths(targetNode)))
        .put(
            "FRAMEWORK_SEARCH_PATHS",
            Joiner.on(' ').join(collectRecursiveFrameworkSearchPaths(targetNode)));

    setTargetBuildConfigurations(
        buildTarget,
        target,
        targetGroup,
        targetNode.getConstructorArg().configs.get(),
        extraSettingsBuilder.build(),
        defaultSettingsBuilder.build(),
        appendConfigsBuilder.build());

    // -- phases
    if (targetNode.getConstructorArg().getUseBuckHeaderMaps()) {
      addHeaderMapsForHeaders(
          targetNode,
          targetNode.getConstructorArg().headerPathPrefix,
          sources.srcs,
          sources.perFileFlags);
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

  /**
   * Create project level (if it does not exist) and target level configuration entries.
   *
   * Each configuration should have an empty entry at the project level. The target level entries
   * combine the configuration values of every layer into a single configuration file that is
   * effectively laid out in layers.
   *
   * @param buildTarget current build target being processed, used for error reporting.
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
      BuildTarget buildTarget,
      PBXTarget target,
      PBXGroup targetGroup,
      ImmutableMap<String, XcodeRuleConfiguration> configurations,
      ImmutableMap<String, String> overrideBuildSettings,
      ImmutableMap<String, String> defaultBuildSettings,
      ImmutableMap<String, String> appendBuildSettings)
      throws IOException {

    PBXGroup configurationsGroup = targetGroup.getOrCreateChildGroupByName("Configurations");

    for (Map.Entry<String, XcodeRuleConfiguration> configurationEntry : configurations.entrySet()) {
      ConfigInXcodeLayout layers =
          extractXcodeConfigurationLayers(buildTarget, configurationEntry.getValue());
      xcodeConfigurationLayersMultimapBuilder.put(configurationEntry.getKey(), layers);

      XCBuildConfiguration outputConfiguration = target
          .getBuildConfigurationList()
          .getBuildConfigurationsByName()
          .getUnchecked(configurationEntry.getKey());

      HashMap<String, String> combinedOverrideConfigs = Maps.newHashMap(overrideBuildSettings);
      for (Map.Entry<String, String> entry: defaultBuildSettings.entrySet()) {
        String existingSetting = layers.targetLevelInlineSettings.get(entry.getKey());
        if (existingSetting == null) {
          combinedOverrideConfigs.put(entry.getKey(), entry.getValue());
        }
      }

      for (Map.Entry<String, String> entry : appendBuildSettings.entrySet()) {
        String existingSetting = layers.targetLevelInlineSettings.get(entry.getKey());
        String settingPrefix = existingSetting != null ? existingSetting : "$(inherited)";
        combinedOverrideConfigs.put(entry.getKey(), settingPrefix + " " + entry.getValue());
      }

      if (options.contains(Option.REFERENCE_EXISTING_XCCONFIGS)) {
        Iterable<Map.Entry<String, String>> entries = Iterables.concat(
            layers.targetLevelInlineSettings.entrySet(),
            combinedOverrideConfigs.entrySet());

        if (layers.targetLevelConfigFile.isPresent()) {
          PBXFileReference fileReference =
              configurationsGroup.getOrCreateFileReferenceBySourceTreePath(
                  new SourceTreePath(
                      PBXReference.SourceTree.SOURCE_ROOT,
                      pathRelativizer.outputPathToSourcePath(
                          layers.targetLevelConfigFile.get())));
          outputConfiguration.setBaseConfigurationReference(fileReference);

          NSDictionary inlineSettings = new NSDictionary();
          for (Map.Entry<String, String> entry : entries) {
            inlineSettings.put(entry.getKey(), entry.getValue());
          }
          outputConfiguration.setBuildSettings(inlineSettings);
        } else {
          Path xcconfigPath = BuildTargets.getGenPath(buildTarget, "%s.xcconfig");
          projectFilesystem.mkdirs(xcconfigPath.getParent());

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
      } else {
        Path outputConfigurationDirectory = outputDirectory.resolve("Configurations");
        projectFilesystem.mkdirs(outputConfigurationDirectory);

        Path originalProjectPath = projectFilesystem.getPathForRelativePath(
            Paths.get(buildTarget.getBasePathWithSlash()));

        // XCConfig search path is relative to the xcode project and the file itself.
        ImmutableList<Path> searchPaths = ImmutableList.of(originalProjectPath);

        // Call for effect to create a stub configuration entry at project level.
        project.getBuildConfigurationList()
            .getBuildConfigurationsByName()
            .getUnchecked(configurationEntry.getKey());

        // Write an xcconfig that embodies all the config levels, and set that as the target config.
        Path configurationFilePath = outputConfigurationDirectory.resolve(
            mangledBuildTargetName(buildTarget) + "-" + configurationEntry.getKey() + ".xcconfig");
        String serializedConfiguration = serializeBuildConfiguration(
            configurationEntry.getValue(),
            searchPaths,
            ImmutableMap.copyOf(combinedOverrideConfigs));
        if (MorePaths.fileContentsDiffer(
            new ByteArrayInputStream(serializedConfiguration.getBytes(Charsets.UTF_8)),
            configurationFilePath,
            projectFilesystem)) {
          if (shouldGenerateReadOnlyFiles()) {
            projectFilesystem.writeContentsToPath(
                serializedConfiguration,
                configurationFilePath,
                READ_ONLY_FILE_ATTRIBUTE);
          } else {
            projectFilesystem.writeContentsToPath(
                serializedConfiguration,
                configurationFilePath);
          }
        }

        PBXFileReference fileReference =
            configurationsGroup.getOrCreateFileReferenceBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SOURCE_ROOT,
                    pathRelativizer.outputDirToRootRelative(configurationFilePath)));
        outputConfiguration.setBaseConfigurationReference(fileReference);
      }
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

  /**
   * Create header map files and write them to disk.
   *
   * @param groupedSources Source files to include in the header map.
   *                       Implementation files in the source groups are ignored.
   * @param sourceFlags    Source path to flag mapping.
   */
  private void addHeaderMapsForHeaders(
      TargetNode<AppleNativeTargetDescriptionArg> targetNode,
      Optional<String> headerPathPrefix,
      Iterable<GroupedSource> groupedSources,
      ImmutableMap<SourcePath, String> sourceFlags) throws IOException {
    HeaderMap.Builder publicMapBuilder = HeaderMap.builder();
    HeaderMap.Builder targetMapBuilder = HeaderMap.builder();
    HeaderMap.Builder targetUserMapBuilder = HeaderMap.builder();
    addGroupedSourcesToHeaderMaps(
        publicMapBuilder,
        targetMapBuilder,
        targetUserMapBuilder,
        Paths.get(headerPathPrefix.or(getProductName(targetNode.getBuildTarget()))),
        groupedSources,
        sourceFlags);
    writeHeaderMap(publicMapBuilder.build(), targetNode, HeaderMapType.PUBLIC_HEADER_MAP);
    writeHeaderMap(targetMapBuilder.build(), targetNode, HeaderMapType.TARGET_HEADER_MAP);
    writeHeaderMap(targetUserMapBuilder.build(), targetNode, HeaderMapType.TARGET_USER_HEADER_MAP);
  }

  private void addGroupedSourcesToHeaderMaps(
      HeaderMap.Builder publicHeaderMap,
      HeaderMap.Builder targetHeaderMap,
      HeaderMap.Builder targetUserHeaderMap,
      Path prefix,
      Iterable<GroupedSource> groupedSources,
      ImmutableMap<SourcePath, String> sourceFlags) {
    for (GroupedSource groupedSource : groupedSources) {
      switch (groupedSource.getType()) {
        case SOURCE_PATH:
          if (resolver.isSourcePathExtensionInSet(
              groupedSource.getSourcePath(),
              FileExtensions.CLANG_HEADERS)) {
            addSourcePathToHeaderMaps(
                groupedSource.getSourcePath(),
                prefix,
                publicHeaderMap,
                targetHeaderMap,
                targetUserHeaderMap,
                sourceFlags);
          }
          break;
        case SOURCE_GROUP:
          addGroupedSourcesToHeaderMaps(
              publicHeaderMap,
              targetHeaderMap,
              targetUserHeaderMap,
              prefix,
              groupedSource.getSourceGroup(),
              sourceFlags);
          break;
        default:
          throw new RuntimeException("Unhandled grouped source type: " + groupedSource.getType());
      }
    }
  }

  private void addHeaderMapEntry(
      HeaderMap.Builder builder,
      String builderName,
      String key,
      Path value) {
    builder.add(key, value);
    LOG.verbose(
        "Adding %s mapping %s -> %s",
        builderName,
        key,
        value);
  }

  private void addSourcePathToHeaderMaps(
      SourcePath headerPath,
      Path prefix,
      HeaderMap.Builder publicHeaderMap,
      HeaderMap.Builder targetHeaderMap,
      HeaderMap.Builder targetUserHeaderMap,
      ImmutableMap<SourcePath, String> sourceFlags) {
    HeaderVisibility visibility = HeaderVisibility.PROJECT;
    String headerFlags = sourceFlags.get(headerPath);
    if (headerFlags != null) {
      visibility = HeaderVisibility.fromString(headerFlags);
    }
    String fileName = resolver.getPath(headerPath).getFileName().toString();
    String prefixedFileName = prefix.resolve(fileName).toString();
    Path value =
        projectFilesystem.getPathForRelativePath(resolver.getPath(headerPath))
            .toAbsolutePath().normalize();

    // Add an entry Prefix/File.h -> AbsolutePathTo/File.h
    // to targetHeaderMap and possibly publicHeaderMap
    addHeaderMapEntry(targetHeaderMap, "target", prefixedFileName, value);
    if (visibility == HeaderVisibility.PUBLIC) {
      addHeaderMapEntry(publicHeaderMap, "public", prefixedFileName, value);
    }

    // Add an entry File.h -> AbsolutePathTo/File.h
    // to targetUserHeaderMap
    addHeaderMapEntry(targetUserHeaderMap, "target-user", fileName, value);
    if (visibility == HeaderVisibility.PRIVATE) {
      throw new HumanReadableException(
          "Xcode's so-called 'private' headers have been deprecated in the new header map mode. " +
          "Please declare '" + fileName + "' as public, " +
          "or use the default visibility (i.e. by target) instead.");
    }
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
          .contains(AppleLibraryDescription.DYNAMIC_LIBRARY)) {
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

  private String serializeBuildConfiguration(
      XcodeRuleConfiguration configuration,
      ImmutableList<Path> searchPaths,
      ImmutableMap<String, String> extra) {

    XcconfigStack.Builder builder = XcconfigStack.builder();
    for (XcodeRuleConfigurationLayer layer : configuration.getLayers()) {
      switch (layer.getLayerType()) {
        case FILE:
          builder.addSettingsFromFile(
              projectFilesystem,
              searchPaths,
              resolver.getPath(layer.getSourcePath().get()));
          break;
        case INLINE_SETTINGS:
          ImmutableMap<String, String> entries = layer.getInlineSettings().get();
          for (ImmutableMap.Entry<String, String> entry : entries.entrySet()) {
            builder.addSetting(entry.getKey(), entry.getValue());
          }
          break;
      }
      builder.pushLayer();
    }

    for (ImmutableMap.Entry<String, String> entry : extra.entrySet()) {
      builder.addSetting(entry.getKey(), entry.getValue());
    }
    builder.pushLayer();

    XcconfigStack stack = builder.build();

    ImmutableList<String> resolvedConfigs = stack.resolveConfigStack();
    ImmutableSortedSet<String> sortedConfigs = ImmutableSortedSet.copyOf(resolvedConfigs);
    StringBuilder stringBuilder = new StringBuilder();
    stringBuilder.append(
        "// This configuration is autogenerated.\n" +
            "// Re-run buck to update this file after modifying the hand-written configs.\n");
    for (String line : sortedConfigs) {
      stringBuilder.append(line);
      stringBuilder.append('\n');
    }
    return stringBuilder.toString();
  }

  /**
   * Mangle the full build target string such that it's safe for use in a path.
   */
  private static String mangledBuildTargetName(BuildTarget buildTarget) {
    return buildTarget.getFullyQualifiedName().replace('/', '-');
  }

  private static String getProductName(BuildTarget buildTarget) {
    return buildTarget.getShortNameOnly();
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
      TargetNode<AppleNativeTargetDescriptionArg> targetNode,
      HeaderMapType headerMapType) {
    Optional<Path> filePath = AbstractAppleNativeTargetBuildRuleDescriptions.getPathToHeaderMap(
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
        (targetNode.getType().equals(AppleBundleDescription.TYPE) &&
        (!((AppleBundleDescription.Arg)
            targetNode.getConstructorArg()).getExtensionValue().isPresent() ||
        (!((AppleBundleDescription.Arg)
            targetNode.getConstructorArg()).getExtensionValue().get().equals(
            AppleBundleExtension.FRAMEWORK))))) {
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

  private static boolean hasBuckHeaderMaps(TargetNode<AppleNativeTargetDescriptionArg> targetNode) {
    return targetNode.getConstructorArg().getUseBuckHeaderMaps();
  }

  @SuppressWarnings("unchecked")
  private static Optional<TargetNode<AppleNativeTargetDescriptionArg>>
      getLibraryNode(TargetGraph targetGraph, TargetNode<?> targetNode) {
    Optional<TargetNode<AppleNativeTargetDescriptionArg>> library = Optional.absent();
    if (targetNode.getType().equals(AppleLibraryDescription.TYPE)) {
      library = Optional.of((TargetNode<AppleNativeTargetDescriptionArg>) targetNode);
    } else if (targetNode.getType().equals(AppleBundleDescription.TYPE)) {
      TargetNode<AppleBundleDescription.Arg> bundle =
          (TargetNode<AppleBundleDescription.Arg>) targetNode;
      Optional<AppleBundleExtension> extension =
          bundle.getConstructorArg().getExtensionValue();
      if (extension.isPresent() && extension.get() == AppleBundleExtension.FRAMEWORK) {
        library = Optional.of(Preconditions.checkNotNull(
            (TargetNode<AppleNativeTargetDescriptionArg>) targetGraph.get(
                bundle.getConstructorArg().binary)));
      }
    }
    return library;
  }

  private ImmutableSet<String> collectRecursiveHeaderSearchPaths(
      TargetNode<AppleNativeTargetDescriptionArg> targetNode) {
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
                Optional<TargetNode<AppleNativeTargetDescriptionArg>> library =
                    getLibraryNode(targetGraph, input);
                return library.isPresent() && !hasBuckHeaderMaps(library.get());
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
      TargetNode<AppleNativeTargetDescriptionArg> targetNode) {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    if (hasBuckHeaderMaps(targetNode)) {
      builder.add(
          getHeaderMapRelativePath(targetNode, HeaderMapType.TARGET_HEADER_MAP));
    }

    for (TargetNode<?> input :
        AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
            targetGraph,
            AppleBuildRules.RecursiveDependenciesMode.BUILDING,
            targetNode,
            Optional.of(AppleBuildRules.XCODE_TARGET_BUILD_RULE_TYPES))) {
      Optional<TargetNode<AppleNativeTargetDescriptionArg>> library =
          getLibraryNode(targetGraph, input);
      if (library.isPresent() && hasBuckHeaderMaps(library.get())) {
        builder.add(getHeaderMapRelativePath(library.get(), HeaderMapType.PUBLIC_HEADER_MAP));
      }
    }

    return builder.build();
  }

  private ImmutableSet<String> collectUserHeaderMaps(
      TargetNode<AppleNativeTargetDescriptionArg> targetNode) {
    if (hasBuckHeaderMaps(targetNode)) {
      return ImmutableSet.of(
          getHeaderMapRelativePath(
              targetNode,
              HeaderMapType.TARGET_USER_HEADER_MAP));
    } else {
      return ImmutableSet.of();
    }
  }

  private ImmutableSet<String> collectRecursiveLibrarySearchPaths(TargetNode<?> targetNode) {
    return FluentIterable
        .from(
            AppleBuildRules
                .<AppleNativeTargetDescriptionArg>getRecursiveTargetNodeDependenciesOfType(
                    targetGraph,
                    AppleBuildRules.RecursiveDependenciesMode.LINKING,
                    targetNode,
                    AppleLibraryDescription.TYPE))
        .transform(
            new Function<TargetNode<AppleNativeTargetDescriptionArg>, String>() {
              @Override
              public String apply(TargetNode<AppleNativeTargetDescriptionArg> input) {
                return getTargetOutputPath(input);
              }
            })
        .toSet();
  }

  private ImmutableSet<String> collectRecursiveFrameworkSearchPaths(TargetNode<?> targetNode) {
    return FluentIterable
        .from(
            AppleBuildRules
                .<AppleBundleDescription.Arg>getRecursiveTargetNodeDependenciesOfType(
                    targetGraph,
                    AppleBuildRules.RecursiveDependenciesMode.LINKING,
                    targetNode,
                    AppleBundleDescription.TYPE))
        .filter(
            new Predicate<TargetNode<AppleBundleDescription.Arg>>() {
              @Override
              public boolean apply(TargetNode<AppleBundleDescription.Arg> input) {
                return getLibraryNode(targetGraph, input).isPresent();
              }
            })
        .transform(
          new Function<TargetNode<AppleBundleDescription.Arg>, String>() {
            @Override
            public String apply(TargetNode<AppleBundleDescription.Arg> input) {
              return getTargetOutputPath(input);
            }
          })
        .toSet();
  }

  private void collectRecursiveFrameworkDependencies(
      TargetNode<?> targetNode,
      ImmutableSet.Builder<String> frameworksBuilder) {
    for (TargetNode<?> dependency :
           AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
               targetGraph,
               AppleBuildRules.RecursiveDependenciesMode.LINKING,
               targetNode,
               Optional.of(AppleBuildRules.XCODE_TARGET_BUILD_RULE_TYPES))) {
      Optional<TargetNode<AppleNativeTargetDescriptionArg>> library =
          getLibraryNode(targetGraph, dependency);
      // Dynamically linked dependencies don't require including their frameworks in dependencies.
      if (library.isPresent() &&
          !AppleLibraryDescription.isDynamicLibraryTarget(library.get().getBuildTarget())) {
        frameworksBuilder.addAll(
            library.get().getConstructorArg().frameworks.get());
      }
    }
  }

  private ImmutableSet<PBXFileReference> collectRecursiveLibraryDependencies(
      TargetNode<?> targetNode) {
    return FluentIterable
        .from(AppleBuildRules.getRecursiveTargetNodeDependenciesOfTypes(
                targetGraph,
                AppleBuildRules.RecursiveDependenciesMode.LINKING,
                targetNode,
                Optional.of(AppleBuildRules.XCODE_TARGET_BUILD_RULE_TYPES)))
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

  private SourceTreePath getProductsSourceTreePath(TargetNode<?> targetNode) {
    String productName = getProductName(targetNode.getBuildTarget());
    String productOutputName;

    if (targetNode.getType().equals(AppleLibraryDescription.TYPE)) {
      String productOutputFormat = AppleLibrary.getOutputFileNameFormat(
          targetNode
              .getBuildTarget()
              .getFlavors()
              .contains(AppleLibraryDescription.DYNAMIC_LIBRARY));
      productOutputName = String.format(productOutputFormat, productName);
    } else if (targetNode.getType().equals(AppleBundleDescription.TYPE)) {
      AppleBundleDescription.Arg arg = (AppleBundleDescription.Arg) targetNode.getConstructorArg();
      productOutputName = productName + "." + arg.getExtensionString();
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
    if (targetNode.getType().equals(AppleLibraryDescription.TYPE) ||
        targetNode.getType().equals(AppleBundleDescription.TYPE)) {
      // Don't re-use the productReference from other targets in this project.
      // File references set as a productReference don't work with custom paths.
      SourceTreePath productsPath = getProductsSourceTreePath(targetNode);
      return project.getMainGroup()
          .getOrCreateChildGroupByName("Frameworks")
          .getOrCreateFileReferenceBySourceTreePath(productsPath);
    } else {
      throw new RuntimeException("Unexpected type: " + targetNode.getType());
    }
  }

  /**
   * Whether a given build target is built by the project being generated, or being build elsewhere.
   */
  private boolean isBuiltByCurrentProject(BuildTarget buildTarget) {
    return initialTargets.contains(buildTarget);
  }

  private String getXcodeTargetName(BuildTarget target) {
    return options.contains(Option.USE_SHORT_NAMES_FOR_TARGETS)
        ? target.getShortNameOnly()
        : target.getFullyQualifiedName();
  }

  /**
   * Collect resources from recursive dependencies.
   *
   * @param targetNode {@link TargetNode} at the tip of the traversal.
   * @return The recursive resource buildables.
   */
  private ImmutableSet<AppleResourceDescription.Arg> collectRecursiveResources(
      TargetNode<?> targetNode) {
    return FluentIterable
        .from(
            AppleBuildRules
                .<AppleResourceDescription.Arg>getRecursiveTargetNodeDependenciesOfType(
                    targetGraph,
                    AppleBuildRules.RecursiveDependenciesMode.COPYING,
                    targetNode,
                    AppleResourceDescription.TYPE))
        .transform(
            new Function<
                TargetNode<AppleResourceDescription.Arg>, AppleResourceDescription.Arg>() {
              @Override
              public AppleResourceDescription.Arg apply(
                  TargetNode<AppleResourceDescription.Arg> input) {
                return input.getConstructorArg();
              }
            })
        .toSet();
  }

  /**
   * Collect asset catalogs from recursive dependencies.
   */
  private ImmutableSet<AppleAssetCatalogDescription.Arg> collectRecursiveAssetCatalogs(
      TargetNode<?> targetNode) {
    return FluentIterable
        .from(
            AppleBuildRules
                .<AppleAssetCatalogDescription.Arg>getRecursiveTargetNodeDependenciesOfType(
                    targetGraph,
                    AppleBuildRules.RecursiveDependenciesMode.COPYING,
                    targetNode,
                    AppleAssetCatalogDescription.TYPE))
        .transform(
            new Function<
                TargetNode<AppleAssetCatalogDescription.Arg>, AppleAssetCatalogDescription.Arg>() {
              @Override
              public AppleAssetCatalogDescription.Arg apply(
                  TargetNode<AppleAssetCatalogDescription.Arg> input) {
                return input.getConstructorArg();
              }
            })
        .toSet();
  }

  @SuppressWarnings({"incomplete-switch", "unchecked"})
  private PBXTarget.ProductType bundleToTargetProductType(
      TargetNode<AppleBundleDescription.Arg> targetNode) {
    TargetNode<AppleNativeTargetDescriptionArg> binary =
        (TargetNode<AppleNativeTargetDescriptionArg>) Preconditions.checkNotNull(
            targetGraph.get(targetNode.getConstructorArg().binary));

    if (targetNode.getConstructorArg().extension.isLeft()) {
      AppleBundleExtension extension = targetNode.getConstructorArg().extension.getLeft();

      if (binary.getType().equals(AppleLibraryDescription.TYPE)) {
        if (binary.getBuildTarget().getFlavors().contains(
            AppleLibraryDescription.DYNAMIC_LIBRARY)) {
          switch (extension) {
            case FRAMEWORK:
              return PBXTarget.ProductType.FRAMEWORK;
            case APPEX:
              return PBXTarget.ProductType.APP_EXTENSION;
            case BUNDLE:
              return PBXTarget.ProductType.BUNDLE;
            case OCTEST:
              return PBXTarget.ProductType.BUNDLE;
            case XCTEST:
              return PBXTarget.ProductType.UNIT_TEST;
          }
        } else {
          switch (extension) {
            case FRAMEWORK:
              return PBXTarget.ProductType.STATIC_FRAMEWORK;
          }
        }
      } else if (binary.getType().equals(AppleBinaryDescription.TYPE)) {
        switch (extension) {
          case APP:
            return PBXTarget.ProductType.APPLICATION;
        }
      }
    }

    return PBXTarget.ProductType.BUNDLE;
  }

  /**
   * For all inputs by name, verify every entry has identical project level config, and pick one
   * such config to return.
   *
   * @param configInXcodeLayoutMultimap input mapping of { Config Name -> Config List }
   * @throws com.facebook.buck.util.HumanReadableException
   *    if project-level configs are not identical for a named configuration
   */
  private static
  ImmutableMap<String, ConfigInXcodeLayout> collectProjectLevelConfigsIfIdenticalOrFail(
      ImmutableMultimap<String, ConfigInXcodeLayout> configInXcodeLayoutMultimap) {

    ImmutableMap.Builder<String, ConfigInXcodeLayout> builder = ImmutableMap.builder();

    for (String configName : configInXcodeLayoutMultimap.keySet()) {
      ConfigInXcodeLayout firstConfig = null;
      for (ConfigInXcodeLayout config : configInXcodeLayoutMultimap.get(configName)) {
        if (firstConfig == null) {
          firstConfig = config;
        } else if (
            !firstConfig.projectLevelConfigFile.equals(config.projectLevelConfigFile) ||
            !firstConfig.projectLevelInlineSettings.equals(config.projectLevelInlineSettings)) {
          throw new HumanReadableException(String.format(
              "Project level configurations should be identical:\n" +
              "  Config named: `%s` in `%s` and `%s` ",
              configName,
              firstConfig.buildTarget,
              config.buildTarget));
        }
      }
      Preconditions.checkNotNull(firstConfig);

      builder.put(configName, firstConfig);
    }

    return builder.build();
  }

  private void setProjectLevelConfigs(
      SourcePathResolver resolver,
      PBXProject project,
      ImmutableMap<String, ConfigInXcodeLayout> configs) {
    for (Map.Entry<String, ConfigInXcodeLayout> configEntry : configs.entrySet()) {
      XCBuildConfiguration outputConfig = project
          .getBuildConfigurationList()
          .getBuildConfigurationsByName()
          .getUnchecked(configEntry.getKey());

      ConfigInXcodeLayout config = configEntry.getValue();

      if (config.projectLevelConfigFile.isPresent()) {
        PBXGroup configurationsGroup = project.getMainGroup().getOrCreateChildGroupByName(
            "Configurations");
        PBXFileReference fileReference =
            configurationsGroup.getOrCreateFileReferenceBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SOURCE_ROOT,
                    pathRelativizer.outputDirToRootRelative(
                        resolver.getPath(config.projectLevelConfigFile.get()).normalize())));
        outputConfig.setBaseConfigurationReference(fileReference);
      }

      NSDictionary inlineSettings = new NSDictionary();
      for (Map.Entry<String, String> entry : config.projectLevelInlineSettings.entrySet()) {
        inlineSettings.put(entry.getKey(), entry.getValue());
      }
      outputConfig.setBuildSettings(inlineSettings);
    }
  }


  /**
   * Take a List of configuration layers and try to fit it into the xcode configuration layers
   * layout.
   *
   * @throws com.facebook.buck.util.HumanReadableException if the configuration layers are not in
   *  the right layout to be coerced into standard xcode layout.
   */
  private static ConfigInXcodeLayout extractXcodeConfigurationLayers(
      BuildTarget buildTarget,
      XcodeRuleConfiguration configuration) {
    ConfigInXcodeLayout extractedLayers = null;
    ImmutableList<XcodeRuleConfigurationLayer> layers = configuration.getLayers();
    switch (layers.size()) {
      case 1:
        if (layers.get(0).getLayerType() == XcodeRuleConfigurationLayer.TYPE.FILE) {
          extractedLayers = new ConfigInXcodeLayout(
              buildTarget,
              Optional.<SourcePath>absent(),
              ImmutableMap.<String, String>of(),
              layers.get(0).getSourcePath(),
              ImmutableMap.<String, String>of());
        } else {
          extractedLayers = new ConfigInXcodeLayout(
              buildTarget,
              Optional.<SourcePath>absent(),
              ImmutableMap.<String, String>of(),
              Optional.<SourcePath>absent(),
              layers.get(0).getInlineSettings().or(ImmutableMap.<String, String>of()));
        }
        break;
      case 2:
        if (layers.get(0).getLayerType() == XcodeRuleConfigurationLayer.TYPE.FILE &&
            layers.get(1).getLayerType() == XcodeRuleConfigurationLayer.TYPE.FILE) {
          extractedLayers = new ConfigInXcodeLayout(
              buildTarget,
              layers.get(0).getSourcePath(),
              ImmutableMap.<String, String>of(),
              layers.get(1).getSourcePath(),
              ImmutableMap.<String, String>of());
        }
        break;
      case 4:
        if (layers.get(0).getLayerType() == XcodeRuleConfigurationLayer.TYPE.FILE &&
            layers.get(1).getLayerType() == XcodeRuleConfigurationLayer.TYPE.INLINE_SETTINGS &&
            layers.get(2).getLayerType() == XcodeRuleConfigurationLayer.TYPE.FILE &&
            layers.get(3).getLayerType() == XcodeRuleConfigurationLayer.TYPE.INLINE_SETTINGS) {
          extractedLayers = new ConfigInXcodeLayout(
              buildTarget,
              layers.get(0).getSourcePath(),
              layers.get(1).getInlineSettings().or(ImmutableMap.<String, String>of()),
              layers.get(2).getSourcePath(),
              layers.get(3).getInlineSettings().or(ImmutableMap.<String, String>of()));
        }
        break;
      default:
        // handled later on by the fact that extractLayers is null
        break;
    }
    if (extractedLayers == null) {
      throw new HumanReadableException(
          "Configuration layers cannot be expressed in xcode for target: " + buildTarget + "\n" +
              "   expected: [File, Inline settings, File, Inline settings]");
    }
    return extractedLayers;
  }

  private boolean shouldGenerateReadOnlyFiles() {
    return options.contains(Option.GENERATE_READ_ONLY_FILES);
  }

  private static class ConfigInXcodeLayout {
    /** Tracks the originating build target for error reporting. */
    public final BuildTarget buildTarget;

    public final Optional<SourcePath> projectLevelConfigFile;
    public final ImmutableMap<String, String> projectLevelInlineSettings;
    public final Optional<SourcePath> targetLevelConfigFile;
    public final ImmutableMap<String, String> targetLevelInlineSettings;

    private ConfigInXcodeLayout(
        BuildTarget buildTarget,
        Optional<SourcePath> projectLevelConfigFile,
        ImmutableMap<String, String> projectLevelInlineSettings,
        Optional<SourcePath> targetLevelConfigFile,
        ImmutableMap<String, String> targetLevelInlineSettings) {
      this.buildTarget = buildTarget;
      this.projectLevelConfigFile = projectLevelConfigFile;
      this.projectLevelInlineSettings = projectLevelInlineSettings;
      this.targetLevelConfigFile = targetLevelConfigFile;
      this.targetLevelInlineSettings = targetLevelInlineSettings;
    }
  }
}
