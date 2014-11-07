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
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.FileExtensions;
import com.facebook.buck.apple.GroupedSource;
import com.facebook.buck.apple.HeaderVisibility;
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
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXVariantGroup;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Configures a PBXProject by adding a PBXNativeTarget and its associated dependencies into a
 * PBXProject object graph.
 */
public class NewNativeTargetProjectMutator {
  private static final Logger LOG = Logger.get(NewNativeTargetProjectMutator.class);

  public static class Result {
    public final PBXNativeTarget target;
    public final PBXGroup targetGroup;

    private Result(PBXNativeTarget target, PBXGroup targetGroup) {
      this.target = target;
      this.targetGroup = targetGroup;
    }
  }

  private final TargetNodeToBuildRuleTransformer targetNodeToBuildRuleTransformer =
      new TargetNodeToBuildRuleTransformer();
  private final TargetGraph targetGraph;
  private final ExecutionContext executionContext;
  private final PathRelativizer pathRelativizer;
  private final SourcePathResolver sourcePathResolver;
  private final BuildTarget buildTarget;

  private PBXTarget.ProductType productType = PBXTarget.ProductType.BUNDLE;
  private Path productOutputPath = Paths.get("");
  private String productName = "";
  private String targetName;
  private Optional<String> gid = Optional.absent();
  private Iterable<GroupedSource> sources = ImmutableList.of();
  private ImmutableMap<SourcePath, String> sourceFlags = ImmutableMap.of();
  private boolean shouldGenerateCopyHeadersPhase = true;
  private ImmutableSet<String> frameworks = ImmutableSet.of();
  private ImmutableSet<PBXFileReference> archives = ImmutableSet.of();
  private ImmutableSet<AppleResourceDescription.Arg> resources = ImmutableSet.of();
  private ImmutableSet<AppleAssetCatalogDescription.Arg> assetCatalogs = ImmutableSet.of();
  private Path assetCatalogBuildScript = Paths.get("");
  private Iterable<TargetNode<?>> preBuildRunScriptPhases = ImmutableList.of();
  private Iterable<TargetNode<?>> postBuildRunScriptPhases = ImmutableList.of();

  public NewNativeTargetProjectMutator(
      TargetGraph targetGraph,
      ExecutionContext executionContext,
      PathRelativizer pathRelativizer,
      SourcePathResolver sourcePathResolver,
      BuildTarget buildTarget) {
    this.targetGraph = targetGraph;
    this.executionContext = executionContext;
    this.pathRelativizer = pathRelativizer;
    this.sourcePathResolver = sourcePathResolver;
    this.buildTarget = buildTarget;
    this.targetName = buildTarget.getFullyQualifiedName();
  }

  /**
   * Set product related configuration.
   *
   * @param productType       declared product type
   * @param productName       product display name
   * @param productOutputPath build output relative product path.
   */
  public NewNativeTargetProjectMutator setProduct(
      PBXNativeTarget.ProductType productType,
      String productName,
      Path productOutputPath) {
    this.productName = productName;
    this.productType = productType;
    this.productOutputPath = productOutputPath;
    return this;
  }

  public NewNativeTargetProjectMutator setGid(Optional<String> gid) {
    this.gid = gid;
    return this;
  }

  public NewNativeTargetProjectMutator setTargetName(String targetName) {
    this.targetName = targetName;
    return this;
  }

  public NewNativeTargetProjectMutator setSources(
      Iterable<GroupedSource> sources,
      ImmutableMap<SourcePath, String> sourceFlags) {
    this.sources = sources;
    this.sourceFlags = sourceFlags;
    return this;
  }

  public NewNativeTargetProjectMutator setShouldGenerateCopyHeadersPhase(boolean value) {
    this.shouldGenerateCopyHeadersPhase = value;
    return this;
  }

  public NewNativeTargetProjectMutator setFrameworks(ImmutableSet<String> frameworks) {
    this.frameworks = frameworks;
    return this;
  }

  public NewNativeTargetProjectMutator setArchives(ImmutableSet<PBXFileReference> archives) {
    this.archives = archives;
    return this;
  }

  public NewNativeTargetProjectMutator setResources(
      ImmutableSet<AppleResourceDescription.Arg> resources) {
    this.resources = resources;
    return this;
  }

  public NewNativeTargetProjectMutator setPreBuildRunScriptPhases(Iterable<TargetNode<?>> phases) {
    preBuildRunScriptPhases = phases;
    return this;
  }

  public NewNativeTargetProjectMutator setPostBuildRunScriptPhases(Iterable<TargetNode<?>> phases) {
    postBuildRunScriptPhases = phases;
    return this;
  }

