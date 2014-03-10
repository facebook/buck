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
import com.facebook.buck.apple.XcodeNative;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.XcodeRuleConfiguration;
import com.facebook.buck.apple.xcode.xcconfig.XcconfigStack;
import com.facebook.buck.apple.xcode.xcodeproj.PBXAggregateTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXContainerItemProxy;
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
import com.facebook.buck.apple.xcode.xcodeproj.PBXTargetDependency;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.codegen.SourceSigner;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
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
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.UncheckedExecutionException;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

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
     * Generate a build scheme
     */
    GENERATE_SCHEME,

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
  public static final Option[] COMBINED_PROJECT_OPTIONS = new Option[] {
      Option.GENERATE_SCHEME,
      Option.GENERATE_TARGETS_FOR_DEPENDENCIES,
      Option.GENERATE_WORKSPACE,
  };

  public static final Option[] SEPARATED_PROJECT_OPTIONS = new Option[] {
      Option.REFERENCE_EXISTING_XCCONFIGS,
      Option.USE_SHORT_NAMES_FOR_TARGETS,
  };

  private final PartialGraph partialGraph;
  private final ProjectFilesystem projectFilesystem;
  private final ExecutionContext executionContext;
  private final Path outputDirectory;
  private final String projectName;
  private final ImmutableSet<BuildTarget> initialTargets;
  private final Path projectPath;
  private final Path repoRootRelativeToOutputDirectory;

  private final ImmutableSet<Option> options;

  // These fields are created/filled when creating the projects.
  private final PBXProject project;
  private final LoadingCache<BuildRule, Optional<PBXTarget>> buildRuleToXcodeTarget;
  private XCScheme scheme = null;
  private Document workspace = null;

  /**
   * Populated while generating project configurations, in order to collect the possible
   * project-level configurations to set when operation with
   * {@link Option#REFERENCE_EXISTING_XCCONFIGS}.
   */
  private final ImmutableMultimap.Builder<String, ConfigInXcodeLayout>
    xcodeConfigurationLayersMultimapBuilder;


  public ProjectGenerator(
      PartialGraph partialGraph,
      ImmutableSet<BuildTarget> initialTargets,
      ProjectFilesystem projectFilesystem,
      ExecutionContext executionContext,
      Path outputDirectory,
      String projectName,
      Option... options) {
    this.partialGraph = partialGraph;
    this.initialTargets = initialTargets;
    this.projectFilesystem = projectFilesystem;
    this.executionContext = executionContext;
    this.outputDirectory = outputDirectory;
    this.projectName = projectName;
    this.options = Sets.immutableEnumSet(Arrays.asList(options));

    this.projectPath = outputDirectory.resolve(projectName + ".xcodeproj");
    this.repoRootRelativeToOutputDirectory =
        this.outputDirectory.normalize().toAbsolutePath().relativize(
            projectFilesystem.getRootPath().toAbsolutePath());
    this.project = new PBXProject(projectName);

    this.buildRuleToXcodeTarget = CacheBuilder.newBuilder().build(
        new CacheLoader<BuildRule, Optional<PBXTarget>>() {
          @Override
          public Optional<PBXTarget> load(BuildRule key) throws Exception {
            return generateTargetForBuildRule(key);
          }
        });

    xcodeConfigurationLayersMultimapBuilder = ImmutableMultimap.builder();
  }

  @Nullable
  @VisibleForTesting
  XCScheme getGeneratedScheme() {
    return scheme;
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

  public void createXcodeProjects() throws IOException {
    try {
      Iterable<BuildRule> allRules = RuleDependencyFinder.getAllRules(partialGraph, initialTargets);
      ImmutableMap.Builder<BuildRule, PBXTarget> ruleToTargetMapBuilder = ImmutableMap.builder();
      for (BuildRule rule : allRules) {
        if (isBuiltByCurrentProject(rule)) {
          // Trigger the loading cache to call the generateTargetForBuildRule function.
          Optional<PBXTarget> target = buildRuleToXcodeTarget.getUnchecked(rule);
          if (target.isPresent()) {
            ruleToTargetMapBuilder.put(rule, target.get());
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

      if (options.contains(Option.GENERATE_SCHEME)) {
        scheme = SchemeGenerator.createScheme(
            partialGraph, projectPath, ruleToTargetMapBuilder.build());
        SchemeGenerator.writeScheme(projectFilesystem, scheme, projectPath);
      }
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
    if (rule.getType().equals(IosLibraryDescription.TYPE)) {
      return Optional.of((PBXTarget) generateIosLibraryTarget(
          project, rule, (IosLibrary) rule.getBuildable()));
    } else if (rule.getType().equals(XcodeNativeDescription.TYPE)) {
      return Optional.of((PBXTarget) generateXcodeNativeTarget(
          project, rule, (XcodeNative) rule.getBuildable()));
    } else if (rule.getType().equals(IosTestDescription.TYPE)) {
      return Optional.of((PBXTarget) generateIosTestTarget(
          project, rule, (IosTest) rule.getBuildable()));
    } else if (rule.getType().equals(IosBinaryDescription.TYPE)) {
      return Optional.of((PBXTarget) generateIOSBinaryTarget(
          project, rule, (IosBinary) rule.getBuildable()));
    } else {
      return Optional.absent();
    }
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
    addSourcesBuildPhase(
        target,
        targetGroup,
        buildable.getGroupedSrcs(),
        buildable.getPerFileCompilerFlags());
    addHeadersBuildPhase(
        target,
        targetGroup,
        buildable.getGroupedHeaders(),
        buildable.getPerHeaderVisibility());

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
    target.setProductType(PBXTarget.ProductType.IOS_TEST);

    PBXGroup targetGroup = project.getMainGroup().getOrCreateChildGroupByName(target.getName());

    // -- configurations
    Path infoPlistPath = this.repoRootRelativeToOutputDirectory.resolve(buildable.getInfoPlist());
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
    addSourcesBuildPhase(
        target,
        targetGroup,
        buildable.getGroupedSrcs(),
        buildable.getPerFileCompilerFlags());
    addFrameworksBuildPhase(
        rule.getBuildTarget(),
        target,
        project.getMainGroup().getOrCreateChildGroupByName("Frameworks"),
        buildable.getFrameworks(),
        collectRecursiveLibraryDependencies(rule));
    addResourcesBuildPhase(target, targetGroup, collectRecursiveResources(rule));

    // -- products
    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    String productName = getProductName(rule.getBuildTarget());
    String productOutputName = productName + ".octest";
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
    PBXNativeTarget target = new PBXNativeTarget(getXcodeTargetName(rule));
    target.setProductType(PBXTarget.ProductType.IOS_BINARY);

    PBXGroup targetGroup = project.getMainGroup().getOrCreateChildGroupByName(target.getName());

    // -- configurations
    Path infoPlistPath = this.repoRootRelativeToOutputDirectory.resolve(buildable.getInfoPlist());
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
    addSourcesBuildPhase(
        target,
        targetGroup,
        buildable.getGroupedSrcs(),
        buildable.getPerFileCompilerFlags());
    addFrameworksBuildPhase(
        rule.getBuildTarget(),
        target,
        project.getMainGroup().getOrCreateChildGroupByName("Frameworks"),
        buildable.getFrameworks(),
        collectRecursiveLibraryDependencies(rule));
    addResourcesBuildPhase(target, targetGroup, collectRecursiveResources(rule));

    // -- products
    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    String productName = getProductName(rule.getBuildTarget());
    String productOutputName = productName + ".app";
    PBXFileReference productReference = new PBXFileReference(
        productOutputName, productOutputName, PBXReference.SourceTree.BUILT_PRODUCTS_DIR);
    productsGroup.getChildren().add(productReference);
    target.setProductName(productName);
    target.setProductReference(productReference);

    project.getTargets().add(target);
    return target;
  }

  private PBXAggregateTarget generateXcodeNativeTarget(
      PBXProject project,
      BuildRule rule,
      XcodeNative buildable) {
    Path referencedProjectPath =
        buildable.getProjectContainerPath().resolve(partialGraph.getDependencyGraph());
    PBXFileReference referencedProject = project.getMainGroup()
        .getOrCreateChildGroupByName("Project References")
        .getOrCreateFileReferenceBySourceTreePath(new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            this.outputDirectory.normalize().toAbsolutePath()
                .relativize(referencedProjectPath.toAbsolutePath()))
        );
    PBXContainerItemProxy proxy = new PBXContainerItemProxy(
        referencedProject,
        buildable.getTargetGid(),
        PBXContainerItemProxy.ProxyType.TARGET_REFERENCE);
    PBXAggregateTarget target = new PBXAggregateTarget(getXcodeTargetName(rule));
    target.getDependencies().add(new PBXTargetDependency(proxy));
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

    ImmutableMap<String, String> extraConfigs = ImmutableMap.<String, String>builder()
        .putAll(extraBuildSettings)
        .put("TARGET_NAME", getProductName(buildTarget))
        .put("SRCROOT", relativizeBuckRelativePathToGeneratedProject(buildTarget, "").toString())
        .put("GCC_PREFIX_HEADER", "$(SRCROOT)/$(inherited)")
        .build();

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
                      this.repoRootRelativeToOutputDirectory.resolve(
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
                    this.repoRootRelativeToOutputDirectory.resolve(configurationFilePath)
                ));
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
      shellScriptBuildPhase.getInputPaths().add(path.toString());
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
      if (dependency.getType().equals(BuildRuleType.GENRULE)) {
        addRunScriptBuildPhase(target, (Genrule) dependency);
      }
    }
  }

  /**
   * Add a sources build phase to a target, and add references to the target's group.
   *
   * @param target      Target to add the build phase to.
   * @param targetGroup Group to link the source files to.
   * @param groupedSources Grouped sources to include in the build
   *        phase, path relative to project root.
   * @param sourceFlags    Source to compiler flag mapping.
   */
  private void addSourcesBuildPhase(
      PBXNativeTarget target,
      PBXGroup targetGroup,
      Iterable<GroupedSource> groupedSources,
      ImmutableMap<SourcePath, String> sourceFlags) {
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");
    // Sources groups stay in the order in which they're declared in the BUCK file.
    sourcesGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
    PBXSourcesBuildPhase sourcesBuildPhase = new PBXSourcesBuildPhase();
    target.getBuildPhases().add(sourcesBuildPhase);

    addGroupedSourcesToBuildPhase(
        sourcesGroup,
        sourcesBuildPhase,
        groupedSources,
        sourceFlags);
  }

  private void addGroupedSourcesToBuildPhase(
      PBXGroup sourcesGroup,
      PBXSourcesBuildPhase sourcesBuildPhase,
      Iterable<GroupedSource> groupedSources,
      ImmutableMap<SourcePath, String> sourceFlags) {
    for (GroupedSource groupedSource : groupedSources) {
      switch (groupedSource.getType()) {
        case SOURCE_PATH:
          addSourcePathToBuildPhase(
              groupedSource.getSourcePath(),
              sourcesGroup,
              sourcesBuildPhase,
              sourceFlags);
          break;
        case SOURCE_GROUP:
          PBXGroup newSourceGroup = sourcesGroup.getOrCreateChildGroupByName(
              groupedSource.getSourceGroupName());
          // Sources groups stay in the order in which they're declared in the BUCK file.
          newSourceGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
          addGroupedSourcesToBuildPhase(
              newSourceGroup,
              sourcesBuildPhase,
              groupedSource.getSourceGroup(),
              sourceFlags);
          break;
        default:
          throw new RuntimeException("Unhandled grouped source type: " + groupedSource.getType());
      }
    }
  }

  private void addSourcePathToBuildPhase(
      SourcePath sourcePath,
      PBXGroup sourcesGroup,
      PBXSourcesBuildPhase sourcesBuildPhase,
      ImmutableMap<SourcePath, String> sourceFlags) {
    Path path = sourcePath.resolve(partialGraph.getDependencyGraph());
    PBXFileReference fileReference = sourcesGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            this.repoRootRelativeToOutputDirectory.resolve(path)
        ));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    sourcesBuildPhase.getFiles().add(buildFile);
    String customFlags = sourceFlags.get(sourcePath);
    if (customFlags != null) {
      NSDictionary settings = new NSDictionary();
      settings.put("COMPILER_FLAGS", customFlags);
      buildFile.setSettings(Optional.of(settings));
    }
  }

  /**
   * Add a header copy phase to a target, and add references of the header to the group.
   */
  private void addHeadersBuildPhase(
      PBXNativeTarget target,
      PBXGroup targetGroup,
      Iterable<GroupedSource> groupedHeaders,
      ImmutableMap<SourcePath, HeaderVisibility> headerVisibilityFlags) {
    PBXGroup headersGroup = targetGroup.getOrCreateChildGroupByName("Headers");
    headersGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
    PBXHeadersBuildPhase headersBuildPhase = new PBXHeadersBuildPhase();
    target.getBuildPhases().add(headersBuildPhase);

    addGroupedHeadersToBuildPhase(
        headersGroup,
        headersBuildPhase,
        groupedHeaders,
        headerVisibilityFlags);
  }

  private void addGroupedHeadersToBuildPhase(
      PBXGroup headersGroup,
      PBXHeadersBuildPhase headersBuildPhase,
      Iterable<GroupedSource> groupedHeaders,
      ImmutableMap<SourcePath, HeaderVisibility> headerVisibilityFlags) {
    for (GroupedSource groupedHeader : groupedHeaders) {
      switch (groupedHeader.getType()) {
        case SOURCE_PATH:
          addHeaderPathToBuildPhase(
              groupedHeader.getSourcePath(),
              headersGroup,
              headersBuildPhase,
              headerVisibilityFlags);
          break;
        case SOURCE_GROUP:
          PBXGroup newHeaderGroup = headersGroup.getOrCreateChildGroupByName(
              groupedHeader.getSourceGroupName());
          // Header groups stay in the order in which they're declared in the BUCK file.
          newHeaderGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
          addGroupedHeadersToBuildPhase(
              newHeaderGroup,
              headersBuildPhase,
              groupedHeader.getSourceGroup(),
              headerVisibilityFlags);
          break;
        default:
          throw new RuntimeException("Unhandled grouped source type: " + groupedHeader.getType());
      }
    }
  }

  private void addHeaderPathToBuildPhase(
      SourcePath headerPath,
      PBXGroup headersGroup,
      PBXHeadersBuildPhase headersBuildPhase,
      ImmutableMap<SourcePath, HeaderVisibility> headerVisibilityFlags) {
    Path path = headerPath.resolve(partialGraph.getDependencyGraph());
    PBXFileReference fileReference = headersGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            this.repoRootRelativeToOutputDirectory.resolve(path)
        ));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    NSDictionary settings = new NSDictionary();
    HeaderVisibility headerVisibility = headerVisibilityFlags.get(headerPath);
    if (headerVisibility != null) {
      // If we specify nothing, Xcode will use "project" visibility.
      settings.put("ATTRIBUTES", new NSArray(new NSString(headerVisibility.toXcodeAttribute())));
      buildFile.setSettings(Optional.of(settings));
    } else {
      buildFile.setSettings(Optional.<NSDictionary>absent());
    }
    headersBuildPhase.getFiles().add(buildFile);
  }

  private void addResourcesBuildPhase(
      PBXNativeTarget target, PBXGroup targetGroup, Iterable<Path> resources) {
    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");
    PBXBuildPhase phase = new PBXResourcesBuildPhase();
    target.getBuildPhases().add(phase);
    for (Path resource : resources) {
      PBXFileReference fileReference = resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
          new SourceTreePath(
              PBXReference.SourceTree.SOURCE_ROOT,
              this.repoRootRelativeToOutputDirectory.resolve(resource)
          ));
      PBXBuildFile buildFile = new PBXBuildFile(fileReference);
      phase.getFiles().add(buildFile);
    }
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
      if (path.startsWith("$SDKROOT")) {
        Path sdkRootRelativePath = path.subpath(1, path.getNameCount());
        PBXFileReference fileReference =
            sharedFrameworksGroup.getOrCreateFileReferenceBySourceTreePath(
                new SourceTreePath(PBXReference.SourceTree.SDKROOT, sdkRootRelativePath));
        frameworksBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
      } else if (!path.toString().startsWith("$")) {
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

  private void addGeneratedSignedSourceTarget(PBXProject project) {
    PBXAggregateTarget target = new PBXAggregateTarget("GeneratedSignedSourceTarget");
    PBXShellScriptBuildPhase generatedSignedSourceScriptPhase = new PBXShellScriptBuildPhase();
    generatedSignedSourceScriptPhase.setShellScript(
        "# Do not change or remove this. This is a generated script phase\n" +
            "# used solely to include a signature in the generated Xcode project.\n" +
            "# " + SourceSigner.SIGNED_SOURCE_PLACEHOLDER
    );
    target.getBuildPhases().add(generatedSignedSourceScriptPhase);
    project.getTargets().add(target);
  }

  /**
   * Create the project bundle structure and write {@code project.pbxproj}.
   */
  private Path writeProjectFile(PBXProject project) throws IOException {
    XcodeprojSerializer serializer = new XcodeprojSerializer(new GidGenerator(0), project);
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
    return this.repoRootRelativeToOutputDirectory.resolve(originalProjectPath).resolve(path);
  }

  private ImmutableSet<PBXFileReference> collectRecursiveLibraryDependencies(BuildRule rule) {
    return FluentIterable
        .from(getRecursiveRuleDependenciesOfType(
            rule,
            IosLibraryDescription.TYPE,
            XcodeNativeDescription.TYPE))
        .transform(
            new Function<BuildRule, PBXFileReference>() {
              @Override
              public PBXFileReference apply(BuildRule input) {
                return getLibraryFileReferenceForRule(input);
              }
            }
        ).toSet();
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
    } else if (rule.getType().equals(XcodeNativeDescription.TYPE)) {
      XcodeNative xcodeNative = (XcodeNative) Preconditions.checkNotNull(rule.getBuildable());
      return project.getMainGroup()
          .getOrCreateChildGroupByName("Frameworks")
          .getOrCreateFileReferenceBySourceTreePath(
              new SourceTreePath(
                  PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
                  Paths.get(xcodeNative.getProduct())));
    } else {
      throw new RuntimeException("Unexpected type: " + rule.getType());
    }
  }

  /**
   * Whether a given build rule is built by the project being generated, or being build elsewhere.
   */
  private boolean isBuiltByCurrentProject(BuildRule rule) {
    return options.contains(Option.GENERATE_TARGETS_FOR_DEPENDENCIES)
        || initialTargets.contains(rule.getBuildTarget());
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
   * @return  Paths to resource files and folders, children of folder are not included.
   */
  private Iterable<Path> collectRecursiveResources(BuildRule rule) {
    Iterable<BuildRule> resourceRules = getRecursiveRuleDependenciesOfType(
        rule, IosResourceDescription.TYPE);
    ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
    for (BuildRule resourceRule : resourceRules) {
      AppleResource resource =
          (AppleResource) Preconditions.checkNotNull(resourceRule.getBuildable());
      paths.addAll(resource.getDirs());
      paths.addAll(SourcePaths.toPaths(resource.getFiles(), partialGraph.getDependencyGraph()));
    }
    return paths.build();
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
