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

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.AppleAssetCatalogDescriptionArg;
import com.facebook.buck.apple.AppleHeaderVisibilities;
import com.facebook.buck.apple.AppleResourceDescriptionArg;
import com.facebook.buck.apple.AppleWrapperResourceArg;
import com.facebook.buck.apple.GroupedSource;
import com.facebook.buck.apple.RuleUtils;
import com.facebook.buck.apple.XcodePostbuildScriptDescription;
import com.facebook.buck.apple.XcodePrebuildScriptDescription;
import com.facebook.buck.apple.XcodeScriptDescriptionArg;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
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
import com.facebook.buck.apple.xcode.xcodeproj.PBXVariantGroup;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.js.CoreReactNativeLibraryArg;
import com.facebook.buck.js.IosReactNativeLibraryDescription;
import com.facebook.buck.js.ReactNativeBundle;
import com.facebook.buck.js.ReactNativeLibraryArg;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.stringtemplate.v4.ST;

/**
 * Configures a PBXProject by adding a PBXNativeTarget and its associated dependencies into a
 * PBXProject object graph.
 */
class NewNativeTargetProjectMutator {
  private static final Logger LOG = Logger.get(NewNativeTargetProjectMutator.class);
  private static final String REACT_NATIVE_PACKAGE_TEMPLATE = "rn-package.st";

  public static class Result {
    public final PBXNativeTarget target;
    public final Optional<PBXGroup> targetGroup;

    private Result(PBXNativeTarget target, Optional<PBXGroup> targetGroup) {
      this.target = target;
      this.targetGroup = targetGroup;
    }
  }

  private final PathRelativizer pathRelativizer;
  private final Function<SourcePath, Path> sourcePathResolver;

  private ProductType productType = ProductType.BUNDLE;
  private Path productOutputPath = Paths.get("");
  private String productName = "";
  private String targetName = "";
  private boolean frameworkHeadersEnabled = false;
  private ImmutableMap<CxxSource.Type, ImmutableList<String>> langPreprocessorFlags =
      ImmutableMap.of();
  private ImmutableList<String> targetGroupPath = ImmutableList.of();
  private ImmutableSet<SourceWithFlags> sourcesWithFlags = ImmutableSet.of();
  private ImmutableSet<SourcePath> extraXcodeSources = ImmutableSet.of();
  private ImmutableSet<SourcePath> extraXcodeFiles = ImmutableSet.of();
  private ImmutableSet<SourcePath> publicHeaders = ImmutableSet.of();
  private ImmutableSet<SourcePath> privateHeaders = ImmutableSet.of();
  private Optional<SourcePath> prefixHeader = Optional.empty();
  private Optional<SourcePath> infoPlist = Optional.empty();
  private Optional<SourcePath> bridgingHeader = Optional.empty();
  private ImmutableSet<FrameworkPath> frameworks = ImmutableSet.of();
  private ImmutableSet<PBXFileReference> archives = ImmutableSet.of();
  private ImmutableSet<AppleResourceDescriptionArg> recursiveResources = ImmutableSet.of();
  private ImmutableSet<AppleResourceDescriptionArg> directResources = ImmutableSet.of();
  private ImmutableSet<AppleAssetCatalogDescriptionArg> recursiveAssetCatalogs = ImmutableSet.of();
  private ImmutableSet<AppleAssetCatalogDescriptionArg> directAssetCatalogs = ImmutableSet.of();
  private ImmutableSet<AppleWrapperResourceArg> wrapperResources = ImmutableSet.of();
  private Iterable<PBXShellScriptBuildPhase> preBuildRunScriptPhases = ImmutableList.of();
  private Iterable<PBXBuildPhase> copyFilesPhases = ImmutableList.of();
  private Iterable<PBXShellScriptBuildPhase> postBuildRunScriptPhases = ImmutableList.of();

