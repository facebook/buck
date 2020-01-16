/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.apple.projectV2;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.AppleAssetCatalogDescriptionArg;
import com.facebook.buck.apple.AppleHeaderVisibilities;
import com.facebook.buck.apple.AppleResourceDescriptionArg;
import com.facebook.buck.apple.AppleWrapperResourceArg;
import com.facebook.buck.apple.GroupedSource;
import com.facebook.buck.apple.RuleUtils;
import com.facebook.buck.apple.xcode.AbstractPBXObjectFactory;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFrameworksBuildPhase;
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
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.apple.xcode.xcodeproj.XCVersionGroup;
import com.facebook.buck.core.cell.NewCellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Configures a PBXProject by adding a PBXNativeTarget and its associated dependencies into a
 * PBXProject object graph.
 */
class XcodeNativeTargetProjectWriter {
  private static final Logger LOG = Logger.get(XcodeNativeTargetProjectWriter.class);

  public static class Result {
    private final Optional<PBXNativeTarget> target;
    private final PBXGroup targetGroup;

    private Result(Optional<PBXNativeTarget> target, PBXGroup targetGroup) {
      this.target = target;
      this.targetGroup = targetGroup;
    }

    public Optional<PBXNativeTarget> getTarget() {
      return target;
    }

    public PBXGroup getTargetGroup() {
      return targetGroup;
    }
  }

  private final PathRelativizer pathRelativizer;
  private final Function<SourcePath, Path> sourcePathResolver;
  private final boolean shouldUseShortNamesForTargets;
  private final AbstractPBXObjectFactory objectFactory;
  private final NewCellPathResolver newCellPathResolver;

  public XcodeNativeTargetProjectWriter(
      PathRelativizer pathRelativizer,
      Function<SourcePath, Path> sourcePathResolver,
      boolean shouldUseShortNamesForTargets,
      NewCellPathResolver newCellPathResolver,
      AbstractPBXObjectFactory objectFactory) {
    this.pathRelativizer = pathRelativizer;
    this.sourcePathResolver = sourcePathResolver;
    this.shouldUseShortNamesForTargets = shouldUseShortNamesForTargets;
    this.newCellPathResolver = newCellPathResolver;
    this.objectFactory = objectFactory;
  }

