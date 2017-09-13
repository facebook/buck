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

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.packageable.AndroidPackageableCollection;
import com.facebook.buck.android.redex.ReDexStep;
import com.facebook.buck.android.redex.RedexOptions;
import com.facebook.buck.android.resources.ResourcesZipBuilder;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.AccumulateClassNamesStep;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.AbstractGenruleStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.XzStep;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.zip.RepackZipEntriesStep;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.hash.HashCode;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

class AndroidBinaryBuildable implements AddsToRuleKey {
  /**
   * The filename of the solidly compressed libraries if compressAssetLibraries is set to true. This
   * file can be found in assets/lib.
   */
  private static final String SOLID_COMPRESSED_ASSET_LIBRARY_FILENAME = "libs.xzs";

  /**
   * This is the path from the root of the APK that should contain the metadata.txt and
   * secondary-N.dex.jar files for secondary dexes.
   */
  static final String SMART_DEX_SECONDARY_DEX_SUBDIR =
      "assets/smart-dex-secondary-program-dex-jars";

  @AddToRuleKey private final SourcePath keystorePath;
  @AddToRuleKey private final SourcePath keystorePropertiesPath;
  @AddToRuleKey private final DexSplitMode dexSplitMode;
  @AddToRuleKey private final ProGuardObfuscateStep.SdkProguardType sdkProguardConfig;
  @AddToRuleKey private final Optional<Integer> optimizationPasses;
  @AddToRuleKey private final Optional<SourcePath> proguardConfig;
  @AddToRuleKey private final Optional<SourcePath> proguardJarOverride;
  @AddToRuleKey private final Optional<RedexOptions> redexOptions;
  @AddToRuleKey private final String proguardMaxHeapSize;
  @AddToRuleKey private final Optional<List<String>> proguardJvmArgs;
  @AddToRuleKey private final Optional<String> proguardAgentPath;
  @AddToRuleKey private final ImmutableSet<NdkCxxPlatforms.TargetCpuType> cpuFilters;
  @AddToRuleKey private final EnumSet<AndroidBinary.ExopackageMode> exopackageModes;
  @AddToRuleKey private final Optional<Arg> preprocessJavaClassesBash;
  @AddToRuleKey private final boolean reorderClassesIntraDex;
  @AddToRuleKey private final Optional<SourcePath> dexReorderToolFile;
  @AddToRuleKey private final Optional<SourcePath> dexReorderDataDumpFile;
  @AddToRuleKey private final Optional<Integer> xzCompressionLevel;
  @AddToRuleKey private final boolean packageAssetLibraries;
  @AddToRuleKey private final boolean compressAssetLibraries;
  @AddToRuleKey private final boolean skipProguard;
  @AddToRuleKey private final Tool javaRuntimeLauncher;
  @AddToRuleKey private final SourcePath androidManifestPath;
  @AddToRuleKey private final SourcePath resourcesApkPath;
  @AddToRuleKey private final ImmutableList<SourcePath> primaryApkAssetsZips;
  @AddToRuleKey private final SourcePath aaptGeneratedProguardConfigFile;
  @AddToRuleKey private final Optional<String> dxMaxHeapSize;
  @AddToRuleKey private final ImmutableList<SourcePath> proguardConfigs;
  @AddToRuleKey private final boolean isCompressResources;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> additionalJarsForProguard;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> pathsToThirdPartyJars;
  @AddToRuleKey private final boolean hasLinkableAssets;
  @AddToRuleKey private final ImmutableSortedSet<APKModule> apkModules;
  @AddToRuleKey private final boolean isPreDexed;
  @AddToRuleKey private final Optional<SourcePath> predexedPrimaryDexPath;
  @AddToRuleKey private final Optional<ImmutableSortedSet<SourcePath>> predexedSecondaryDirectories;
  @AddToRuleKey private final APKModule rootAPKModule;
  @AddToRuleKey private Optional<ImmutableSortedMap<APKModule, SourcePath>> nativeLibsDirs;
  // TODO(cjhopman): why is this derived differently than nativeLibAssetsDirectories?
  @AddToRuleKey private Optional<ImmutableSortedMap<APKModule, SourcePath>> nativeLibsAssetsDirs;

  @AddToRuleKey private final Optional<ImmutableSet<SourcePath>> classpathEntriesToDexSourcePaths;

  @AddToRuleKey
  private final Optional<ImmutableSortedMap<APKModule, ImmutableList<SourcePath>>>
      moduleMappedClasspathEntriesToDex;

  @AddToRuleKey
  private final ImmutableSortedMap<APKModule, ImmutableList<SourcePath>> nativeLibAssetsDirectories;

  @AddToRuleKey
  private final ImmutableSortedMap<APKModule, ImmutableSortedSet<APKModule>> apkModuleMap;

  @AddToRuleKey private final Optional<SourcePath> appModularityResult;

  @AddToRuleKey
  private final Optional<ImmutableSortedMap<APKModule, ImmutableList<SourcePath>>>
      moduleMappedClasspathEntriesForConsistency;

  // These should be the only things not added to the rulekey.
  private final ProjectFilesystem filesystem;
  private final BuildTarget buildTarget;
  private final ListeningExecutorService dxExecutorService;