  /**
   * @param assetCatalogBuildScript Path of the asset catalog build script relative to repo root.
   * @param assetCatalogs List of asset catalog targets.
   */
  public NewNativeTargetProjectMutator setAssetCatalogs(
      Path assetCatalogBuildScript,
      ImmutableSet<AppleAssetCatalogDescription.Arg> assetCatalogs) {
    this.assetCatalogBuildScript = assetCatalogBuildScript;
    this.assetCatalogs = assetCatalogs;
    return this;
  }

  public Result buildTargetAndAddToProject(PBXProject project)
      throws NoSuchBuildTargetException {
    PBXNativeTarget target = new PBXNativeTarget(targetName, productType);
    PBXGroup targetGroup = project.getMainGroup().getOrCreateChildGroupByName(targetName);

    if (gid.isPresent()) {
      target.setGlobalID(gid.get());
    }

    // Phases
    addRunScriptBuildPhases(target, preBuildRunScriptPhases);
    addPhasesAndGroupsForSources(target, targetGroup);
    addFrameworksBuildPhase(project, target);
    addResourcesBuildPhase(target, targetGroup);
    addAssetCatalogBuildPhase(target, targetGroup);
    addRunScriptBuildPhases(target, postBuildRunScriptPhases);

    // Product

    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    PBXFileReference productReference = productsGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(PBXReference.SourceTree.BUILT_PRODUCTS_DIR, productOutputPath));
    target.setProductName(productName);
    target.setProductReference(productReference);

