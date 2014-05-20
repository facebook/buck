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
import com.dd.plist.NSString;
import com.facebook.buck.apple.AppleAssetCatalog;
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.AppleBuildable;
import com.facebook.buck.apple.AppleResource;
import com.facebook.buck.apple.GroupedSource;
import com.facebook.buck.apple.HeaderVisibility;
import com.facebook.buck.apple.IosBinary;
import com.facebook.buck.apple.IosBinaryDescription;
import com.facebook.buck.apple.IosLibrary;
import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.IosResourceDescription;
import com.facebook.buck.apple.IosTest;
import com.facebook.buck.apple.IosTestDescription;
import com.facebook.buck.apple.IosTestType;
import com.facebook.buck.apple.MacosxBinary;
import com.facebook.buck.apple.MacosxBinaryDescription;
import com.facebook.buck.apple.MacosxFramework;
import com.facebook.buck.apple.MacosxFrameworkDescription;
import com.facebook.buck.apple.OsxResourceDescription;
import com.facebook.buck.apple.XcodeRuleConfiguration;
import com.facebook.buck.apple.xcode.xcconfig.XcconfigStack;
import com.facebook.buck.apple.xcode.xcodeproj.PBXAggregateTarget;
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
import com.facebook.buck.apple.xcode.xcodeproj.XCConfigurationList;
import com.facebook.buck.codegen.SourceSigner;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.AbstractBuildable;
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
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
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
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.common.util.concurrent.UncheckedExecutionException;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
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

  private static final ImmutableSet<String> HEADER_FILE_EXTENSIONS =
    ImmutableSet.of("h", "hh", "hpp");

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

  private final PartialGraph partialGraph;
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

  /**
   * Populated while generating project configurations, in order to collect the possible
   * project-level configurations to set when operation with
   * {@link Option#REFERENCE_EXISTING_XCCONFIGS}.
   */
  private final ImmutableMultimap.Builder<String, ConfigInXcodeLayout>
    xcodeConfigurationLayersMultimapBuilder;

  private ImmutableMap<String, String> targetNameToGIDMap;

  public ProjectGenerator(
      PartialGraph partialGraph,
      Set<BuildTarget> initialTargets,
      ProjectFilesystem projectFilesystem,
      ExecutionContext executionContext,
      Path outputDirectory,
      String projectName,
      Set<Option> options) {
    this.partialGraph = Preconditions.checkNotNull(partialGraph);
    this.initialTargets = ImmutableSet.copyOf(initialTargets);
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.executionContext = Preconditions.checkNotNull(executionContext);
    this.outputDirectory = Preconditions.checkNotNull(outputDirectory);
    this.projectName = Preconditions.checkNotNull(projectName);
    this.options = ImmutableSet.copyOf(options);

    this.projectPath = outputDirectory.resolve(projectName + ".xcodeproj");
    this.repoRootRelativeToOutputDirectory =
        this.outputDirectory.normalize().toAbsolutePath().relativize(
            projectFilesystem.getRootPath().toAbsolutePath());

    this.placedAssetCatalogBuildPhaseScript = this.projectFilesystem.getPathForRelativePath(
        BuckConstant.BIN_PATH.resolve("xcode-scripts/compile_asset_catalogs_build_phase.sh"));

    this.project = new PBXProject(projectName);

    this.buildRuleToGeneratedTargetBuilder = ImmutableMap.builder();
    this.buildRuleToXcodeTarget = CacheBuilder.newBuilder().build(
        new CacheLoader<BuildRule, Optional<PBXTarget>>() {
          @Override
          public Optional<PBXTarget> load(BuildRule key) throws Exception {
            return generateTargetForBuildRule(key);
          }
        });

    xcodeConfigurationLayersMultimapBuilder = ImmutableMultimap.builder();
  }

  @VisibleForTesting
  PBXProject getGeneratedProject() {
    return project;
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
    try {
      targetNameToGIDMap = buildTargetNameToGIDMap();
      Iterable<BuildRule> allRules = RuleDependencyFinder.getAllRules(partialGraph, initialTargets);

      for (BuildRule rule : allRules) {
        if (isBuiltByCurrentProject(rule)) {
          // Trigger the loading cache to call the generateTargetForBuildRule function.
          Optional<PBXTarget> target = buildRuleToXcodeTarget.getUnchecked(rule);
          if (target.isPresent()) {
            buildRuleToGeneratedTargetBuilder.put(rule, target.get());
          }
        }
      }

      if (options.contains(Option.REFERENCE_EXISTING_XCCONFIGS)) {
        setProjectLevelConfigs(
            project,
            repoRootRelativeToOutputDirectory,
            collectProjectLevelConfigsIfIdenticalOrFail(
                xcodeConfigurationLayersMultimapBuilder.build()));
      }

      addGeneratedSignedSourceTarget(project);
      writeProjectFile(project);

      if (options.contains(Option.GENERATE_WORKSPACE)) {
        writeWorkspace(projectPath);
      }

      if (shouldPlaceAssetCatalogCompiler) {
        Path placedAssetCatalogCompilerPath = projectFilesystem.getPathForRelativePath(
            BuckConstant.BIN_PATH.resolve(
                "xcode-scripts/compile_asset_catalogs.py"));
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
    Preconditions.checkNotNull(targetNameToGIDMap);
    Optional<PBXTarget> result;
    if (rule.getType().equals(IosLibraryDescription.TYPE)) {
      result = Optional.of((PBXTarget) generateIosLibraryTarget(
          project, rule, (IosLibrary) rule.getBuildable()));
    } else if (rule.getType().equals(IosTestDescription.TYPE)) {
      result = Optional.of((PBXTarget) generateIosTestTarget(
          project, rule, (IosTest) rule.getBuildable()));
    } else if (rule.getType().equals(IosBinaryDescription.TYPE)) {
      result = Optional.of((PBXTarget) generateIOSBinaryTarget(
              project, rule, (IosBinary) rule.getBuildable()));
    } else if (rule.getType().equals(MacosxFrameworkDescription.TYPE)) {
      result = Optional.of((PBXTarget) generateMacosxFrameworkTarget(
              project, rule, (MacosxFramework) rule.getBuildable()));
    } else if (rule.getType().equals(MacosxBinaryDescription.TYPE)) {
    result = Optional.of((PBXTarget) generateMacosxBinaryTarget(
            project, rule, (MacosxBinary) rule.getBuildable()));
    } else {
      result = Optional.absent();
    }

    if (result.isPresent()) {
      setTargetGIDIfNameInMap(result.get(), targetNameToGIDMap);
    }

    return result;
  }

  private PBXNativeTarget generateIosLibraryTarget(
      PBXProject project,
      BuildRule rule,
      IosLibrary buildable)
      throws IOException {
    PBXNativeTarget target = new PBXNativeTarget(getXcodeTargetName(rule));
    target.setProductType(PBXTarget.ProductType.IOS_LIBRARY);

    PBXGroup targetGroup = project.getMainGroup().getOrCreateChildGroupByName(target.getName());

    // -- configurations
    setTargetBuildConfigurations(
        rule.getBuildTarget(), target, targetGroup,
        buildable.getConfigurations(), ImmutableMap.<String, String>of());

    // -- build phases
    // TODO(Task #3772930): Go through all dependencies of the rule
    // and add any shell script rules here
    addRunScriptBuildPhasesForDependencies(rule, target);
    addSourcesAndHeadersBuildPhases(
        target,
        targetGroup,
        buildable.getSrcs(),
        buildable.getPerFileFlags());

    // -- products
    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    String libraryName = "lib" + getProductName(rule.getBuildTarget()) + ".a";
    PBXFileReference productReference = new PBXFileReference(
        libraryName, libraryName, PBXReference.SourceTree.BUILT_PRODUCTS_DIR);
    productsGroup.getChildren().add(productReference);
    target.setProductReference(productReference);

    project.getTargets().add(target);
    return target;
  }

  private PBXNativeTarget generateIosTestTarget(
      PBXProject project, BuildRule rule, IosTest buildable) throws IOException {
    PBXNativeTarget target = new PBXNativeTarget(getXcodeTargetName(rule));
    target.setProductType(testTypeToTargetProductType(buildable.getTestType()));

    PBXGroup targetGroup = project.getMainGroup().getOrCreateChildGroupByName(target.getName());

    // -- configurations
    Path infoPlistPath = repoRootRelativeToOutputDirectory.resolve(buildable.getInfoPlist());
    setTargetBuildConfigurations(
        rule.getBuildTarget(),
        target,
        targetGroup,
        buildable.getConfigurations(),
        ImmutableMap.of(
            "INFOPLIST_FILE", infoPlistPath.toString()));

    // -- phases
    // TODO(Task #3772930): Go through all dependencies of the rule
    // and add any shell script rules here
    addRunScriptBuildPhasesForDependencies(rule, target);
    addSourcesAndHeadersBuildPhases(
        target,
        targetGroup,
        buildable.getSrcs(),
        buildable.getPerFileFlags());
    ImmutableSet.Builder<String> frameworksBuilder = ImmutableSet.builder();
    frameworksBuilder.addAll(buildable.getFrameworks());
    collectRecursiveFrameworkDependencies(rule, frameworksBuilder);
    addFrameworksBuildPhase(
        rule.getBuildTarget(),
        target,
        project.getMainGroup().getOrCreateChildGroupByName("Frameworks"),
        frameworksBuilder.build(),
        collectRecursiveLibraryDependencies(rule));
    addResourcesBuildPhase(
        target,
        targetGroup,
        collectRecursiveResources(rule, IosResourceDescription.TYPE));
    addAssetCatalogBuildPhase(
        target,
        targetGroup,
        collectRecursiveAssetCatalogs(rule));

    // -- products
    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    String productName = getProductName(rule.getBuildTarget());
    String productOutputName = Joiner.on(".").join(
        productName,
        buildable.getTestType().toFileExtension());
    PBXFileReference productReference = new PBXFileReference(
        productOutputName, productOutputName, PBXReference.SourceTree.BUILT_PRODUCTS_DIR);
    productsGroup.getChildren().add(productReference);
    target.setProductName(productName);
    target.setProductReference(productReference);

    project.getTargets().add(target);
    return target;
  }

  private PBXNativeTarget generateIOSBinaryTarget(
      PBXProject project, BuildRule rule, IosBinary buildable)
      throws IOException {
    PBXNativeTarget target =
        generateBinaryTarget(project, rule, buildable, PBXTarget.ProductType.IOS_BINARY,
            IosResourceDescription.TYPE);

    project.getTargets().add(target);
    return target;
  }

  private PBXNativeTarget generateMacosxFrameworkTarget(
      PBXProject project,
      BuildRule rule,
      MacosxFramework buildable)
      throws IOException {
    PBXNativeTarget target = new PBXNativeTarget(getXcodeTargetName(rule));
    target.setProductType(PBXTarget.ProductType.MACOSX_FRAMEWORK);

    PBXGroup targetGroup = project.getMainGroup().getOrCreateChildGroupByName(target.getName());

    // -- configurations
    setTargetBuildConfigurations(
        rule.getBuildTarget(), target, targetGroup,
        buildable.getConfigurations(), ImmutableMap.<String, String>of());

    // -- build phases
    // TODO(Task #3772930): Go through all dependencies of the rule
    // and add any shell script rules here
    addRunScriptBuildPhasesForDependencies(rule, target);
    addSourcesAndHeadersBuildPhases(
        target,
        targetGroup,
        buildable.getSrcs(),
        buildable.getPerFileFlags());

    // MacOSX frameworks actually link with libraries and other frameworks.
    ImmutableSet.Builder<String> frameworksBuilder = ImmutableSet.builder();
    frameworksBuilder.addAll(buildable.getFrameworks());
    collectRecursiveFrameworkDependencies(rule, frameworksBuilder);
    addFrameworksBuildPhase(
        rule.getBuildTarget(),
        target,
        project.getMainGroup().getOrCreateChildGroupByName("Frameworks"),
        frameworksBuilder.build(),
        collectRecursiveLibraryDependencies(rule));
    addResourcesBuildPhase(
        target,
        targetGroup,
        collectRecursiveResources(rule, OsxResourceDescription.TYPE));
    addAssetCatalogBuildPhase(target, targetGroup, collectRecursiveAssetCatalogs(rule));

    // -- products
    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    String frameworkName = getProductName(rule.getBuildTarget()) + ".framework";
    PBXFileReference productReference = new PBXFileReference(
        frameworkName, frameworkName, PBXReference.SourceTree.BUILT_PRODUCTS_DIR);
    productsGroup.getChildren().add(productReference);
    target.setProductReference(productReference);

    project.getTargets().add(target);
    return target;
  }

  private <BuildableBinary extends AbstractBuildable & AppleBuildable> PBXNativeTarget
      generateBinaryTarget(
          PBXProject project,
          BuildRule rule,
          BuildableBinary buildable,
          PBXTarget.ProductType productType,
          BuildRuleType resourceRuleType)
          throws IOException {
    PBXNativeTarget target = new PBXNativeTarget(getXcodeTargetName(rule));
    target.setProductType(productType);

    PBXGroup targetGroup = project.getMainGroup().getOrCreateChildGroupByName(target.getName());

    // -- configurations
    Path infoPlistPath = repoRootRelativeToOutputDirectory.resolve(buildable.getInfoPlist());
    setTargetBuildConfigurations(
        rule.getBuildTarget(),
        target,
        targetGroup,
        buildable.getConfigurations(),
        ImmutableMap.of(
            "INFOPLIST_FILE", infoPlistPath.toString()));

    // -- phases
    // TODO(Task #3772930): Go through all dependencies of the rule
    // and add any shell script rules here
    addRunScriptBuildPhasesForDependencies(rule, target);
    addSourcesAndHeadersBuildPhases(
        target,
        targetGroup,
        buildable.getSrcs(),
        buildable.getPerFileFlags());
    ImmutableSet.Builder<String> frameworksBuilder = ImmutableSet.builder();
    frameworksBuilder.addAll(buildable.getFrameworks());
    collectRecursiveFrameworkDependencies(rule, frameworksBuilder);
    addFrameworksBuildPhase(
        rule.getBuildTarget(),
        target,
        project.getMainGroup().getOrCreateChildGroupByName("Frameworks"),
        frameworksBuilder.build(),
        collectRecursiveLibraryDependencies(rule));
    addResourcesBuildPhase(target, targetGroup, collectRecursiveResources(rule,
            resourceRuleType));
    addAssetCatalogBuildPhase(target, targetGroup, collectRecursiveAssetCatalogs(rule));

    // -- products
    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    String productName = getProductName(rule.getBuildTarget());
    String productOutputName = productName + ".app";
    PBXFileReference productReference = new PBXFileReference(
        productOutputName, productOutputName, PBXReference.SourceTree.BUILT_PRODUCTS_DIR);
    productsGroup.getChildren().add(productReference);
    target.setProductName(productName);
    target.setProductReference(productReference);

    return target;
  }

  private PBXNativeTarget generateMacosxBinaryTarget(
      PBXProject project, BuildRule rule, MacosxBinary buildable)
      throws IOException {
    PBXNativeTarget target =
        generateBinaryTarget(project, rule, buildable, PBXTarget.ProductType.MACOSX_BINARY,
            OsxResourceDescription.TYPE);

    // Unlike an ios target, macosx targets collect their frameworks and copy them in.
    ImmutableSet.Builder<String> frameworksBuilder = ImmutableSet.builder();
    frameworksBuilder.addAll(buildable.getFrameworks());
    collectRecursiveFrameworkDependencies(rule, frameworksBuilder);

    addCopyFrameworksBuildPhase(
        rule.getBuildTarget(),
        target,
        project.getMainGroup().getOrCreateChildGroupByName("Frameworks"),
        frameworksBuilder.build());

    project.getTargets().add(target);
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
      BuildTarget buildTarget,
      PBXTarget target,
      PBXGroup targetGroup,
      ImmutableSet<XcodeRuleConfiguration> configurations,
      ImmutableMap<String, String> extraBuildSettings)
      throws IOException {

    ImmutableMap.Builder<String, String> extraConfigsBuilder = ImmutableMap.builder();

    extraConfigsBuilder
        .putAll(extraBuildSettings)
        .put("TARGET_NAME", getProductName(buildTarget))
        .put("SRCROOT", relativizeBuckRelativePathToGeneratedProject(buildTarget, "").toString());

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
      extraConfigsBuilder.put("GCC_PREFIX_HEADER", "$(SRCROOT)/$(inherited)");
    }

    ImmutableMap<String, String> extraConfigs = extraConfigsBuilder.build();

    PBXGroup configurationsGroup = targetGroup.getOrCreateChildGroupByName("Configurations");

    for (XcodeRuleConfiguration configuration : configurations) {
      if (options.contains(Option.REFERENCE_EXISTING_XCCONFIGS)) {
        ConfigInXcodeLayout layers = extractXcodeConfigurationLayers(buildTarget, configuration);
        xcodeConfigurationLayersMultimapBuilder.put(configuration.getName(), layers);

        XCBuildConfiguration outputConfiguration =
            target.getBuildConfigurationList().getBuildConfigurationsByName()
                .getUnchecked(configuration.getName());
        if (layers.targetLevelConfigFile.isPresent()) {
          PBXFileReference fileReference =
              configurationsGroup.getOrCreateFileReferenceBySourceTreePath(
                  new SourceTreePath(
                      PBXReference.SourceTree.SOURCE_ROOT,
                      repoRootRelativeToOutputDirectory.resolve(
                          layers.targetLevelConfigFile.get()).normalize()));
          outputConfiguration.setBaseConfigurationReference(fileReference);

          NSDictionary inlineSettings = new NSDictionary();
          Iterable<Map.Entry<String, String>> entries = Iterables.concat(
              layers.targetLevelInlineSettings.entrySet(),
              extraConfigs.entrySet());
          for (Map.Entry<String, String> entry : entries) {
            inlineSettings.put(entry.getKey(), entry.getValue());
          }
          outputConfiguration.setBuildSettings(inlineSettings);
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
            .getUnchecked(configuration.getName());

        // Write an xcconfig that embodies all the config levels, and set that as the target config.
        Path configurationFilePath = outputConfigurationDirectory.resolve(
            mangledBuildTargetName(buildTarget) + "-" + configuration.getName() + ".xcconfig");
        String serializedConfiguration = serializeBuildConfiguration(
            configuration, searchPaths, extraConfigs);
        projectFilesystem.writeContentsToPath(serializedConfiguration, configurationFilePath);

        PBXFileReference fileReference =
            configurationsGroup.getOrCreateFileReferenceBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SOURCE_ROOT,
                    repoRootRelativeToOutputDirectory.resolve(configurationFilePath)));
        XCBuildConfiguration outputConfiguration =
            target.getBuildConfigurationList().getBuildConfigurationsByName()
                .getUnchecked(configuration.getName());
        outputConfiguration.setBaseConfigurationReference(fileReference);
      }
    }
  }

  private void addRunScriptBuildPhase(PBXNativeTarget target, Genrule rule) {
    // TODO(user): Check and validate dependencies of the script. If it depends on libraries etc.
    // we can't handle it currently.
    PBXShellScriptBuildPhase shellScriptBuildPhase = new PBXShellScriptBuildPhase();
    target.getBuildPhases().add(shellScriptBuildPhase);
    for (Path path : rule.getSrcs()) {
      shellScriptBuildPhase.getInputPaths().add(
          repoRootRelativeToOutputDirectory.resolve(path).toString());
    }

    StringBuilder bashCommandBuilder = new StringBuilder();
    ShellStep genruleStep = rule.createGenruleStep();
    for (String commandElement : genruleStep.getShellCommand(executionContext)) {
      if (bashCommandBuilder.length() > 0) {
        bashCommandBuilder.append(' ');
      }
      bashCommandBuilder.append(Escaper.escapeAsBashString(commandElement));
    }
    shellScriptBuildPhase.setShellScript(bashCommandBuilder.toString());
  }

  private void addRunScriptBuildPhasesForDependencies(BuildRule rule, PBXNativeTarget target) {
    for (BuildRule dependency : rule.getDeps()) {
      if (dependency.getType().equals(GenruleDescription.TYPE)) {
        addRunScriptBuildPhase(target, (Genrule) dependency.getBuildable());
      }
    }
  }

  /**
   * Add sources and headers build phases to a target, and add references to the target's group.
   *
   * @param target      Target to add the build phases to.
   * @param targetGroup Group to link the source files to.
   * @param groupedSources Grouped sources and headers to include in the build
   *        phase, path relative to project root.
   * @param sourceFlags    Source path to flag mapping.
   */
  private void addSourcesAndHeadersBuildPhases(
      PBXNativeTarget target,
      PBXGroup targetGroup,
      Iterable<GroupedSource> groupedSources,
      ImmutableMap<SourcePath, String> sourceFlags) {
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");
    // Sources groups stay in the order in which they're declared in the BUCK file.
    sourcesGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
    PBXSourcesBuildPhase sourcesBuildPhase = new PBXSourcesBuildPhase();
    PBXHeadersBuildPhase headersBuildPhase = new PBXHeadersBuildPhase();

    addGroupedSourcesToBuildPhases(
        sourcesGroup,
        sourcesBuildPhase,
        Optional.of(headersBuildPhase),
        groupedSources,
        sourceFlags);

    if (!sourcesBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(sourcesBuildPhase);
    }
    if (!headersBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(headersBuildPhase);
    }
  }

  private static boolean isHeaderSourcePath(SourcePath sourcePath) {
    return HEADER_FILE_EXTENSIONS.contains(Files.getFileExtension(sourcePath.toString()));
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
          if (isHeaderSourcePath(groupedSource.getSourcePath())) {
            if (headersBuildPhase.isPresent()) {
              addSourcePathToHeadersBuildPhase(
                  groupedSource.getSourcePath(),
                  sourcesGroup,
                  headersBuildPhase.get(),
                  sourceFlags);
            }
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
  }

  private void addSourcePathToHeadersBuildPhase(
      SourcePath headerPath,
      PBXGroup headersGroup,
      PBXHeadersBuildPhase headersBuildPhase,
      ImmutableMap<SourcePath, String> sourceFlags) {
    Path path = headerPath.resolve();
    PBXFileReference fileReference = headersGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            repoRootRelativeToOutputDirectory.resolve(path)));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    NSDictionary settings = new NSDictionary();
    String headerFlags = sourceFlags.get(headerPath);
    if (headerFlags != null) {
      // If we specify nothing, Xcode will use "project" visibility.
      settings.put(
          "ATTRIBUTES",
          new NSArray(new NSString(HeaderVisibility.fromString(headerFlags).toXcodeAttribute())));
      buildFile.setSettings(Optional.of(settings));
    } else {
      buildFile.setSettings(Optional.<NSDictionary>absent());
    }
    headersBuildPhase.getFiles().add(buildFile);
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

        Path pathRelativeToProjectRoot = outputDirectory.relativize(dir);
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
          outputDirectory.relativize(placedAssetCatalogBuildPhaseScript);
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
    target.getBuildPhases().add(phase);
  }

  private void addFrameworksBuildPhase(
      BuildTarget buildTarget,
      PBXNativeTarget target,
      PBXGroup sharedFrameworksGroup,
      Iterable<String> frameworks,
      Iterable<PBXFileReference> archives) {
    PBXFrameworksBuildPhase frameworksBuildPhase = new PBXFrameworksBuildPhase();
    target.getBuildPhases().add(frameworksBuildPhase);
    for (String framework : frameworks) {
      Path path = Paths.get(framework);

      String firstElement =
        Preconditions.checkNotNull(Iterables.getFirst(path, Paths.get(""))).toString();

      if (firstElement.startsWith("$")) { // NOPMD - length() > 0 && charAt(0) == '$' is ridiculous
        Optional<PBXReference.SourceTree> sourceTree =
            PBXReference.SourceTree.fromBuildSetting(firstElement);
        if (sourceTree.isPresent()) {
          Path sdkRootRelativePath = path.subpath(1, path.getNameCount());
          PBXFileReference fileReference =
              sharedFrameworksGroup.getOrCreateFileReferenceBySourceTreePath(
                  new SourceTreePath(sourceTree.get(), sdkRootRelativePath));
          frameworksBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
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
        frameworksBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
      }
    }

    for (PBXFileReference archive : archives) {
      frameworksBuildPhase.getFiles().add(new PBXBuildFile(archive));
    }
  }

  private void addCopyFrameworksBuildPhase(
      BuildTarget buildTarget,
      PBXNativeTarget target,
      PBXGroup sharedFrameworksGroup,
      Iterable<String> frameworks) {
    PBXCopyFilesBuildPhase copyFrameworksBuildPhase =
        new PBXCopyFilesBuildPhase(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS, "");
    target.getBuildPhases().add(copyFrameworksBuildPhase);

    for (String framework : frameworks) {
      Path path = Paths.get(framework);

      String firstElement =
        Preconditions.checkNotNull(Iterables.getFirst(path, Paths.get(""))).toString();

      if (firstElement.charAt(0) == '$') {
        Optional<PBXReference.SourceTree> sourceTree =
            PBXReference.SourceTree.fromBuildSetting(firstElement);
        if (sourceTree.isPresent() &&
            (sourceTree.get() == PBXReference.SourceTree.BUILT_PRODUCTS_DIR ||
            sourceTree.get() == PBXReference.SourceTree.ABSOLUTE)) {
          Path sdkRootRelativePath = path.subpath(1, path.getNameCount());
          PBXFileReference fileReference =
              sharedFrameworksGroup.getOrCreateFileReferenceBySourceTreePath(
                  new SourceTreePath(sourceTree.get(), sdkRootRelativePath));
          copyFrameworksBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
        }
      } else {
        // regular path
        PBXFileReference fileReference =
            sharedFrameworksGroup.getOrCreateFileReferenceBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.GROUP,
                    relativizeBuckRelativePathToGeneratedProject(buildTarget, path.toString())));
        copyFrameworksBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
      }
    }
  }

  private void addGeneratedSignedSourceTarget(PBXProject project) {
    PBXAggregateTarget target = new PBXAggregateTarget("GeneratedSignedSourceTarget");
    // If we don't do this, Xcode "helpfully" generates a new configuration list
    // with a new GID every time.
    XCConfigurationList buildConfigurationList = new XCConfigurationList();
    for (String projectConfigurationName :
           project.getBuildConfigurationList().getBuildConfigurationsByName().asMap().keySet()) {
      buildConfigurationList.getBuildConfigurationsByName().getUnchecked(projectConfigurationName);
    }
    target.setBuildConfigurationList(buildConfigurationList);
    setTargetGIDIfNameInMap(target, targetNameToGIDMap);
    PBXShellScriptBuildPhase generatedSignedSourceScriptPhase = new PBXShellScriptBuildPhase();
    generatedSignedSourceScriptPhase.setShellScript(
        "# Do not change or remove this. This is a generated script phase\n" +
            "# used solely to include a signature in the generated Xcode project.\n" +
            "# " + SourceSigner.SIGNED_SOURCE_PLACEHOLDER);
    target.getBuildPhases().add(generatedSignedSourceScriptPhase);
    project.getTargets().add(target);
  }

  /**
   * Create the project bundle structure and write {@code project.pbxproj}.
   */
  private Path writeProjectFile(PBXProject project) throws IOException {
    Preconditions.checkNotNull(targetNameToGIDMap);
    XcodeprojSerializer serializer = new XcodeprojSerializer(
        new GidGenerator(ImmutableSet.copyOf(targetNameToGIDMap.values())), project);
    NSDictionary rootObject = serializer.toPlist();
    Path xcodeprojDir = outputDirectory.resolve(projectName + ".xcodeproj");
    projectFilesystem.mkdirs(xcodeprojDir);
    Path serializedProject = xcodeprojDir.resolve("project.pbxproj");
    String unsignedXmlProject = rootObject.toXMLPropertyList();
    Optional<String> signedXmlProject = SourceSigner.sign(unsignedXmlProject);
    String contentsToWrite;
    if (signedXmlProject.isPresent()) {
      contentsToWrite = signedXmlProject.get();
    } else {
      contentsToWrite = unsignedXmlProject;
    }
    projectFilesystem.writeContentsToPath(contentsToWrite, serializedProject);
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
      projectFilesystem.writeContentsToPath(outputStream.toString(), serializedWorkspace);
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
          builder.addSettingsFromFile(projectFilesystem, searchPaths, layer.getPath().get());
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
    return buildTarget.getShortName();
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
    return repoRootRelativeToOutputDirectory.resolve(originalProjectPath).resolve(path);
  }

  private void collectRecursiveFrameworkDependencies(
      BuildRule rule,
      ImmutableSet.Builder<String> frameworksBuilder) {
    for (BuildRule ruleDependency :
           getRecursiveRuleDependenciesOfType(rule, IosLibraryDescription.TYPE)) {
      IosLibrary iosLibrary =
          (IosLibrary) Preconditions.checkNotNull(ruleDependency.getBuildable());
      frameworksBuilder.addAll(iosLibrary.getFrameworks());
    }
  }

  private ImmutableSet<PBXFileReference> collectRecursiveLibraryDependencies(BuildRule rule) {
    return FluentIterable
        .from(getRecursiveRuleDependenciesOfType(
            rule,
            IosLibraryDescription.TYPE))
        .transform(
            new Function<BuildRule, PBXFileReference>() {
              @Override
              public PBXFileReference apply(BuildRule input) {
                return getLibraryFileReferenceForRule(input);
              }
            }).toSet();
  }

  private PBXFileReference getLibraryFileReferenceForRule(BuildRule rule) {
    if (rule.getType().equals(IosLibraryDescription.TYPE)) {
      if (isBuiltByCurrentProject(rule)) {
        PBXNativeTarget target = (PBXNativeTarget) buildRuleToXcodeTarget.getUnchecked(rule).get();
        return target.getProductReference();
      } else {
        return project.getMainGroup()
            .getOrCreateChildGroupByName("Frameworks")
            .getOrCreateFileReferenceBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
                    Paths.get(getLibraryNameFromTargetName(rule.getBuildTarget().getShortName()))));
      }
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

  private static String getLibraryNameFromTargetName(String string) {
    return "lib" + string + ".a";
  }

  private String getXcodeTargetName(BuildRule rule) {
    return options.contains(Option.USE_SHORT_NAMES_FOR_TARGETS)
        ? rule.getBuildTarget().getShortName()
        : rule.getBuildTarget().getFullyQualifiedName();
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
    Iterable<BuildRule> resourceRules = getRecursiveRuleDependenciesOfType(rule, resourceRuleType);
    ImmutableSet.Builder<AppleResource> resources = ImmutableSet.builder();
    for (BuildRule resourceRule : resourceRules) {
      AppleResource resource =
          (AppleResource) Preconditions.checkNotNull(resourceRule.getBuildable());
      resources.add(resource);
    }
    return resources.build();
  }

  /**
   * Collect asset catalogs from recursive dependencies.
   */
  private Iterable<AppleAssetCatalog> collectRecursiveAssetCatalogs(BuildRule rule) {
    Iterable<BuildRule> assetCatalogRules = getRecursiveRuleDependenciesOfType(
        rule,
        AppleAssetCatalogDescription.TYPE);
    ImmutableSet.Builder<AppleAssetCatalog> assetCatalogs = ImmutableSet.builder();
    for (BuildRule assetCatalogRule : assetCatalogRules) {
      AppleAssetCatalog assetCatalog = (AppleAssetCatalog) Preconditions.checkNotNull(
          assetCatalogRule.getBuildable());
      assetCatalogs.add(assetCatalog);
    }
    return assetCatalogs.build();
  }

  private Iterable<BuildRule> getRecursiveRuleDependenciesOfType(
      final BuildRule rule, BuildRuleType... types) {
    final ImmutableSet<BuildRuleType> requestedTypes = ImmutableSet.copyOf(types);
    final ImmutableList.Builder<BuildRule> filteredRules = ImmutableList.builder();
    AbstractAcyclicDepthFirstPostOrderTraversal<BuildRule> traversal =
        new AbstractAcyclicDepthFirstPostOrderTraversal<BuildRule>() {
          @Override
          protected Iterator<BuildRule> findChildren(BuildRule node) throws IOException {
            return node.getDeps().iterator();
          }

          @Override
          protected void onNodeExplored(BuildRule node) {
            if (node != rule && requestedTypes.contains(node.getType())) {
              filteredRules.add(node);
            }
          }

          @Override
          protected void onTraversalComplete(Iterable<BuildRule> nodesInExplorationOrder) {
          }
        };
    try {
      traversal.traverse(ImmutableList.of(rule));
    } catch (AbstractAcyclicDepthFirstPostOrderTraversal.CycleException | IOException e) {
      // actual load failures and cycle exceptions should have been caught at an earlier stage
      throw new RuntimeException(e);
    }
    return filteredRules.build();
  }

  /**
   * Once we've generated the target, check if there's already a GID for a
   * target with the same name in an existing on-disk Xcode project.
   *
   * If there is, then re-use that target's GID instead of generating
   * a new one based on the target's name.
   */
  private static void setTargetGIDIfNameInMap(
      PBXTarget target,
      ImmutableMap<String, String> targetNameToGIDMap) {
    @Nullable String existingTargetGID = targetNameToGIDMap.get(target.getName());
    if (existingTargetGID == null) {
      return;
    }

    if (target.getGlobalID() == null) {
      target.setGlobalID(existingTargetGID);
    } else {
      // We better not have already generated some other GID for this
      // target.
      Preconditions.checkState(target.getGlobalID().equals(existingTargetGID));
    }
  }

  /**
   * Reads in an existing Xcode project at
   * "projectPath/project.pbxproj" and returns a map of {target-name:
   * GID} pairs.
   *
   * If no such project exists, returns an empty map.
   */
  @SuppressWarnings("PMD.EmptyCatchBlock")
  private ImmutableMap<String, String> buildTargetNameToGIDMap() throws IOException {
    ImmutableMap.Builder<String, String> targetNameToGIDMapBuilder = ImmutableMap.builder();
    try {
      InputStream projectInputStream =
        projectFilesystem.newFileInputStream(projectPath.resolve(Paths.get("project.pbxproj")));
      NSDictionary projectObjects = ProjectParser.extractObjectsFromXcodeProject(
          projectInputStream);
      ProjectParser.extractTargetNameToGIDMap(
          projectObjects,
          targetNameToGIDMapBuilder);
    } catch (NoSuchFileException e) {
      // We'll leave the builder empty in this case and return an empty map.
    }
    return targetNameToGIDMapBuilder.build();
  }

  private static PBXTarget.ProductType testTypeToTargetProductType(IosTestType testType) {
      switch (testType) {
        case OCTEST:
          return PBXTarget.ProductType.IOS_TEST_OCTEST;
        case XCTEST:
          return PBXTarget.ProductType.IOS_TEST_XCTEST;
        default:
          throw new IllegalStateException("Invalid test type value: " + testType.toString());
      }
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
                      config.projectLevelConfigFile.get()).normalize()));
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
              layers.get(0).getPath(),
              ImmutableMap.<String, String>of(),
              layers.get(1).getPath(),
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
              layers.get(0).getPath(),
              layers.get(1).getInlineSettings().or(ImmutableMap.<String, String>of()),
              layers.get(2).getPath(),
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

  private static class ConfigInXcodeLayout {
    /** Tracks the originating build target for error reporting. */
    public final BuildTarget buildTarget;

    public final Optional<Path> projectLevelConfigFile;
    public final ImmutableMap<String, String> projectLevelInlineSettings;
    public final Optional<Path> targetLevelConfigFile;
    public final ImmutableMap<String, String> targetLevelInlineSettings;

    private ConfigInXcodeLayout(
        BuildTarget buildTarget,
        Optional<Path> projectLevelConfigFile,
        ImmutableMap<String, String> projectLevelInlineSettings,
        Optional<Path> targetLevelConfigFile,
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