  AndroidBinaryBuildable(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      SourcePath keystorePath,
      SourcePath keystorePropertiesPath,
      DexSplitMode dexSplitMode,
      ProGuardObfuscateStep.SdkProguardType sdkProguardConfig,
      Optional<Integer> optimizationPasses,
      Optional<SourcePath> proguardConfig,
      Optional<SourcePath> proguardJarOverride,
      Optional<RedexOptions> redexOptions,
      String proguardMaxHeapSize,
      Optional<List<String>> proguardJvmArgs,
      Optional<String> proguardAgentPath,
      ImmutableSet<NdkCxxPlatforms.TargetCpuType> cpuFilters,
      EnumSet<AndroidBinary.ExopackageMode> exopackageModes,
      Optional<Arg> preprocessJavaClassesBash,
      boolean reorderClassesIntraDex,
      Optional<SourcePath> dexReorderToolFile,
      Optional<SourcePath> dexReorderDataDumpFile,
      ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex,
      AndroidGraphEnhancementResult enhancementResult,
      ListeningExecutorService dxExecutorService,
      Optional<Integer> xzCompressionLevel,
      boolean packageAssetLibraries,
      boolean compressAssetLibraries,
      boolean skipProguard,
      Tool javaRuntimeLauncher,
      SourcePath androidManifestPath,
      SourcePath resourcesApkPath,
      ImmutableList<SourcePath> primaryApkAssetsZips,
      SourcePath aaptGeneratedProguardConfigFile,
      Optional<String> dxMaxHeapSize,
      ImmutableList<SourcePath> proguardConfigs,
      boolean isCompressResources,
      Optional<SourcePath> appModularityResult) {
    this.filesystem = filesystem;
    this.buildTarget = buildTarget;

    this.keystorePath = keystorePath;
    this.keystorePropertiesPath = keystorePropertiesPath;
    this.dexSplitMode = dexSplitMode;
    this.sdkProguardConfig = sdkProguardConfig;
    this.optimizationPasses = optimizationPasses;
    this.proguardConfig = proguardConfig;
    this.proguardJarOverride = proguardJarOverride;
    this.redexOptions = redexOptions;
    this.proguardMaxHeapSize = proguardMaxHeapSize;
    this.proguardJvmArgs = proguardJvmArgs;
    this.proguardAgentPath = proguardAgentPath;
    this.cpuFilters = cpuFilters;
    this.exopackageModes = exopackageModes;
    this.preprocessJavaClassesBash = preprocessJavaClassesBash;
    this.reorderClassesIntraDex = reorderClassesIntraDex;
    this.dexReorderToolFile = dexReorderToolFile;
    this.dexReorderDataDumpFile = dexReorderDataDumpFile;
    this.dxExecutorService = dxExecutorService;
    this.xzCompressionLevel = xzCompressionLevel;
    this.packageAssetLibraries = packageAssetLibraries;
    this.compressAssetLibraries = compressAssetLibraries;
    this.skipProguard = skipProguard;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.androidManifestPath = androidManifestPath;
    this.resourcesApkPath = resourcesApkPath;
    this.primaryApkAssetsZips = primaryApkAssetsZips;
    this.aaptGeneratedProguardConfigFile = aaptGeneratedProguardConfigFile;
    this.dxMaxHeapSize = dxMaxHeapSize;
    this.proguardConfigs = proguardConfigs;
    this.isCompressResources = isCompressResources;
    this.additionalJarsForProguard =
        rulesToExcludeFromDex
            .stream()
            .flatMap((javaLibrary) -> javaLibrary.getImmediateClasspaths().stream())
            .collect(MoreCollectors.toImmutableSortedSet());
    AndroidPackageableCollection packageableCollection =
        enhancementResult.getPackageableCollection();

    this.hasLinkableAssets = packageableCollection.getNativeLinkablesAssets().isEmpty();
    this.pathsToThirdPartyJars =
        ImmutableSortedSet.copyOf(packageableCollection.getPathsToThirdPartyJars());
    if (AndroidBinary.ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      this.predexedSecondaryDirectories = Optional.empty();
    } else {
      this.predexedSecondaryDirectories =
          enhancementResult.getPreDexMerge().map(PreDexMerge::getSecondaryDexSourcePaths);
    }

    APKModuleGraph apkModuleGraph = enhancementResult.getAPKModuleGraph();
    this.nativeLibAssetsDirectories =
        convertToMapOfLists(packageableCollection.getNativeLibAssetsDirectories());
    this.apkModules =
        ImmutableSortedSet.copyOf(enhancementResult.getAPKModuleGraph().getAPKModules());
    this.apkModuleMap = apkModuleGraph.toOutgoingEdgesMap();
    this.rootAPKModule = apkModuleGraph.getRootAPKModule();

    this.isPreDexed = enhancementResult.getPreDexMerge().isPresent();

    Optional<ImmutableMap<APKModule, CopyNativeLibraries>> copyNativeLibraries =
        enhancementResult.getCopyNativeLibraries();

    boolean exopackageForNativeEnabled =
        AndroidBinary.ExopackageMode.enabledForNativeLibraries(exopackageModes);
    if (exopackageForNativeEnabled) {
      this.nativeLibsDirs = Optional.empty();
    } else {
      this.nativeLibsDirs =
          copyNativeLibraries.map(
              cnl ->
                  cnl.entrySet()
                      .stream()
                      .collect(
                          MoreCollectors.toImmutableSortedMap(
                              e -> e.getKey(), e -> e.getValue().getSourcePathToNativeLibsDir())));
    }

    this.nativeLibsAssetsDirs =
        copyNativeLibraries.map(
            cnl ->
                cnl.entrySet()
                    .stream()
                    .filter(
                        entry ->
                            !exopackageForNativeEnabled
                                || packageAssetLibraries
                                || !entry.getKey().isRootModule())
                    .collect(
                        MoreCollectors.toImmutableSortedMap(
                            e -> e.getKey(),
                            e -> e.getValue().getSourcePathToNativeLibsAssetsDir())));

    this.predexedPrimaryDexPath =
        enhancementResult.getPreDexMerge().map(PreDexMerge::getSourcePathToPrimaryDex);
    if (isPreDexed) {
      Preconditions.checkState(!preprocessJavaClassesBash.isPresent());
      this.classpathEntriesToDexSourcePaths = Optional.empty();
      this.moduleMappedClasspathEntriesToDex = Optional.empty();
    } else {
      this.classpathEntriesToDexSourcePaths =
          Optional.of(
              RichStream.from(enhancementResult.getClasspathEntriesToDex())
                  .concat(
                      RichStream.of(
                          enhancementResult.getCompiledUberRDotJava().getSourcePathToOutput()))
                  .collect(MoreCollectors.toImmutableSet()));
      this.moduleMappedClasspathEntriesToDex =
          Optional.of(
              convertToMapOfLists(packageableCollection.getModuleMappedClasspathEntriesToDex()));
    }
    this.appModularityResult = appModularityResult;
    if (appModularityResult.isPresent()) {
      this.moduleMappedClasspathEntriesForConsistency =
          Optional.of(
              convertToMapOfLists(packageableCollection.getModuleMappedClasspathEntriesToDex()));
    } else {
      this.moduleMappedClasspathEntriesForConsistency = Optional.empty();
    }
  }

