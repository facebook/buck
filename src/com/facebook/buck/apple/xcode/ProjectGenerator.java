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
import com.facebook.buck.apple.IosLibrary;
import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.XcodeNative;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.XcodeRuleConfiguration;
import com.facebook.buck.apple.xcode.xcconfig.XcconfigStack;
import com.facebook.buck.apple.xcode.xcodeproj.PBXAggregateTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXContainerItemProxy;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXHeadersBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXSourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTargetDependency;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.graph.AbstractAcyclicDepthFirstPostOrderTraversal;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.UncheckedExecutionException;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
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
  private final PartialGraph partialGraph;
  private final ProjectFilesystem projectFilesystem;
  private final Path outputDirectory;
  private final String projectName;
  private final ImmutableList<BuildTarget> initialTargets;
  private final Path projectPath;
  private final Path repoRootRelativeToOutputDirectory;

  // These fields are created/filled when creating the projects.
  private final PBXProject project;
  private final LoadingCache<BuildRule, PBXTarget> buildRuleToXcodeTarget;
  private XCScheme scheme = null;
  private Document workspace = null;

  public ProjectGenerator(
      PartialGraph partialGraph,
      ImmutableList<BuildTarget> initialTargets,
      ProjectFilesystem projectFilesystem,
      Path outputDirectory,
      String projectName) {
    this.partialGraph = partialGraph;
    this.initialTargets = initialTargets;
    this.projectFilesystem = projectFilesystem;
    this.outputDirectory = outputDirectory;
    this.projectName = projectName;

    this.projectPath = outputDirectory.resolve(projectName + ".xcodeproj");
    this.repoRootRelativeToOutputDirectory =
        this.outputDirectory.normalize().toAbsolutePath().relativize(
            projectFilesystem.getRootPath().toAbsolutePath());
    this.project = new PBXProject(projectName);
    project.getBuildConfigurationList().getBuildConfigurationsByName().getUnchecked("Debug");
    project.getBuildConfigurationList().getBuildConfigurationsByName().getUnchecked("Release");

    this.buildRuleToXcodeTarget = CacheBuilder.newBuilder().build(
        new CacheLoader<BuildRule, PBXTarget>() {
          @Override
          public PBXTarget load(BuildRule key) throws Exception {
            return generateTargetForBuildRule(key).orNull();
          }
        });
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
      ImmutableList<BuildRule> allRules = getAllRules(partialGraph, initialTargets);
      for (BuildRule rule : allRules) {
        // Trigger the loading cache to call the generateTargetForBuildRule function.
        buildRuleToXcodeTarget.getUnchecked(rule);
      }
      writeProjectFile(project);
      scheme = createScheme(partialGraph, projectPath, buildRuleToXcodeTarget.asMap());
      writeWorkspace(projectPath);
      writeScheme(scheme, projectPath);
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
    if (rule.getType().equals(IosLibraryDescription.TYPE)) {
      return Optional.of((PBXTarget) generateIosLibraryTarget(
          project, rule, (IosLibrary) rule.getBuildable()));
    } else if (rule.getType().equals(XcodeNativeDescription.TYPE)) {
      return Optional.of((PBXTarget) generateXcodeNativeTarget(
          project, rule, (XcodeNative) rule.getBuildable()));
    } else {
      return Optional.absent();
    }
  }

  private static ImmutableList<BuildRule> getAllRules(
      PartialGraph graph,
      Iterable<BuildTarget> initialTargets) {

    ImmutableList.Builder<BuildRule> initialRules = ImmutableList.builder();
    for (BuildTarget target : initialTargets) {
      BuildRule rule = graph.getDependencyGraph().findBuildRuleByTarget(target);
      initialRules.add(rule);
    }

    final ImmutableList.Builder<BuildRule> buildRules = ImmutableList.builder();
    AbstractAcyclicDepthFirstPostOrderTraversal<BuildRule> traversal =
        new AbstractAcyclicDepthFirstPostOrderTraversal<BuildRule>() {
          @Override
          protected Iterator<BuildRule> findChildren(BuildRule node) throws IOException {
            return node.getDeps().iterator();
          }
          @Override
          protected void onNodeExplored(BuildRule node) {
          }
          @Override
          protected void onTraversalComplete(Iterable<BuildRule> nodesInExplorationOrder) {
            buildRules.addAll(nodesInExplorationOrder);
          }
        };
    try {
      traversal.traverse(initialRules.build());
    } catch (AbstractAcyclicDepthFirstPostOrderTraversal.CycleException e) {
      throw new HumanReadableException(e,
          "Cycle detected while gathering build rule dependencies for project generation:\n " +
              e.getMessage());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return buildRules.build();
  }

  private PBXNativeTarget generateIosLibraryTarget(
      PBXProject project,
      BuildRule rule,
      IosLibrary buildable)
      throws IOException {
    PBXNativeTarget target = new PBXNativeTarget(rule.getFullyQualifiedName());
    target.setProductType(PBXTarget.ProductType.IOS_LIBRARY);

    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(rule.getFullyQualifiedName());

    // -- configurations
    setTargetConfigurations(rule.getBuildTarget(), target, targetGroup,
        buildable.getConfigurations(), ImmutableMap.<String, String>of());

    // -- build phases
    addSourcesBuildPhase(
        target, targetGroup, buildable.getSrcs(), buildable.getPerFileCompilerFlags());
    addHeadersBuildPhase(target, targetGroup, buildable.getHeaders());

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

  private PBXAggregateTarget generateXcodeNativeTarget(
      PBXProject project,
      BuildRule rule,
      XcodeNative buildable) {
    Path referencedProjectPath =
        buildable.getProjectContainerPath().resolve(partialGraph.getDependencyGraph()).normalize();
    PBXFileReference referencedProject = project.getMainGroup()
        .getOrCreateChildGroupByName("Project References")
        .getOrCreateFileReferenceBySourceTreePath(SourceTreePath.absolute(referencedProjectPath));
    PBXContainerItemProxy proxy = new PBXContainerItemProxy(
        referencedProject, buildable.getTargetGid(), PBXContainerItemProxy.ProxyType.TARGET_REFERENCE);
    PBXAggregateTarget target = new PBXAggregateTarget(rule.getFullyQualifiedName());
    target.getDependencies().add(new PBXTargetDependency(proxy));
    project.getTargets().add(target);
    return target;
  }

  private void setTargetConfigurations(
      BuildTarget buildTarget,
      PBXTarget target,
      PBXGroup targetGroup,
      ImmutableSet<XcodeRuleConfiguration> configurations,
      ImmutableMap<String, String> extraBuildSettings)
      throws IOException {
    Path outputConfigurationDirectory = outputDirectory.resolve("Configurations");
    projectFilesystem.mkdirs(outputConfigurationDirectory);

    Path originalProjectPath = projectFilesystem.getPathForRelativePath(
        Paths.get(buildTarget.getBasePathWithSlash()));

    ImmutableMap<String, String> extraConfigs = ImmutableMap.<String, String>builder()
        .putAll(extraBuildSettings)
        .put("TARGET_NAME", getProductName(buildTarget))
        .put("SRCROOT", relativizeBuckRelativePathToGeneratedProject(buildTarget, "").toString())
        .put("GCC_PREFIX_HEADER", "$(SRCROOT)/$(inherited)")
        .build();

    PBXGroup configurationsGroup = targetGroup.getOrCreateChildGroupByName("Configurations");
    // XCConfig search path is relative to the xcode project and the file itself.
    ImmutableList<Path> searchPaths = ImmutableList.of(originalProjectPath);
    for (XcodeRuleConfiguration configuration : configurations) {
      Path configurationFilePath = outputConfigurationDirectory.resolve(
          mangledBuildTargetName(buildTarget) + "-" + configuration.getName() + ".xcconfig");
      String serializedConfiguration = serializeBuildConfiguration(
          configuration, searchPaths, extraConfigs);
      projectFilesystem.writeContentsToPath(serializedConfiguration, configurationFilePath);

      PBXFileReference fileReference =
          configurationsGroup.getOrCreateFileReferenceBySourceTreePath(
              SourceTreePath.absolute(configurationFilePath));
      XCBuildConfiguration outputConfiguration =
          target.getBuildConfigurationList().getBuildConfigurationsByName()
              .getUnchecked(configuration.getName());
      outputConfiguration.setBaseConfigurationReference(fileReference);
    }
  }

  /**
   * Add a sources build phase to a target, and add references to the target's group.
   *
   * @param target      Target to add the build phase to.
   * @param targetGroup Group to link the source files to.
   * @param sources        Sources to include in the build phase, path relative to project root.
   * @param sourceFlags    Source to compiler flag mapping.
   */
  private void addSourcesBuildPhase(
      PBXNativeTarget target,
      PBXGroup targetGroup,
      Iterable<SourcePath> sources,
      ImmutableMap<SourcePath, String> sourceFlags) {
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");
    PBXSourcesBuildPhase sourcesBuildPhase = new PBXSourcesBuildPhase();
    target.getBuildPhases().add(sourcesBuildPhase);
    for (SourcePath sourcePath : sources) {
      Path path = sourcePath.resolve(partialGraph.getDependencyGraph());
      PBXFileReference fileReference = sourcesGroup.getOrCreateFileReferenceBySourceTreePath(
          SourceTreePath.absolute(projectFilesystem.getPathForRelativePath(path)));
      PBXBuildFile buildFile = new PBXBuildFile(fileReference);
      sourcesBuildPhase.getFiles().add(buildFile);
      String customFlags = sourceFlags.get(sourcePath);
      if (customFlags != null) {
        NSDictionary settings = new NSDictionary();
        settings.put("COMPILER_FLAGS", customFlags);
        buildFile.setSettings(Optional.of(settings));
      }
    }
  }

  /**
   * Add a header copy phase to a target, and add references of the header to the group.
   */
  private void addHeadersBuildPhase(
      PBXNativeTarget target,
      PBXGroup targetGroup,
      Iterable<SourcePath> headers) {
    PBXGroup headersGroup = targetGroup.getOrCreateChildGroupByName("Headers");
    PBXHeadersBuildPhase headersBuildPhase = new PBXHeadersBuildPhase();
    target.getBuildPhases().add(headersBuildPhase);
    for (SourcePath sourcePath : headers) {
      Path path = sourcePath.resolve(partialGraph.getDependencyGraph());
      PBXFileReference fileReference = headersGroup.getOrCreateFileReferenceBySourceTreePath(
          SourceTreePath.absolute(projectFilesystem.getPathForRelativePath(path)));
      PBXBuildFile buildFile = new PBXBuildFile(fileReference);
      NSDictionary settings = new NSDictionary();
      settings.put("ATTRIBUTES", new NSArray(new NSString("Public")));
      buildFile.setSettings(Optional.of(settings));
      headersBuildPhase.getFiles().add(buildFile);
    }
  }

  private Path writeProjectFile(PBXProject project) throws IOException {
    XcodeprojSerializer serializer = new XcodeprojSerializer(new GidGenerator(0), project);
    NSDictionary rootObject = serializer.toPlist();
    Path xcodeprojDir = outputDirectory.resolve(projectName + ".xcodeproj");
    projectFilesystem.mkdirs(xcodeprojDir);
    Path serializedProject = xcodeprojDir.resolve("project.pbxproj");
    projectFilesystem.writeContentsToPath(rootObject.toXMLPropertyList(), serializedProject);
    return xcodeprojDir;
  }

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

  private static XCScheme createScheme(
      PartialGraph partialGraph,
      Path projectPath,
      final Map<BuildRule, PBXTarget> ruleToTargetMap) throws IOException {

    List<BuildRule> orderedBuildRules = TopologicalSort.sort(
        partialGraph.getDependencyGraph(),
        new Predicate<BuildRule>() {
          @Override
          public boolean apply(@Nullable BuildRule input) {
            return ruleToTargetMap.containsKey(input);
          }
        });

    XCScheme scheme = new XCScheme("Scheme");
    for (BuildRule rule : orderedBuildRules) {
      scheme.addBuildAction(
          projectPath.getFileName().toString(),
          ruleToTargetMap.get(rule).getGlobalID());
    }

    return scheme;
  }

  private void writeScheme(XCScheme scheme, Path projectPath) throws IOException {
    Path schemeDirectory = projectPath.resolve("xcshareddata/xcschemes");
    projectFilesystem.mkdirs(schemeDirectory);
    Path schemePath = schemeDirectory.resolve(scheme.getName() + ".xcscheme");
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      serializeScheme(scheme, outputStream);
      projectFilesystem.writeContentsToPath(outputStream.toString(), schemePath);
    }
  }

  private static void serializeScheme(XCScheme scheme, OutputStream stream) {
    DocumentBuilder docBuilder;
    Transformer transformer;
    try {
      docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      transformer = TransformerFactory.newInstance().newTransformer();
    } catch (ParserConfigurationException | TransformerConfigurationException e) {
      throw new RuntimeException(e);
    }

    DOMImplementation domImplementation = docBuilder.getDOMImplementation();
    Document doc = domImplementation.createDocument(null, "Scheme", null);
    doc.setXmlVersion("1.0");

    Element rootElem = doc.getDocumentElement();
    rootElem.setAttribute("LastUpgradeVersion", "0500");
    rootElem.setAttribute("version", "1.7");

    // serialize the scheme
    Element buildActionElem = doc.createElement("BuildAction");
    rootElem.appendChild(buildActionElem);
    buildActionElem.setAttribute("parallelizeBuildables", "NO");
    buildActionElem.setAttribute("buildImplicitDependencies", "NO");

    Element buildActionEntriesElem = doc.createElement("BuildActionEntries");
    buildActionElem.appendChild(buildActionEntriesElem);

    for (XCScheme.BuildActionEntry entry : scheme.getBuildAction()) {
      Element entryElem = doc.createElement("BuildActionEntry");
      buildActionEntriesElem.appendChild(entryElem);
      entryElem.setAttribute("buildForRunning", "YES");
      entryElem.setAttribute("buildForTesting", "YES");
      entryElem.setAttribute("buildForProfiling", "YES");
      entryElem.setAttribute("buildForArchiving", "YES");
      entryElem.setAttribute("buildForAnalyzing", "YES");
      Element refElem = doc.createElement("BuildableReference");
      entryElem.appendChild(refElem);
      refElem.setAttribute("BuildableIdentifier", "primary");
      refElem.setAttribute("BlueprintIdentifier", entry.getBlueprintIdentifier());
      refElem.setAttribute("referencedContainer", "container:" + entry.getContainerRelativePath());
    }

    // write out

    DOMSource source = new DOMSource(doc);
    StreamResult result = new StreamResult(stream);

    try {
      transformer.transform(source, result);
    } catch (TransformerException e) {
      throw new RuntimeException(e);
    }
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
}