  public Result writeTargetToProject(
      XCodeNativeTargetAttributes targetAttributes, PBXProject project) {
    ProjectFileWriter projectFileWriter =
        new ProjectFileWriter(project, pathRelativizer, sourcePathResolver);

    PBXSourcesBuildPhase sourcesBuildPhase = objectFactory.createSourcesBuildPhase();
    PBXHeadersBuildPhase headersBuildPhase = objectFactory.createHeadersBuildPhase();

    // Phases
    addPhasesAndGroupsForSources(
        targetAttributes, projectFileWriter, sourcesBuildPhase, headersBuildPhase);
    addResourcesFileReference(
        targetAttributes.directResources(),
        targetAttributes.directAssetCatalogs(),
        projectFileWriter);
    addWrapperResources(projectFileWriter, targetAttributes.wrapperResources());
    addCoreDataModelBuildPhaseToProject(
        targetAttributes.coreDataResources(), project, sourcesBuildPhase);

    Optional<PBXNativeTarget> generatedNativeTarget =
        targetAttributes
            .target()
            .map(
                buildTarget -> {
                  PBXNativeTarget nativeTarget =
                      objectFactory.createNativeTarget(getXcodeTargetName(buildTarget));
                  // Source files must be compiled in order to be indexed.
                  if (!sourcesBuildPhase.getFiles().isEmpty()) {
                    nativeTarget.getBuildPhases().add(sourcesBuildPhase);
                  }

                  // TODO(chatatap): Verify if we still need header build phases (specifically for
                  // swift).
                  if (!headersBuildPhase.getFiles().isEmpty()) {
                    nativeTarget.getBuildPhases().add(headersBuildPhase);
                  }

                  // Using a buck build phase doesnt support having other build phases.
                  // TODO(chatatap): Find solutions around this, as this will likely break indexing.
                  // TODO(chatatap): Need to handle dependencies for unit tests
                  targetAttributes
                      .product()
                      .ifPresent(
                          product -> {
                            if (ImmutableList.of(
                                    ProductTypes.APPLICATION,
                                    ProductTypes.UI_TEST,
                                    ProductTypes.APP_EXTENSION,
                                    ProductTypes.WATCH_APPLICATION)
                                .contains(product.getType())) {
                              nativeTarget.getBuildPhases().clear();
                            }

                            if (product.getType() == ProductTypes.UNIT_TEST) {
                              addSystemFrameworksBuildPhase(
                                  targetAttributes.systemFrameworks(), project, nativeTarget);
                            }
                          });

                  addBuckBuildPhase(
                      buildTarget,
                      targetAttributes.shell(),
                      targetAttributes.buildScriptPath(),
                      nativeTarget);

                  targetAttributes
                      .product()
                      .ifPresent(
                          product -> {
                            PBXGroup productsGroup =
                                project.getMainGroup().getOrCreateChildGroupByName("Products");
                            PBXFileReference productReference =
                                productsGroup.getOrCreateFileReferenceBySourceTreePath(
                                    new SourceTreePath(
                                        PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
                                        product.getOutputPath(),
                                        Optional.empty()));
                            nativeTarget.setProductName(product.getName());
                            nativeTarget.setProductReference(productReference);
                            nativeTarget.setProductType(product.getType());
                          });

                  for (XcconfigBaseConfiguration config : targetAttributes.xcconfigs()) {
                    XCBuildConfiguration outputConfiguration =
                        nativeTarget
                            .getBuildConfigurationList()
                            .getBuildConfigurationsByName()
                            .getUnchecked(config.getName());

                    PBXFileReference fileReference =
                        projectFileWriter
                            .writeFilePath(config.getPath(), Optional.empty())
                            .getFileReference();
                    outputConfiguration.setBaseConfigurationReference(fileReference);
                  }

                  project.getTargets().add(nativeTarget);
                  return nativeTarget;
                });

    targetAttributes
        .products()
        .forEach(
            product ->
                project
                    .getMainGroup()
                    .getOrCreateChildGroupByName("Products")
                    .getOrCreateFileReferenceBySourceTreePath(product));
    targetAttributes
        .frameworks()
        .forEach(
            framework ->
                project
                    .getMainGroup()
                    .getOrCreateChildGroupByName("Frameworks")
                    .getOrCreateFileReferenceBySourceTreePath(framework));
    targetAttributes
        .dependencies()
        .forEach(
            dep ->
                project
                    .getMainGroup()
                    .getOrCreateChildGroupByName("Dependencies")
                    .getOrCreateFileReferenceBySourceTreePath(dep));

    targetAttributes
        .genruleFiles()
        .forEach(
            sourcePath ->
                projectFileWriter.writeSourcePath(sourcePath, targetAttributes.packagePath()));

    PBXGroup targetGroup = project.getMainGroup();

    return new Result(generatedNativeTarget, targetGroup);
  }

