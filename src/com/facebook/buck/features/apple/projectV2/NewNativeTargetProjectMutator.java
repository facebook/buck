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

package com.facebook.buck.features.apple.projectV2;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.AppleAssetCatalogDescriptionArg;
import com.facebook.buck.apple.AppleBundleDestination;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleHeaderVisibilities;
import com.facebook.buck.apple.AppleResourceDescriptionArg;
import com.facebook.buck.apple.AppleWrapperResourceArg;
import com.facebook.buck.apple.GroupedSource;
import com.facebook.buck.apple.RuleUtils;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXHeadersBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXSourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Configures a PBXProject by adding a PBXNativeTarget and its associated dependencies into a
 * PBXProject object graph.
 */
class NewNativeTargetProjectMutator {
  private static final Logger LOG = Logger.get(NewNativeTargetProjectMutator.class);

  public static class Result {
    public final PBXNativeTarget target;
    public final Optional<PBXGroup> targetGroup;

    private Result(PBXNativeTarget target, Optional<PBXGroup> targetGroup) {
      this.target = target;
      this.targetGroup = targetGroup;
    }
  }

  private final BuildTarget target;
  private final Path shell;
  private final Path buildScriptPath;
  private final PathRelativizer pathRelativizer;
  private final Function<SourcePath, Path> sourcePathResolver;

  private ProductType productType = ProductTypes.BUNDLE;
  private Path productOutputPath = Paths.get("");
  private String productName = "";
  private String targetName = "";
  private boolean frameworkHeadersEnabled = false;
  private ImmutableMap<CxxSource.Type, ImmutableList<String>> langPreprocessorFlags =
      ImmutableMap.of();
  private ImmutableSet<SourceWithFlags> sourcesWithFlags = ImmutableSet.of();
  private ImmutableSet<SourcePath> extraXcodeSources = ImmutableSet.of();
  private ImmutableSet<SourcePath> extraXcodeFiles = ImmutableSet.of();
  private ImmutableSet<SourcePath> publicHeaders = ImmutableSet.of();
  private ImmutableSet<SourcePath> privateHeaders = ImmutableSet.of();
  private Optional<SourcePath> prefixHeader = Optional.empty();
  private Optional<SourcePath> infoPlist = Optional.empty();
  private Optional<SourcePath> bridgingHeader = Optional.empty();
  private Optional<Path> buckFilePath = Optional.empty();
  private ImmutableSet<AppleResourceDescriptionArg> recursiveResources = ImmutableSet.of();
  private ImmutableSet<AppleResourceDescriptionArg> directResources = ImmutableSet.of();
  private ImmutableSet<AppleAssetCatalogDescriptionArg> recursiveAssetCatalogs = ImmutableSet.of();
  private ImmutableSet<AppleAssetCatalogDescriptionArg> directAssetCatalogs = ImmutableSet.of();
  private ImmutableSet<AppleWrapperResourceArg> wrapperResources = ImmutableSet.of();

  public NewNativeTargetProjectMutator(
      PathRelativizer pathRelativizer,
      Function<SourcePath, Path> sourcePathResolver,
      BuildTarget target,
      AppleConfig appleConfig) {
    this.target = target;
    this.shell = appleConfig.shellPath();
    this.buildScriptPath = appleConfig.buildScriptPath();

    this.pathRelativizer = pathRelativizer;
    this.sourcePathResolver = sourcePathResolver;
  }

  /**
   * Set product related configuration.
   *
   * @param productType declared product type
   * @param productName product display name
   * @param productOutputPath build output relative product path.
   */
  public NewNativeTargetProjectMutator setProduct(
      ProductType productType, String productName, Path productOutputPath) {
    this.productName = productName;
    this.productType = productType;
    this.productOutputPath = productOutputPath;
    return this;
  }

  public NewNativeTargetProjectMutator setTargetName(String targetName) {
    this.targetName = targetName;
    return this;
  }

  public NewNativeTargetProjectMutator setFrameworkHeadersEnabled(boolean enabled) {
    this.frameworkHeadersEnabled = enabled;
    return this;
  }