  private <K extends Comparable<?>, V> ImmutableSortedMap<K, ImmutableList<V>> convertToMapOfLists(
      ImmutableMultimap<K, V> multimap) {
    return multimap
        .asMap()
        .entrySet()
        .stream()
        .collect(
            MoreCollectors.toImmutableSortedMap(
                e -> e.getKey(), e -> ImmutableList.copyOf(e.getValue())));
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolver pathResolver = context.getSourcePathResolver();
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // The `HasInstallableApk` interface needs access to the manifest, so make sure we create our
    // own copy of this so that we don't have a runtime dep on the `AaptPackageResources` step.
    Path manifestPath = getManifestPath();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), manifestPath.getParent())));
    steps.add(
        CopyStep.forFile(
            getProjectFilesystem(),
            pathResolver.getRelativePath(androidManifestPath),
            manifestPath));
    buildableContext.recordArtifact(manifestPath);

    // Create the .dex files if we aren't doing pre-dexing.
    DexFilesInfo dexFilesInfo = addFinalDxSteps(buildableContext, context, steps);

    ////
    // BE VERY CAREFUL adding any code below here.
    // Any inputs to apkbuilder must be reflected in the hash returned by getAbiKeyForDeps.
    ////

    ImmutableSet.Builder<Path> nativeLibraryDirectoriesBuilder = ImmutableSet.builder();
    // Copy the transitive closure of native-libs-as-assets to a single directory, if any.
    ImmutableSet.Builder<Path> nativeLibraryAsAssetDirectories = ImmutableSet.builder();

    for (final APKModule module : apkModules) {
      boolean shouldPackageAssetLibraries = packageAssetLibraries || !module.isRootModule();
      if (!AndroidBinary.ExopackageMode.enabledForNativeLibraries(exopackageModes)
          && nativeLibsDirs.isPresent()
          && nativeLibsDirs.get().containsKey(module)) {
        if (shouldPackageAssetLibraries) {
          nativeLibraryDirectoriesBuilder.add(
              pathResolver.getRelativePath(nativeLibsDirs.get().get(module)));
        } else {
          nativeLibraryDirectoriesBuilder.add(
              pathResolver.getRelativePath(nativeLibsDirs.get().get(module)));
          nativeLibraryDirectoriesBuilder.add(
              pathResolver.getRelativePath(nativeLibsAssetsDirs.get().get(module)));
        }
      }

      if ((!nativeLibAssetsDirectories.isEmpty())
          || (!hasLinkableAssets && shouldPackageAssetLibraries)) {
        Preconditions.checkState(
            !AndroidBinary.ExopackageMode.enabledForResources(exopackageModes));
        Path pathForNativeLibsAsAssets = getPathForNativeLibsAsAssets();

        final Path libSubdirectory =
            pathForNativeLibsAsAssets
                .resolve("assets")
                .resolve(module.isRootModule() ? "lib" : module.getName());
        ImmutableCollection<SourcePath> nativeLibDirs = nativeLibAssetsDirectories.get(module);

        getStepsForNativeAssets(
            context,
            steps,
            nativeLibDirs == null ? Optional.empty() : Optional.of(nativeLibDirs),
            libSubdirectory,
            module.isRootModule() ? "metadata.txt" : "libs.txt",
            module);

        nativeLibraryAsAssetDirectories.add(pathForNativeLibsAsAssets);
      }
    }

    // If non-english strings are to be stored as assets, pass them to ApkBuilder.
    ImmutableSet.Builder<Path> zipFiles = ImmutableSet.builder();
    RichStream.from(primaryApkAssetsZips).map(pathResolver::getRelativePath).forEach(zipFiles::add);

    if (AndroidBinary.ExopackageMode.enabledForNativeLibraries(exopackageModes)) {
      // We need to include a few dummy native libraries with our application so that Android knows
      // to run it as 32-bit.  Android defaults to 64-bit when no libraries are provided at all,
      // causing us to fail to load our 32-bit exopackage native libraries later.
      String fakeNativeLibraryBundle = System.getProperty("buck.native_exopackage_fake_path");

      if (fakeNativeLibraryBundle == null) {
        throw new RuntimeException("fake native bundle not specified in properties");
      }

      zipFiles.add(Paths.get(fakeNativeLibraryBundle));
    }

    ImmutableSet<Path> allAssetDirectories =
        ImmutableSet.<Path>builder()
            .addAll(nativeLibraryAsAssetDirectories.build())
            .addAll(dexFilesInfo.secondaryDexDirs)
            .build();

    SourcePathResolver resolver = context.getSourcePathResolver();
    Path signedApkPath = getSignedApkPath();
    final Path pathToKeystore = resolver.getAbsolutePath(keystorePath);
    Supplier<KeystoreProperties> keystoreProperties =
        Suppliers.memoize(
            () -> {
              try {
                return KeystoreProperties.createFromPropertiesFile(
                    pathToKeystore,
                    resolver.getAbsolutePath(keystorePropertiesPath),
                    getProjectFilesystem());
              } catch (IOException e) {
                throw new RuntimeException();
              }
            });

    ImmutableSet<Path> thirdPartyJars =
        pathsToThirdPartyJars
            .stream()
            .map(resolver::getAbsolutePath)
            .collect(MoreCollectors.toImmutableSet());

    if (AndroidBinary.ExopackageMode.enabledForResources(exopackageModes)) {
      steps.add(createMergedThirdPartyJarsStep(thirdPartyJars));
      buildableContext.recordArtifact(getMergedThirdPartyJarsPath());
    }

    ApkBuilderStep apkBuilderCommand =
        new ApkBuilderStep(
            getProjectFilesystem(),
            pathResolver.getAbsolutePath(resourcesApkPath),
            getSignedApkPath(),
            dexFilesInfo.primaryDexPath,
            allAssetDirectories,
            nativeLibraryDirectoriesBuilder.build(),
            zipFiles.build(),
            thirdPartyJars,
            pathToKeystore,
            keystoreProperties,
            /* debugMode */ false,
            javaRuntimeLauncher.getCommandPrefix(resolver));
    steps.add(apkBuilderCommand);

    // The `ApkBuilderStep` delegates to android tools to build a ZIP with timestamps in it, making
    // the output non-deterministic.  So use an additional scrubbing step to zero these out.
    steps.add(ZipScrubberStep.of(getProjectFilesystem().resolve(signedApkPath)));

    Path apkToRedexAndAlign;
    // Optionally, compress the resources file in the .apk.
    if (isCompressResources) {
      Path compressedApkPath = getCompressedResourcesApkPath();
      apkToRedexAndAlign = compressedApkPath;
      RepackZipEntriesStep arscComp =
          new RepackZipEntriesStep(
              getProjectFilesystem(),
              signedApkPath,
              compressedApkPath,
              ImmutableSet.of("resources.arsc"));
      steps.add(arscComp);
    } else {
      apkToRedexAndAlign = signedApkPath;
    }

    boolean applyRedex = redexOptions.isPresent();
    Path apkPath = getFinalApkPath();
    Path apkToAlign = apkToRedexAndAlign;

    // redex
    if (applyRedex) {
      Path proguardConfigDir = getProguardTextFilesPath();
      Path redexedApk = getRedexedApkPath();
      apkToAlign = redexedApk;
      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), redexedApk.getParent())));
      ImmutableList<Step> redexSteps =
          ReDexStep.createSteps(
              getProjectFilesystem(),
              resolver,
              redexOptions.get(),
              apkToRedexAndAlign,
              redexedApk,
              keystoreProperties,
              proguardConfigDir,
              buildableContext);
      steps.addAll(redexSteps);
    }

    steps.add(new ZipalignStep(getProjectFilesystem().getRootPath(), apkToAlign, apkPath));

    buildableContext.recordArtifact(apkPath);
    return steps.build();
  }

  private Step createMergedThirdPartyJarsStep(ImmutableSet<Path> thirdPartyJars) {
    return new AbstractExecutionStep("merging_third_party_jar_resources") {
      @Override
      public StepExecutionResult execute(ExecutionContext context)
          throws IOException, InterruptedException {
        try (ResourcesZipBuilder builder =
            new ResourcesZipBuilder(
                getProjectFilesystem().resolve(getMergedThirdPartyJarsPath()))) {
          for (Path jar : thirdPartyJars) {
            try (ZipFile base = new ZipFile(jar.toFile())) {
              for (ZipEntry inputEntry : Collections.list(base.entries())) {
                if (inputEntry.isDirectory()) {
                  continue;
                }
                String name = inputEntry.getName();
                String ext = Files.getFileExtension(name);
                String filename = Paths.get(name).getFileName().toString();
                // Android's ApkBuilder filters out a lot of files from Java resources. Try to
                // match its behavior.
                // See https://android.googlesource.com/platform/sdk/+/jb-release/sdkmanager/libs/sdklib/src/com/android/sdklib/build/ApkBuilder.java
                if (name.startsWith(".")
                    || name.endsWith("~")
                    || name.startsWith("META-INF")
                    || "aidl".equalsIgnoreCase(ext)
                    || "rs".equalsIgnoreCase(ext)
                    || "rsh".equalsIgnoreCase(ext)
                    || "d".equalsIgnoreCase(ext)
                    || "java".equalsIgnoreCase(ext)
                    || "scala".equalsIgnoreCase(ext)
                    || "class".equalsIgnoreCase(ext)
                    || "scc".equalsIgnoreCase(ext)
                    || "swp".equalsIgnoreCase(ext)
                    || "thumbs.db".equalsIgnoreCase(filename)
                    || "picasa.ini".equalsIgnoreCase(filename)
                    || "package.html".equalsIgnoreCase(filename)
                    || "overview.html".equalsIgnoreCase(filename)) {
                  continue;
                }
                try (InputStream inputStream = base.getInputStream(inputEntry)) {
                  builder.addEntry(
                      inputStream,
                      inputEntry.getSize(),
                      inputEntry.getCrc(),
                      name,
                      Deflater.NO_COMPRESSION,
                      false);
                }
              }
            }
          }
        }
        return StepExecutionResult.SUCCESS;
      }
    };
  }

  private void getStepsForNativeAssets(
      BuildContext context,
      ImmutableList.Builder<Step> steps,
      Optional<ImmutableCollection<SourcePath>> nativeLibDirs,
      final Path libSubdirectory,
      final String metadataFilename,
      final APKModule module) {

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), libSubdirectory)));

    // Filter, rename and copy the ndk libraries marked as assets.
    if (nativeLibDirs.isPresent()) {
      for (SourcePath nativeLibDir : nativeLibDirs.get()) {
        CopyNativeLibraries.copyNativeLibrary(
            context,
            getProjectFilesystem(),
            context.getSourcePathResolver().getAbsolutePath(nativeLibDir),
            libSubdirectory,
            cpuFilters,
            steps);
      }
    }

    // Input asset libraries are sorted in descending filesize order.
    final ImmutableSortedSet.Builder<Path> inputAssetLibrariesBuilder =
        ImmutableSortedSet.orderedBy(
            (libPath1, libPath2) -> {
              try {
                ProjectFilesystem filesystem = getProjectFilesystem();
                int filesizeResult =
                    -Long.compare(
                        filesystem.getFileSize(libPath1), filesystem.getFileSize(libPath2));
                int pathnameResult = libPath1.compareTo(libPath2);
                return filesizeResult != 0 ? filesizeResult : pathnameResult;
              } catch (IOException e) {
                return 0;
              }
            });

    if (packageAssetLibraries || !module.isRootModule()) {
      // TODO(cjhopman): Why is this packaging native libs as assets even when native exopackage is
      // enabled?
      if (nativeLibsAssetsDirs.isPresent() && nativeLibsAssetsDirs.get().containsKey(module)) {
        // Copy in cxx libraries marked as assets. Filtering and renaming was already done
        // in CopyNativeLibraries.getBuildSteps().
        Path cxxNativeLibsSrc =
            context.getSourcePathResolver().getRelativePath(nativeLibsAssetsDirs.get().get(module));
        steps.add(
            CopyStep.forDirectory(
                getProjectFilesystem(),
                cxxNativeLibsSrc,
                libSubdirectory,
                CopyStep.DirectoryMode.CONTENTS_ONLY));
      }

      steps.add(
          // Step that populates a list of libraries and writes a metadata.txt to decompress.
          new AbstractExecutionStep("write_metadata_for_asset_libraries_" + module.getName()) {
            @Override
            public StepExecutionResult execute(ExecutionContext context)
                throws IOException, InterruptedException {
              ProjectFilesystem filesystem = getProjectFilesystem();
              // Walk file tree to find libraries
              filesystem.walkRelativeFileTree(
                  libSubdirectory,
                  new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                      if (!file.toString().endsWith(".so")) {
                        throw new IOException("unexpected file in lib directory");
                      }
                      inputAssetLibrariesBuilder.add(file);
                      return FileVisitResult.CONTINUE;
                    }
                  });

              // Write a metadata
              ImmutableList.Builder<String> metadataLines = ImmutableList.builder();
              Path metadataOutput = libSubdirectory.resolve(metadataFilename);
              for (Path libPath : inputAssetLibrariesBuilder.build()) {
                // Should return something like x86/libfoo.so
                Path relativeLibPath = libSubdirectory.relativize(libPath);
                long filesize = filesystem.getFileSize(libPath);
                String desiredOutput = relativeLibPath.toString();
                String checksum = filesystem.computeSha256(libPath);
                metadataLines.add(desiredOutput + ' ' + filesize + ' ' + checksum);
              }
              ImmutableList<String> metadata = metadataLines.build();
              if (!metadata.isEmpty()) {
                filesystem.writeLinesToPath(metadata, metadataOutput);
              }
              return StepExecutionResult.SUCCESS;
            }
          });
    }
    if (compressAssetLibraries || !module.isRootModule()) {
      final ImmutableList.Builder<Path> outputAssetLibrariesBuilder = ImmutableList.builder();
      steps.add(
          new AbstractExecutionStep("rename_asset_libraries_as_temp_files_" + module.getName()) {
            @Override
            public StepExecutionResult execute(ExecutionContext context)
                throws IOException, InterruptedException {
              ProjectFilesystem filesystem = getProjectFilesystem();
              for (Path libPath : inputAssetLibrariesBuilder.build()) {
                Path tempPath = libPath.resolveSibling(libPath.getFileName() + "~");
                filesystem.move(libPath, tempPath);
                outputAssetLibrariesBuilder.add(tempPath);
              }
              return StepExecutionResult.SUCCESS;
            }
          });
      // Concat and xz compress.
      Path libOutputBlob = libSubdirectory.resolve("libraries.blob");
      steps.add(new ConcatStep(getProjectFilesystem(), outputAssetLibrariesBuilder, libOutputBlob));
      int compressionLevel = xzCompressionLevel.orElse(XzStep.DEFAULT_COMPRESSION_LEVEL).intValue();
      steps.add(
          new XzStep(
              getProjectFilesystem(),
              libOutputBlob,
              libSubdirectory.resolve(SOLID_COMPRESSED_ASSET_LIBRARY_FILENAME),
              compressionLevel));
    }
  }

  /** Adds steps to do the final dexing or dex merging before building the apk. */
  private DexFilesInfo addFinalDxSteps(
      BuildableContext buildableContext,
      BuildContext buildContext,
      ImmutableList.Builder<Step> steps) {

    Optional<Path> proguardFullConfigFile = Optional.empty();
    Optional<Path> proguardMappingFile = Optional.empty();
    if (shouldProguard()) {
      Path proguardConfigDir = getProguardTextFilesPath();
      proguardFullConfigFile = Optional.of(proguardConfigDir.resolve("configuration.txt"));
      proguardMappingFile = Optional.of(proguardConfigDir.resolve("mapping.txt"));
    }

    if (appModularityResult.isPresent()) {
      ImmutableMultimap<APKModule, Path> additionalDexStoreToJarPathMap =
          moduleMappedClasspathEntriesForConsistency
              .get()
              .entrySet()
              .stream()
              .flatMap(
                  entry ->
                      entry
                          .getValue()
                          .stream()
                          .map(
                              v ->
                                  new AbstractMap.SimpleEntry<>(
                                      entry.getKey(),
                                      buildContext.getSourcePathResolver().getAbsolutePath(v))))
              .collect(MoreCollectors.toImmutableMultimap(e -> e.getKey(), e -> e.getValue()));

      steps.add(
          AndroidModuleConsistencyStep.ensureModuleConsistency(
              buildContext.getSourcePathResolver().getRelativePath(appModularityResult.get()),
              additionalDexStoreToJarPathMap,
              filesystem,
              proguardFullConfigFile,
              proguardMappingFile,
              skipProguard));
    }

    if (isPreDexed) {
      ImmutableSortedSet<Path> secondaryDexDirs;
      if (AndroidBinary.ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
        secondaryDexDirs = ImmutableSortedSet.of();
      } else {
        secondaryDexDirs =
            predexedSecondaryDirectories
                .get()
                .stream()
                .map(buildContext.getSourcePathResolver()::getRelativePath)
                .collect(MoreCollectors.toImmutableSortedSet());
      }
      return new DexFilesInfo(
          buildContext.getSourcePathResolver().getRelativePath(predexedPrimaryDexPath.get()),
          secondaryDexDirs);
    }

    ImmutableSet<Path> classpathEntriesToDex =
        classpathEntriesToDexSourcePaths
            .get()
            .stream()
            .map(
                input ->
                    getProjectFilesystem()
                        .relativize(buildContext.getSourcePathResolver().getAbsolutePath(input)))
            .collect(MoreCollectors.toImmutableSet());

    ImmutableMultimap<APKModule, Path> additionalDexStoreToJarPathMap =
        moduleMappedClasspathEntriesToDex
            .get()
            .entrySet()
            .stream()
            .flatMap(
                entry ->
                    entry
                        .getValue()
                        .stream()
                        .map(
                            v ->
                                new AbstractMap.SimpleEntry<>(
                                    entry.getKey(),
                                    buildContext.getSourcePathResolver().getAbsolutePath(v))))
            .collect(MoreCollectors.toImmutableMultimap(e -> e.getKey(), e -> e.getValue()));

    // Execute preprocess_java_classes_binary, if appropriate.
    if (preprocessJavaClassesBash.isPresent()) {
      // Symlink everything in dexTransitiveDependencies.classpathEntriesToDex to the input
      // directory.
      Path preprocessJavaClassesInDir = getBinPath("java_classes_preprocess_in_%s");
      Path preprocessJavaClassesOutDir = getBinPath("java_classes_preprocess_out_%s");
      Path ESCAPED_PARENT = getProjectFilesystem().getPath("_.._");

      ImmutableList.Builder<Pair<Path, Path>> pathToTargetBuilder = ImmutableList.builder();
      ImmutableSet.Builder<Path> outDirPaths = ImmutableSet.builder();
      for (Path entry : classpathEntriesToDex) {
        // The entries are relative to the current cell root, and may contain '..' to
        // reference entries in other roots. To construct the path in InDir, escape '..'
        // with a normal directory name, so that the path does not escape InDir.
        Path relPath =
            RichStream.from(entry)
                .map(fragment -> fragment.toString().equals("..") ? ESCAPED_PARENT : fragment)
                .reduce(Path::resolve)
                .orElse(getProjectFilesystem().getPath(""));
        pathToTargetBuilder.add(new Pair<>(preprocessJavaClassesInDir.resolve(relPath), entry));
        outDirPaths.add(preprocessJavaClassesOutDir.resolve(relPath));
      }
      // cell relative path of where the symlink should go, to where the symlink should map to.
      ImmutableList<Pair<Path, Path>> pathToTarget = pathToTargetBuilder.build();
      // Expect parallel outputs in the output directory and update classpathEntriesToDex
      // to reflect that.
      classpathEntriesToDex = outDirPaths.build();

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  preprocessJavaClassesInDir)));

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  preprocessJavaClassesOutDir)));

      steps.add(
          new AbstractExecutionStep("symlinking for preprocessing") {
            @Override
            public StepExecutionResult execute(ExecutionContext context)
                throws IOException, InterruptedException {
              for (Pair<Path, Path> entry : pathToTarget) {
                Path symlinkPath = getProjectFilesystem().resolve(entry.getFirst());
                Path symlinkTarget = getProjectFilesystem().resolve(entry.getSecond());
                java.nio.file.Files.createDirectories(symlinkPath.getParent());
                java.nio.file.Files.createSymbolicLink(symlinkPath, symlinkTarget);
              }
              return StepExecutionResult.SUCCESS;
            }
          });

      AbstractGenruleStep.CommandString commandString =
          new AbstractGenruleStep.CommandString(
              /* cmd */ Optional.empty(),
              /* bash */ Arg.flattenToSpaceSeparatedString(
                  preprocessJavaClassesBash, buildContext.getSourcePathResolver()),
              /* cmdExe */ Optional.empty());
      steps.add(
          new AbstractGenruleStep(
              getProjectFilesystem(),
              this.getBuildTarget(),
              commandString,
              getProjectFilesystem().getRootPath().resolve(preprocessJavaClassesInDir)) {

            @Override
            protected void addEnvironmentVariables(
                ExecutionContext context,
                ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
              environmentVariablesBuilder.put(
                  "IN_JARS_DIR",
                  getProjectFilesystem().resolve(preprocessJavaClassesInDir).toString());
              environmentVariablesBuilder.put(
                  "OUT_JARS_DIR",
                  getProjectFilesystem().resolve(preprocessJavaClassesOutDir).toString());

              AndroidPlatformTarget platformTarget = context.getAndroidPlatformTarget();
              String bootclasspath =
                  Joiner.on(':')
                      .join(
                          Iterables.transform(
                              platformTarget.getBootclasspathEntries(),
                              getProjectFilesystem()::resolve));

              environmentVariablesBuilder.put("ANDROID_BOOTCLASSPATH", bootclasspath);
            }
          });
    }

    // Execute proguard if desired (transforms input classpaths).
    if (shouldProguard()) {
      classpathEntriesToDex =
          addProguardCommands(
              classpathEntriesToDex,
              proguardConfigs
                  .stream()
                  .map(buildContext.getSourcePathResolver()::getAbsolutePath)
                  .collect(MoreCollectors.toImmutableSet()),
              skipProguard,
              steps,
              buildableContext,
              buildContext);
    }

    // Create the final DEX (or set of DEX files in the case of split dex).
    // The APK building command needs to take a directory of raw files, so primaryDexPath
    // can only contain .dex files from this build rule.

    // Create dex artifacts. If split-dex is used, the assets/ directory should contain entries
    // that look something like the following:
    //
    // assets/secondary-program-dex-jars/metadata.txt
    // assets/secondary-program-dex-jars/secondary-1.dex.jar
    // assets/secondary-program-dex-jars/secondary-2.dex.jar
    // assets/secondary-program-dex-jars/secondary-3.dex.jar
    //
    // The contents of the metadata.txt file should look like:
    // secondary-1.dex.jar fffe66877038db3af2cbd0fe2d9231ed5912e317 secondary.dex01.Canary
    // secondary-2.dex.jar b218a3ea56c530fed6501d9f9ed918d1210cc658 secondary.dex02.Canary
    // secondary-3.dex.jar 40f11878a8f7a278a3f12401c643da0d4a135e1a secondary.dex03.Canary
    //
    // The scratch directories that contain the metadata.txt and secondary-N.dex.jar files must be
    // listed in secondaryDexDirectoriesBuilder so that their contents will be compressed
    // appropriately for Froyo.
    ImmutableSet.Builder<Path> secondaryDexDirectoriesBuilder = ImmutableSet.builder();
    Supplier<ImmutableMap<String, HashCode>> classNamesToHashesSupplier =
        addAccumulateClassNamesStep(classpathEntriesToDex, steps);

    Path primaryDexPath = getNonPredexedPrimaryDexPath();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(),
                getProjectFilesystem(),
                primaryDexPath.getParent())));

    addDexingSteps(
        classpathEntriesToDex,
        classNamesToHashesSupplier,
        secondaryDexDirectoriesBuilder,
        steps,
        primaryDexPath,
        dexReorderToolFile,
        dexReorderDataDumpFile,
        additionalDexStoreToJarPathMap,
        buildContext);

    return new DexFilesInfo(primaryDexPath, secondaryDexDirectoriesBuilder.build());
  }

  public Supplier<ImmutableMap<String, HashCode>> addAccumulateClassNamesStep(
      final ImmutableSet<Path> classPathEntriesToDex, ImmutableList.Builder<Step> steps) {
    final ImmutableMap.Builder<String, HashCode> builder = ImmutableMap.builder();

    steps.add(
        new AbstractExecutionStep("collect_all_class_names") {
          @Override
          public StepExecutionResult execute(ExecutionContext context)
              throws IOException, InterruptedException {
            Map<String, Path> classesToSources = new HashMap<>();
            for (Path path : classPathEntriesToDex) {
              Optional<ImmutableSortedMap<String, HashCode>> hashes =
                  AccumulateClassNamesStep.calculateClassHashes(
                      context, getProjectFilesystem(), path);
              if (!hashes.isPresent()) {
                return StepExecutionResult.ERROR;
              }
              builder.putAll(hashes.get());

              for (String className : hashes.get().keySet()) {
                if (classesToSources.containsKey(className)) {
                  throw new IllegalArgumentException(
                      String.format(
                          "Duplicate class: %s was found in both %s and %s.",
                          className, classesToSources.get(className), path));
                }
                classesToSources.put(className, path);
              }
            }
            return StepExecutionResult.SUCCESS;
          }
        });

    return Suppliers.memoize(builder::build);
  }

  /** @return the resulting set of ProGuarded classpath entries to dex. */
  @VisibleForTesting
  ImmutableSet<Path> addProguardCommands(
      Set<Path> classpathEntriesToDex,
      Set<Path> depsProguardConfigs,
      boolean skipProguard,
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext,
      BuildContext buildContext) {
    // Create list of proguard Configs for the app project and its dependencies
    ImmutableSet.Builder<Path> proguardConfigsBuilder = ImmutableSet.builder();
    proguardConfigsBuilder.addAll(depsProguardConfigs);
    if (proguardConfig.isPresent()) {
      proguardConfigsBuilder.add(
          buildContext.getSourcePathResolver().getAbsolutePath(proguardConfig.get()));
    }

    Path proguardConfigDir = getProguardTextFilesPath();
    // Transform our input classpath to a set of output locations for each input classpath.
    // TODO(devjasta): the output path we choose is the result of a slicing function against
    // input classpath. This is fragile and should be replaced with knowledge of the BuildTarget.
    final ImmutableMap<Path, Path> inputOutputEntries =
        classpathEntriesToDex
            .stream()
            .collect(
                MoreCollectors.toImmutableMap(
                    java.util.function.Function.identity(),
                    (path) -> getProguardOutputFromInputClasspath(proguardConfigDir, path)));

    // Run ProGuard on the classpath entries.
    ProGuardObfuscateStep.create(
        javaRuntimeLauncher.getCommandPrefix(buildContext.getSourcePathResolver()),
        getProjectFilesystem(),
        proguardJarOverride.isPresent()
            ? Optional.of(
                buildContext.getSourcePathResolver().getAbsolutePath(proguardJarOverride.get()))
            : Optional.empty(),
        proguardMaxHeapSize,
        proguardAgentPath,
        buildContext.getSourcePathResolver().getRelativePath(aaptGeneratedProguardConfigFile),
        proguardConfigsBuilder.build(),
        sdkProguardConfig,
        optimizationPasses,
        proguardJvmArgs,
        inputOutputEntries,
        buildContext.getSourcePathResolver().getAllAbsolutePaths(additionalJarsForProguard),
        proguardConfigDir,
        buildableContext,
        buildContext,
        skipProguard,
        steps);

    // Apply the transformed inputs to the classpath (this will modify deps.classpathEntriesToDex
    // so that we're now dexing the proguarded artifacts). However, if we are not running
    // ProGuard then return the input classes to dex.
    if (skipProguard) {
      return ImmutableSet.copyOf(inputOutputEntries.keySet());
    } else {
      return ImmutableSet.copyOf(inputOutputEntries.values());
    }
  }

  /**
   * Create dex artifacts for all of the individual directories of compiled .class files (or the
   * obfuscated jar files if proguard is used). If split dex is used, multiple dex artifacts will be
   * produced.
   *
   * @param classpathEntriesToDex Full set of classpath entries that must make their way into the
   *     final APK structure (but not necessarily into the primary dex).
   * @param secondaryDexDirectories The contract for updating this builder must match that of {@link
   *     PreDexMerge#getSecondaryDexSourcePaths()}.
   * @param steps List of steps to add to.
   * @param primaryDexPath Output path for the primary dex file.
   */
  @VisibleForTesting
  void addDexingSteps(
      Set<Path> classpathEntriesToDex,
      Supplier<ImmutableMap<String, HashCode>> classNamesToHashesSupplier,
      ImmutableSet.Builder<Path> secondaryDexDirectories,
      ImmutableList.Builder<Step> steps,
      Path primaryDexPath,
      Optional<SourcePath> dexReorderToolFile,
      Optional<SourcePath> dexReorderDataDumpFile,
      ImmutableMultimap<APKModule, Path> additionalDexStoreToJarPathMap,
      BuildContext buildContext) {
    SourcePathResolver resolver = buildContext.getSourcePathResolver();
    final Supplier<Set<Path>> primaryInputsToDex;
    final Optional<Path> secondaryDexDir;
    final Optional<Supplier<Multimap<Path, Path>>> secondaryOutputToInputs;
    Path secondaryDexParentDir = getBinPath("__%s_secondary_dex__/");
    Path additionalDexParentDir = getBinPath("__%s_additional_dex__/");
    Path additionalDexAssetsDir = additionalDexParentDir.resolve("assets");
    final Optional<ImmutableSet<Path>> additionalDexDirs;

    if (shouldSplitDex()) {
      Optional<Path> proguardFullConfigFile = Optional.empty();
      Optional<Path> proguardMappingFile = Optional.empty();
      if (shouldProguard()) {
        Path proguardConfigDir = getProguardTextFilesPath();
        proguardFullConfigFile = Optional.of(proguardConfigDir.resolve("configuration.txt"));
        proguardMappingFile = Optional.of(proguardConfigDir.resolve("mapping.txt"));
      }
      // DexLibLoader expects that metadata.txt and secondary jar files are under this dir
      // in assets.

      // Intermediate directory holding the primary split-zip jar.
      Path splitZipDir = getBinPath("__%s_split_zip__");

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(), getProjectFilesystem(), splitZipDir)));
      Path primaryJarPath = splitZipDir.resolve("primary.jar");

      Path secondaryJarMetaDirParent = splitZipDir.resolve("secondary_meta");
      Path secondaryJarMetaDir =
          secondaryJarMetaDirParent.resolve(AndroidBinary.SECONDARY_DEX_SUBDIR);

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  secondaryJarMetaDir)));
      Path secondaryJarMeta = secondaryJarMetaDir.resolve("metadata.txt");

      // Intermediate directory holding _ONLY_ the secondary split-zip jar files.  This is
      // important because SmartDexingCommand will try to dx every entry in this directory.  It
      // does this because it's impossible to know what outputs split-zip will generate until it
      // runs.
      final Path secondaryZipDir = getBinPath("__%s_secondary_zip__");

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(), getProjectFilesystem(), secondaryZipDir)));

      // Intermediate directory holding the directories holding _ONLY_ the additional split-zip
      // jar files that are intended for that dex store.
      final Path additionalDexStoresZipDir = getBinPath("__%s_dex_stores_zip__");

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  additionalDexStoresZipDir)));
      for (APKModule dexStore : additionalDexStoreToJarPathMap.keySet()) {

        steps.addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    additionalDexStoresZipDir.resolve(dexStore.getName()))));

        steps.addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    secondaryJarMetaDirParent.resolve("assets").resolve(dexStore.getName()))));
      }

      // Run the split-zip command which is responsible for dividing the large set of input
      // classpaths into a more compact set of jar files such that no one jar file when dexed will
      // yield a dex artifact too large for dexopt or the dx method limit to handle.
      Path zipSplitReportDir = getBinPath("__%s_split_zip_report__");

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(), getProjectFilesystem(), zipSplitReportDir)));
      SplitZipStep splitZipCommand =
          new SplitZipStep(
              getProjectFilesystem(),
              classpathEntriesToDex,
              secondaryJarMeta,
              primaryJarPath,
              secondaryZipDir,
              "secondary-%d.jar",
              secondaryJarMetaDirParent,
              additionalDexStoresZipDir,
              proguardFullConfigFile,
              proguardMappingFile,
              skipProguard,
              dexSplitMode,
              dexSplitMode.getPrimaryDexScenarioFile().map(resolver::getAbsolutePath),
              dexSplitMode.getPrimaryDexClassesFile().map(resolver::getAbsolutePath),
              dexSplitMode.getSecondaryDexHeadClassesFile().map(resolver::getAbsolutePath),
              dexSplitMode.getSecondaryDexTailClassesFile().map(resolver::getAbsolutePath),
              additionalDexStoreToJarPathMap,
              apkModuleMap,
              rootAPKModule,
              zipSplitReportDir);
      steps.add(splitZipCommand);

      // Add the secondary dex directory that has yet to be created, but will be by the
      // smart dexing command.  Smart dex will handle "cleaning" this directory properly.
      if (reorderClassesIntraDex) {
        secondaryDexDir =
            Optional.of(secondaryDexParentDir.resolve(SMART_DEX_SECONDARY_DEX_SUBDIR));
        Path intraDexReorderSecondaryDexDir =
            secondaryDexParentDir.resolve(AndroidBinary.SECONDARY_DEX_SUBDIR);

        steps.addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    secondaryDexDir.get())));

        steps.addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    intraDexReorderSecondaryDexDir)));
      } else {
        secondaryDexDir =
            Optional.of(secondaryDexParentDir.resolve(AndroidBinary.SECONDARY_DEX_SUBDIR));
        steps.add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    secondaryDexDir.get())));
      }

      if (additionalDexStoreToJarPathMap.isEmpty()) {
        additionalDexDirs = Optional.empty();
      } else {
        ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
        for (APKModule dexStore : additionalDexStoreToJarPathMap.keySet()) {
          Path dexStorePath = additionalDexAssetsDir.resolve(dexStore.getName());
          builder.add(dexStorePath);

          steps.addAll(
              MakeCleanDirectoryStep.of(
                  BuildCellRelativePath.fromCellRelativePath(
                      buildContext.getBuildCellRootPath(), getProjectFilesystem(), dexStorePath)));
        }
        additionalDexDirs = Optional.of(builder.build());
      }

      if (dexSplitMode.getDexStore() == DexStore.RAW) {
        secondaryDexDirectories.add(secondaryDexDir.get());
      } else {
        secondaryDexDirectories.add(secondaryJarMetaDirParent);
        secondaryDexDirectories.add(secondaryDexParentDir);
      }
      if (additionalDexDirs.isPresent()) {
        secondaryDexDirectories.add(additionalDexParentDir);
      }

      // Adjust smart-dex inputs for the split-zip case.
      primaryInputsToDex = Suppliers.ofInstance(ImmutableSet.of(primaryJarPath));
      Supplier<Multimap<Path, Path>> secondaryOutputToInputsMap =
          splitZipCommand.getOutputToInputsMapSupplier(
              secondaryDexDir.get(), additionalDexAssetsDir);
      secondaryOutputToInputs = Optional.of(secondaryOutputToInputsMap);
    } else {
      // Simple case where our inputs are the natural classpath directories and we don't have
      // to worry about secondary jar/dex files.
      primaryInputsToDex = Suppliers.ofInstance(classpathEntriesToDex);
      secondaryDexDir = Optional.empty();
      secondaryOutputToInputs = Optional.empty();
    }

    HashInputJarsToDexStep hashInputJarsToDexStep =
        new HashInputJarsToDexStep(
            getProjectFilesystem(),
            primaryInputsToDex,
            secondaryOutputToInputs,
            classNamesToHashesSupplier);
    steps.add(hashInputJarsToDexStep);

    // Stores checksum information from each invocation to intelligently decide when dx needs
    // to be re-run.
    Path successDir = getBinPath("__%s_smart_dex__/.success");
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                buildContext.getBuildCellRootPath(), getProjectFilesystem(), successDir)));

    // Add the smart dexing tool that is capable of avoiding the external dx invocation(s) if
    // it can be shown that the inputs have not changed.  It also parallelizes dx invocations
    // where applicable.
    //
    // Note that by not specifying the number of threads this command will use it will select an
    // optimal default regardless of the value of --num-threads.  This decision was made with the
    // assumption that --num-threads specifies the threading of build rule execution and does not
    // directly apply to the internal threading/parallelization details of various build commands
    // being executed.  For example, aapt is internally threaded by default when preprocessing
    // images.
    EnumSet<DxStep.Option> dxOptions =
        shouldProguard()
            ? EnumSet.of(DxStep.Option.NO_LOCALS)
            : EnumSet.of(DxStep.Option.NO_OPTIMIZE);
    Path selectedPrimaryDexPath = primaryDexPath;
    if (reorderClassesIntraDex) {
      String primaryDexFileName = primaryDexPath.getFileName().toString();
      String smartDexPrimaryDexFileName = "smart-dex-" + primaryDexFileName;
      Path smartDexPrimaryDexPath =
          Paths.get(
              primaryDexPath.toString().replace(primaryDexFileName, smartDexPrimaryDexFileName));
      selectedPrimaryDexPath = smartDexPrimaryDexPath;
    }
    SmartDexingStep smartDexingCommand =
        new SmartDexingStep(
            buildContext,
            getProjectFilesystem(),
            selectedPrimaryDexPath,
            primaryInputsToDex,
            secondaryDexDir,
            secondaryOutputToInputs,
            hashInputJarsToDexStep,
            successDir,
            dxOptions,
            dxExecutorService,
            xzCompressionLevel,
            dxMaxHeapSize);
    steps.add(smartDexingCommand);

    if (reorderClassesIntraDex) {
      IntraDexReorderStep intraDexReorderStep =
          new IntraDexReorderStep(
              buildContext,
              getProjectFilesystem(),
              resolver.getAbsolutePath(dexReorderToolFile.get()),
              resolver.getAbsolutePath(dexReorderDataDumpFile.get()),
              getBuildTarget(),
              selectedPrimaryDexPath,
              primaryDexPath,
              secondaryOutputToInputs,
              SMART_DEX_SECONDARY_DEX_SUBDIR,
              AndroidBinary.SECONDARY_DEX_SUBDIR);
      steps.add(intraDexReorderStep);
    }
  }

  private boolean shouldProguard() {
    return proguardConfig.isPresent()
        || !ProGuardObfuscateStep.SdkProguardType.NONE.equals(sdkProguardConfig);
  }

  boolean shouldSplitDex() {
    return dexSplitMode.isShouldSplitDex();
  }

  public ProjectFilesystem getProjectFilesystem() {
    return filesystem;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public Path getManifestPath() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/AndroidManifest.xml");
  }

  /** Encapsulates the information about dexing output that must be passed to ApkBuilder. */
  private static class DexFilesInfo {
    final Path primaryDexPath;
    final ImmutableSet<Path> secondaryDexDirs;

    DexFilesInfo(Path primaryDexPath, ImmutableSet<Path> secondaryDexDirs) {
      this.primaryDexPath = primaryDexPath;
      this.secondaryDexDirs = secondaryDexDirs;
    }
  }

  /** All native-libs-as-assets are copied to this directory before running apkbuilder. */
  private Path getPathForNativeLibsAsAssets() {
    return getBinPath("__native_libs_as_assets_%s__");
  }

  Path getMergedThirdPartyJarsPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.java.resources");
  }

  /** The APK at this path will be signed, but not zipaligned. */
  Path getSignedApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".signed.apk"));
  }

  Path getFinalApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".apk"));
  }

  /** The APK at this path will have compressed resources, but will not be zipaligned. */
  private Path getCompressedResourcesApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".compressed.apk"));
  }

  public String getUnsignedApkPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.unsigned.apk")
        .toString();
  }

  private Path getRedexedApkPath() {
    Path path = BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s__redex");
    return path.resolve(getBuildTarget().getShortName() + ".redex.apk");
  }

  private Path getBinPath(String format) {
    return BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), format);
  }

  private Path getNonPredexedPrimaryDexPath() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "%s/.dex/classes.dex");
  }

  /**
   * Directory of text files used by proguard. Unforunately, this contains both inputs and outputs.
   */
  private Path getProguardTextFilesPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/proguard");
  }

  @VisibleForTesting
  static Path getProguardOutputFromInputClasspath(Path proguardConfigDir, Path classpathEntry) {
    // Hehe, this is so ridiculously fragile.
    Preconditions.checkArgument(
        !classpathEntry.isAbsolute(),
        "Classpath entries should be relative rather than absolute paths: %s",
        classpathEntry);
    String obfuscatedName =
        Files.getNameWithoutExtension(classpathEntry.toString()) + "-obfuscated.jar";
    Path dirName = classpathEntry.getParent();
    return proguardConfigDir.resolve(dirName).resolve(obfuscatedName);
  }
}