  private void addPhasesAndGroupsForSources(
      XCodeNativeTargetAttributes targetAttributes,
      ProjectFileWriter projectFileWriter,
      PBXSourcesBuildPhase sourcesBuildPhase,
      PBXHeadersBuildPhase headersBuildPhase) {
    traverseGroupsTreeAndHandleSources(
        projectFileWriter,
        sourcesBuildPhase,
        headersBuildPhase,
        targetAttributes.packagePath(),
        targetAttributes.langPreprocessorFlags(),
        targetAttributes.frameworkHeadersEnabled(),
        targetAttributes.product().map(product -> product.getType()),
        RuleUtils.createGroupsFromSourcePaths(
            pathRelativizer::outputPathToSourcePath,
            targetAttributes.sourcesWithFlags(),
            targetAttributes.extraXcodeSources(),
            targetAttributes.extraXcodeFiles(),
            targetAttributes.publicHeaders(),
            targetAttributes.privateHeaders()));

    Optional<SourcePath> prefixHeader = targetAttributes.prefixHeader();
    if (prefixHeader.isPresent()) {
      projectFileWriter.writeSourcePath(prefixHeader.get(), targetAttributes.packagePath());
    }

    Optional<SourcePath> infoPlist = targetAttributes.infoPlist();
    if (infoPlist.isPresent()) {
      projectFileWriter.writeSourcePath(infoPlist.get(), targetAttributes.packagePath());
    }

    Optional<SourcePath> bridgingHeader = targetAttributes.bridgingHeader();
    if (bridgingHeader.isPresent()) {
      projectFileWriter.writeSourcePath(bridgingHeader.get(), targetAttributes.packagePath());
    }

    Optional<Path> buckFilePath = targetAttributes.buckFilePath();
    if (buckFilePath.isPresent()) {
      PBXFileReference buckFileReference =
          projectFileWriter.writeFilePath(buckFilePath.get(), Optional.empty()).getFileReference();
      buckFileReference.setExplicitFileType(Optional.of("text.script.python"));
    }

    targetAttributes
        .entitlementsPlistPath()
        .ifPresent(path -> projectFileWriter.writeFilePath(path, Optional.empty()));
  }

  private void traverseGroupsTreeAndHandleSources(
      ProjectFileWriter projectFileWriter,
      PBXSourcesBuildPhase sourcesBuildPhase,
      PBXHeadersBuildPhase headersBuildPhase,
      Optional<Path> packagePath,
      ImmutableMap<CxxSource.Type, ImmutableList<String>> langPreprocessorFlags,
      boolean frameworkHeadersEnabled,
      Optional<ProductType> productType,
      Iterable<GroupedSource> groupedSources) {
    GroupedSource.Visitor visitor =
        new GroupedSource.Visitor() {
          @Override
          public void visitSourceWithFlags(SourceWithFlags sourceWithFlags) {
            ProjectFileWriter.Result result =
                projectFileWriter.writeSourcePath(sourceWithFlags.getSourcePath(), packagePath);
            addFileReferenceToSourcesBuildPhase(
                result, sourceWithFlags, sourcesBuildPhase, langPreprocessorFlags);
          }

          @Override
          public void visitIgnoredSource(SourcePath source) {
            projectFileWriter.writeSourcePath(source, packagePath);
          }

          @Override
          public void visitPublicHeader(SourcePath publicHeader) {
            PBXFileReference fileReference =
                projectFileWriter.writeSourcePath(publicHeader, packagePath).getFileReference();
            addFileReferenceToHeadersBuildPhase(
                fileReference,
                headersBuildPhase,
                HeaderVisibility.PUBLIC,
                frameworkHeadersEnabled,
                productType);
          }

          @Override
          public void visitPrivateHeader(SourcePath privateHeader) {
            PBXFileReference fileReference =
                projectFileWriter.writeSourcePath(privateHeader, packagePath).getFileReference();
            addFileReferenceToHeadersBuildPhase(
                fileReference,
                headersBuildPhase,
                HeaderVisibility.PRIVATE,
                frameworkHeadersEnabled,
                productType);
          }

          @Override
          public void visitSourceGroup(
              String sourceGroupName,
              Path sourceGroupPathRelativeToTarget,
              List<GroupedSource> sourceGroup) {
            traverseGroupsTreeAndHandleSources(
                projectFileWriter,
                sourcesBuildPhase,
                headersBuildPhase,
                packagePath,
                langPreprocessorFlags,
                frameworkHeadersEnabled,
                productType,
                sourceGroup);
          }
        };
    for (GroupedSource groupedSource : groupedSources) {
      groupedSource.visit(visitor);
    }
  }

