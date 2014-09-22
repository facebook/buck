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

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.facebook.buck.apple.AbstractAppleNativeTargetBuildRule;
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
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.apple.FileExtensions;
import com.facebook.buck.apple.GroupedSource;
import com.facebook.buck.apple.HeaderVisibility;
import com.facebook.buck.apple.IosPostprocessResourcesDescription;
import com.facebook.buck.apple.XcodeNative;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.XcodeRuleConfiguration;
import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.apple.xcode.xcconfig.XcconfigStack;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXCopyFilesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFrameworksBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXHeadersBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXResourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXSourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXVariantGroup;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.apple.xcode.xcodeproj.XCVersionGroup;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.Escaper;
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
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
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

  private static final String PUBLIC_HEADER_MAP_SUFFIX = "-public-headers.hmap";
  private static final String TARGET_HEADER_MAP_SUFFIX = "-target-headers.hmap";
  private static final String TARGET_USER_HEADER_MAP_SUFFIX = "-target-user-headers.hmap";

  private static final FileAttribute<?> READ_ONLY_FILE_ATTRIBUTE =
    PosixFilePermissions.asFileAttribute(
      ImmutableSet.<PosixFilePermission>of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ));

  private final ImmutableSet<BuildRule> rulesToBuild;
  private final ProjectFilesystem projectFilesystem;
  private final ExecutionContext executionContext;
  private final Path outputDirectory;
  private final String projectName;
  private final ImmutableSet<BuildTarget> initialTargets;
  private final Path projectPath;
  private final Path repoRootRelativeToOutputDirectory;
  private final Path placedAssetCatalogBuildPhaseScript;

  private final ImmutableSet<Option> options;

  // These fields are created/filled when creating the projects.
  private final PBXProject project;
  private final LoadingCache<BuildRule, Optional<PBXTarget>> buildRuleToXcodeTarget;
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
      Iterable<BuildRule> rulesToBuild,
      Set<BuildTarget> initialTargets,
      ProjectFilesystem projectFilesystem,
      ExecutionContext executionContext,
      Path outputDirectory,
      String projectName,
      Set<Option> options) {
    this.rulesToBuild = ImmutableSet.copyOf(rulesToBuild);
    this.initialTargets = ImmutableSet.copyOf(initialTargets);
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.executionContext = Preconditions.checkNotNull(executionContext);
    this.outputDirectory = Preconditions.checkNotNull(outputDirectory);
    this.projectName = Preconditions.checkNotNull(projectName);
    this.options = ImmutableSet.copyOf(options);

    this.projectPath = outputDirectory.resolve(projectName + ".xcodeproj");
    this.repoRootRelativeToOutputDirectory =
      MorePaths.relativize(
          this.outputDirectory.toAbsolutePath(),
          projectFilesystem.getRootPath().toAbsolutePath());

    LOG.debug(
        "Output directory %s, profile fs root path %s, repo root relative to output dir %s",
        this.outputDirectory,
        projectFilesystem.getRootPath(),
        this.repoRootRelativeToOutputDirectory);

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

  public Map<BuildRule, PBXTarget> getBuildRuleToGeneratedTargetMap() {
    Preconditions.checkState(projectGenerated, "Must have called createXcodeProjects");
    return buildRuleToGeneratedTargetBuilder.build();
  }

  public void createXcodeProjects() throws IOException {
    LOG.debug("Creating projects for targets %s", initialTargets);

    try {
      for (BuildRule rule : rulesToBuild) {
        if (isBuiltByCurrentProject(rule)) {
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
            project,
            repoRootRelativeToOutputDirectory,
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
        isBuiltByCurrentProject(rule),
        "should not generate rule if it shouldn't be built by current project");
    Optional<PBXTarget> result;
    Optional<AbstractAppleNativeTargetBuildRule> nativeTargetRule;
    if (rule.getType().equals(AppleLibraryDescription.TYPE)) {
      AppleLibrary library = (AppleLibrary) rule;
      result = Optional.of(
          (PBXTarget) generateAppleLibraryTarget(
              project, rule, library));
      nativeTargetRule = Optional.<AbstractAppleNativeTargetBuildRule>of(library);
    } else if (rule.getType().equals(AppleBinaryDescription.TYPE)) {
      AppleBinary binary = (AppleBinary) rule;
      result = Optional.of(
          (PBXTarget) generateAppleBinaryTarget(
              project, rule, binary));
      nativeTargetRule = Optional.<AbstractAppleNativeTargetBuildRule>of(binary);
    } else if (rule.getType().equals(AppleBundleDescription.TYPE)) {
      AppleBundle bundle = (AppleBundle) rule;
      result = Optional.of(
          (PBXTarget) generateAppleBundleTarget(
              project, rule, bundle));
      nativeTargetRule = Optional.of((AbstractAppleNativeTargetBuildRule) bundle.getBinary());
    } else if (rule.getType().equals(AppleTestDescription.TYPE)) {
      AppleTest test = (AppleTest) rule;
      if (test.getTestBundle().getType().equals(AppleBundleDescription.TYPE)) {
        AppleBundle bundle = (AppleBundle) test.getTestBundle();
        if (bundle.getExtensionValue().isPresent() &&
            AppleBuildRules.isXcodeTargetTestBundleExtension(bundle.getExtensionValue().get())) {
          result = Optional.of(
              (PBXTarget) generateAppleBundleTarget(
                  project, bundle, bundle));
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
      BuildRule rule,
      AppleBundle bundle)
      throws IOException {
    Optional<Path> infoPlistPath;
    if (bundle.getInfoPlist().isPresent()) {
      infoPlistPath = Optional.of(bundle.getInfoPlist().get().resolve());
    } else {
      infoPlistPath = Optional.absent();
    }

    PBXNativeTarget target = generateBinaryTarget(
        project,
        rule,
        (AbstractAppleNativeTargetBuildRule) bundle.getBinary(),
        bundleToTargetProductType(bundle),
        "%s." + bundle.getExtensionString(),
        infoPlistPath,
        /* includeFrameworks */ true,
        /* includeResources */ true);

    // -- copy any binary and bundle targets into this bundle
    Iterable<BuildRule> copiedRules = AppleBuildRules.getRecursiveRuleDependenciesOfType(
        AppleBuildRules.RecursiveRuleDependenciesMode.COPYING,
        rule,
        AppleLibraryDescription.TYPE,
        AppleBinaryDescription.TYPE,
        AppleBundleDescription.TYPE);
    generateCopyFilesBuildPhases(project, target, copiedRules);

    addPostBuildScriptPhasesForDependencies(rule, target);

    project.getTargets().add(target);
    LOG.debug("Generated iOS bundle target %s", target);
    return target;
  }

  private PBXNativeTarget generateAppleBinaryTarget(
      PBXProject project,
      BuildRule rule,
      AppleBinary buildable)
      throws IOException {
    PBXNativeTarget target = generateBinaryTarget(
        project,
        rule,
        buildable,
        PBXTarget.ProductType.TOOL,
        "%s",
        Optional.<Path>absent(),
        /* includeFrameworks */ true,
        /* includeResources */ false);
    project.getTargets().add(target);
    LOG.debug("Generated Apple binary target %s", target);
    return target;
  }

  private PBXNativeTarget generateAppleLibraryTarget(
      PBXProject project,
      BuildRule rule,
      AppleLibrary buildable)
      throws IOException {
    PBXTarget.ProductType productType = buildable.getLinkedDynamically() ?
        PBXTarget.ProductType.DYNAMIC_LIBRARY : PBXTarget.ProductType.STATIC_LIBRARY;
    PBXNativeTarget target = generateBinaryTarget(
        project,
        rule,
        buildable,
        productType,
        AppleLibrary.getOutputFileNameFormat(buildable.getLinkedDynamically()),
        Optional.<Path>absent(),
        /* includeFrameworks */ buildable.getLinkedDynamically(),
        /* includeResources */ false);
    project.getTargets().add(target);
    LOG.debug("Generated iOS library target %s", target);
    return target;
  }

  private void writeHeaderMap(HeaderMap headerMap, BuildTarget target, String suffix)
      throws IOException {
    if (headerMap.getNumEntries() == 0) {
      return;
    }
    Path headerMapFile = getHeaderMapPathForTarget(target, suffix);
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

  private void setNativeTargetGid(
      PBXNativeTarget target,
      AbstractAppleNativeTargetBuildRule buildable) {
    Optional<String> buildableGid = buildable.getGid();
    if (buildableGid.isPresent()) {
      target.setGlobalID(buildableGid.get());
    }
  }

  private PBXNativeTarget generateBinaryTarget(
      PBXProject project,
      BuildRule rule,
      AbstractAppleNativeTargetBuildRule buildable,
      PBXTarget.ProductType productType,
      String productOutputFormat,
      Optional<Path> infoPlistOptional,
      boolean includeFrameworks,
      boolean includeResources)
      throws IOException {
    PBXNativeTarget target = new PBXNativeTarget(getXcodeTargetName(rule), productType);
    setNativeTargetGid(target, buildable);

    PBXGroup targetGroup = project.getMainGroup().getOrCreateChildGroupByName(target.getName());

    // -- configurations
    ImmutableMap.Builder<String, String> extraSettingsBuilder = ImmutableMap.builder();
    if (infoPlistOptional.isPresent()) {
      Path infoPlistPath = repoRootRelativeToOutputDirectory.resolve(infoPlistOptional.get());
      extraSettingsBuilder.put("INFOPLIST_FILE", infoPlistPath.toString());
    }
    if (buildable.getUseBuckHeaderMaps()) {
      extraSettingsBuilder.put("USE_HEADERMAP", "NO");
    }
    ImmutableMap.Builder<String, String> defaultSettingsBuilder = ImmutableMap.builder();
    defaultSettingsBuilder.put("PUBLIC_HEADERS_FOLDER_PATH",
        getHeaderOutputPathForRule(buildable.getHeaderPathPrefix()));
    if (rule.getType().equals(AppleLibraryDescription.TYPE)) {
      defaultSettingsBuilder.put("CONFIGURATION_BUILD_DIR", getObjectOutputPathForRule(rule));
    }
    setTargetBuildConfigurations(
        rule,
        target,
        targetGroup,
        buildable.getConfigurations(),
        extraSettingsBuilder.build(),
        defaultSettingsBuilder.build(),
        ImmutableMap.<String, String>of());

    // -- phases
    // TODO(Task #3772930): Go through all dependencies of the rule
    // and add any shell script rules here
    addRunScriptBuildPhasesForDependencies(rule, target);
    if (rule != buildable) {
      addRunScriptBuildPhasesForDependencies(buildable, target);
    }
    addBuildPhasesGroupsAndHeaderMapsForSourcesAndHeaders(
        rule.getBuildTarget(),
        target,
        targetGroup,
        buildable.getHeaderPathPrefix(),
        buildable.getUseBuckHeaderMaps(),
        buildable.getSrcs(),
        buildable.getPerFileFlags());
    if (includeFrameworks) {
      ImmutableSet.Builder<String> frameworksBuilder = ImmutableSet.builder();
      frameworksBuilder.addAll(buildable.getFrameworks());
      collectRecursiveFrameworkDependencies(rule, frameworksBuilder);
      ImmutableSet.Builder<String> weakFrameworksBuilder = ImmutableSet.builder();
      weakFrameworksBuilder.addAll(buildable.getWeakFrameworks());
      collectRecursiveWeakFrameworkDependencies(rule, weakFrameworksBuilder);
      addFrameworksBuildPhase(
          rule.getBuildTarget(),
          target,
          project.getMainGroup().getOrCreateChildGroupByName("Frameworks"),
          frameworksBuilder.build(),
          weakFrameworksBuilder.build(),
          collectRecursiveLibraryDependencies(rule));
    }
    if (includeResources) {
      addResourcesBuildPhase(
          target,
          targetGroup,
          collectRecursiveResources(rule, AppleResourceDescription.TYPE));
      addAssetCatalogBuildPhase(target, targetGroup, collectRecursiveAssetCatalogs(rule));
    }
    addCoreDataModelBuildPhase(
        targetGroup,
        collectCoreDataModels(rule.getDeps()));

    // -- products
    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    String productName = getProductName(rule.getBuildTarget());
    String outputName = String.format(productOutputFormat, productName);
    PBXFileReference productReference = productsGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Paths.get(outputName)));
    target.setProductName(productName);
    target.setProductReference(productReference);

    return target;
  }

  /**
   * Create project level (if it does not exist) and target level configuration entries.
   *
   * Each configuration should have an empty entry at the project level. The target level entries
   * combine the configuration values of every layer into a single configuration file that is
   * effectively laid out in layers.
   */
  private void setTargetBuildConfigurations(
      BuildRule buildRule,
      PBXTarget target,
      PBXGroup targetGroup,
      ImmutableMap<String, XcodeRuleConfiguration> configurations,
      ImmutableMap<String, String> overrideBuildSettings,
      ImmutableMap<String, String> defaultBuildSettings,
      ImmutableMap<String, String> appendBuildSettings)
      throws IOException {
    BuildTarget buildTarget = buildRule.getBuildTarget();

    ImmutableMap.Builder<String, String> overrideConfigsBuilder = ImmutableMap.builder();

    overrideConfigsBuilder
        .putAll(overrideBuildSettings)
        .put("TARGET_NAME", getProductName(buildTarget))
        .put("SRCROOT", relativizeBuckRelativePathToGeneratedProject(buildTarget, "").toString());

    ImmutableMap.Builder<String, String> defaultConfigsBuilder = ImmutableMap.builder();

    defaultConfigsBuilder
        .putAll(defaultBuildSettings)
        .put("PRODUCT_NAME", getProductName(buildTarget));

    ImmutableMap.Builder<String, String> appendConfigsBuilder = ImmutableMap.builder();

    appendConfigsBuilder
        .putAll(
            appendBuildSettings)
        .put(
            "HEADER_SEARCH_PATHS",
            Joiner.on(' ').join(Iterators.concat(
                collectRecursiveHeaderSearchPaths(buildRule).iterator(),
                collectRecursiveHeaderMaps(buildRule).iterator())))
        .put(
            "USER_HEADER_SEARCH_PATHS",
            Joiner.on(' ').join(collectUserHeaderMaps(buildRule)))
        .put(
            "LIBRARY_SEARCH_PATHS",
            Joiner.on(' ').join(collectRecursiveLibrarySearchPaths(buildRule)))
        .put(
            "FRAMEWORK_SEARCH_PATHS",
            Joiner.on(' ').join(collectRecursiveFrameworkSearchPaths(buildRule)));

    // HACK: GCC_PREFIX_HEADER needs to be modified because the path is referenced relative to
    // project root, so if the project is generated in a different place from the BUCK file, it
    // would break. This forces it to be based off of SRCROOT, which is overriden to point to the
    // BUCK file location.
    // However, when using REFERENCE_EXISTING_XCCONFIGS, this setting is not put into another layer,
    // and therefore may override an existing setting in the target-inline-config level.
    // Fortunately, this option is only set when we are generating separated projects, which are
    // placed next to the BUCK files, so avoiding this is OK.
    // In the long run, setting should be written relative to SRCROOT everywhere, and this entire
    // hack can be deleted.
    if (!options.contains(Option.REFERENCE_EXISTING_XCCONFIGS)) {
      overrideConfigsBuilder.put("GCC_PREFIX_HEADER", "$(SRCROOT)/$(inherited)");
    }

    ImmutableMap<String, String> overrideConfigs = overrideConfigsBuilder.build();
    ImmutableMap<String, String> defaultConfigs = defaultConfigsBuilder.build();
    ImmutableMap<String, String> appendConfigs = appendConfigsBuilder.build();

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
          {
            Map<String, String> mutableOverrideConfigs = new HashMap<>(overrideConfigs);
            for (Map.Entry<String, String> entry: defaultConfigs.entrySet()) {
              String existingSetting = layers.targetLevelInlineSettings.get(entry.getKey());
              if (existingSetting == null) {
                mutableOverrideConfigs.put(entry.getKey(), entry.getValue());
              }
            }

            for (Map.Entry<String, String> entry : appendConfigs.entrySet()) {
              String existingSetting = layers.targetLevelInlineSettings.get(entry.getKey());
              String settingPrefix;
              if (existingSetting != null) {
                settingPrefix = existingSetting + ' ';
              } else {
                settingPrefix = "$(inherited) ";
              }
              mutableOverrideConfigs.put(entry.getKey(), settingPrefix + entry.getValue());
            }
            overrideConfigs = ImmutableMap.copyOf(mutableOverrideConfigs);
          }

          PBXFileReference fileReference =
              configurationsGroup.getOrCreateFileReferenceBySourceTreePath(
                  new SourceTreePath(
                      PBXReference.SourceTree.SOURCE_ROOT,
                      repoRootRelativeToOutputDirectory.resolve(
                          layers.targetLevelConfigFile.get().resolve()).normalize()));
          outputConfiguration.setBaseConfigurationReference(fileReference);

          NSDictionary inlineSettings = new NSDictionary();
          Iterable<Map.Entry<String, String>> entries = Iterables.concat(
              layers.targetLevelInlineSettings.entrySet(),
              overrideConfigs.entrySet());
          for (Map.Entry<String, String> entry : entries) {
            inlineSettings.put(entry.getKey(), entry.getValue());
          }
          outputConfiguration.setBuildSettings(inlineSettings);
        }
      } else {
        // Add search paths for dependencies
        Map<String, String> mutableExtraConfigs = new HashMap<>(overrideConfigs);
        for (Map.Entry<String, String> entry : appendBuildSettings.entrySet()) {
          String setting = "$(inherited) " + entry.getValue();
          mutableExtraConfigs.put(entry.getKey(), setting);
        }
        overrideConfigs = ImmutableMap.copyOf(mutableExtraConfigs);

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
            overrideConfigs);
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
                    repoRootRelativeToOutputDirectory.resolve(configurationFilePath)));
        XCBuildConfiguration outputConfiguration = target
            .getBuildConfigurationList()
            .getBuildConfigurationsByName()
            .getUnchecked(configurationEntry.getKey());
        outputConfiguration.setBaseConfigurationReference(fileReference);
      }
    }
  }

  private void addRunScriptBuildPhase(
    PBXNativeTarget target,
    ImmutableList<Path> srcs,
    ShellStep genruleStep,
    String outputName) {
    // TODO(user): Check and validate dependencies of the script. If it depends on libraries etc.
    // we can't handle it currently.
    PBXShellScriptBuildPhase shellScriptBuildPhase = new PBXShellScriptBuildPhase();
    target.getBuildPhases().add(shellScriptBuildPhase);
    for (Path path : srcs) {
      shellScriptBuildPhase.getInputPaths().add(
          repoRootRelativeToOutputDirectory.resolve(path).toString());
    }

    StringBuilder bashCommandBuilder = new StringBuilder();
    for (String commandElement : genruleStep.getShellCommand(executionContext)) {
      if (bashCommandBuilder.length() > 0) {
        bashCommandBuilder.append(' ');
      }
      bashCommandBuilder.append(Escaper.escapeAsBashString(commandElement));
    }
    shellScriptBuildPhase.setShellScript(bashCommandBuilder.toString());
    if (outputName.length() > 0) {
      shellScriptBuildPhase.getOutputPaths().add(outputName);
    }
  }

  private void addRunScriptBuildPhasesForDependenciesWithType(
      BuildRule rule,
      PBXNativeTarget target,
      BuildRuleType type) {
    for (BuildRule dependency : rule.getDeps()) {
      if (dependency.getType().equals(type)) {
        Genrule genrule = (Genrule) dependency;
        addRunScriptBuildPhase(
            target,
            genrule.getSrcs(),
            genrule.createGenruleStep(),
            genrule.getOutputName());
      }
    }
  }

  private void addRunScriptBuildPhasesForDependencies(BuildRule rule, PBXNativeTarget target) {
    addRunScriptBuildPhasesForDependenciesWithType(rule, target, GenruleDescription.TYPE);
  }

  private void addPostBuildScriptPhasesForDependencies(BuildRule rule, PBXNativeTarget target) {
    addRunScriptBuildPhasesForDependenciesWithType(
        rule,
        target,
        IosPostprocessResourcesDescription.TYPE);
  }

  /**
   * Add sources and headers build phases to a target, and add references to the target's group.
   * Create header map files and write them to disk.
   *
   * @param target      Target to add the build phases to.
   * @param targetGroup Group to link the source files to.
   * @param groupedSources Grouped sources and headers to include in the build
   *        phase, path relative to project root.
   * @param sourceFlags    Source path to flag mapping.
   */
  private void addBuildPhasesGroupsAndHeaderMapsForSourcesAndHeaders(
      BuildTarget buildTarget,
      PBXNativeTarget target,
      PBXGroup targetGroup,
      Optional<String> headerPathPrefix,
      boolean useBuckHeaderMaps,
      Iterable<GroupedSource> groupedSources,
      ImmutableMap<SourcePath, String> sourceFlags) throws IOException {
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");
    // Sources groups stay in the order in which they're declared in the BUCK file.
    sourcesGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
    PBXSourcesBuildPhase sourcesBuildPhase = new PBXSourcesBuildPhase();
    PBXHeadersBuildPhase headersBuildPhase = new PBXHeadersBuildPhase();

    addGroupedSourcesToBuildPhases(
        sourcesGroup,
        sourcesBuildPhase,
        // We still want to create groups for header files even if header build phases
        // are replaced with header maps.
        (useBuckHeaderMaps ? Optional.<PBXHeadersBuildPhase>absent()
            : Optional.of(headersBuildPhase)),
        groupedSources,
        sourceFlags);

    if (!sourcesBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(sourcesBuildPhase);
    }
    if (!headersBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(headersBuildPhase);
    }

    // -- header maps
    if (useBuckHeaderMaps) {
      HeaderMap.Builder publicMapBuilder = HeaderMap.builder();
      HeaderMap.Builder targetMapBuilder = HeaderMap.builder();
      HeaderMap.Builder targetUserMapBuilder = HeaderMap.builder();
      addGroupedSourcesToHeaderMaps(
          publicMapBuilder,
          targetMapBuilder,
          targetUserMapBuilder,
          Paths.get(headerPathPrefix.or(getProductName(buildTarget))),
          groupedSources,
          sourceFlags);
      writeHeaderMap(publicMapBuilder.build(), buildTarget, PUBLIC_HEADER_MAP_SUFFIX);
      writeHeaderMap(targetMapBuilder.build(), buildTarget, TARGET_HEADER_MAP_SUFFIX);
      writeHeaderMap(targetUserMapBuilder.build(), buildTarget, TARGET_USER_HEADER_MAP_SUFFIX);
    }

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
          if (SourcePaths.isSourcePathExtensionInSet(
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

  private void addGroupedSourcesToBuildPhases(
      PBXGroup sourcesGroup,
      PBXSourcesBuildPhase sourcesBuildPhase,
      Optional<PBXHeadersBuildPhase> headersBuildPhase,
      Iterable<GroupedSource> groupedSources,
      ImmutableMap<SourcePath, String> sourceFlags) {
    for (GroupedSource groupedSource : groupedSources) {
      switch (groupedSource.getType()) {
        case SOURCE_PATH:
          if (SourcePaths.isSourcePathExtensionInSet(
                  groupedSource.getSourcePath(),
                  FileExtensions.CLANG_HEADERS)) {
              addSourcePathToHeadersBuildPhase(
                  groupedSource.getSourcePath(),
                  sourcesGroup,
                  headersBuildPhase,
                  sourceFlags);
          } else {
            addSourcePathToSourcesBuildPhase(
                groupedSource.getSourcePath(),
                sourcesGroup,
                sourcesBuildPhase,
                sourceFlags);
          }
          break;
        case SOURCE_GROUP:
          PBXGroup newSourceGroup = sourcesGroup.getOrCreateChildGroupByName(
              groupedSource.getSourceGroupName());
          // Sources groups stay in the order in which they're declared in the BUCK file.
          newSourceGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
          addGroupedSourcesToBuildPhases(
              newSourceGroup,
              sourcesBuildPhase,
              headersBuildPhase,
              groupedSource.getSourceGroup(),
              sourceFlags);
          break;
        default:
          throw new RuntimeException("Unhandled grouped source type: " + groupedSource.getType());
      }
    }
  }

  private void addSourcePathToSourcesBuildPhase(
      SourcePath sourcePath,
      PBXGroup sourcesGroup,
      PBXSourcesBuildPhase sourcesBuildPhase,
      ImmutableMap<SourcePath, String> sourceFlags) {
    Path path = sourcePath.resolve();
    PBXFileReference fileReference = sourcesGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            repoRootRelativeToOutputDirectory.resolve(path)));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    sourcesBuildPhase.getFiles().add(buildFile);
    String customFlags = sourceFlags.get(sourcePath);
    if (customFlags != null) {
      NSDictionary settings = new NSDictionary();
      settings.put("COMPILER_FLAGS", customFlags);
      buildFile.setSettings(Optional.of(settings));
    }
    LOG.verbose(
        "Added source path %s to group %s, flags %s, PBXFileReference %s",
        sourcePath,
        sourcesGroup.getName(),
        customFlags,
        fileReference);
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
    String fileName = headerPath.resolve().getFileName().toString();
    String prefixedFileName = prefix.resolve(fileName).toString();
    Path value =
        projectFilesystem.getPathForRelativePath(headerPath.resolve())
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

  private void addSourcePathToHeadersBuildPhase(
      SourcePath headerPath,
      PBXGroup headersGroup,
      Optional<PBXHeadersBuildPhase> headersBuildPhase,
      ImmutableMap<SourcePath, String> sourceFlags) {
    Path path = headerPath.resolve();
    Path repoRootRelativePath = repoRootRelativeToOutputDirectory.resolve(path);
    PBXFileReference fileReference = headersGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            repoRootRelativePath));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    String headerFlags = sourceFlags.get(headerPath);
    if (headerFlags != null) {
      // If we specify nothing, Xcode will use "project" visibility.
      NSDictionary settings = new NSDictionary();
      settings.put(
          "ATTRIBUTES",
          new NSArray(new NSString(HeaderVisibility.fromString(headerFlags).toXcodeAttribute())));
      buildFile.setSettings(Optional.of(settings));
    } else {
      buildFile.setSettings(Optional.<NSDictionary>absent());
    }
    if (headersBuildPhase.isPresent()) {
      headersBuildPhase.get().getFiles().add(buildFile);
      LOG.verbose(
          "Added header path %s to headers group %s, flags %s, PBXFileReference %s",
          headerPath,
          headersGroup.getName(),
          headerFlags,
          fileReference);
    } else {
      LOG.verbose(
          "Skipped header path %s to headers group %s, flags %s, PBXFileReference %s",
          headerPath,
          headersGroup.getName(),
          headerFlags,
          fileReference);
    }
  }

  private void addResourcesBuildPhase(
      PBXNativeTarget target,
      PBXGroup targetGroup,
      Iterable<AppleResource> resources) {
    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");
    PBXBuildPhase phase = new PBXResourcesBuildPhase();
    target.getBuildPhases().add(phase);
    for (AppleResource resource : resources) {
      Iterable<Path> paths = Iterables.concat(
          SourcePaths.toPaths(resource.getFiles()),
          resource.getDirs());
      for (Path path : paths) {
        PBXFileReference fileReference = resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                repoRootRelativeToOutputDirectory.resolve(path)));
        PBXBuildFile buildFile = new PBXBuildFile(fileReference);
        phase.getFiles().add(buildFile);
      }

      for (String virtualOutputPath : resource.getVariants().keySet()) {
        ImmutableMap<String, SourcePath> contents = resource.getVariants().get(virtualOutputPath);

        String variantName = Paths.get(virtualOutputPath).getFileName().toString();
        PBXVariantGroup variantGroup =
            resourcesGroup.getOrCreateChildVariantGroupByName(variantName);

        PBXBuildFile buildFile = new PBXBuildFile(variantGroup);
        phase.getFiles().add(buildFile);

        for (String childVirtualName : contents.keySet()) {
          Path childPath = contents.get(childVirtualName).resolve();
          SourceTreePath sourceTreePath = new SourceTreePath(
              PBXReference.SourceTree.SOURCE_ROOT,
              repoRootRelativeToOutputDirectory.resolve(childPath));

          variantGroup.getOrCreateVariantFileReferenceByNameAndSourceTreePath(
              childVirtualName,
              sourceTreePath);
        }
      }
    }
    LOG.debug("Added resources build phase %s", phase);
  }

  private void addAssetCatalogBuildPhase(
      PBXNativeTarget target, PBXGroup targetGroup,
      final Iterable<AppleAssetCatalog> assetCatalogs) {

    // Asset catalogs go in the resources group also.
    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

    // Some asset catalogs should be copied to their sibling bundles, while others use the default
    // output format (which may be to copy individual files to the root resource output path or to
    // be archived in Assets.car if it is supported by the target platform version).

    ImmutableList.Builder<String> commonAssetCatalogsBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> assetCatalogsToSplitIntoBundlesBuilder =
        ImmutableList.builder();
    for (AppleAssetCatalog assetCatalog : assetCatalogs) {

      List<String> scriptArguments = Lists.newArrayList();
      for (Path dir : assetCatalog.getDirs()) {
        resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                repoRootRelativeToOutputDirectory.resolve(dir)));

        Path pathRelativeToProjectRoot = MorePaths.relativize(outputDirectory, dir);
        LOG.debug(
            "Resolved asset catalog path %s, output directory %s, result %s",
            dir, outputDirectory, pathRelativeToProjectRoot);
        scriptArguments.add("$PROJECT_DIR/" + pathRelativeToProjectRoot.toString());
      }

      if (assetCatalog.getCopyToBundles()) {
        assetCatalogsToSplitIntoBundlesBuilder.addAll(scriptArguments);
      } else {
        commonAssetCatalogsBuilder.addAll(scriptArguments);
      }
    }

    ImmutableList<String> commonAssetCatalogs = commonAssetCatalogsBuilder.build();
    ImmutableList<String> assetCatalogsToSplitIntoBundles =
        assetCatalogsToSplitIntoBundlesBuilder.build();

    // If there are no asset catalogs, don't add the build phase
    if (commonAssetCatalogs.size() == 0 &&
        assetCatalogsToSplitIntoBundles.size() == 0) {
      return;
    }

    Path assetCatalogBuildPhaseScriptRelativeToProjectRoot;

    if (PATH_OVERRIDE_FOR_ASSET_CATALOG_BUILD_PHASE_SCRIPT != null) {
      assetCatalogBuildPhaseScriptRelativeToProjectRoot =
          this.repoRootRelativeToOutputDirectory.resolve(
              PATH_OVERRIDE_FOR_ASSET_CATALOG_BUILD_PHASE_SCRIPT).normalize();
    } else {
      // In order for the script to run, it must be accessible by Xcode and
      // deserves to be part of the generated output.
      shouldPlaceAssetCatalogCompiler = true;

      assetCatalogBuildPhaseScriptRelativeToProjectRoot =
        MorePaths.relativize(outputDirectory, placedAssetCatalogBuildPhaseScript);
    }

    // Map asset catalog paths to their shell script arguments relative to the project's root
    String combinedAssetCatalogsToBeSplitIntoBundlesScriptArguments =
        Joiner.on(' ').join(assetCatalogsToSplitIntoBundles);
    String combinedCommonAssetCatalogsScriptArguments = Joiner.on(' ').join(commonAssetCatalogs);

    PBXShellScriptBuildPhase phase = new PBXShellScriptBuildPhase();

    StringBuilder scriptBuilder = new StringBuilder("set -e\n");
    if (commonAssetCatalogs.size() != 0) {
      scriptBuilder.append("\"${PROJECT_DIR}/\"" +
              assetCatalogBuildPhaseScriptRelativeToProjectRoot.toString() + " " +
              combinedCommonAssetCatalogsScriptArguments + "\n");
    }

    if (assetCatalogsToSplitIntoBundles.size() != 0) {
      scriptBuilder.append("\"${PROJECT_DIR}/\"" +
              assetCatalogBuildPhaseScriptRelativeToProjectRoot.toString() + " -b " +
              combinedAssetCatalogsToBeSplitIntoBundlesScriptArguments);
    }
    phase.setShellScript(scriptBuilder.toString());
    LOG.debug("Added asset catalog build phase %s", phase);
    target.getBuildPhases().add(phase);
  }

  private void addCoreDataModelBuildPhase(
      PBXGroup targetGroup,
      final Iterable<CoreDataModel> dataModels) throws IOException {
    // TODO(user): actually add a build phase

    for (final CoreDataModel dataModel: dataModels) {
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
                    repoRootRelativeToOutputDirectory.resolve(dataModel.getPath())
                    ));

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
                        repoRootRelativeToOutputDirectory.resolve(dir)
                    ));
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
                  repoRootRelativeToOutputDirectory.resolve(dataModel.getPath().resolve(
                          currentVersionName.toString()))
              ));
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
                repoRootRelativeToOutputDirectory.resolve(dataModel.getPath())));
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
    ImmutableMap.Builder<PBXCopyFilesBuildPhase.Destination, ImmutableSet.Builder<BuildRule>>
        destinationRulesBuildersBuilder = ImmutableMap.builder();
    for (PBXCopyFilesBuildPhase.Destination destination :
        PBXCopyFilesBuildPhase.Destination.values()) {
      destinationRulesBuildersBuilder.put(destination, ImmutableSet.<BuildRule>builder());
    }

    ImmutableMap<PBXCopyFilesBuildPhase.Destination, ImmutableSet.Builder<BuildRule>>
        destinationRulesBuilders = destinationRulesBuildersBuilder.build();

    for (BuildRule copiedRule : copiedRules) {
      Optional<PBXCopyFilesBuildPhase.Destination> optionalDestination =
          getDestinationForRule(copiedRule);

      if (optionalDestination.isPresent()) {
        PBXCopyFilesBuildPhase.Destination destination = optionalDestination.get();
        ImmutableSet.Builder<BuildRule> rulesBuilder = destinationRulesBuilders.get(destination);
        rulesBuilder.add(copiedRule);
      }
    }

    for (PBXCopyFilesBuildPhase.Destination destination : destinationRulesBuilders.keySet()) {
      ImmutableSet<BuildRule> rules = destinationRulesBuilders.get(destination).build();

      ImmutableSet.Builder<SourceTreePath> copiedSourceTreePathsBuilder = ImmutableSet.builder();
      for (BuildRule rule : rules) {
        copiedSourceTreePathsBuilder.add(getProductsSourceTreePathForRule(rule));
      }

      addCopyFilesBuildPhase(
          target,
          project.getMainGroup().getOrCreateChildGroupByName("Dependencies"),
          destination,
          "",
          copiedSourceTreePathsBuilder.build());
    }
  }

  private void addFrameworksBuildPhase(
      BuildTarget buildTarget,
      PBXNativeTarget target,
      PBXGroup sharedFrameworksGroup,
      Iterable<String> frameworks,
      Iterable<String> weakFrameworks,
      Iterable<PBXFileReference> archives) {
    PBXFrameworksBuildPhase frameworksBuildPhase = new PBXFrameworksBuildPhase();
    target.getBuildPhases().add(frameworksBuildPhase);

    // If a framework is listed as both weak and strong, prefer strong.
    Set<String> includedWeakFrameworks = Sets.difference(
        ImmutableSortedSet.copyOf(weakFrameworks),
        ImmutableSortedSet.copyOf(frameworks));

    for (String framework : Iterables.concat(frameworks, includedWeakFrameworks)) {
      Path path = Paths.get(framework);

      String firstElement =
        Preconditions.checkNotNull(Iterables.getFirst(path, Paths.get(""))).toString();

      PBXBuildFile buildFile;

      if (firstElement.startsWith("$")) { // NOPMD - length() > 0 && charAt(0) == '$' is ridiculous
        Optional<PBXReference.SourceTree> sourceTree =
            PBXReference.SourceTree.fromBuildSetting(firstElement);
        if (sourceTree.isPresent()) {
          Path sdkRootRelativePath = path.subpath(1, path.getNameCount());
          PBXFileReference fileReference =
              sharedFrameworksGroup.getOrCreateFileReferenceBySourceTreePath(
                  new SourceTreePath(sourceTree.get(), sdkRootRelativePath));
          buildFile = new PBXBuildFile(fileReference);
          frameworksBuildPhase.getFiles().add(buildFile);
        } else {
          throw new HumanReadableException(String.format(
              "Unknown SourceTree: %s in build target: %s. Should be one of: %s",
              firstElement,
              buildTarget,
              Joiner.on(',').join(Iterables.transform(
                  ImmutableList.copyOf(PBXReference.SourceTree.values()),
                  new Function<PBXReference.SourceTree, String>() {
                    @Override
                    public String apply(PBXReference.SourceTree input) {
                      return "$" + input.toString();
                    }
                  }))));
        }
      } else {
        // regular path
        PBXFileReference fileReference =
            sharedFrameworksGroup.getOrCreateFileReferenceBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.GROUP,
                    relativizeBuckRelativePathToGeneratedProject(buildTarget, path.toString())));
        buildFile = new PBXBuildFile(fileReference);
        frameworksBuildPhase.getFiles().add(buildFile);
      }

      if (includedWeakFrameworks.contains(framework)) {
        NSDictionary settings = new NSDictionary();
        settings.put("ATTRIBUTES", new NSArray(new NSString("Weak")));
        buildFile.setSettings(Optional.of(settings));
      }
    }

    for (PBXFileReference archive : archives) {
      frameworksBuildPhase.getFiles().add(new PBXBuildFile(archive));
    }
  }

  private void addCopyFilesBuildPhase(
      PBXNativeTarget target,
      PBXGroup sharedGroup,
      PBXCopyFilesBuildPhase.Destination destination,
      String destinationSubpath,
      Iterable<SourceTreePath> files) {
    PBXCopyFilesBuildPhase copyFilesBuildPhase = null;
    for (SourceTreePath file : files) {
      if (copyFilesBuildPhase == null) {
        copyFilesBuildPhase =
            new PBXCopyFilesBuildPhase(destination, destinationSubpath);
        target.getBuildPhases().add(copyFilesBuildPhase);
      }
      PBXFileReference fileReference = sharedGroup.getOrCreateFileReferenceBySourceTreePath(
          file);
      copyFilesBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
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
    for (XcodeRuleConfiguration.Layer layer : configuration.getLayers()) {
      switch (layer.getLayerType()) {
        case FILE:
          builder.addSettingsFromFile(
              projectFilesystem,
              searchPaths,
              layer.getSourcePath().get().resolve());
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

  /**
   * Given a path relative to a BUCK file, return a path relative to the generated project.
   *
   * @param buildTarget
   * @param path          path relative to build target
   * @return  the given path relative to the generated project
   */
  private Path relativizeBuckRelativePathToGeneratedProject(BuildTarget buildTarget, String path) {
    Path originalProjectPath = projectFilesystem.getPathForRelativePath(
        Paths.get(buildTarget.getBasePathWithSlash()));
    return repoRootRelativeToOutputDirectory.resolve(originalProjectPath).resolve(path).normalize();
  }

  private String getHeaderOutputPathForRule(Optional<String> headerPathPrefix) {
    // This is appended to $(CONFIGURATION_BUILD_DIR), so we need to get reference the parent
    // directory to get $(SYMROOT)
    return Joiner.on('/').join(
        "..",
        "Headers",
        headerPathPrefix.or("$TARGET_NAME"));
  }

  private Path getHeaderMapPathForTarget(BuildTarget target, String suffix) {
    Path targetPath = target.getBasePath();
    String fileName = getProductName(target) + suffix;
    return BuckConstant.BUCK_OUTPUT_PATH.resolve(targetPath).resolve(fileName);
  }

  private String getHeaderMapRelativePathForRule(BuildRule rule, String suffix) {
    Path filePath = getHeaderMapPathForTarget(rule.getBuildTarget(), suffix);
    return repoRootRelativeToOutputDirectory.resolve(filePath).toString();
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
            }
        )
        .toSet();
  }

  private ImmutableSet<String> collectRecursiveHeaderMaps(BuildRule rule) {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    if (hasBuckHeaderMaps(rule)) {
      builder.add(getHeaderMapRelativePathForRule(rule, TARGET_HEADER_MAP_SUFFIX));
    }

    for (BuildRule input :
        AppleBuildRules.getRecursiveRuleDependenciesOfType(
            AppleBuildRules.RecursiveRuleDependenciesMode.BUILDING,
            rule,
            AppleLibraryDescription.TYPE)) {
      if (hasBuckHeaderMaps(input)) {
        builder.add(getHeaderMapRelativePathForRule(input, PUBLIC_HEADER_MAP_SUFFIX));
      }
    }

    return builder.build();
  }

  private ImmutableSet<String> collectUserHeaderMaps(BuildRule rule) {
    if (hasBuckHeaderMaps(rule)) {
      return ImmutableSet.of(getHeaderMapRelativePathForRule(rule,
          TARGET_USER_HEADER_MAP_SUFFIX));
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
      AbstractAppleNativeTargetBuildRule buildRule =
          (AbstractAppleNativeTargetBuildRule) Preconditions.checkNotNull(ruleDependency);
      // TODO(user): Add support to xcode_native rule for framework dependencies
      frameworksBuilder.addAll(buildRule.getFrameworks());
    }
  }

  private void collectRecursiveWeakFrameworkDependencies(
      BuildRule rule,
      ImmutableSet.Builder<String> weakFrameworksBuilder) {
    for (BuildRule ruleDependency :
        AppleBuildRules.getRecursiveRuleDependenciesOfType(
            AppleBuildRules.RecursiveRuleDependenciesMode.LINKING,
            rule,
            AppleLibraryDescription.TYPE)) {
      AbstractAppleNativeTargetBuildRule buildRule =
          (AbstractAppleNativeTargetBuildRule) Preconditions.checkNotNull(ruleDependency);
      weakFrameworksBuilder.addAll(buildRule.getWeakFrameworks());
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
      if (isBuiltByCurrentProject(rule)) {
        PBXNativeTarget target = (PBXNativeTarget) buildRuleToXcodeTarget.getUnchecked(rule).get();
        return target.getProductReference();
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
   * Whether a given build rule is built by the project being generated, or being build elsewhere.
   */
  private boolean isBuiltByCurrentProject(BuildRule rule) {
    return options.contains(Option.GENERATE_TARGETS_FOR_DEPENDENCIES) ||
        initialTargets.contains(rule.getBuildTarget());
  }

  private String getXcodeTargetName(BuildRule rule) {
    return options.contains(Option.USE_SHORT_NAMES_FOR_TARGETS)
        ? rule.getBuildTarget().getShortNameOnly()
        : rule.getBuildTarget().getFullyQualifiedName();
  }

  private Iterable<CoreDataModel> collectCoreDataModels(Iterable<BuildRule> rules) {
    return Iterables.filter(
        getRuleDependenciesOfType(rules, CoreDataModelDescription.TYPE),
        CoreDataModel.class);
  }
  /**
   * Collect resources from recursive dependencies.
   *
   * @param rule  Build rule at the tip of the traversal.
   * @return The recursive resource buildables.
   */
  private Iterable<AppleResource> collectRecursiveResources(
      BuildRule rule,
      BuildRuleType resourceRuleType) {
    Iterable<BuildRule> resourceRules = AppleBuildRules.getRecursiveRuleDependenciesOfType(
        AppleBuildRules.RecursiveRuleDependenciesMode.COPYING,
        rule,
        resourceRuleType);
    ImmutableSet.Builder<AppleResource> resources = ImmutableSet.builder();
    for (BuildRule resourceRule : resourceRules) {
      AppleResource resource =
          (AppleResource) Preconditions.checkNotNull(resourceRule);
      resources.add(resource);
    }
    return resources.build();
  }

  /**
   * Collect asset catalogs from recursive dependencies.
   */
  private Iterable<AppleAssetCatalog> collectRecursiveAssetCatalogs(BuildRule rule) {
    Iterable<BuildRule> assetCatalogRules = AppleBuildRules.getRecursiveRuleDependenciesOfType(
        AppleBuildRules.RecursiveRuleDependenciesMode.COPYING,
        rule,
        AppleAssetCatalogDescription.TYPE);
    ImmutableSet.Builder<AppleAssetCatalog> assetCatalogs = ImmutableSet.builder();
    for (BuildRule assetCatalogRule : assetCatalogRules) {
      AppleAssetCatalog assetCatalog = (AppleAssetCatalog) Preconditions.checkNotNull(
          assetCatalogRule);
      assetCatalogs.add(assetCatalog);
    }
    return assetCatalogs.build();
  }

  private Iterable<BuildRule> getRuleDependenciesOfType(
      final Iterable<BuildRule> rules, BuildRuleType... types) {
    final ImmutableSet<BuildRuleType> requestedTypes = ImmutableSet.copyOf(types);
    return Iterables.filter(
        rules,
        new Predicate<BuildRule>() {
          @Override
          public boolean apply(BuildRule input) {
            return requestedTypes.contains(input.getType());
          }
        }
    );
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

  private static void setProjectLevelConfigs(
      PBXProject project,
      Path repoRootRelativeToOutputDirectory,
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
                  repoRootRelativeToOutputDirectory.resolve(
                      config.projectLevelConfigFile.get().resolve()).normalize()));
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
    ImmutableList<XcodeRuleConfiguration.Layer> layers = configuration.getLayers();
    switch (layers.size()) {
      case 2:
        if (layers.get(0).getLayerType() == XcodeRuleConfiguration.LayerType.FILE &&
            layers.get(1).getLayerType() == XcodeRuleConfiguration.LayerType.FILE) {
          extractedLayers = new ConfigInXcodeLayout(
              buildTarget,
              layers.get(0).getSourcePath(),
              ImmutableMap.<String, String>of(),
              layers.get(1).getSourcePath(),
              ImmutableMap.<String, String>of());
        }
        break;
      case 4:
        if (layers.get(0).getLayerType() == XcodeRuleConfiguration.LayerType.FILE &&
            layers.get(1).getLayerType() == XcodeRuleConfiguration.LayerType.INLINE_SETTINGS &&
            layers.get(2).getLayerType() == XcodeRuleConfiguration.LayerType.FILE &&
            layers.get(3).getLayerType() == XcodeRuleConfiguration.LayerType.INLINE_SETTINGS) {
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
      this.buildTarget = Preconditions.checkNotNull(buildTarget);
      this.projectLevelConfigFile =
          Preconditions.checkNotNull(projectLevelConfigFile);
      this.projectLevelInlineSettings = Preconditions.checkNotNull(projectLevelInlineSettings);
      this.targetLevelConfigFile = Preconditions.checkNotNull(targetLevelConfigFile);
      this.targetLevelInlineSettings = Preconditions.checkNotNull(targetLevelInlineSettings);
    }
  }
}