    project.getTargets().add(target);
    return new Result(target, targetGroup);
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
        // We still want to create groups for header files even if header build phases
        // are replaced with header maps.
        !shouldGenerateCopyHeadersPhase
            ? Optional.<PBXHeadersBuildPhase>absent()
            : Optional.of(headersBuildPhase),
        sources,
        sourceFlags);

    if (!sourcesBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(sourcesBuildPhase);
    }
    if (!headersBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(headersBuildPhase);
    }
  }

  private void traverseGroupsTreeAndHandleSources(
      PBXGroup sourcesGroup,
      PBXSourcesBuildPhase sourcesBuildPhase,
      Optional<PBXHeadersBuildPhase> headersBuildPhase,
      Iterable<GroupedSource> groupedSources,
      ImmutableMap<SourcePath, String> sourceFlags) {
    for (GroupedSource groupedSource : groupedSources) {
      switch (groupedSource.getType()) {
        case SOURCE_PATH:
          if (sourcePathResolver.isSourcePathExtensionInSet(
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
          traverseGroupsTreeAndHandleSources(
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
    PBXFileReference fileReference = sourcesGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputDirToRootRelative(sourcePathResolver.getPath(sourcePath))));
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

  private void addSourcePathToHeadersBuildPhase(
      SourcePath headerPath,
      PBXGroup headersGroup,
      Optional<PBXHeadersBuildPhase> headersBuildPhase,
      ImmutableMap<SourcePath, String> sourceFlags) {
    PBXFileReference fileReference = headersGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToSourcePath(headerPath)));
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

  private void addFrameworksBuildPhase(PBXProject project, PBXNativeTarget target) {
    if (frameworks.isEmpty() && archives.isEmpty()) {
      return;
    }

    PBXGroup sharedFrameworksGroup =
        project.getMainGroup().getOrCreateChildGroupByName("Frameworks");
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
                    pathRelativizer.outputPathToBuildTargetPath(buildTarget, path)));
        frameworksBuildPhase.getFiles().add(new PBXBuildFile(fileReference));
      }
    }

    for (PBXFileReference archive : archives) {
      frameworksBuildPhase.getFiles().add(new PBXBuildFile(archive));
    }
  }

  private void addResourcesBuildPhase(PBXNativeTarget target, PBXGroup targetGroup) {
    if (resources.isEmpty()) {
      return;
    }

    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");
    PBXBuildPhase phase = new PBXResourcesBuildPhase();
    target.getBuildPhases().add(phase);
    for (AppleResourceDescription.Arg resource : resources) {
      Iterable<Path> paths = Iterables.concat(
          sourcePathResolver.getAllPaths(resource.files),
          resource.dirs);
      for (Path path : paths) {
        PBXFileReference fileReference = resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativizer.outputDirToRootRelative(path)));
        PBXBuildFile buildFile = new PBXBuildFile(fileReference);
        phase.getFiles().add(buildFile);
      }

      for (Map.Entry<String, Map<String, SourcePath>> virtualOutputEntry :
          resource.variants.get().entrySet()) {
        String variantName = Paths.get(virtualOutputEntry.getKey()).getFileName().toString();
        PBXVariantGroup variantGroup =
            resourcesGroup.getOrCreateChildVariantGroupByName(variantName);

        PBXBuildFile buildFile = new PBXBuildFile(variantGroup);
        phase.getFiles().add(buildFile);

        for (Map.Entry<String, SourcePath> childVirtualNameEntry :
            virtualOutputEntry.getValue().entrySet()) {
          SourceTreePath sourceTreePath = new SourceTreePath(
              PBXReference.SourceTree.SOURCE_ROOT,
              pathRelativizer.outputPathToSourcePath(childVirtualNameEntry.getValue()));

          variantGroup.getOrCreateVariantFileReferenceByNameAndSourceTreePath(
              childVirtualNameEntry.getKey(),
              sourceTreePath);
        }
      }
    }
    LOG.debug("Added resources build phase %s", phase);
  }

  private void addAssetCatalogBuildPhase(PBXNativeTarget target, PBXGroup targetGroup) {
    if (assetCatalogs.isEmpty()) {
      return;
    }

    // Asset catalogs go in the resources group also.
    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

    // Some asset catalogs should be copied to their sibling bundles, while others use the default
    // output format (which may be to copy individual files to the root resource output path or to
    // be archived in Assets.car if it is supported by the target platform version).

    ImmutableList.Builder<String> commonAssetCatalogsBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> assetCatalogsToSplitIntoBundlesBuilder =
        ImmutableList.builder();
    for (AppleAssetCatalogDescription.Arg assetCatalog : assetCatalogs) {
      for (Path dir : assetCatalog.dirs) {
        Path pathRelativeToProjectRoot = pathRelativizer.outputDirToRootRelative(dir);

        resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativeToProjectRoot));

        LOG.debug("Resolved asset catalog path %s, result %s", dir, pathRelativeToProjectRoot);

        String bundlePath = "$PROJECT_DIR/" + pathRelativeToProjectRoot.toString();
        if (assetCatalog.getCopyToBundles()) {
          assetCatalogsToSplitIntoBundlesBuilder.add(bundlePath);
        } else {
          commonAssetCatalogsBuilder.add(bundlePath);
        }
      }
    }

    ImmutableList<String> commonAssetCatalogs = commonAssetCatalogsBuilder.build();
    ImmutableList<String> assetCatalogsToSplitIntoBundles =
        assetCatalogsToSplitIntoBundlesBuilder.build();

    // Map asset catalog paths to their shell script arguments relative to the project's root
    Path buildScript = pathRelativizer.outputDirToRootRelative(assetCatalogBuildScript);
    StringBuilder scriptBuilder = new StringBuilder("set -e\n");
    if (commonAssetCatalogs.size() != 0) {
      scriptBuilder
          .append("\"${PROJECT_DIR}/\"")
          .append(buildScript.toString())
          .append(" ")
          .append(Joiner.on(' ').join(commonAssetCatalogs))
          .append("\n");
    }
    if (assetCatalogsToSplitIntoBundles.size() != 0) {
      scriptBuilder
          .append("\"${PROJECT_DIR}/\"")
          .append(buildScript.toString())
          .append(" -b ")
          .append(Joiner.on(' ').join(assetCatalogsToSplitIntoBundles))
          .append("\n");
    }

    PBXShellScriptBuildPhase phase = new PBXShellScriptBuildPhase();
    target.getBuildPhases().add(phase);
    phase.setShellScript(scriptBuilder.toString());
    LOG.debug("Added asset catalog build phase %s", phase);
  }

  private void addRunScriptBuildPhases(
      PBXNativeTarget target,
      Iterable<TargetNode<?>> nodes) throws NoSuchBuildTargetException{
    for (TargetNode<?> node : nodes) {
      // TODO(user): Check and validate dependencies of the script. If it depends on libraries etc.
      // we can't handle it currently.
      Genrule rule = (Genrule) targetNodeToBuildRuleTransformer.transform(
          targetGraph,
          new BuildRuleResolver(),
          node);
      PBXShellScriptBuildPhase shellScriptBuildPhase = new PBXShellScriptBuildPhase();
      target.getBuildPhases().add(shellScriptBuildPhase);
      for (Path path : rule.getSrcs()) {
        shellScriptBuildPhase.getInputPaths().add(
            pathRelativizer.outputDirToRootRelative(path).toString());
      }

      StringBuilder bashCommandBuilder = new StringBuilder();
      for (String commandElement : rule.createGenruleStep().getShellCommand(executionContext)) {
        if (bashCommandBuilder.length() > 0) {
          bashCommandBuilder.append(' ');
        }
        bashCommandBuilder.append(Escaper.escapeAsBashString(commandElement));
      }
      shellScriptBuildPhase.setShellScript(bashCommandBuilder.toString());
      if (rule.getOutputName().length() > 0) {
        shellScriptBuildPhase.getOutputPaths().add(rule.getOutputName());
      }
    }
  }
}