  private void addFileReferenceToSourcesBuildPhase(
      ProjectFileWriter.Result result,
      SourceWithFlags sourceWithFlags,
      PBXSourcesBuildPhase sourcesBuildPhase,
      ImmutableMap<CxxSource.Type, ImmutableList<String>> langPreprocessorFlags) {
    PBXFileReference fileReference = result.getFileReference();
    SourceTreePath sourceTreePath = result.getSourceTreePath();
    PBXBuildFile buildFile = objectFactory.createBuildFile(fileReference);
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
      HeaderVisibility visibility,
      boolean frameworkHeadersEnabled,
      Optional<ProductType> productType) {
    PBXBuildFile buildFile = objectFactory.createBuildFile(fileReference);
    if (visibility != HeaderVisibility.PRIVATE) {
      productType.ifPresent(
          type -> {
            if (frameworkHeadersEnabled
                && (type == ProductTypes.FRAMEWORK || type == ProductTypes.STATIC_FRAMEWORK)) {
              headersBuildPhase.getFiles().add(buildFile);
            }
          });

      NSDictionary settings = new NSDictionary();
      settings.put(
          "ATTRIBUTES",
          new NSArray(new NSString(AppleHeaderVisibilities.toXcodeAttribute(visibility))));
      buildFile.setSettings(Optional.of(settings));
    } else {
      buildFile.setSettings(Optional.empty());
    }
  }