  public NewNativeTargetProjectMutator(
      PathRelativizer pathRelativizer, Function<SourcePath, Path> sourcePathResolver) {
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

  public NewNativeTargetProjectMutator setTargetGroupPath(ImmutableList<String> targetGroupPath) {
    this.targetGroupPath = targetGroupPath;
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

  public NewNativeTargetProjectMutator setFrameworks(Set<FrameworkPath> frameworks) {
    this.frameworks = ImmutableSet.copyOf(frameworks);
    return this;
  }

  public NewNativeTargetProjectMutator setArchives(Set<PBXFileReference> archives) {
    this.archives = ImmutableSet.copyOf(archives);
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

  public NewNativeTargetProjectMutator setPreBuildRunScriptPhasesFromTargetNodes(
      Iterable<TargetNode<?, ?>> nodes) {
    preBuildRunScriptPhases = createScriptsForTargetNodes(nodes);
    return this;
  }

  public NewNativeTargetProjectMutator setPreBuildRunScriptPhases(
      Iterable<PBXShellScriptBuildPhase> phases) {
    preBuildRunScriptPhases = phases;
    return this;
  }

  public NewNativeTargetProjectMutator setCopyFilesPhases(Iterable<PBXBuildPhase> phases) {
    copyFilesPhases = phases;
    return this;
  }

  public NewNativeTargetProjectMutator setPostBuildRunScriptPhasesFromTargetNodes(
      Iterable<TargetNode<?, ?>> nodes) {
    postBuildRunScriptPhases = createScriptsForTargetNodes(nodes);
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
    PBXNativeTarget target = new PBXNativeTarget(targetName);

    Optional<PBXGroup> optTargetGroup;
    if (addBuildPhases) {
      PBXGroup targetGroup =
          project.getMainGroup().getOrCreateDescendantGroupByPath(targetGroupPath);
      targetGroup = targetGroup.getOrCreateChildGroupByName(targetName);

      // Phases
      addRunScriptBuildPhases(target, preBuildRunScriptPhases);
      addPhasesAndGroupsForSources(target, targetGroup);
      addFrameworksBuildPhase(project, target);
      addResourcesFileReference(targetGroup);
      addResourcesBuildPhase(target, targetGroup);
      target.getBuildPhases().addAll((Collection<? extends PBXBuildPhase>) copyFilesPhases);
      addRunScriptBuildPhases(target, postBuildRunScriptPhases);

      optTargetGroup = Optional.of(targetGroup);
    } else {
      optTargetGroup = Optional.empty();
    }

    // Product

    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    PBXFileReference productReference =
        productsGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.BUILT_PRODUCTS_DIR, productOutputPath, Optional.empty()));
    target.setProductName(productName);
    target.setProductReference(productReference);
    target.setProductType(productType);

    project.getTargets().add(target);
    return new Result(target, optTargetGroup);
  }

  private void addPhasesAndGroupsForSources(PBXNativeTarget target, PBXGroup targetGroup) {
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");
    // Sources groups stay in the order in which they're declared in the BUCK file.
    sourcesGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
    PBXSourcesBuildPhase sourcesBuildPhase = new PBXSourcesBuildPhase();
    PBXHeadersBuildPhase headersBuildPhase = new PBXHeadersBuildPhase();

    traverseGroupsTreeAndHandleSources(
        sourcesGroup,
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
      SourceTreePath prefixHeaderSourceTreePath =
          new SourceTreePath(
              PBXReference.SourceTree.GROUP,
              pathRelativizer.outputPathToSourcePath(prefixHeader.get()),
              Optional.empty());
      sourcesGroup.getOrCreateFileReferenceBySourceTreePath(prefixHeaderSourceTreePath);
    }

    if (infoPlist.isPresent()) {
      SourceTreePath infoPlistSourceTreePath =
          new SourceTreePath(
              PBXReference.SourceTree.GROUP,
              pathRelativizer.outputPathToSourcePath(infoPlist.get()),
              Optional.empty());
      sourcesGroup.getOrCreateFileReferenceBySourceTreePath(infoPlistSourceTreePath);
    }

    if (bridgingHeader.isPresent()) {
      SourceTreePath bridgingHeaderSourceTreePath =
          new SourceTreePath(
              PBXReference.SourceTree.GROUP,
              pathRelativizer.outputPathToSourcePath(bridgingHeader.get()),
              Optional.empty());
      sourcesGroup.getOrCreateFileReferenceBySourceTreePath(bridgingHeaderSourceTreePath);
    }

    if (!sourcesBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(sourcesBuildPhase);
    }
    if (!headersBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(headersBuildPhase);
    }
  }

  private void traverseGroupsTreeAndHandleSources(
      final PBXGroup sourcesGroup,
      final PBXSourcesBuildPhase sourcesBuildPhase,
      final PBXHeadersBuildPhase headersBuildPhase,
      Iterable<GroupedSource> groupedSources) {
    GroupedSource.Visitor visitor =
        new GroupedSource.Visitor() {
          @Override
          public void visitSourceWithFlags(SourceWithFlags sourceWithFlags) {
            addSourcePathToSourcesBuildPhase(sourceWithFlags, sourcesGroup, sourcesBuildPhase);
          }

          @Override
          public void visitIgnoredSource(SourcePath source) {
            addSourcePathToSourceTree(source, sourcesGroup);
          }

          @Override
          public void visitPublicHeader(SourcePath publicHeader) {
            addSourcePathToHeadersBuildPhase(
                publicHeader, sourcesGroup, headersBuildPhase, HeaderVisibility.PUBLIC);
          }

          @Override
          public void visitPrivateHeader(SourcePath privateHeader) {
            addSourcePathToHeadersBuildPhase(
                privateHeader, sourcesGroup, headersBuildPhase, HeaderVisibility.PRIVATE);
          }

          @Override
          public void visitSourceGroup(
              String sourceGroupName,
              Path sourceGroupPathRelativeToTarget,
              List<GroupedSource> sourceGroup) {
            PBXGroup newSourceGroup = sourcesGroup.getOrCreateChildGroupByName(sourceGroupName);
            newSourceGroup.setSourceTree(PBXReference.SourceTree.SOURCE_ROOT);
            newSourceGroup.setPath(sourceGroupPathRelativeToTarget.toString());
            // Sources groups stay in the order in which they're in the GroupedSource.
            newSourceGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
            traverseGroupsTreeAndHandleSources(
                newSourceGroup, sourcesBuildPhase, headersBuildPhase, sourceGroup);
          }
        };
    for (GroupedSource groupedSource : groupedSources) {
      groupedSource.visit(visitor);
    }
  }

  private void addSourcePathToSourcesBuildPhase(
      SourceWithFlags sourceWithFlags,
      PBXGroup sourcesGroup,
      PBXSourcesBuildPhase sourcesBuildPhase) {
    SourceTreePath sourceTreePath =
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputDirToRootRelative(
                sourcePathResolver.apply(sourceWithFlags.getSourcePath())),
            Optional.empty());
    PBXFileReference fileReference =
        sourcesGroup.getOrCreateFileReferenceBySourceTreePath(sourceTreePath);
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
        "Added source path %s to group %s, flags %s, PBXFileReference %s",
        sourceWithFlags, sourcesGroup.getName(), customFlags, fileReference);
  }

  private void addSourcePathToSourceTree(SourcePath sourcePath, PBXGroup sourcesGroup) {
    sourcesGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToSourcePath(sourcePath),
            Optional.empty()));
  }

  private void addSourcePathToHeadersBuildPhase(
      SourcePath headerPath,
      PBXGroup headersGroup,
      PBXHeadersBuildPhase headersBuildPhase,
      HeaderVisibility visibility) {
    PBXFileReference fileReference =
        headersGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativizer.outputPathToSourcePath(headerPath),
                Optional.empty()));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    if (visibility != HeaderVisibility.PRIVATE) {

      if (this.frameworkHeadersEnabled
          && (this.productType == ProductType.FRAMEWORK
              || this.productType == ProductType.STATIC_FRAMEWORK)) {
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

  private void addFrameworksBuildPhase(PBXProject project, PBXNativeTarget target) {
    if (frameworks.isEmpty() && archives.isEmpty()) {
      return;
    }

    PBXGroup sharedFrameworksGroup =
        project.getMainGroup().getOrCreateChildGroupByName("Frameworks");
    PBXFrameworksBuildPhase frameworksBuildPhase = new PBXFrameworksBuildPhase();
    target.getBuildPhases().add(frameworksBuildPhase);

    for (FrameworkPath framework : frameworks) {
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
        throw new RuntimeException();
      }
      PBXFileReference fileReference =
          sharedFrameworksGroup.getOrCreateFileReferenceBySourceTreePath(sourceTreePath);
      frameworksBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
    }

    for (PBXFileReference archive : archives) {
      frameworksBuildPhase.getFiles().add(new PBXBuildFile(archive));
    }
  }

  private void addResourcesFileReference(PBXGroup targetGroup) {
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
        targetGroup,
        resourceFiles.build(),
        resourceDirs.build(),
        variantResourceFiles.build(),
        ignored -> {},
        ignored -> {});
  }

  private PBXBuildPhase addResourcesBuildPhase(PBXNativeTarget target, PBXGroup targetGroup) {
    ImmutableSet.Builder<Path> resourceFiles = ImmutableSet.builder();
    ImmutableSet.Builder<Path> resourceDirs = ImmutableSet.builder();
    ImmutableSet.Builder<Path> variantResourceFiles = ImmutableSet.builder();

    collectResourcePathsFromConstructorArgs(
        recursiveResources,
        recursiveAssetCatalogs,
        wrapperResources,
        resourceFiles,
        resourceDirs,
        variantResourceFiles);

    final PBXBuildPhase phase = new PBXResourcesBuildPhase();
    addResourcesFileReference(
        targetGroup,
        resourceFiles.build(),
        resourceDirs.build(),
        variantResourceFiles.build(),
        input -> {
          PBXBuildFile buildFile = new PBXBuildFile(input);
          phase.getFiles().add(buildFile);
        },
        input -> {
          PBXBuildFile buildFile = new PBXBuildFile(input);
          phase.getFiles().add(buildFile);
        });
    if (!phase.getFiles().isEmpty()) {
      target.getBuildPhases().add(phase);
      LOG.debug("Added resources build phase %s", phase);
    }
    return phase;
  }

  private void collectResourcePathsFromConstructorArgs(
      Set<AppleResourceDescriptionArg> resourceArgs,
      Set<AppleAssetCatalogDescriptionArg> assetCatalogArgs,
      Set<AppleWrapperResourceArg> resourcePathArgs,
      ImmutableSet.Builder<Path> resourceFilesBuilder,
      ImmutableSet.Builder<Path> resourceDirsBuilder,
      ImmutableSet.Builder<Path> variantResourceFilesBuilder) {
    for (AppleResourceDescriptionArg arg : resourceArgs) {
      resourceFilesBuilder.addAll(Iterables.transform(arg.getFiles(), sourcePathResolver));
      resourceDirsBuilder.addAll(Iterables.transform(arg.getDirs(), sourcePathResolver));
      variantResourceFilesBuilder.addAll(
          Iterables.transform(arg.getVariants(), sourcePathResolver));
    }

    for (AppleAssetCatalogDescriptionArg arg : assetCatalogArgs) {
      resourceDirsBuilder.addAll(Iterables.transform(arg.getDirs(), sourcePathResolver));
    }

    for (AppleWrapperResourceArg arg : resourcePathArgs) {
      resourceDirsBuilder.add(arg.getPath());
    }
  }

  private void addResourcesFileReference(
      PBXGroup targetGroup,
      ImmutableSet<Path> resourceFiles,
      ImmutableSet<Path> resourceDirs,
      ImmutableSet<Path> variantResourceFiles,
      Consumer<? super PBXFileReference> resourceCallback,
      Consumer<? super PBXVariantGroup> variantGroupCallback) {
    if (resourceFiles.isEmpty() && resourceDirs.isEmpty() && variantResourceFiles.isEmpty()) {
      return;
    }

    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");
    for (Path path : resourceFiles) {
      PBXFileReference fileReference =
          resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
              new SourceTreePath(
                  PBXReference.SourceTree.SOURCE_ROOT,
                  pathRelativizer.outputDirToRootRelative(path),
                  Optional.empty()));
      resourceCallback.accept(fileReference);
    }
    for (Path path : resourceDirs) {
      PBXFileReference fileReference =
          resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
              new SourceTreePath(
                  PBXReference.SourceTree.SOURCE_ROOT,
                  pathRelativizer.outputDirToRootRelative(path),
                  Optional.of("folder")));
      resourceCallback.accept(fileReference);
    }

    Map<String, PBXVariantGroup> variantGroups = new HashMap<>();
    for (Path variantFilePath : variantResourceFiles) {
      String lprojSuffix = ".lproj";
      Path variantDirectory = variantFilePath.getParent();
      if (variantDirectory == null || !variantDirectory.toString().endsWith(lprojSuffix)) {
        throw new HumanReadableException(
            "Variant files have to be in a directory with name ending in '.lproj', "
                + "but '%s' is not.",
            variantFilePath);
      }
      String variantDirectoryName = variantDirectory.getFileName().toString();
      String variantLocalization =
          variantDirectoryName.substring(0, variantDirectoryName.length() - lprojSuffix.length());
      String variantFileName = variantFilePath.getFileName().toString();
      PBXVariantGroup variantGroup = variantGroups.get(variantFileName);
      if (variantGroup == null) {
        variantGroup = resourcesGroup.getOrCreateChildVariantGroupByName(variantFileName);
        variantGroupCallback.accept(variantGroup);
        variantGroups.put(variantFileName, variantGroup);
      }
      SourceTreePath sourceTreePath =
          new SourceTreePath(
              PBXReference.SourceTree.SOURCE_ROOT,
              pathRelativizer.outputDirToRootRelative(variantFilePath),
              Optional.empty());
      variantGroup.getOrCreateVariantFileReferenceByNameAndSourceTreePath(
          variantLocalization, sourceTreePath);
    }
  }

  private ImmutableList<PBXShellScriptBuildPhase> createScriptsForTargetNodes(
      Iterable<TargetNode<?, ?>> nodes) throws IllegalStateException {
    ImmutableList.Builder<PBXShellScriptBuildPhase> builder = ImmutableList.builder();
    for (TargetNode<?, ?> node : nodes) {
      PBXShellScriptBuildPhase shellScriptBuildPhase = new PBXShellScriptBuildPhase();
      boolean nodeIsPrebuildScript =
          node.getDescription() instanceof XcodePrebuildScriptDescription;
      boolean nodeIsPostbuildScript =
          node.getDescription() instanceof XcodePostbuildScriptDescription;
      if (nodeIsPrebuildScript || nodeIsPostbuildScript) {
        XcodeScriptDescriptionArg arg = (XcodeScriptDescriptionArg) node.getConstructorArg();
        shellScriptBuildPhase
            .getInputPaths()
            .addAll(
                FluentIterable.from(arg.getSrcs())
                    .transform(sourcePathResolver)
                    .transform(pathRelativizer::outputDirToRootRelative)
                    .transform(Object::toString)
                    .toSet());
        shellScriptBuildPhase.getOutputPaths().addAll(arg.getOutputs());
        shellScriptBuildPhase.setShellScript(arg.getCmd());
      } else if (node.getDescription() instanceof IosReactNativeLibraryDescription) {
        shellScriptBuildPhase.setShellScript(generateXcodeShellScript(node));
      } else {
        // unreachable
        throw new IllegalStateException("Invalid rule type for shell script build phase");
      }
      builder.add(shellScriptBuildPhase);
    }
    return builder.build();
  }

  private void addRunScriptBuildPhases(
      PBXNativeTarget target, Iterable<PBXShellScriptBuildPhase> phases) {
    for (PBXShellScriptBuildPhase phase : phases) {
      target.getBuildPhases().add(phase);
    }
  }

  private String generateXcodeShellScript(TargetNode<?, ?> targetNode) {
    Preconditions.checkArgument(targetNode.getConstructorArg() instanceof ReactNativeLibraryArg);

    ST template;
    try {
      template =
          new ST(
              Resources.toString(
                  Resources.getResource(
                      NewNativeTargetProjectMutator.class, REACT_NATIVE_PACKAGE_TEMPLATE),
                  Charsets.UTF_8));
    } catch (IOException e) {
      throw new RuntimeException("There was an error loading 'rn_package.st' template", e);
    }

    CoreReactNativeLibraryArg args = (CoreReactNativeLibraryArg) targetNode.getConstructorArg();

    template.add("bundle_name", args.getBundleName());

    ProjectFilesystem filesystem = targetNode.getFilesystem();
    BuildTarget buildTarget = targetNode.getBuildTarget();
    Path jsOutput =
        ReactNativeBundle.getPathToJSBundleDir(buildTarget, filesystem)
            .resolve(args.getBundleName());
    template.add("built_bundle_path", filesystem.resolve(jsOutput));

    Path resourceOutput = ReactNativeBundle.getPathToResources(buildTarget, filesystem);
    template.add("built_resources_path", filesystem.resolve(resourceOutput));

    Path sourceMap = ReactNativeBundle.getPathToSourceMap(buildTarget, filesystem);
    template.add("built_source_map_path", filesystem.resolve(sourceMap));

    return template.render();
  }
}
