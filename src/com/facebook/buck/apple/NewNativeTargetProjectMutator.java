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

package com.facebook.buck.apple;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
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
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.js.IosReactNativeLibraryDescription;
import com.facebook.buck.js.ReactNativeBundle;
import com.facebook.buck.js.ReactNativeFlavors;
import com.facebook.buck.js.ReactNativeLibraryArgs;
import com.facebook.buck.js.ReactNativePlatform;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

  private final PathRelativizer pathRelativizer;
  private final Function<SourcePath, Path> sourcePathResolver;

  private ProductType productType = ProductType.BUNDLE;
  private Path productOutputPath = Paths.get("");
  private String productName = "";
  private String targetName = "";
  private ImmutableList<String> targetGroupPath = ImmutableList.of();
  private Optional<String> gid = Optional.absent();
  private ImmutableSet<SourceWithFlags> sourcesWithFlags = ImmutableSet.of();
  private ImmutableSet<SourcePath> extraXcodeSources = ImmutableSet.of();
  private ImmutableSet<SourcePath> publicHeaders = ImmutableSet.of();
  private ImmutableSet<SourcePath> privateHeaders = ImmutableSet.of();
  private Optional<SourcePath> prefixHeader = Optional.absent();
  private boolean shouldGenerateCopyHeadersPhase = true;
  private ImmutableSet<FrameworkPath> frameworks = ImmutableSet.of();
  private ImmutableSet<PBXFileReference> archives = ImmutableSet.of();
  private ImmutableSet<AppleResourceDescription.Arg> resources = ImmutableSet.of();
  private ImmutableSet<AppleAssetCatalogDescription.Arg> assetCatalogs = ImmutableSet.of();
  private Path assetCatalogBuildScript = Paths.get("");
  private Iterable<TargetNode<?>> preBuildRunScriptPhases = ImmutableList.of();
  private Iterable<TargetNode<?>> postBuildRunScriptPhases = ImmutableList.of();
  private boolean skipRNBundle = false;
  private Collection<Path> additionalRunScripts = ImmutableList.of();

  public NewNativeTargetProjectMutator(
      PathRelativizer pathRelativizer,
      Function<SourcePath, Path> sourcePathResolver) {
    this.pathRelativizer = pathRelativizer;
    this.sourcePathResolver = sourcePathResolver;
  }

  /**
   * Set product related configuration.
   *
   * @param productType       declared product type
   * @param productName       product display name
   * @param productOutputPath build output relative product path.
   */
  public NewNativeTargetProjectMutator setProduct(
      ProductType productType,
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

  public NewNativeTargetProjectMutator setTargetGroupPath(ImmutableList<String> targetGroupPath) {
    this.targetGroupPath = targetGroupPath;
    return this;
  }

  public NewNativeTargetProjectMutator setSourcesWithFlags(
      Set<SourceWithFlags> sourcesWithFlags) {
    this.sourcesWithFlags = ImmutableSet.copyOf(sourcesWithFlags);
    return this;
  }

  public NewNativeTargetProjectMutator setExtraXcodeSources(
      Set<SourcePath> extraXcodeSources) {
    this.extraXcodeSources = ImmutableSet.copyOf(extraXcodeSources);
    return this;
  }

  public NewNativeTargetProjectMutator setPublicHeaders(
      Set<SourcePath> publicHeaders) {
    this.publicHeaders = ImmutableSet.copyOf(publicHeaders);
    return this;
  }

  public NewNativeTargetProjectMutator setPrivateHeaders(
      Set<SourcePath> privateHeaders) {
    this.privateHeaders = ImmutableSet.copyOf(privateHeaders);
    return this;
  }

  public NewNativeTargetProjectMutator setPrefixHeader(Optional<SourcePath> prefixHeader) {
    this.prefixHeader = prefixHeader;
    return this;
  }

  public NewNativeTargetProjectMutator setShouldGenerateCopyHeadersPhase(boolean value) {
    this.shouldGenerateCopyHeadersPhase = value;
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

  public NewNativeTargetProjectMutator setResources(Set<AppleResourceDescription.Arg> resources) {
    this.resources = ImmutableSet.copyOf(resources);
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

  public NewNativeTargetProjectMutator skipReactNativeBundle(boolean skipRNBundle) {
    this.skipRNBundle = skipRNBundle;
    return this;
  }

  public void setAdditionalRunScripts(Collection<Path> scripts) {
    additionalRunScripts = scripts;
  }

  /**
   * @param assetCatalogBuildScript Path of the asset catalog build script relative to repo root.
   * @param assetCatalogs List of asset catalog targets.
   */
  public NewNativeTargetProjectMutator setAssetCatalogs(
      Path assetCatalogBuildScript,
      Set<AppleAssetCatalogDescription.Arg> assetCatalogs) {
    this.assetCatalogBuildScript = assetCatalogBuildScript;
    this.assetCatalogs = ImmutableSet.copyOf(assetCatalogs);
    return this;
  }

  public Result buildTargetAndAddToProject(PBXProject project)
      throws NoSuchBuildTargetException {
    PBXNativeTarget target = new PBXNativeTarget(targetName);

    PBXGroup targetGroup = project.getMainGroup().getOrCreateDescendantGroupByPath(targetGroupPath);
    targetGroup = targetGroup.getOrCreateChildGroupByName(targetName);

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
    addRawScriptBuildPhases(target);

    // Product

    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    PBXFileReference productReference = productsGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(PBXReference.SourceTree.BUILT_PRODUCTS_DIR, productOutputPath));
    target.setProductName(productName);
    target.setProductReference(productReference);
    target.setProductType(productType);

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
        RuleUtils.createGroupsFromSourcePaths(
            new Function<SourcePath, Path>() {
              @Override
              public Path apply(SourcePath sourcePath) {
                return pathRelativizer.outputPathToSourcePath(sourcePath);
              }
            },
            sourcesWithFlags,
            extraXcodeSources,
            publicHeaders,
            privateHeaders));

    if (prefixHeader.isPresent()) {
      SourceTreePath prefixHeaderSourceTreePath = new SourceTreePath(
          PBXReference.SourceTree.GROUP,
          pathRelativizer.outputPathToSourcePath(prefixHeader.get())
      );
      sourcesGroup.getOrCreateFileReferenceBySourceTreePath(prefixHeaderSourceTreePath);
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
      final Optional<PBXHeadersBuildPhase> headersBuildPhase,
      Iterable<GroupedSource> groupedSources) {
    GroupedSource.Visitor visitor = new GroupedSource.Visitor() {
      @Override
      public void visitSourceWithFlags(SourceWithFlags sourceWithFlags) {
        addSourcePathToSourcesBuildPhase(
            sourceWithFlags,
            sourcesGroup,
            sourcesBuildPhase);
      }

      @Override
      public void visitPublicHeader(SourcePath publicHeader) {
        addSourcePathToHeadersBuildPhase(
            publicHeader,
            sourcesGroup,
            headersBuildPhase,
            HeaderVisibility.PUBLIC);
      }

      @Override
      public void visitPrivateHeader(SourcePath privateHeader) {
        addSourcePathToHeadersBuildPhase(
            privateHeader,
            sourcesGroup,
            headersBuildPhase,
            HeaderVisibility.PRIVATE);
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
            newSourceGroup,
            sourcesBuildPhase,
            headersBuildPhase,
            sourceGroup);
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
    PBXFileReference fileReference = sourcesGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputDirToRootRelative(
                sourcePathResolver.apply(sourceWithFlags.getSourcePath()))));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    sourcesBuildPhase.getFiles().add(buildFile);
    List<String> customFlags = sourceWithFlags.getFlags();
    if (!customFlags.isEmpty()) {
      NSDictionary settings = new NSDictionary();
      settings.put("COMPILER_FLAGS", Joiner.on(' ').join(customFlags));
      buildFile.setSettings(Optional.of(settings));
    }
    LOG.verbose(
        "Added source path %s to group %s, flags %s, PBXFileReference %s",
        sourceWithFlags,
        sourcesGroup.getName(),
        customFlags,
        fileReference);
  }

  private void addSourcePathToHeadersBuildPhase(
      SourcePath headerPath,
      PBXGroup headersGroup,
      Optional<PBXHeadersBuildPhase> headersBuildPhase,
      HeaderVisibility visibility) {
    PBXFileReference fileReference = headersGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToSourcePath(headerPath)));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    if (visibility != HeaderVisibility.PRIVATE) {
      NSDictionary settings = new NSDictionary();
      settings.put(
          "ATTRIBUTES",
          new NSArray(new NSString(AppleHeaderVisibilities.toXcodeAttribute(visibility))));
      buildFile.setSettings(Optional.of(settings));
    } else {
      buildFile.setSettings(Optional.<NSDictionary>absent());
    }
    if (headersBuildPhase.isPresent()) {
      headersBuildPhase.get().getFiles().add(buildFile);
      LOG.verbose(
          "Added header path %s to headers group %s, PBXFileReference %s",
          headerPath,
          headersGroup.getName(),
          fileReference);
    } else {
      LOG.verbose(
          "Skipped header path %s to headers group %s, PBXFileReference %s",
          headerPath,
          headersGroup.getName(),
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

    for (FrameworkPath framework : frameworks) {
      SourceTreePath sourceTreePath;
      if (framework.getSourceTreePath().isPresent()) {
        sourceTreePath = framework.getSourceTreePath().get();
      } else if (framework.getSourcePath().isPresent()) {
        sourceTreePath = new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToSourcePath(framework.getSourcePath().get()));
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

  private void addResourcesBuildPhase(PBXNativeTarget target, PBXGroup targetGroup) {
    if (resources.isEmpty()) {
      return;
    }

    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");
    PBXBuildPhase phase = new PBXResourcesBuildPhase();
    target.getBuildPhases().add(phase);
    for (AppleResourceDescription.Arg resource : resources) {
      Iterable<Path> paths = Iterables.transform(
          Iterables.concat(resource.files, resource.dirs),
          sourcePathResolver);
      for (Path path : paths) {
        PBXFileReference fileReference = resourcesGroup.getOrCreateFileReferenceBySourceTreePath(
            new SourceTreePath(
                PBXReference.SourceTree.SOURCE_ROOT,
                pathRelativizer.outputDirToRootRelative(path)));
        PBXBuildFile buildFile = new PBXBuildFile(fileReference);
        phase.getFiles().add(buildFile);
      }

      Map<String, PBXVariantGroup> variantGroups = Maps.newHashMap();
      for (SourcePath variantSourcePath : resource.variants.get()) {
        String lprojSuffix = ".lproj";
        Path variantFilePath = sourcePathResolver.apply(variantSourcePath);
        Path variantDirectory = variantFilePath.getParent();
        if (variantDirectory == null || !variantDirectory.toString().endsWith(lprojSuffix)) {
          throw new HumanReadableException(
              "Variant files have to be in a directory with name ending in '.lproj', " +
                  "but '%s' is not.",
              variantFilePath);
        }
        String variantDirectoryName = variantDirectory.getFileName().toString();
        String variantLocalization =
            variantDirectoryName.substring(0, variantDirectoryName.length() - lprojSuffix.length());
        String variantFileName = variantFilePath.getFileName().toString();
        PBXVariantGroup variantGroup = variantGroups.get(variantFileName);
        if (variantGroup == null) {
          variantGroup = resourcesGroup.getOrCreateChildVariantGroupByName(variantFileName);
          PBXBuildFile buildFile = new PBXBuildFile(variantGroup);
          phase.getFiles().add(buildFile);
          variantGroups.put(variantFileName, variantGroup);
        }
        SourceTreePath sourceTreePath = new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToSourcePath(variantSourcePath));
        variantGroup.getOrCreateVariantFileReferenceByNameAndSourceTreePath(
            variantLocalization,
            sourceTreePath);
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
    scriptBuilder
        .append("TMPDIR=`mktemp -d -t buckAssetCatalogs.XXXXXX`\n")
        .append("trap \"rm -rf '${TMPDIR}'\" exit\n");
    if (commonAssetCatalogs.size() != 0) {
      scriptBuilder
          .append("COMMON_ARGS_FILE=\"${TMPDIR}\"/common_args\n")
          .append("cat <<EOT >\"${COMMON_ARGS_FILE}\"\n");

      Joiner.on('\n').appendTo(scriptBuilder, commonAssetCatalogs);

      scriptBuilder
          .append("\n")
          .append("EOT\n")
          .append("\"${PROJECT_DIR}/\"")
          .append(buildScript.toString())
          .append(" @\"${COMMON_ARGS_FILE}\"\n");
    }
    if (assetCatalogsToSplitIntoBundles.size() != 0) {
      scriptBuilder
        .append("BUNDLE_ARGS_FILE=\"${TMPDIR}\"/bundle_args\n")
        .append("cat <<EOT >\"${BUNDLE_ARGS_FILE}\"\n");

      Joiner.on('\n').appendTo(scriptBuilder, assetCatalogsToSplitIntoBundles);

      scriptBuilder
        .append("\n")
        .append("EOT\n")
        .append("\"${PROJECT_DIR}/\"")
        .append(buildScript.toString())
        .append(" -b ")
        .append("@\"${BUNDLE_ARGS_FILE}\"\n");
    }

    PBXShellScriptBuildPhase phase = new PBXShellScriptBuildPhase();
    target.getBuildPhases().add(phase);
    phase.setShellPath("/bin/bash");
    phase.setShellScript(scriptBuilder.toString());
    LOG.debug("Added asset catalog build phase %s", phase);
  }

  private void addRunScriptBuildPhases(
      PBXNativeTarget target,
      Iterable<TargetNode<?>> nodes) throws NoSuchBuildTargetException{
    for (TargetNode<?> node : nodes) {
      // TODO(user): Check and validate dependencies of the script. If it depends on libraries etc.
      // we can't handle it currently.
      PBXShellScriptBuildPhase shellScriptBuildPhase = new PBXShellScriptBuildPhase();
      target.getBuildPhases().add(shellScriptBuildPhase);
      if (GenruleDescription.TYPE.equals(node.getType())) {
        GenruleDescription.Arg arg = (GenruleDescription.Arg) node.getConstructorArg();
        for (Path path : Iterables.transform(arg.srcs.get(), sourcePathResolver)) {
          shellScriptBuildPhase.getInputPaths().add(
              pathRelativizer.outputDirToRootRelative(path).toString());
        }
        if (arg.cmd.isPresent() && !arg.cmd.get().isEmpty()) {
          shellScriptBuildPhase.setShellScript(arg.cmd.get());
        } else if (arg.bash.isPresent() || arg.cmdExe.isPresent()) {
          throw new IllegalStateException("Shell script phase only supports cmd for genrule.");
        }
        if (!arg.out.isEmpty()) {
          shellScriptBuildPhase.getOutputPaths().add(arg.out);
        }
      } else if (XcodePrebuildScriptDescription.TYPE.equals(node.getType())) {
        XcodePrebuildScriptDescription.Arg arg =
            (XcodePrebuildScriptDescription.Arg) node.getConstructorArg();
        shellScriptBuildPhase.setShellScript(arg.cmd);
      } else if (XcodePostbuildScriptDescription.TYPE.equals(node.getType())) {
        XcodePostbuildScriptDescription.Arg arg =
            (XcodePostbuildScriptDescription.Arg) node.getConstructorArg();
        shellScriptBuildPhase.setShellScript(arg.cmd);
      } else if (IosReactNativeLibraryDescription.TYPE.equals(node.getType())) {
        shellScriptBuildPhase.setShellScript(generateXcodeShellScript(node));
      } else {
        // unreachable
        throw new IllegalStateException("Invalid rule type for shell script build phase");
      }
    }
  }

  private void addRawScriptBuildPhases(PBXNativeTarget target) {
    for (Path runScript : additionalRunScripts) {
      PBXShellScriptBuildPhase phase = new PBXShellScriptBuildPhase();
      phase.setShellScript(runScript.toString());
      target.getBuildPhases().add(phase);
    }
  }

  private String generateXcodeShellScript(TargetNode<?> targetNode) {
    Preconditions.checkArgument(targetNode.getConstructorArg() instanceof ReactNativeLibraryArgs);

    ProjectFilesystem filesystem = targetNode.getRuleFactoryParams().getProjectFilesystem();
    ReactNativeLibraryArgs args = (ReactNativeLibraryArgs) targetNode.getConstructorArg();
    IosReactNativeLibraryDescription description =
        (IosReactNativeLibraryDescription) targetNode.getDescription();
    ImmutableList.Builder<String> script = ImmutableList.builder();
    script.add("BASE_DIR=${CONFIGURATION_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}");
    script.add("JS_OUT=${BASE_DIR}/" + args.bundleName);
    script.add("SOURCE_MAP=${TEMP_DIR}/rn_source_map/" + args.bundleName + ".map");

    if (skipRNBundle) {
      // Working in server mode: make sure that we clear the bundle from a previous build.
      script.add("rm -rf ${JS_OUT}");
    } else {
      script.add("mkdir -p `dirname ${JS_OUT}`");
      script.add("mkdir -p `dirname ${SOURCE_MAP}`");

      script.add(Joiner.on(" ").join(
              ReactNativeBundle.getBundleScript(
                  filesystem.resolve(
                      sourcePathResolver.apply(description.getReactNativePackager())),
                  filesystem.resolve(sourcePathResolver.apply(args.entryPath)),
                  ReactNativePlatform.IOS,
                  ReactNativeFlavors.isDevMode(targetNode.getBuildTarget()),
                  "${JS_OUT}",
                  "${BASE_DIR}",
                  "${SOURCE_MAP}")));
    }

    return Joiner.on(" && ").join(script.build());
  }
}