  public NewNativeTargetProjectMutator setLangPreprocessorFlags(
      ImmutableMap<CxxSource.Type, ImmutableList<String>> langPreprocessorFlags) {
    this.langPreprocessorFlags = langPreprocessorFlags;
    return this;
  }

  public NewNativeTargetProjectMutator setSourcesWithFlags(Set<SourceWithFlags> sourcesWithFlags) {
    this.sourcesWithFlags = ImmutableSet.copyOf(sourcesWithFlags);
    return this;
  }

  public NewNativeTargetProjectMutator setExtraXcodeSources(Set<SourcePath> extraXcodeSources) {
    this.extraXcodeSources = ImmutableSet.copyOf(extraXcodeSources);
    return this;
  }

  public NewNativeTargetProjectMutator setExtraXcodeFiles(Set<SourcePath> extraXcodeFiles) {
    this.extraXcodeFiles = ImmutableSet.copyOf(extraXcodeFiles);
    return this;
  }

  public NewNativeTargetProjectMutator setPublicHeaders(Set<SourcePath> publicHeaders) {
    this.publicHeaders = ImmutableSet.copyOf(publicHeaders);
    return this;
  }

  public NewNativeTargetProjectMutator setPrivateHeaders(Set<SourcePath> privateHeaders) {
    this.privateHeaders = ImmutableSet.copyOf(privateHeaders);
    return this;
  }

  public NewNativeTargetProjectMutator setPrefixHeader(Optional<SourcePath> prefixHeader) {
    this.prefixHeader = prefixHeader;
    return this;
  }

  public NewNativeTargetProjectMutator setInfoPlist(Optional<SourcePath> infoPlist) {
    this.infoPlist = infoPlist;
    return this;
  }

  public NewNativeTargetProjectMutator setBridgingHeader(Optional<SourcePath> bridgingHeader) {
    this.bridgingHeader = bridgingHeader;
    return this;
  }

  public NewNativeTargetProjectMutator setRecursiveResources(
      Set<AppleResourceDescriptionArg> recursiveResources) {
    this.recursiveResources = ImmutableSet.copyOf(recursiveResources);
    return this;
  }

  public NewNativeTargetProjectMutator setDirectResources(
      ImmutableSet<AppleResourceDescriptionArg> directResources) {
    this.directResources = directResources;
    return this;
  }

  public NewNativeTargetProjectMutator setWrapperResources(
      ImmutableSet<AppleWrapperResourceArg> wrapperResources) {
    this.wrapperResources = wrapperResources;
    return this;
  }

  public NewNativeTargetProjectMutator setBuckFilePath(Optional<Path> buckFilePath) {
    this.buckFilePath = buckFilePath;
    return this;
  }

  /**
   * @param recursiveAssetCatalogs List of asset catalog targets of targetNode and dependencies of
   *     targetNode.
   */
  public NewNativeTargetProjectMutator setRecursiveAssetCatalogs(
      Set<AppleAssetCatalogDescriptionArg> recursiveAssetCatalogs) {
    this.recursiveAssetCatalogs = ImmutableSet.copyOf(recursiveAssetCatalogs);
    return this;
  }

  /** @param directAssetCatalogs List of asset catalog targets targetNode directly depends on */
  public NewNativeTargetProjectMutator setDirectAssetCatalogs(
      Set<AppleAssetCatalogDescriptionArg> directAssetCatalogs) {
    this.directAssetCatalogs = ImmutableSet.copyOf(directAssetCatalogs);
    return this;
  }

