/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.exopackage.ExopackageMode;
import com.facebook.buck.android.exopackage.ExopackagePathAndHash;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildRules;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.shell.ExportFile;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.ExportFileDirectoryAction;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;

class AndroidBinaryResourcesGraphEnhancer {
  static final Flavor MANIFEST_MERGE_FLAVOR = InternalFlavor.of("manifest_merge");
  static final Flavor RESOURCES_FILTER_FLAVOR = InternalFlavor.of("resources_filter");
  static final Flavor AAPT_PACKAGE_FLAVOR = InternalFlavor.of("aapt_package");
  static final Flavor AAPT2_LINK_FLAVOR = InternalFlavor.of("aapt2_link");
  static final Flavor PACKAGE_STRING_ASSETS_FLAVOR = InternalFlavor.of("package_string_assets");
  private static final Flavor MERGE_ASSETS_FLAVOR = InternalFlavor.of("merge_assets");
  static final Flavor GENERATE_RDOT_JAVA_FLAVOR = InternalFlavor.of("generate_rdot_java");
  private static final Flavor SPLIT_RESOURCES_FLAVOR = InternalFlavor.of("split_resources");
  static final Flavor GENERATE_STRING_RESOURCES_FLAVOR =
      InternalFlavor.of("generate_string_resources");
  private static final Flavor MERGE_THIRD_PARTY_JAR_RESOURCES_FLAVOR =
      InternalFlavor.of("merge_third_party_jar_resources");
  private static final Flavor WRITE_EXO_RESOURCES_HASH_FLAVOR =
      InternalFlavor.of("write_exo_resources_hash");
  private static final Flavor COPY_MANIFEST_FLAVOR = InternalFlavor.of("copy_manifest");

  private final AndroidPlatformTarget androidPlatformTarget;
  private final SourcePathRuleFinder ruleFinder;
  private final FilterResourcesSteps.ResourceFilter resourceFilter;
  private final ResourcesFilter.ResourceCompressionMode resourceCompressionMode;
  private final ImmutableSet<String> locales;
  private final Optional<String> localizedStringFileName;
  private final BuildTarget buildTarget;
  private final ProjectFilesystem projectFilesystem;
  private final ActionGraphBuilder graphBuilder;
  private final AaptMode aaptMode;
  private final Optional<SourcePath> rawManifest;
  private final Optional<SourcePath> manifestSkeleton;
  private final Optional<SourcePath> moduleManifestSkeleton;
  private final Optional<String> resourceUnionPackage;
  private final boolean shouldBuildStringSourceMap;
  private final boolean skipCrunchPngs;
  private final boolean includesVectorDrawables;
  private final EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes;
  private final Optional<SourcePath> duplicateResourceWhitelistPath;
  private final ManifestEntries manifestEntries;
  private final BuildTarget originalBuildTarget;
  private final Optional<Arg> postFilterResourcesCmd;
  private final boolean exopackageForResources;
  private final boolean noAutoVersionResources;
  private final boolean noVersionTransitionsResources;
  private final boolean noAutoAddOverlayResources;
  private final APKModuleGraph apkModuleGraph;
  private final boolean useProtoFormat;

