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
import com.facebook.buck.apple.AbstractAppleNativeTargetBuildRule;
import com.facebook.buck.apple.AbstractAppleNativeTargetBuildRule.HeaderMapType;
import com.facebook.buck.apple.AppleAssetCatalog;
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.AppleBinary;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.AppleBundle;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibrary;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleResource;
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.AppleTest;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.CoreDataModel;
import com.facebook.buck.apple.FileExtensions;
import com.facebook.buck.apple.GroupedSource;
import com.facebook.buck.apple.HeaderVisibility;
import com.facebook.buck.apple.IosPostprocessResourcesDescription;
import com.facebook.buck.apple.XcodeNative;
import com.facebook.buck.apple.XcodeNativeDescription;
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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.XcodeRuleConfiguration;
import com.facebook.buck.rules.coercer.XcodeRuleConfigurationLayer;
import com.facebook.buck.shell.Genrule;
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

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Generator for xcode project and associated files from a set of xcode/ios rules.
 */
public class ProjectGenerator {
  private static final Logger LOG = Logger.get(ProjectGenerator.class);

  public enum Option {
    /**
     * generate native xcode targets for dependent build targets.
     */
    GENERATE_TARGETS_FOR_DEPENDENCIES,

    /**
     * Generate a workspace
     */
    GENERATE_WORKSPACE,

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
   * Standard options for generating a combined project
   */
  public static final ImmutableSet<Option> COMBINED_PROJECT_OPTIONS = ImmutableSet.of(
      Option.GENERATE_TARGETS_FOR_DEPENDENCIES,
      Option.GENERATE_WORKSPACE);

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
      ImmutableSet.<PosixFilePermission>of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ));

  private final SourcePathResolver resolver;
  private final ImmutableSet<BuildRule> rulesToBuild;
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
  private final LoadingCache<BuildRule, Optional<PBXTarget>> buildRuleToXcodeTarget;
  @Nullable
  private Document workspace = null;
  private boolean shouldPlaceAssetCatalogCompiler = false;
  private final ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToGeneratedTargetBuilder;
  private boolean projectGenerated;
  private List<Path> headerMaps;

  /**
   * Populated while generating project configurations, in order to collect the possible
   * project-level configurations to set when operation with
   * {@link Option#REFERENCE_EXISTING_XCCONFIGS}.
   */
  private final ImmutableMultimap.Builder<String, ConfigInXcodeLayout>
    xcodeConfigurationLayersMultimapBuilder;

  private Set<String> nativeTargetGIDs;

  public ProjectGenerator(
      SourcePathResolver resolver,
      Iterable<BuildRule> rulesToBuild,
      Set<BuildTarget> initialTargets,
      ProjectFilesystem projectFilesystem,
      ExecutionContext executionContext,
      Path outputDirectory,
      String projectName,
      Set<Option> options) {
    this.resolver = resolver;
    this.rulesToBuild = ImmutableSet.copyOf(rulesToBuild);
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

    this.buildRuleToGeneratedTargetBuilder = ImmutableMap.builder();
    this.buildRuleToXcodeTarget = CacheBuilder.newBuilder().build(
        new CacheLoader<BuildRule, Optional<PBXTarget>>() {
          @Override
          public Optional<PBXTarget> load(BuildRule key) throws Exception {
            return generateTargetForBuildRule(key);
          }
        });

    xcodeConfigurationLayersMultimapBuilder = ImmutableMultimap.builder();
    nativeTargetGIDs = new HashSet<>();
  }

  @VisibleForTesting
  PBXProject getGeneratedProject() {
    return project;
  }

  @VisibleForTesting
  List<Path> getGeneratedHeaderMaps() {
    return headerMaps;
  }

  @Nullable
  @VisibleForTesting
  Document getGeneratedWorkspace() {
    return workspace;
  }

  public Path getProjectPath() {
    return projectPath;
  }

  public ImmutableMap<BuildTarget, PBXTarget> getBuildTargetToGeneratedTargetMap() {
    Preconditions.checkState(projectGenerated, "Must have called createXcodeProjects");
    ImmutableMap.Builder<BuildTarget, PBXTarget> buildTargetToPbxTargetMap = ImmutableMap.builder();
    for (Map.Entry<BuildRule, PBXTarget> entry :
        buildRuleToGeneratedTargetBuilder.build().entrySet()) {
      buildTargetToPbxTargetMap.put(entry.getKey().getBuildTarget(), entry.getValue());
    }
    return buildTargetToPbxTargetMap.build();
  }

  public void createXcodeProjects() throws IOException {
    LOG.debug("Creating projects for targets %s", initialTargets);

    try {
      for (BuildRule rule : rulesToBuild) {
        if (isBuiltByCurrentProject(rule.getBuildTarget())) {
          LOG.debug("Including rule %s in project", rule);
          // Trigger the loading cache to call the generateTargetForBuildRule function.
          Optional<PBXTarget> target = buildRuleToXcodeTarget.getUnchecked(rule);
          if (target.isPresent()) {
            // TODO(grp, t4964329): SchemeGenerator should look for the AppleTest rule itself.
            if (rule.getType().equals(AppleTestDescription.TYPE)) {
              AppleTest test = (AppleTest) rule;
              rule = test.getTestBundle();
            }
            buildRuleToGeneratedTargetBuilder.put(rule, target.get());
          }
        } else {
          LOG.verbose("Excluding rule %s (not built by current project)", rule);
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

      if (options.contains(Option.GENERATE_WORKSPACE)) {
        writeWorkspace(projectPath);
      }

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
      if (e.getCause() instanceof HumanReadableException) {
        throw (HumanReadableException) e.getCause();
      } else {
        throw e;
      }
    }
  }

  private Optional<PBXTarget> generateTargetForBuildRule(BuildRule rule) throws IOException {
    Preconditions.checkState(
        isBuiltByCurrentProject(rule.getBuildTarget()),
        "should not generate rule if it shouldn't be built by current project");
    Optional<PBXTarget> result;
    Optional<AbstractAppleNativeTargetBuildRule> nativeTargetRule;
    if (rule.getType().equals(AppleLibraryDescription.TYPE)) {
      AppleLibrary appleLibrary = (AppleLibrary) rule;
      result = Optional.<PBXTarget>of(generateAppleLibraryTarget(project, appleLibrary));
      nativeTargetRule = Optional.<AbstractAppleNativeTargetBuildRule>of(appleLibrary);
    } else if (rule.getType().equals(AppleBinaryDescription.TYPE)) {
      AppleBinary appleBinary = (AppleBinary) rule;
      result = Optional.<PBXTarget>of(generateAppleBinaryTarget(project, appleBinary));
      nativeTargetRule = Optional.<AbstractAppleNativeTargetBuildRule>of(appleBinary);
    } else if (rule.getType().equals(AppleBundleDescription.TYPE)) {
      AppleBundle bundle = (AppleBundle) rule;
      result = Optional.<PBXTarget>of(generateAppleBundleTarget(project, bundle));
      nativeTargetRule = Optional.of((AbstractAppleNativeTargetBuildRule) bundle.getBinary());
    } else if (rule.getType().equals(AppleTestDescription.TYPE)) {
      AppleTest test = (AppleTest) rule;
      if (test.getTestBundle().getType().equals(AppleBundleDescription.TYPE)) {
        AppleBundle bundle = (AppleBundle) test.getTestBundle();
        if (bundle.getExtensionValue().isPresent() &&
            AppleBuildRules.isXcodeTargetTestBundleExtension(bundle.getExtensionValue().get())) {
          result = Optional.<PBXTarget>of(generateAppleBundleTarget(project, bundle));
          nativeTargetRule = Optional.of((AbstractAppleNativeTargetBuildRule) bundle.getBinary());
        } else {
          throw new HumanReadableException("Incorrect extension: " + bundle.getExtensionString());
        }
      } else {
        throw new HumanReadableException("Test bundle should be a bundle: " + test.getTestBundle());
      }
    } else {
      result = Optional.absent();
      nativeTargetRule = Optional.absent();
    }

    if (result.isPresent() &&
        nativeTargetRule.isPresent()) {
      Optional<String> gid = nativeTargetRule.get().getGid();
      if (gid.isPresent()) {
        // Remember the GID hard-coded in the target's BUCK file
        // so we don't try to re-use it later.
        nativeTargetGIDs.add(gid.get());
      }
    }

    return result;
  }

  private PBXNativeTarget generateAppleBundleTarget(
      PBXProject project,
      AppleBundle bundle)
      throws IOException {
    Optional<Path> infoPlistPath;
    if (bundle.getInfoPlist().isPresent()) {
      infoPlistPath = Optional.of(resolver.getPath(bundle.getInfoPlist().get()));
    } else {
      infoPlistPath = Optional.absent();
    }

    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.of(bundle),
        (AbstractAppleNativeTargetBuildRule) bundle.getBinary(),
        bundleToTargetProductType(bundle),
        "%s." + bundle.getExtensionString(),
        infoPlistPath,
        /* includeFrameworks */ true,
        collectRecursiveResources(bundle),
        collectRecursiveAssetCatalogs(bundle));

    // -- copy any binary and bundle targets into this bundle
    Iterable<BuildRule> copiedRules = AppleBuildRules.getRecursiveRuleDependenciesOfType(
        AppleBuildRules.RecursiveRuleDependenciesMode.COPYING,
        bundle,
        AppleLibraryDescription.TYPE,
        AppleBinaryDescription.TYPE,
        AppleBundleDescription.TYPE);
    generateCopyFilesBuildPhases(project, target, copiedRules);

    LOG.debug("Generated iOS bundle target %s", target);
    return target;
  }

  private PBXNativeTarget generateAppleBinaryTarget(PBXProject project, AppleBinary appleBinary)
      throws IOException {
    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.<AppleBundle>absent(),
        appleBinary,
        PBXTarget.ProductType.TOOL,
        "%s",
        Optional.<Path>absent(),
        /* includeFrameworks */ true,
        ImmutableSet.<AppleResource>of(),
        ImmutableSet.<AppleAssetCatalog>of());
    LOG.debug("Generated Apple binary target %s", target);
    return target;
  }

  private PBXNativeTarget generateAppleLibraryTarget(
      PBXProject project,
      AppleLibrary appleLibrary)
      throws IOException {
    PBXTarget.ProductType productType = appleLibrary.getLinkedDynamically() ?
        PBXTarget.ProductType.DYNAMIC_LIBRARY : PBXTarget.ProductType.STATIC_LIBRARY;
    PBXNativeTarget target = generateBinaryTarget(
        project,
        Optional.<AppleBundle>absent(),
        appleLibrary,
        productType,
        AppleLibrary.getOutputFileNameFormat(appleLibrary.getLinkedDynamically()),
        Optional.<Path>absent(),
        /* includeFrameworks */ appleLibrary.getLinkedDynamically(),
        ImmutableSet.<AppleResource>of(),
        ImmutableSet.<AppleAssetCatalog>of());
    LOG.debug("Generated iOS library target %s", target);
    return target;
  }

  private void writeHeaderMap(
      HeaderMap headerMap,
      AbstractAppleNativeTargetBuildRule buildRule,
      HeaderMapType headerMapType)
      throws IOException {
    if (headerMap.getNumEntries() == 0) {
      return;
    }
    Path headerMapFile = buildRule.getPathToHeaderMap(headerMapType).get();
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
      Optional<AppleBundle> bundle,
      AbstractAppleNativeTargetBuildRule appleBuildRule,
      PBXTarget.ProductType productType,
      String productOutputFormat,
      Optional<Path> infoPlistOptional,
      boolean includeFrameworks,
      ImmutableSet<AppleResource> resources,
      ImmutableSet<AppleAssetCatalog> assetCatalogs)
      throws IOException {
    BuildTarget buildTarget = bundle.isPresent()
        ? bundle.get().getBuildTarget()
        : appleBuildRule.getBuildTarget();

    String productName = getProductName(buildTarget);
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
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
        .setGid(appleBuildRule.getGid())
        .setShouldGenerateCopyHeadersPhase(!appleBuildRule.getUseBuckHeaderMaps())
        .setSources(appleBuildRule.getSrcs(), appleBuildRule.getPerFileFlags())
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
      frameworksBuilder.addAll(appleBuildRule.getFrameworks());
      collectRecursiveFrameworkDependencies(appleBuildRule, frameworksBuilder);
      mutator.setFrameworks(frameworksBuilder.build());
      mutator.setArchives(collectRecursiveLibraryDependencies(appleBuildRule));
    }

    // TODO(Task #3772930): Go through all dependencies of the rule
    // and add any shell script rules here
    ImmutableList.Builder<Genrule> preScriptPhases = ImmutableList.builder();
    ImmutableList.Builder<Genrule> postScriptPhases = ImmutableList.builder();
    if (bundle.isPresent()) {
      collectBuildScriptDependencies(bundle.get().getDeps(), preScriptPhases, postScriptPhases);
    }
    collectBuildScriptDependencies(appleBuildRule.getDeps(), preScriptPhases, postScriptPhases);
    mutator.setPreBuildRunScriptPhases(preScriptPhases.build());
    mutator.setPostBuildRunScriptPhases(postScriptPhases.build());

    NewNativeTargetProjectMutator.Result targetBuilderResult =
        mutator.buildTargetAndAddToProject(project);
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
    Optional<SourcePath> prefixHeaderOptional = appleBuildRule.getPrefixHeader();
    if (prefixHeaderOptional.isPresent()) {
        Path prefixHeaderRelative = resolver.getPath(prefixHeaderOptional.get());
        Path prefixHeaderPath = pathRelativizer.outputDirToRootRelative(prefixHeaderRelative);
        extraSettingsBuilder.put("GCC_PREFIX_HEADER", prefixHeaderPath.toString());
    }
    if (appleBuildRule.getUseBuckHeaderMaps()) {
      extraSettingsBuilder.put("USE_HEADERMAP", "NO");
    }

    ImmutableMap.Builder<String, String> defaultSettingsBuilder = ImmutableMap.builder();
    defaultSettingsBuilder.put("PRODUCT_NAME", getProductName(buildTarget));
    if (bundle.isPresent()) {
      defaultSettingsBuilder.put("WRAPPER_EXTENSION", bundle.get().getExtensionString());
    }
    defaultSettingsBuilder.put(
        "PUBLIC_HEADERS_FOLDER_PATH",
        getHeaderOutputPathForRule(appleBuildRule.getHeaderPathPrefix()));
    if (!bundle.isPresent() && appleBuildRule.getType().equals(AppleLibraryDescription.TYPE)) {
      defaultSettingsBuilder.put(
          "CONFIGURATION_BUILD_DIR", getObjectOutputPathForRule(appleBuildRule));
    }

    ImmutableMap.Builder<String, String> appendConfigsBuilder = ImmutableMap.builder();
    appendConfigsBuilder
        .put(
            "HEADER_SEARCH_PATHS",
            Joiner.on(' ').join(
                Iterators.concat(
                    collectRecursiveHeaderSearchPaths(appleBuildRule).iterator(),
                    collectRecursiveHeaderMaps(appleBuildRule).iterator())))
        .put(
            "USER_HEADER_SEARCH_PATHS",
            Joiner.on(' ').join(collectUserHeaderMaps(appleBuildRule)))
        .put(
            "LIBRARY_SEARCH_PATHS",
            Joiner.on(' ').join(collectRecursiveLibrarySearchPaths(appleBuildRule)))
        .put(
            "FRAMEWORK_SEARCH_PATHS",
            Joiner.on(' ').join(collectRecursiveFrameworkSearchPaths(appleBuildRule)));

    setTargetBuildConfigurations(
        buildTarget,
        target,
        targetGroup,
        appleBuildRule.getConfigurations(),
        extraSettingsBuilder.build(),
        defaultSettingsBuilder.build(),
        appendConfigsBuilder.build());

    // -- phases
    if (appleBuildRule.getUseBuckHeaderMaps()) {
      addHeaderMapsForHeaders(
          appleBuildRule,
          appleBuildRule.getHeaderPathPrefix(),
          appleBuildRule.getSrcs(),
          appleBuildRule.getPerFileFlags());
    }

    // Use Core Data models from immediate dependencies only.
    addCoreDataModelBuildPhase(
        targetGroup,
        Iterables.filter(appleBuildRule.getDeps(), CoreDataModel.class));

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
      if (options.contains(Option.REFERENCE_EXISTING_XCCONFIGS)) {
        ConfigInXcodeLayout layers =
            extractXcodeConfigurationLayers(buildTarget, configurationEntry.getValue());
        xcodeConfigurationLayersMultimapBuilder.put(configurationEntry.getKey(), layers);

        XCBuildConfiguration outputConfiguration = target
            .getBuildConfigurationList()
            .getBuildConfigurationsByName()
            .getUnchecked(configurationEntry.getKey());
        if (layers.targetLevelConfigFile.isPresent()) {
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

          PBXFileReference fileReference =
              configurationsGroup.getOrCreateFileReferenceBySourceTreePath(
                  new SourceTreePath(
                      PBXReference.SourceTree.SOURCE_ROOT,
                      pathRelativizer.outputPathToSourcePath(
                          layers.targetLevelConfigFile.get())));
          outputConfiguration.setBaseConfigurationReference(fileReference);

          NSDictionary inlineSettings = new NSDictionary();
          Iterable<Map.Entry<String, String>> entries = Iterables.concat(
              layers.targetLevelInlineSettings.entrySet(),
              combinedOverrideConfigs.entrySet());
          for (Map.Entry<String, String> entry : entries) {
            inlineSettings.put(entry.getKey(), entry.getValue());
          }
          outputConfiguration.setBuildSettings(inlineSettings);
        }
      } else {
        // Add search paths for dependencies
        Map<String, String> mutableExtraConfigs = new HashMap<>(overrideBuildSettings);
        for (Map.Entry<String, String> entry : appendBuildSettings.entrySet()) {
          String setting = "$(inherited) " + entry.getValue();
          mutableExtraConfigs.put(entry.getKey(), setting);
        }
        overrideBuildSettings = ImmutableMap.copyOf(mutableExtraConfigs);

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
            overrideBuildSettings);
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
        XCBuildConfiguration outputConfiguration = target
            .getBuildConfigurationList()
            .getBuildConfigurationsByName()
            .getUnchecked(configurationEntry.getKey());
        outputConfiguration.setBaseConfigurationReference(fileReference);
      }
    }
  }

  private void collectBuildScriptDependencies(
      Iterable<BuildRule> rules,
      ImmutableList.Builder<Genrule> preRules,
      ImmutableList.Builder<Genrule> postRules) {
    for (Genrule rule : Iterables.filter(rules, Genrule.class)) {
      if (rule.getType().equals(IosPostprocessResourcesDescription.TYPE)) {
        postRules.add(rule);
      } else {
        preRules.add(rule);
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
      AbstractAppleNativeTargetBuildRule buildRule,
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
        Paths.get(headerPathPrefix.or(getProductName(buildRule.getBuildTarget()))),
        groupedSources,
        sourceFlags);
    writeHeaderMap(publicMapBuilder.build(), buildRule, HeaderMapType.PUBLIC_HEADER_MAP);
    writeHeaderMap(targetMapBuilder.build(), buildRule, HeaderMapType.TARGET_HEADER_MAP);
    writeHeaderMap(targetUserMapBuilder.build(), buildRule, HeaderMapType.TARGET_USER_HEADER_MAP);
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
      final Iterable<CoreDataModel> dataModels) throws IOException {
    // TODO(user): actually add a build phase

    for (final CoreDataModel dataModel : dataModels) {
      // Core data models go in the resources group also.
      PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

      if (dataModel.isVersioned()) {
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
                    pathRelativizer.outputDirToRootRelative(dataModel.getPath())));

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
                        pathRelativizer.outputDirToRootRelative(dir)));
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
          PBXFileReference ref = versionGroup.getOrCreateFileReferenceBySourceTreePath(
              new SourceTreePath(
                  PBXReference.SourceTree.SOURCE_ROOT,
                  pathRelativizer.outputDirToRootRelative(
                      dataModel.getPath().resolve(currentVersionName.toString()))));
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
                pathRelativizer.outputDirToRootRelative(dataModel.getPath())));
      }
    }
  }

  private Optional<PBXCopyFilesBuildPhase.Destination> getDestinationForRule(BuildRule rule) {
    if (rule.getType().equals(AppleBundleDescription.TYPE)) {
      AppleBundle bundle = (AppleBundle) rule;
      AppleBundleExtension extension = bundle.getExtensionValue().or(AppleBundleExtension.BUNDLE);
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
    } else if (rule.getType().equals(AppleLibraryDescription.TYPE)) {
      AppleLibrary library = (AppleLibrary) rule;
      if (library.getLinkedDynamically()) {
        return Optional.of(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS);
      } else {
        return Optional.absent();
      }
    } else if (rule.getType().equals(AppleBinaryDescription.TYPE)) {
      return Optional.of(PBXCopyFilesBuildPhase.Destination.EXECUTABLES);
    } else {
      throw new RuntimeException("Unexpected type: " + rule.getType());
    }
  }

  private void generateCopyFilesBuildPhases(
      PBXProject project,
      PBXNativeTarget target,
      Iterable<BuildRule> copiedRules) {

    // Bucket build rules into bins by their destinations
    ImmutableSetMultimap.Builder<PBXCopyFilesBuildPhase.Destination, BuildRule>
        ruleByDestinationBuilder = ImmutableSetMultimap.builder();
    for (BuildRule copiedRule : copiedRules) {
      Optional<PBXCopyFilesBuildPhase.Destination> optionalDestination =
          getDestinationForRule(copiedRule);
      if (optionalDestination.isPresent()) {
        ruleByDestinationBuilder.put(optionalDestination.get(), copiedRule);
      }
    }
    ImmutableSetMultimap<PBXCopyFilesBuildPhase.Destination, BuildRule> ruleByDestination =
        ruleByDestinationBuilder.build();

    // Emit a copy files phase for each destination.
    for (PBXCopyFilesBuildPhase.Destination destination : ruleByDestination.keySet()) {
      PBXCopyFilesBuildPhase copyFilesBuildPhase = new PBXCopyFilesBuildPhase(destination, "");
      target.getBuildPhases().add(copyFilesBuildPhase);
      for (BuildRule file : ruleByDestination.get(destination)) {
        PBXFileReference fileReference = project
            .getMainGroup()
            .getOrCreateChildGroupByName("Dependencies")
            .getOrCreateFileReferenceBySourceTreePath(getProductsSourceTreePathForRule(file));
        copyFilesBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
      }
    }
  }

  /**
   * Create the project bundle structure and write {@code project.pbxproj}.
   */
  private Path writeProjectFile(PBXProject project) throws IOException {
    XcodeprojSerializer serializer = new XcodeprojSerializer(
        new GidGenerator(
            ImmutableSet.<String>builder()
                .addAll(nativeTargetGIDs)
                .build()),
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

  /**
   * Create the workspace bundle structure and write the workspace file.
   *
   * Updates {@link #workspace} with the written document for examination.
   */
  private void writeWorkspace(Path xcodeprojDir) throws IOException {
    DocumentBuilder docBuilder;
    Transformer transformer;
    try {
      docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      transformer = TransformerFactory.newInstance().newTransformer();
    } catch (ParserConfigurationException | TransformerConfigurationException e) {
      throw new RuntimeException(e);
    }

    DOMImplementation domImplementation = docBuilder.getDOMImplementation();
    Document doc = domImplementation.createDocument(null, "Workspace", null);
    doc.setXmlVersion("1.0");

    Element rootElem = doc.getDocumentElement();
    rootElem.setAttribute("version", "1.0");
    Element fileRef = doc.createElement("FileRef");
    fileRef.setAttribute("location", "container:" + xcodeprojDir.getFileName().toString());
    rootElem.appendChild(fileRef);

    workspace = doc;

    Path projectWorkspaceDir = xcodeprojDir.getParent().resolve(projectName + ".xcworkspace");
    projectFilesystem.mkdirs(projectWorkspaceDir);
    Path serializedWorkspace = projectWorkspaceDir.resolve("contents.xcworkspacedata");
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      DOMSource source = new DOMSource(doc);
      StreamResult result = new StreamResult(outputStream);
      transformer.transform(source, result);
      String contentsToWrite = outputStream.toString();
      if (MorePaths.fileContentsDiffer(
          new ByteArrayInputStream(contentsToWrite.getBytes(Charsets.UTF_8)),
          serializedWorkspace,
          projectFilesystem)) {
        if (shouldGenerateReadOnlyFiles()) {
          projectFilesystem.writeContentsToPath(
              contentsToWrite,
              serializedWorkspace,
              READ_ONLY_FILE_ATTRIBUTE);
        } else {
          projectFilesystem.writeContentsToPath(
              contentsToWrite,
              serializedWorkspace);
        }
      }
    } catch (TransformerException e) {
      throw new RuntimeException(e);
    }
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

  private String getHeaderOutputPathForRule(Optional<String> headerPathPrefix) {
    // This is appended to $(CONFIGURATION_BUILD_DIR), so we need to get reference the parent
    // directory to get $(SYMROOT)
    return Joiner.on('/').join(
        "..",
        "Headers",
        headerPathPrefix.or("$TARGET_NAME"));
  }

  /**
   * @param appleRule Must have a header map or an exception will be thrown.
   */
  private String getHeaderMapRelativePathForRule(
      AbstractAppleNativeTargetBuildRule appleRule,
      HeaderMapType headerMapType) {
    Optional<Path> filePath = appleRule.getPathToHeaderMap(headerMapType);
    Preconditions.checkState(filePath.isPresent(), "%s does not have a header map.", appleRule);
    return pathRelativizer.outputDirToRootRelative(filePath.get()).toString();
  }

  private String getHeaderSearchPathForRule(BuildRule rule) {
    if (rule.getType().equals(AppleLibraryDescription.TYPE)) {
      return Joiner.on('/').join(
          "$SYMROOT",
          BaseEncoding
              .base32()
              .omitPadding()
              .encode(rule.getFullyQualifiedName().getBytes()),
          "Headers");
    } else if (rule.getType().equals(XcodeNativeDescription.TYPE)) {
      return "$BUILT_PRODUCTS_DIR/Headers";
    } else {
      throw new RuntimeException("Unexpected type: " + rule.getType());
    }
  }

  private String getObjectOutputPathForRule(BuildRule rule) {
    if (rule.getType().equals(AppleLibraryDescription.TYPE)) {
      return Joiner.on('/').join(
          "$SYMROOT",
          BaseEncoding
              .base32()
              .omitPadding()
              .encode(rule.getFullyQualifiedName().getBytes()),
          // $EFFECTIVE_PLATFORM_NAME starts with a dash, so this expands to something like:
          // Debug-iphonesimulator
          "$CONFIGURATION$EFFECTIVE_PLATFORM_NAME");
    } else if (rule.getType().equals(XcodeNativeDescription.TYPE)) {
      return "$BUILT_PRODUCTS_DIR";
    } else {
      throw new RuntimeException("Unexpected type: " + rule.getType());
    }
  }

  private static boolean hasBuckHeaderMaps(BuildRule rule) {
    return (rule instanceof AbstractAppleNativeTargetBuildRule) &&
        ((AbstractAppleNativeTargetBuildRule) rule).getUseBuckHeaderMaps();
  }

  private ImmutableSet<String> collectRecursiveHeaderSearchPaths(BuildRule rule) {
    return FluentIterable
        .from(
            AppleBuildRules.getRecursiveRuleDependenciesOfType(
                AppleBuildRules.RecursiveRuleDependenciesMode.BUILDING,
                rule,
                AppleLibraryDescription.TYPE,
                XcodeNativeDescription.TYPE))
        .filter(new Predicate<BuildRule>() {
          @Override
          public boolean apply(BuildRule input) {
            return !hasBuckHeaderMaps(input);
          }
        })
        .transform(
            new Function<BuildRule, String>() {
              @Override
              public String apply(BuildRule input) {
                return getHeaderSearchPathForRule(input);
              }
            })
        .toSet();
  }

  private ImmutableSet<String> collectRecursiveHeaderMaps(BuildRule rule) {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    if (hasBuckHeaderMaps(rule)) {
      AbstractAppleNativeTargetBuildRule appleRule = (AbstractAppleNativeTargetBuildRule) rule;
      builder.add(getHeaderMapRelativePathForRule(appleRule, HeaderMapType.TARGET_HEADER_MAP));
    }

    for (BuildRule input :
        AppleBuildRules.getRecursiveRuleDependenciesOfType(
            AppleBuildRules.RecursiveRuleDependenciesMode.BUILDING,
            rule,
            AppleLibraryDescription.TYPE)) {
      if (hasBuckHeaderMaps(input)) {
        AbstractAppleNativeTargetBuildRule appleRule = (AbstractAppleNativeTargetBuildRule) input;
        builder.add(getHeaderMapRelativePathForRule(appleRule, HeaderMapType.PUBLIC_HEADER_MAP));
      }
    }

    return builder.build();
  }

  private ImmutableSet<String> collectUserHeaderMaps(BuildRule rule) {
    if (hasBuckHeaderMaps(rule)) {
      AbstractAppleNativeTargetBuildRule appleRule = (AbstractAppleNativeTargetBuildRule) rule;
      return ImmutableSet.of(getHeaderMapRelativePathForRule(
          appleRule,
          HeaderMapType.TARGET_USER_HEADER_MAP));
    } else {
      return ImmutableSet.of();
    }
  }

  private ImmutableSet<String> collectRecursiveLibrarySearchPaths(BuildRule rule) {
    return FluentIterable
        .from(
            AppleBuildRules.getRecursiveRuleDependenciesOfType(
                AppleBuildRules.RecursiveRuleDependenciesMode.LINKING,
                rule,
                AppleLibraryDescription.TYPE,
                XcodeNativeDescription.TYPE))
        .transform(
            new Function<BuildRule, String>() {
              @Override
              public String apply(BuildRule input) {
                return getObjectOutputPathForRule(input);
              }
            })
        .toSet();
  }

  private ImmutableSet<String> collectRecursiveFrameworkSearchPaths(BuildRule rule) {
    return FluentIterable
        .from(
            AppleBuildRules.getRecursiveRuleDependenciesOfType(
                AppleBuildRules.RecursiveRuleDependenciesMode.LINKING,
                rule,
                AppleLibraryDescription.TYPE,
                XcodeNativeDescription.TYPE))
        .transform(
            new Function<BuildRule, String>() {
              @Override
              public String apply(BuildRule input) {
                return getObjectOutputPathForRule(input);
              }
            })
        .toSet();
  }

  private void collectRecursiveFrameworkDependencies(
      BuildRule rule,
      ImmutableSet.Builder<String> frameworksBuilder) {
    for (BuildRule ruleDependency :
           AppleBuildRules.getRecursiveRuleDependenciesOfType(
               AppleBuildRules.RecursiveRuleDependenciesMode.LINKING,
               rule,
               AppleLibraryDescription.TYPE)) {
      // TODO(user): Add support to xcode_native rule for framework dependencies
      AppleLibrary appleLibrary =
          (AppleLibrary) Preconditions.checkNotNull(ruleDependency);
      frameworksBuilder.addAll(appleLibrary.getFrameworks());
    }
  }

  private ImmutableSet<PBXFileReference> collectRecursiveLibraryDependencies(BuildRule rule) {
    return FluentIterable
        .from(AppleBuildRules.getRecursiveRuleDependenciesOfType(
            AppleBuildRules.RecursiveRuleDependenciesMode.LINKING,
            rule,
            AppleLibraryDescription.TYPE,
            AppleBundleDescription.TYPE,
            XcodeNativeDescription.TYPE))
        .filter(
            new Predicate<BuildRule>() {
              @Override
              public boolean apply(BuildRule input) {
                if (input.getType().equals(AppleBundleDescription.TYPE)) {
                  AppleBundle bundle = (AppleBundle) input;
                  Optional<AppleBundleExtension> extension = bundle.getExtensionValue();
                  return extension.isPresent() && extension.get() == AppleBundleExtension.FRAMEWORK;
                } else {
                  return true;
                }
              }
            }
        )
        .transform(
            new Function<BuildRule, PBXFileReference>() {
              @Override
              public PBXFileReference apply(BuildRule input) {
                return getLibraryFileReferenceForRule(input);
              }
            }).toSet();
  }

  private SourceTreePath getProductsSourceTreePathForRule(BuildRule rule) {
    String productName = getProductName(rule.getBuildTarget());
    String productOutputName;

    if (rule.getType().equals(AppleLibraryDescription.TYPE)) {
      AppleLibrary library = (AppleLibrary) rule;
      String productOutputFormat =
          AppleLibrary.getOutputFileNameFormat(library.getLinkedDynamically());
      productOutputName = String.format(productOutputFormat, productName);
    } else if (rule.getType().equals(AppleBundleDescription.TYPE)) {
      AppleBundle bundle = (AppleBundle) rule;
      productOutputName = productName + "." + bundle.getExtensionString();
    } else if (rule.getType().equals(AppleBinaryDescription.TYPE)) {
      productOutputName = productName;
    } else {
      throw new RuntimeException("Unexpected type: " + rule.getType());
    }

    return new SourceTreePath(
        PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
        Paths.get(productOutputName));
  }

  private PBXFileReference getLibraryFileReferenceForRule(BuildRule rule) {
    if (rule.getType().equals(AppleLibraryDescription.TYPE) ||
        rule.getType().equals(AppleBundleDescription.TYPE)) {
      if (isBuiltByCurrentProject(rule.getBuildTarget())) {
        PBXNativeTarget target = (PBXNativeTarget) buildRuleToXcodeTarget.getUnchecked(rule).get();
        return Preconditions.checkNotNull(target.getProductReference());
      } else {
        SourceTreePath productsPath = getProductsSourceTreePathForRule(rule);
        return project.getMainGroup()
            .getOrCreateChildGroupByName("Frameworks")
            .getOrCreateFileReferenceBySourceTreePath(productsPath);
      }
    } else if (rule.getType().equals(XcodeNativeDescription.TYPE)) {
      XcodeNative nativeRule = (XcodeNative) rule;
        return project.getMainGroup()
            .getOrCreateChildGroupByName("Frameworks")
            .getOrCreateFileReferenceBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
                    Paths.get(nativeRule.getBuildableName())));
    } else {
      throw new RuntimeException("Unexpected type: " + rule.getType());
    }
  }

  /**
   * Whether a given build target is built by the project being generated, or being build elsewhere.
   */
  private boolean isBuiltByCurrentProject(BuildTarget buildTarget) {
    return options.contains(Option.GENERATE_TARGETS_FOR_DEPENDENCIES) ||
        initialTargets.contains(buildTarget);
  }

  private String getXcodeTargetName(BuildTarget target) {
    return options.contains(Option.USE_SHORT_NAMES_FOR_TARGETS)
        ? target.getShortNameOnly()
        : target.getFullyQualifiedName();
  }

  /**
   * Collect resources from recursive dependencies.
   *
   * @param rule  Build rule at the tip of the traversal.
   * @return The recursive resource buildables.
   */
  private static ImmutableSet<AppleResource> collectRecursiveResources(BuildRule rule) {
    Iterable<BuildRule> resourceRules = AppleBuildRules.getRecursiveRuleDependenciesOfType(
        AppleBuildRules.RecursiveRuleDependenciesMode.COPYING,
        rule,
        AppleResourceDescription.TYPE);
    return ImmutableSet.copyOf(Iterables.filter(resourceRules, AppleResource.class));
  }

  /**
   * Collect asset catalogs from recursive dependencies.
   */
  private static ImmutableSet<AppleAssetCatalog> collectRecursiveAssetCatalogs(BuildRule rule) {
    Iterable<BuildRule> assetCatalogRules = AppleBuildRules.getRecursiveRuleDependenciesOfType(
        AppleBuildRules.RecursiveRuleDependenciesMode.COPYING,
        rule,
        AppleAssetCatalogDescription.TYPE);
    return ImmutableSet.copyOf(Iterables.filter(assetCatalogRules, AppleAssetCatalog.class));
  }

  @SuppressWarnings("incomplete-switch")
  private static PBXTarget.ProductType bundleToTargetProductType(AppleBundle bundle) {
    BuildRule binary = bundle.getBinary();

    if (bundle.getExtensionValue().isPresent()) {
      AppleBundleExtension extension = bundle.getExtensionValue().get();

      if (binary.getType().equals(AppleLibraryDescription.TYPE)) {
        AppleLibrary library = (AppleLibrary) binary;

        if (library.getLinkedDynamically()) {
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

      PBXGroup configurationsGroup = project.getMainGroup().getOrCreateChildGroupByName(
          "Configurations");
      PBXFileReference fileReference =
          configurationsGroup.getOrCreateFileReferenceBySourceTreePath(
              new SourceTreePath(
                  PBXReference.SourceTree.SOURCE_ROOT,
                  pathRelativizer.outputDirToRootRelative(
                      resolver.getPath(config.projectLevelConfigFile.get()).normalize())));
      outputConfig.setBaseConfigurationReference(fileReference);

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