  private void addResourcesFileReference(
      ImmutableSet<AppleResourceDescriptionArg> directResources,
      ImmutableSet<AppleAssetCatalogDescriptionArg> directAssetCatalogs,
      ProjectFileWriter projectFileWriter) {
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
        projectFileWriter,
        resourceFiles.build(),
        resourceDirs.build(),
        variantResourceFiles.build());
  }

  private void addWrapperResources(
      ProjectFileWriter projectFileWriter, Set<AppleWrapperResourceArg> wrapperResources) {
    ImmutableSet.Builder<Path> resourceFiles = ImmutableSet.builder();
    ImmutableSet.Builder<Path> resourceDirs = ImmutableSet.builder();
    ImmutableSet.Builder<Path> variantResourceFiles = ImmutableSet.builder();

    collectResourcePathsFromConstructorArgs(
        ImmutableSet.of(),
        ImmutableSet.of(),
        wrapperResources,
        resourceFiles,
        resourceDirs,
        variantResourceFiles);

    addResourcesFileReference(
        projectFileWriter,
        resourceFiles.build(),
        resourceDirs.build(),
        variantResourceFiles.build());
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
      ProjectFileWriter projectFileWriter,
      ImmutableSet<Path> resourceFiles,
      ImmutableSet<Path> resourceDirs,
      ImmutableSet<Path> variantResourceFiles) {
    if (resourceFiles.isEmpty() && resourceDirs.isEmpty() && variantResourceFiles.isEmpty()) {
      return;
    }

    for (Path path : resourceFiles) {
      projectFileWriter.writeFilePath(path, Optional.empty()).getFileReference();
    }

    for (Path path : resourceDirs) {
      projectFileWriter.writeFilePath(path, Optional.of("folder")).getFileReference();
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
      projectFileWriter.writeFilePath(variantFilePath, Optional.empty());
    }
  }

  /**
   * Add a `buck build TARGET` phase to the nativeTarget.
   *
   * @param nativeTarget PBXNativeTarget to which to add the buck build phase.
   */
  private void addBuckBuildPhase(
      BuildTarget target, Path shell, Path buildScriptPath, PBXNativeTarget nativeTarget) {
    PBXShellScriptBuildPhase buckBuildPhase = objectFactory.createShellScriptBuildPhase();
    buckBuildPhase.setName(Optional.of("Buck Build"));
    buckBuildPhase.setShellPath(shell.toString());

    // Form relative paths to the cell root and build script
    Path targetCellPath = newCellPathResolver.getCellPath(target.getCell());
    Path targetPath =
        targetCellPath.resolve(
            target.getCellRelativeBasePath().getPath().toPath(targetCellPath.getFileSystem()));
    Path targetRelativeCellRoot = targetPath.relativize(targetCellPath);
    Path cellRootRelativeBuildScript = targetCellPath.relativize(buildScriptPath);

    String shellCommand =
        String.format(
            "cd $SOURCE_ROOT/%s && ./%s", targetRelativeCellRoot, cellRootRelativeBuildScript);
    buckBuildPhase.setShellScript(shellCommand);

    nativeTarget.getBuildPhases().add(buckBuildPhase);
  }

  private void addCoreDataModelBuildPhaseToProject(
      ImmutableList<CoreDataResource> coreDataResources,
      PBXProject project,
      PBXSourcesBuildPhase sourcesBuildPhase) {
    for (CoreDataResource dataFile : coreDataResources) {
      // Core data models go in the resources group also.
      PBXGroup coreDataModelGroup;
      Path coreDataModelPath = dataFile.getPath();
      if (coreDataModelPath.getParent() != null) {
        coreDataModelGroup =
            project
                .getMainGroup()
                .getOrCreateDescendantGroupByPath(
                    RichStream.from(coreDataModelPath.getParent())
                        .map(Object::toString)
                        .toImmutableList());
      } else {
        coreDataModelGroup = project.getMainGroup();
      }

      if (dataFile.versionInfo().isPresent()) {
        CoreDataResource.VersionInformation versionInformation = dataFile.versionInfo().get();

        XCVersionGroup versionGroup =
            coreDataModelGroup.getOrCreateChildVersionGroupsBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SOURCE_ROOT,
                    pathRelativizer.outputDirToRootRelative(coreDataModelPath),
                    Optional.empty()));

        PBXBuildFile buildFile = objectFactory.createBuildFile(versionGroup);
        sourcesBuildPhase.getFiles().add(buildFile);

        for (Path versionPath : versionInformation.getAllVersionPaths()) {
          versionGroup.getOrCreateFileReferenceBySourceTreePath(
              new SourceTreePath(
                  PBXReference.SourceTree.SOURCE_ROOT,
                  pathRelativizer.outputDirToRootRelative(versionPath),
                  Optional.empty()));
        }

        PBXFileReference ref =
            versionGroup.getOrCreateFileReferenceBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SOURCE_ROOT,
                    pathRelativizer.outputDirToRootRelative(
                        versionInformation.getCurrentVersionPath()),
                    Optional.empty()));
        versionGroup.setCurrentVersion(ref);
      } else {
        PBXFileReference fileRef =
            coreDataModelGroup.getOrCreateFileReferenceBySourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SOURCE_ROOT,
                    pathRelativizer.outputDirToRootRelative(dataFile.getPath()),
                    Optional.empty()));
        PBXBuildFile buildFile = new PBXBuildFile(fileRef);
        sourcesBuildPhase.getFiles().add(buildFile);
      }
    }
  }

  private void addSystemFrameworksBuildPhase(
      ImmutableList<FrameworkPath> systemFrameworkPaths,
      PBXProject project,
      PBXNativeTarget target) {
    if (systemFrameworkPaths.isEmpty()) {
      return;
    }

    PBXGroup sharedFrameworksGroup =
        project.getMainGroup().getOrCreateChildGroupByName("Frameworks");
    PBXFrameworksBuildPhase frameworksBuildPhase = objectFactory.createFrameworksBuildPhase();
    target.getBuildPhases().add(frameworksBuildPhase);

    for (FrameworkPath framework : systemFrameworkPaths) {
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
      frameworksBuildPhase.getFiles().add(objectFactory.createBuildFile(fileReference));
    }
  }

  private String getXcodeTargetName(BuildTarget target) {
    return shouldUseShortNamesForTargets
        ? target.getShortNameAndFlavorPostfix() // make sure Xcode UI shows unique names by flavor
        : target.getFullyQualifiedName();
  }
}