  public AndroidBinaryResourcesGraphEnhancer(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AndroidPlatformTarget androidPlatformTarget,
      ActionGraphBuilder graphBuilder,
      BuildTarget originalBuildTarget,
      boolean exopackageForResources,
      Optional<SourcePath> rawManifest,
      Optional<SourcePath> manifestSkeleton,
      Optional<SourcePath> moduleManifestSkeleton,
      AaptMode aaptMode,
      FilterResourcesSteps.ResourceFilter resourceFilter,
      ResourcesFilter.ResourceCompressionMode resourceCompressionMode,
      ImmutableSet<String> locales,
      Optional<String> localizedStringFileName,
      Optional<String> resourceUnionPackage,
      boolean shouldBuildStringSourceMap,
      boolean skipCrunchPngs,
      boolean includesVectorDrawables,
      EnumSet<RDotTxtEntry.RType> bannedDuplicateResourceTypes,
      Optional<SourcePath> duplicateResourceWhitelistPath,
      ManifestEntries manifestEntries,
      Optional<Arg> postFilterResourcesCmd,
      boolean noAutoVersionResources,
      boolean noVersionTransitionsResources,
      boolean noAutoAddOverlayResources,
      APKModuleGraph apkModuleGraph,
      boolean useProtoFormat) {
    this.androidPlatformTarget = androidPlatformTarget;
    this.buildTarget = buildTarget;
    this.projectFilesystem = projectFilesystem;
    this.graphBuilder = graphBuilder;
    this.ruleFinder = new SourcePathRuleFinder(graphBuilder);
    this.exopackageForResources = exopackageForResources;
    this.resourceFilter = resourceFilter;
    this.resourceCompressionMode = resourceCompressionMode;
    this.locales = locales;
    this.localizedStringFileName = localizedStringFileName;
    this.aaptMode = aaptMode;
    this.rawManifest = rawManifest;
    this.manifestSkeleton = manifestSkeleton;
    this.moduleManifestSkeleton = moduleManifestSkeleton;
    this.resourceUnionPackage = resourceUnionPackage;
    this.shouldBuildStringSourceMap = shouldBuildStringSourceMap;
    this.skipCrunchPngs = skipCrunchPngs;
    this.includesVectorDrawables = includesVectorDrawables;
    this.bannedDuplicateResourceTypes = bannedDuplicateResourceTypes;
    this.duplicateResourceWhitelistPath = duplicateResourceWhitelistPath;
    this.manifestEntries = manifestEntries;
    this.originalBuildTarget = originalBuildTarget;
    this.postFilterResourcesCmd = postFilterResourcesCmd;
    this.noAutoVersionResources = noAutoVersionResources;
    this.noVersionTransitionsResources = noVersionTransitionsResources;
    this.noAutoAddOverlayResources = noAutoAddOverlayResources;
    this.apkModuleGraph = apkModuleGraph;
    this.useProtoFormat = useProtoFormat;
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractAndroidBinaryResourcesGraphEnhancementResult {
    SourcePath getPathToRDotTxt();

    Optional<SourcePath> getRDotJavaDir();

    SourcePath getPrimaryResourcesApkPath();

    SourcePath getAndroidManifestXml();

    SourcePath getAaptGeneratedProguardConfigFile();

    Optional<PackageStringAssets> getPackageStringAssets();

    ImmutableList<SourcePath> getPrimaryApkAssetZips();

    ImmutableList<ExopackagePathAndHash> getExoResources();

    ImmutableMap<APKModule, SourcePath> getModuleResourceApkPaths();
  }

  AndroidBinaryResourcesGraphEnhancementResult enhance(
      AndroidPackageableCollection packageableCollection) {

    AndroidPackageableCollection.ResourceDetails resourceDetails =
        packageableCollection.getResourceDetails().get(apkModuleGraph.getRootAPKModule());
    if (resourceDetails == null) {
      throw new HumanReadableException("Resource details for the base APK were not found.");
    }

    ImmutableSortedSet<BuildRule> resourceRules =
        getTargetsAsRules(resourceDetails.getResourcesWithNonEmptyResDir());

    ImmutableCollection<BuildRule> rulesWithResourceDirectories =
        ruleFinder.filterBuildRuleInputs(resourceDetails.getResourceDirectories());

    FilteredResourcesProvider filteredResourcesProvider;
    boolean needsResourceFiltering =
        resourceFilter.isEnabled()
            || postFilterResourcesCmd.isPresent()
            || resourceCompressionMode.isStoreStringsAsAssets()
            || !locales.isEmpty();

    if (needsResourceFiltering) {
      ResourcesFilter resourcesFilter =
          createResourcesFilter(
              InternalFlavor.of(apkModuleGraph.getRootAPKModule().getName()),
              resourceDetails,
              resourceRules,
              rulesWithResourceDirectories);
      graphBuilder.addToIndex(resourcesFilter);
      filteredResourcesProvider = resourcesFilter;
      resourceRules = ImmutableSortedSet.of(resourcesFilter);
    } else {
      filteredResourcesProvider =
          new IdentityResourcesProvider(resourceDetails.getResourceDirectories());
    }

    SourcePath realManifest;
    if (rawManifest.isPresent()) {
      if (manifestSkeleton.isPresent()) {
        throw new HumanReadableException(
            "android_binary " + buildTarget + " specified both manifest and manifest_skeleton.");
      }
      realManifest = rawManifest.get();
    } else if (manifestSkeleton.isPresent()) {
      AndroidManifest manifestMergeRule =
          new AndroidManifest(
              buildTarget.withAppendedFlavors(MANIFEST_MERGE_FLAVOR),
              projectFilesystem,
              ruleFinder,
              manifestSkeleton.get(),
              apkModuleGraph.getRootAPKModule(),
              packageableCollection.getAndroidManifestPieces().values());
      graphBuilder.addToIndex(manifestMergeRule);
      realManifest = manifestMergeRule.getSourcePathToOutput();
    } else {
      throw new HumanReadableException(
          "android_binary " + buildTarget + " did not specify manifest or manifest_skeleton.");
    }

    List<SourcePath> apkResourceDependencyList = new ArrayList<>();

    AaptOutputInfo aaptOutputInfo;
    switch (aaptMode) {
      case AAPT1:
        {
          // Create the AaptPackageResourcesBuildable.
          AaptPackageResources aaptPackageResources =
              createAaptPackageResources(realManifest, resourceDetails, filteredResourcesProvider);
          graphBuilder.addToIndex(aaptPackageResources);
          aaptOutputInfo = aaptPackageResources.getAaptOutputInfo();
          apkResourceDependencyList.add(aaptOutputInfo.getPrimaryResourcesApkPath());
        }
        break;

      case AAPT2:
        {
          Aapt2Link aapt2Link =
              createAapt2Link(
                  0,
                  InternalFlavor.of(apkModuleGraph.getRootAPKModule().getName()),
                  realManifest,
                  resourceDetails,
                  needsResourceFiltering
                      ? Optional.of(filteredResourcesProvider)
                      : Optional.empty(),
                  ImmutableList.of(),
                  useProtoFormat);
          graphBuilder.addToIndex(aapt2Link);
          if (useProtoFormat) {
            Aapt2Link aapt2LinkArsc =
                createAapt2Link(
                    0,
                    InternalFlavor.of(apkModuleGraph.getRootAPKModule().getName()),
                    realManifest,
                    resourceDetails,
                    needsResourceFiltering
                        ? Optional.of(filteredResourcesProvider)
                        : Optional.empty(),
                    ImmutableList.of(),
                    false);
            graphBuilder.addToIndex(aapt2LinkArsc);
            apkResourceDependencyList.add(
                aapt2LinkArsc.getAaptOutputInfo().getPrimaryResourcesApkPath());
          } else {
            apkResourceDependencyList.add(
                aapt2Link.getAaptOutputInfo().getPrimaryResourcesApkPath());
          }
          aaptOutputInfo = aapt2Link.getAaptOutputInfo();
        }
        break;

      default:
        throw new RuntimeException("Unexpected aaptMode: " + aaptMode);
    }

    AndroidBinaryResourcesGraphEnhancementResult.Builder resultBuilder =
        AndroidBinaryResourcesGraphEnhancementResult.builder();

    ImmutableSet.Builder<SourcePath> pathToRDotTxtFiles = ImmutableSet.builder();

    int packageIdOffset = 1;
    if (moduleManifestSkeleton.isPresent()) {
      for (APKModule module : apkModuleGraph.getAPKModules()) {
        if (module.isRootModule()) {
          continue;
        }

        AndroidPackageableCollection.ResourceDetails moduleResourceDetails =
            packageableCollection.getResourceDetails().get(module);
        if (moduleResourceDetails == null) {
          throw new RuntimeException(
              String.format("Missing resource details for module %s", module.getName()));
        }

        InternalFlavor moduleFlavor = InternalFlavor.of(module.getName());

        ImmutableSortedSet<BuildRule> moduleResourceRules =
            getTargetsAsRules(moduleResourceDetails.getResourcesWithNonEmptyResDir());

        ImmutableCollection<BuildRule> moduleRulesWithResourceDirectories =
            ruleFinder.filterBuildRuleInputs(moduleResourceDetails.getResourceDirectories());

        FilteredResourcesProvider moduleFilteredResourcesProvider;

        if (needsResourceFiltering) {
          ResourcesFilter resourcesFilter =
              createResourcesFilter(
                  moduleFlavor,
                  moduleResourceDetails,
                  moduleResourceRules,
                  moduleRulesWithResourceDirectories);
          graphBuilder.addToIndex(resourcesFilter);
          moduleFilteredResourcesProvider = resourcesFilter;
          moduleResourceRules = ImmutableSortedSet.of(resourcesFilter);
        } else {
          moduleFilteredResourcesProvider =
              new IdentityResourcesProvider(moduleResourceDetails.getResourceDirectories());
        }

        AndroidManifest moduleManifestMergeRule =
            new AndroidManifest(
                buildTarget.withAppendedFlavors(MANIFEST_MERGE_FLAVOR, moduleFlavor),
                projectFilesystem,
                ruleFinder,
                moduleManifestSkeleton.get(),
                module,
                packageableCollection.getAndroidManifestPieces().get(module));
        graphBuilder.addToIndex(moduleManifestMergeRule);

        switch (aaptMode) {
          case AAPT1:
            {
              if (module.hasResources()) {
                throw new HumanReadableException(
                    "Resources in modules is only supported with aapt_mode=\"aapt2\". %s is"
                        + " declared to have resources",
                    module.getName());
              }
              AaptPackageResources aaptModule =
                  new AaptPackageResources(
                      buildTarget.withAppendedFlavors(AAPT_PACKAGE_FLAVOR, moduleFlavor),
                      projectFilesystem,
                      androidPlatformTarget,
                      ruleFinder,
                      graphBuilder,
                      moduleManifestMergeRule.getSourcePathToOutput(),
                      ImmutableList.copyOf(apkResourceDependencyList),
                      new IdentityResourcesProvider(ImmutableList.of()),
                      ImmutableList.of(),
                      true,
                      false,
                      ManifestEntries.empty());
              graphBuilder.addToIndex(aaptModule);
              resultBuilder.putModuleResourceApkPaths(
                  module, aaptModule.getAaptOutputInfo().getPrimaryResourcesApkPath());
              apkResourceDependencyList.add(
                  aaptModule.getAaptOutputInfo().getPrimaryResourcesApkPath());
            }
            break;

          case AAPT2:
            {
              Aapt2Link aapt2ModuleLink =
                  createAapt2Link(
                      packageIdOffset++,
                      moduleFlavor,
                      moduleManifestMergeRule.getSourcePathToOutput(),
                      moduleResourceDetails,
                      needsResourceFiltering
                          ? Optional.of(moduleFilteredResourcesProvider)
                          : Optional.empty(),
                      ImmutableList.copyOf(apkResourceDependencyList),
                      useProtoFormat);
              graphBuilder.addToIndex(aapt2ModuleLink);
              SourcePath moduleResourceApk =
                  aapt2ModuleLink.getAaptOutputInfo().getPrimaryResourcesApkPath();
              pathToRDotTxtFiles.add(aapt2ModuleLink.getAaptOutputInfo().getPathToRDotTxt());
              resultBuilder.putModuleResourceApkPaths(module, moduleResourceApk);
              apkResourceDependencyList.add(moduleResourceApk);
            }
            break;

          default:
            throw new RuntimeException("Unexpected aaptMode: " + aaptMode);
        }
      }
    }

    Optional<PackageStringAssets> packageStringAssets = Optional.empty();
    if (resourceCompressionMode.isStoreStringsAsAssets()) {
      // TODO(cjhopman): we should be able to support this in exo-for-resources
      if (exopackageForResources) {
        throw new HumanReadableException(
            "exopackage_modes and resource_compression_mode for android_binary %s are "
                + "incompatible. Either remove %s from exopackage_modes or disable storing strings "
                + "as assets.",
            buildTarget, ExopackageMode.RESOURCES);
      }
      packageStringAssets =
          Optional.of(
              createPackageStringAssets(
                  resourceRules,
                  rulesWithResourceDirectories,
                  filteredResourcesProvider,
                  aaptOutputInfo));
      graphBuilder.addToIndex(packageStringAssets.get());
    }
    resultBuilder.setPackageStringAssets(packageStringAssets);

    SourcePath pathToRDotTxt;
    ImmutableList<ExopackagePathAndHash> exoResources;
    if (exopackageForResources) {
      MergeAssets mergeAssets =
          createMergeAssetsRule(
              packageableCollection.getAssetsDirectories().values(), Optional.empty());
      SplitResources splitResources =
          createSplitResourcesRule(
              aaptOutputInfo.getPrimaryResourcesApkPath(), aaptOutputInfo.getPathToRDotTxt());
      MergeThirdPartyJarResources mergeThirdPartyJarResource =
          createMergeThirdPartyJarResources(
              packageableCollection.getPathsToThirdPartyJars().values());

      graphBuilder.addToIndex(mergeAssets);
      graphBuilder.addToIndex(splitResources);
      graphBuilder.addToIndex(mergeThirdPartyJarResource);

      pathToRDotTxt = splitResources.getPathToRDotTxt();
      resultBuilder.setPrimaryResourcesApkPath(splitResources.getPathToPrimaryResources());

      exoResources =
          ImmutableList.of(
              withFileHashCodeRule(splitResources.getPathToExoResources(), "exo_resources"),
              withFileHashCodeRule(mergeAssets.getSourcePathToOutput(), "merged_assets"),
              withFileHashCodeRule(
                  mergeThirdPartyJarResource.getSourcePathToOutput(), "third_party_jar_resources"));

      graphBuilder.addToIndex(splitResources);
      graphBuilder.addToIndex(mergeAssets);
    } else {
      MergeAssets mergeAssets =
          createMergeAssetsRule(
              packageableCollection.getAssetsDirectories().values(),
              Optional.of(aaptOutputInfo.getPrimaryResourcesApkPath()));
      graphBuilder.addToIndex(mergeAssets);

      pathToRDotTxt = aaptOutputInfo.getPathToRDotTxt();
      resultBuilder.setPrimaryResourcesApkPath(mergeAssets.getSourcePathToOutput());
      if (packageStringAssets.isPresent()) {
        resultBuilder.addPrimaryApkAssetZips(
            packageStringAssets.get().getSourcePathToStringAssetsZip());
      }
      exoResources = ImmutableList.of();
    }
    resultBuilder.setExoResources(exoResources);
    pathToRDotTxtFiles.add(pathToRDotTxt);

    Optional<GenerateRDotJava> generateRDotJava = Optional.empty();
    if (filteredResourcesProvider.hasResources()) {
      generateRDotJava =
          Optional.of(
              createGenerateRDotJava(
                  pathToRDotTxtFiles.build(),
                  getTargetsAsRules(
                      packageableCollection
                          .getResourceDetails()
                          .values()
                          .stream()
                          .flatMap(r -> r.getResourcesWithNonEmptyResDir().stream())
                          .collect(ImmutableList.toImmutableList())),
                  filteredResourcesProvider));

      graphBuilder.addToIndex(generateRDotJava.get());

      if (shouldBuildStringSourceMap) {
        graphBuilder.addToIndex(createGenerateStringResources(filteredResourcesProvider));
      }
    }

    // Create a rule that copies the AndroidManifest. This allows the AndroidBinary rule (and
    // exopackage installation rules) to have a runtime dep on the manifest without having to have
    // a runtime dep on the full aapt output.
    ExportFile manifestCopyRule =
        new ExportFile(
            originalBuildTarget.withAppendedFlavors(COPY_MANIFEST_FLAVOR),
            projectFilesystem,
            ruleFinder,
            "AndroidManifest.xml",
            ExportFileDescription.Mode.COPY,
            aaptOutputInfo.getAndroidManifestXml(),
            ExportFileDirectoryAction.FAIL);
    graphBuilder.addToIndex(manifestCopyRule);

    return resultBuilder
        .setAaptGeneratedProguardConfigFile(aaptOutputInfo.getAaptGeneratedProguardConfigFile())
        .setAndroidManifestXml(manifestCopyRule.getSourcePathToOutput())
        .setPathToRDotTxt(aaptOutputInfo.getPathToRDotTxt())
        .setRDotJavaDir(
            generateRDotJava.map(GenerateRDotJava::getSourcePathToGeneratedRDotJavaSrcFiles))
        .build();
  }

  private ExopackagePathAndHash withFileHashCodeRule(SourcePath pathToFile, String name) {
    WriteFileHashCode fileHashCode =
        new WriteFileHashCode(
            buildTarget.withAppendedFlavors(
                WRITE_EXO_RESOURCES_HASH_FLAVOR, InternalFlavor.of(name)),
            projectFilesystem,
            ruleFinder,
            pathToFile);
    graphBuilder.addToIndex(fileHashCode);
    return ExopackagePathAndHash.of(pathToFile, fileHashCode.getSourcePathToOutput());
  }

  private MergeThirdPartyJarResources createMergeThirdPartyJarResources(
      ImmutableCollection<SourcePath> pathsToThirdPartyJars) {
    return new MergeThirdPartyJarResources(
        buildTarget.withAppendedFlavors(MERGE_THIRD_PARTY_JAR_RESOURCES_FLAVOR),
        projectFilesystem,
        ruleFinder,
        pathsToThirdPartyJars);
  }

  private SplitResources createSplitResourcesRule(
      SourcePath aaptOutputPath, SourcePath aaptRDotTxtPath) {
    return new SplitResources(
        buildTarget.withAppendedFlavors(SPLIT_RESOURCES_FLAVOR),
        projectFilesystem,
        ruleFinder,
        aaptOutputPath,
        aaptRDotTxtPath,
        androidPlatformTarget);
  }

  private Aapt2Link createAapt2Link(
      int packageId,
      InternalFlavor flavor,
      SourcePath realManifest,
      AndroidPackageableCollection.ResourceDetails resourceDetails,
      Optional<FilteredResourcesProvider> filteredResourcesProvider,
      ImmutableList<SourcePath> dependencyResourceApks,
      boolean isProtoFormat) {

    ImmutableList.Builder<Aapt2Compile> compileListBuilder = ImmutableList.builder();
    if (filteredResourcesProvider.isPresent()) {
      Optional<BuildRule> resourceFilterRule =
          filteredResourcesProvider.get().getResourceFilterRule();
      Preconditions.checkState(
          resourceFilterRule.isPresent(),
          "Expected ResourceFilterRule to be present when filtered resources are present.");

      ImmutableSortedSet<BuildRule> compileDeps = ImmutableSortedSet.of(resourceFilterRule.get());
      int index = 0;
      for (SourcePath resDir : filteredResourcesProvider.get().getResDirectories()) {
        Aapt2Compile compileRule =
            new Aapt2Compile(
                buildTarget.withAppendedFlavors(
                    InternalFlavor.of("aapt2_compile_" + index), flavor),
                projectFilesystem,
                androidPlatformTarget,
                compileDeps,
                resDir);
        graphBuilder.addToIndex(compileRule);
        compileListBuilder.add(compileRule);
        index++;
      }
    } else {
      for (BuildTarget resTarget : resourceDetails.getResourcesWithNonEmptyResDir()) {
        compileListBuilder.add(
            (Aapt2Compile)
                graphBuilder.requireRule(
                    resTarget.withAppendedFlavors(
                        AndroidResourceDescription.AAPT2_COMPILE_FLAVOR)));
      }
    }
    return new Aapt2Link(
        buildTarget.withAppendedFlavors(
            AAPT2_LINK_FLAVOR, flavor, InternalFlavor.of(isProtoFormat ? "proto" : "arsc")),
        projectFilesystem,
        ruleFinder,
        compileListBuilder.build(),
        getTargetsAsResourceDeps(resourceDetails.getResourcesWithNonEmptyResDir()),
        realManifest,
        manifestEntries,
        packageId,
        dependencyResourceApks,
        includesVectorDrawables,
        noAutoVersionResources,
        noVersionTransitionsResources,
        noAutoAddOverlayResources,
        isProtoFormat,
        androidPlatformTarget);
  }

  private GenerateRDotJava createGenerateRDotJava(
      ImmutableCollection<SourcePath> pathToRDotTxtFiles,
      ImmutableSortedSet<BuildRule> resourceDeps,
      FilteredResourcesProvider resourcesProvider) {
    return new GenerateRDotJava(
        buildTarget.withAppendedFlavors(GENERATE_RDOT_JAVA_FLAVOR),
        projectFilesystem,
        ruleFinder,
        bannedDuplicateResourceTypes,
        duplicateResourceWhitelistPath,
        pathToRDotTxtFiles,
        resourceUnionPackage,
        resourceDeps,
        resourcesProvider);
  }

  private GenerateStringResources createGenerateStringResources(
      FilteredResourcesProvider filteredResourcesProvider) {
    return new GenerateStringResources(
        buildTarget.withAppendedFlavors(GENERATE_STRING_RESOURCES_FLAVOR),
        projectFilesystem,
        ruleFinder,
        filteredResourcesProvider);
  }

  private ResourcesFilter createResourcesFilter(
      InternalFlavor flavor,
      AndroidPackageableCollection.ResourceDetails resourceDetails,
      ImmutableSortedSet<BuildRule> resourceRules,
      ImmutableCollection<BuildRule> rulesWithResourceDirectories) {
    return new ResourcesFilter(
        buildTarget.withAppendedFlavors(RESOURCES_FILTER_FLAVOR, flavor),
        projectFilesystem,
        resourceRules,
        rulesWithResourceDirectories,
        ruleFinder,
        resourceDetails.getResourceDirectories(),
        ImmutableSet.copyOf(resourceDetails.getWhitelistedStringDirectories()),
        locales,
        localizedStringFileName,
        resourceCompressionMode,
        resourceFilter,
        postFilterResourcesCmd);
  }

  private AaptPackageResources createAaptPackageResources(
      SourcePath realManifest,
      AndroidPackageableCollection.ResourceDetails resourceDetails,
      FilteredResourcesProvider filteredResourcesProvider) {
    return new AaptPackageResources(
        buildTarget.withAppendedFlavors(AAPT_PACKAGE_FLAVOR),
        projectFilesystem,
        androidPlatformTarget,
        ruleFinder,
        graphBuilder,
        realManifest,
        ImmutableList.of(),
        filteredResourcesProvider,
        getTargetsAsResourceDeps(resourceDetails.getResourcesWithNonEmptyResDir()),
        skipCrunchPngs,
        includesVectorDrawables,
        manifestEntries);
  }

  private PackageStringAssets createPackageStringAssets(
      ImmutableSortedSet<BuildRule> resourceRules,
      ImmutableCollection<BuildRule> rulesWithResourceDirectories,
      FilteredResourcesProvider filteredResourcesProvider,
      AaptOutputInfo aaptOutputInfo) {
    return new PackageStringAssets(
        buildTarget.withAppendedFlavors(
            AndroidBinaryResourcesGraphEnhancer.PACKAGE_STRING_ASSETS_FLAVOR),
        projectFilesystem,
        ruleFinder,
        resourceRules,
        rulesWithResourceDirectories,
        filteredResourcesProvider,
        locales,
        aaptOutputInfo.getPathToRDotTxt());
  }

  private MergeAssets createMergeAssetsRule(
      ImmutableCollection<SourcePath> assetsDirectories, Optional<SourcePath> baseApk) {
    MergeAssets mergeAssets =
        new MergeAssets(
            buildTarget.withAppendedFlavors(MERGE_ASSETS_FLAVOR),
            projectFilesystem,
            ruleFinder,
            baseApk,
            ImmutableSortedSet.copyOf(assetsDirectories));
    graphBuilder.addToIndex(mergeAssets);
    return mergeAssets;
  }

  private ImmutableSortedSet<BuildRule> getTargetsAsRules(Collection<BuildTarget> buildTargets) {
    return BuildRules.toBuildRulesFor(originalBuildTarget, graphBuilder, buildTargets);
  }

  private ImmutableList<HasAndroidResourceDeps> getTargetsAsResourceDeps(
      Collection<BuildTarget> targets) {
    return getTargetsAsRules(targets)
        .stream()
        .map(
            input -> {
              Preconditions.checkState(input instanceof HasAndroidResourceDeps);
              return (HasAndroidResourceDeps) input;
            })
        .collect(ImmutableList.toImmutableList());
  }
}