  public Result buildTargetAndAddToProject(PBXProject project, boolean addBuildPhases) {
    PBXNativeTarget nativeTarget = new PBXNativeTarget(targetName);

    Optional<PBXGroup> targetGroup;
    if (addBuildPhases) {
      // Phases
      addPhasesAndGroupsForSources(project, nativeTarget);
      addResourcesFileReference(project);
      addCopyResourcesToNonStdDestinations(project);
      addResourcesBuildPhase(project);

      // Using a buck build phase doesnt support having other build phases.
      // TODO(chatatap): Find solutions around this, as this will likely break indexing.
      if (ImmutableList.of(
              ProductTypes.APPLICATION,
              ProductTypes.UNIT_TEST,
              ProductTypes.UI_TEST,
              ProductTypes.APP_EXTENSION,
              ProductTypes.WATCH_APPLICATION)
          .contains(productType)) {
        nativeTarget.getBuildPhases().clear();
      }
      addBuckBuildPhase(nativeTarget);

      targetGroup = Optional.of(project.getMainGroup());
    } else {
      targetGroup = Optional.empty();
    }

    // Product

    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    PBXFileReference productReference =
        productsGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.BUILT_PRODUCTS_DIR, productOutputPath, Optional.empty()));
    nativeTarget.setProductName(productName);
    nativeTarget.setProductReference(productReference);
    nativeTarget.setProductType(productType);

    project.getTargets().add(nativeTarget);
    return new Result(nativeTarget, targetGroup);
  }

  /// Helper class for returning metadata about a created PBXFileReference
  /// Includes a reference to the PBXFileReference and the Source Tree Path
  /// We probably won't need this long term as we kill off Xcode build phases
  /// but for now, let's just use this since it's named and structured
  private static class SourcePathPBXFileReferenceDestination {
    private final PBXFileReference fileReference;
    private final SourceTreePath sourceTreePath;

    public SourcePathPBXFileReferenceDestination(
        PBXFileReference fileReference, SourceTreePath sourceTreePath) {
      this.fileReference = fileReference;
      this.sourceTreePath = sourceTreePath;
    }

    public PBXFileReference getFileReference() {
      return fileReference;
    }

    public SourceTreePath getSourceTreePath() {
      return sourceTreePath;
    }
  }

  /// Writes a source path to a PBXFileReference in the input project. This will
  /// create the relative directory structure based on the path relativizer (cell root).
  ///
  /// Thus, if a file is absolutely located at: /Users/me/dev/MyProject/Header.h
  /// And the cell is: /Users/me/dev/
  /// Then this will create a path in the PBXProject mainGroup as: /MyProject/Header.h
  private SourcePathPBXFileReferenceDestination writeSourcePathToProject(
      PBXProject project, SourcePath sourcePath) {
    Path path = sourcePathResolver.apply(sourcePath);
    SourceTreePath sourceTreePath =
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToSourcePath(sourcePath),
            Optional.empty());
    return writeSourceTreePathAtFullPathToProject(project, path, sourceTreePath);
  }

  /// Writes a file at a path to a PBXFileReference in the input project.
  private SourcePathPBXFileReferenceDestination writeFilePathToProject(
      PBXProject project, Path path, Optional<String> type) {
    SourceTreePath sourceTreePath =
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputDirToRootRelative(path),
            type);

    return writeSourceTreePathAtFullPathToProject(project, path, sourceTreePath);
  }

  private SourcePathPBXFileReferenceDestination writeSourceTreePathAtFullPathToProject(
      PBXProject project, Path path, SourceTreePath sourceTreePath) {
    PBXGroup filePathGroup;
    // This check exists for files located in the root (e.g. ./foo.m instead of ./MyLibrary/foo.m)
    if (path.getParent() != null) {
      ImmutableList<String> filePathComponentList =
          RichStream.from(path.getParent()).map(Object::toString).toImmutableList();
      filePathGroup =
          project.getMainGroup().getOrCreateDescendantGroupByPath(filePathComponentList);
    } else {
      filePathGroup = project.getMainGroup();
    }

    PBXFileReference fileReference =
        filePathGroup.getOrCreateFileReferenceBySourceTreePath(sourceTreePath);

    return new SourcePathPBXFileReferenceDestination(fileReference, sourceTreePath);
  }

  private void addPhasesAndGroupsForSources(PBXProject project, PBXNativeTarget target) {
    PBXSourcesBuildPhase sourcesBuildPhase = new PBXSourcesBuildPhase();
    PBXHeadersBuildPhase headersBuildPhase = new PBXHeadersBuildPhase();

    traverseGroupsTreeAndHandleSources(
        project,
        sourcesBuildPhase,
        headersBuildPhase,
        RuleUtils.createGroupsFromSourcePaths(
            pathRelativizer::outputPathToSourcePath,
            sourcesWithFlags,
            extraXcodeSources,
            extraXcodeFiles,
            publicHeaders,
            privateHeaders));

    if (prefixHeader.isPresent()) {
      writeSourcePathToProject(project, prefixHeader.get());
    }

    if (infoPlist.isPresent()) {
      writeSourcePathToProject(project, infoPlist.get());
    }

    if (bridgingHeader.isPresent()) {
      writeSourcePathToProject(project, bridgingHeader.get());
    }

    if (buckFilePath.isPresent()) {
      PBXFileReference buckFileReference =
          writeFilePathToProject(project, buckFilePath.get(), Optional.empty()).getFileReference();
      buckFileReference.setExplicitFileType(Optional.of("text.script.python"));
    }

    // Source files must be compiled in order to be indexed.
    if (!sourcesBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(sourcesBuildPhase);
    }

    // TODO(chatatap): Verify if we still need header build phases (specifically for swift).
    if (!headersBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(headersBuildPhase);
    }
  }

  private void traverseGroupsTreeAndHandleSources(
      PBXProject project,
      PBXSourcesBuildPhase sourcesBuildPhase,
      PBXHeadersBuildPhase headersBuildPhase,
      Iterable<GroupedSource> groupedSources) {
    GroupedSource.Visitor visitor =
        new GroupedSource.Visitor() {
          @Override
          public void visitSourceWithFlags(SourceWithFlags sourceWithFlags) {
            SourcePathPBXFileReferenceDestination fileReference =
                writeSourcePathToProject(project, sourceWithFlags.getSourcePath());
            addFileReferenceToSourcesBuildPhase(fileReference, sourceWithFlags, sourcesBuildPhase);
          }

          @Override
          public void visitIgnoredSource(SourcePath source) {
            writeSourcePathToProject(project, source);
          }

          @Override
          public void visitPublicHeader(SourcePath publicHeader) {
            PBXFileReference fileReference =
                writeSourcePathToProject(project, publicHeader).getFileReference();
            addFileReferenceToHeadersBuildPhase(
                fileReference, headersBuildPhase, HeaderVisibility.PUBLIC);
          }

          @Override
          public void visitPrivateHeader(SourcePath privateHeader) {
            PBXFileReference fileReference =
                writeSourcePathToProject(project, privateHeader).getFileReference();
            addFileReferenceToHeadersBuildPhase(
                fileReference, headersBuildPhase, HeaderVisibility.PRIVATE);
          }

          @Override
          public void visitSourceGroup(
              String sourceGroupName,
              Path sourceGroupPathRelativeToTarget,
              List<GroupedSource> sourceGroup) {
            traverseGroupsTreeAndHandleSources(
                project, sourcesBuildPhase, headersBuildPhase, sourceGroup);
          }
        };
    for (GroupedSource groupedSource : groupedSources) {
      groupedSource.visit(visitor);
    }
  }

  private void addFileReferenceToSourcesBuildPhase(
      SourcePathPBXFileReferenceDestination sourcePathPBXfileReferenceDestination,
      SourceWithFlags sourceWithFlags,
      PBXSourcesBuildPhase sourcesBuildPhase) {
    PBXFileReference fileReference = sourcePathPBXfileReferenceDestination.getFileReference();
    SourceTreePath sourceTreePath = sourcePathPBXfileReferenceDestination.getSourceTreePath();
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    sourcesBuildPhase.getFiles().add(buildFile);

    ImmutableList<String> customLangPreprocessorFlags = ImmutableList.of();
    Optional<CxxSource.Type> sourceType =
        CxxSource.Type.fromExtension(Files.getFileExtension(sourceTreePath.toString()));
    if (sourceType.isPresent() && langPreprocessorFlags.containsKey(sourceType.get())) {
      customLangPreprocessorFlags = langPreprocessorFlags.get(sourceType.get());
    }

    ImmutableList<String> customFlags =
        ImmutableList.copyOf(
            Iterables.concat(customLangPreprocessorFlags, sourceWithFlags.getFlags()));
    if (!customFlags.isEmpty()) {
      NSDictionary settings = new NSDictionary();
      settings.put("COMPILER_FLAGS", Joiner.on(' ').join(customFlags));
      buildFile.setSettings(Optional.of(settings));
    }
    LOG.verbose(
        "Added source path %s to sources build phase, flags %s, PBXFileReference %s",
        sourceWithFlags, customFlags, fileReference);
  }

  private void addFileReferenceToHeadersBuildPhase(
      PBXFileReference fileReference,
      PBXHeadersBuildPhase headersBuildPhase,
      HeaderVisibility visibility) {
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    if (visibility != HeaderVisibility.PRIVATE) {

      if (this.frameworkHeadersEnabled
          && (this.productType == ProductTypes.FRAMEWORK
              || this.productType == ProductTypes.STATIC_FRAMEWORK)) {
        headersBuildPhase.getFiles().add(buildFile);
      }

      NSDictionary settings = new NSDictionary();
      settings.put(
          "ATTRIBUTES",
          new NSArray(new NSString(AppleHeaderVisibilities.toXcodeAttribute(visibility))));
      buildFile.setSettings(Optional.of(settings));
    } else {
      buildFile.setSettings(Optional.empty());
    }
  }

  private void addResourcesFileReference(PBXProject project) {
    ImmutableSet.Builder<Path> resourceFiles = ImmutableSet.builder();
    ImmutableSet.Builder<Path> resourceDirs = ImmutableSet.builder();
    ImmutableSet.Builder<Path> variantResourceFiles = ImmutableSet.builder();

    collectResourcePathsFromConstructorArgs(
        directResources,
        directAssetCatalogs,
        ImmutableSet.of(),
        resourceFiles,
        resourceDirs,
        variantResourceFiles);

    addResourcesFileReference(
        project, resourceFiles.build(), resourceDirs.build(), variantResourceFiles.build());
  }

  private void addCopyResourcesToNonStdDestinations(PBXProject project) {
    List<AppleBundleDestination> allNonStandardDestinations =
        recursiveResources.stream()
            .map(AppleResourceDescriptionArg::getDestination)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .distinct()
            .filter(e -> e != AppleBundleDestination.RESOURCES)
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.toList());
    for (AppleBundleDestination destination : allNonStandardDestinations) {
      addCopyResourcesToNonStdDestination(project, destination);
    }
  }

  private void addCopyResourcesToNonStdDestination(
      PBXProject project, AppleBundleDestination destination) {
    Set<AppleResourceDescriptionArg> resourceDescriptionArgsForDestination =
        recursiveResources.stream()
            .filter(
                e ->
                    e.getDestination().orElse(AppleBundleDestination.defaultValue()) == destination)
            .collect(Collectors.toSet());
    addResourcePathsToBuildPhase(
        project, resourceDescriptionArgsForDestination, new HashSet<>(), new HashSet<>());
  }

  private void addResourcesBuildPhase(PBXProject project) {
    Set<AppleResourceDescriptionArg> standardDestinationResources =
        recursiveResources.stream()
            .filter(
                e ->
                    e.getDestination().orElse(AppleBundleDestination.defaultValue())
                        == AppleBundleDestination.RESOURCES)
            .collect(Collectors.toSet());
    addResourcePathsToBuildPhase(
        project, standardDestinationResources, recursiveAssetCatalogs, wrapperResources);
  }

  private void addResourcePathsToBuildPhase(
      PBXProject project,
      Set<AppleResourceDescriptionArg> resourceArgs,
      Set<AppleAssetCatalogDescriptionArg> assetCatalogArgs,
      Set<AppleWrapperResourceArg> resourcePathArgs) {
    ImmutableSet.Builder<Path> resourceFiles = ImmutableSet.builder();
    ImmutableSet.Builder<Path> resourceDirs = ImmutableSet.builder();
    ImmutableSet.Builder<Path> variantResourceFiles = ImmutableSet.builder();

    collectResourcePathsFromConstructorArgs(
        resourceArgs,
        assetCatalogArgs,
        resourcePathArgs,
        resourceFiles,
        resourceDirs,
        variantResourceFiles);

    addResourcesFileReference(
        project, resourceFiles.build(), resourceDirs.build(), variantResourceFiles.build());
  }

  private void collectResourcePathsFromConstructorArgs(
      Set<AppleResourceDescriptionArg> resourceArgs,
      Set<AppleAssetCatalogDescriptionArg> assetCatalogArgs,
      Set<AppleWrapperResourceArg> resourcePathArgs,
      ImmutableSet.Builder<Path> resourceFilesBuilder,
      ImmutableSet.Builder<Path> resourceDirsBuilder,
      ImmutableSet.Builder<Path> variantResourceFilesBuilder) {
    for (AppleResourceDescriptionArg arg : resourceArgs) {
      arg.getFiles().stream().map(sourcePathResolver).forEach(resourceFilesBuilder::add);
      arg.getDirs().stream().map(sourcePathResolver).forEach(resourceDirsBuilder::add);
      arg.getVariants().stream().map(sourcePathResolver).forEach(variantResourceFilesBuilder::add);
    }

    for (AppleAssetCatalogDescriptionArg arg : assetCatalogArgs) {
      arg.getDirs().stream().map(sourcePathResolver).forEach(resourceDirsBuilder::add);
    }

    for (AppleWrapperResourceArg arg : resourcePathArgs) {
      resourceDirsBuilder.add(arg.getPath());
    }
  }

  private void addResourcesFileReference(
      PBXProject project,
      ImmutableSet<Path> resourceFiles,
      ImmutableSet<Path> resourceDirs,
      ImmutableSet<Path> variantResourceFiles) {
    if (resourceFiles.isEmpty() && resourceDirs.isEmpty() && variantResourceFiles.isEmpty()) {
      return;
    }

    for (Path path : resourceFiles) {
      writeFilePathToProject(project, path, Optional.empty()).getFileReference();
    }

    for (Path path : resourceDirs) {
      writeFilePathToProject(project, path, Optional.of("folder")).getFileReference();
    }

    for (Path variantFilePath : variantResourceFiles) {
      String lprojSuffix = ".lproj";
      Path variantDirectory = variantFilePath.getParent();
      if (variantDirectory == null || !variantDirectory.toString().endsWith(lprojSuffix)) {
        throw new HumanReadableException(
            "Variant files have to be in a directory with name ending in '.lproj', "
                + "but '%s' is not.",
            variantFilePath);
      }
      writeFilePathToProject(project, variantFilePath, Optional.empty());
    }
  }

  /**
   * Add a `buck build TARGET` phase to the nativeTarget.
   *
   * @param nativeTarget PBXNativeTarget to which to add the buck build phase.
   */
  private void addBuckBuildPhase(PBXNativeTarget nativeTarget) {
    PBXShellScriptBuildPhase buckBuildPhase = new PBXShellScriptBuildPhase();
    buckBuildPhase.setName(Optional.of("Buck Build"));
    buckBuildPhase.setShellPath(shell.toString());

    // Form relative paths to the cell root and build script
    Path targetPath = target.getCellPath().resolve(target.getBasePath());
    Path targetRelativeCellRoot = targetPath.relativize(target.getCellPath());
    Path cellRootRelativeBuildScript = target.getCellPath().relativize(buildScriptPath);

    String shellCommand =
        String.format(
            "cd $SOURCE_ROOT/%s && ./%s", targetRelativeCellRoot, cellRootRelativeBuildScript);
    buckBuildPhase.setShellScript(shellCommand);

    nativeTarget.getBuildPhases().add(buckBuildPhase);
  }
}
