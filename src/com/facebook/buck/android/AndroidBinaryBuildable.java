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
import com.facebook.buck.android.toolchain.TargetCpuType;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
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
import java.util.List;
import java.util.Optional;
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
  @AddToRuleKey private final Optional<RedexOptions> redexOptions;
  @AddToRuleKey private final ImmutableSet<TargetCpuType> cpuFilters;
  @AddToRuleKey private final EnumSet<AndroidBinary.ExopackageMode> exopackageModes;
  @AddToRuleKey private final Optional<Integer> xzCompressionLevel;
  @AddToRuleKey private final boolean packageAssetLibraries;
  @AddToRuleKey private final boolean compressAssetLibraries;
  @AddToRuleKey private final boolean skipProguard;
  @AddToRuleKey private final Tool javaRuntimeLauncher;
  @AddToRuleKey private final SourcePath androidManifestPath;
  @AddToRuleKey private final SourcePath resourcesApkPath;
  @AddToRuleKey private final ImmutableList<SourcePath> primaryApkAssetsZips;
  @AddToRuleKey private final boolean isCompressResources;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> pathsToThirdPartyJars;
  @AddToRuleKey private final boolean hasLinkableAssets;
  @AddToRuleKey private final ImmutableSortedSet<APKModule> apkModules;
  @AddToRuleKey private final boolean isPreDexed;
  @AddToRuleKey private final Optional<SourcePath> predexedPrimaryDexPath;
  @AddToRuleKey private final Optional<ImmutableSortedSet<SourcePath>> predexedSecondaryDirectories;
  @AddToRuleKey private final boolean shouldProguard;
  @AddToRuleKey private Optional<ImmutableSortedMap<APKModule, SourcePath>> nativeLibsDirs;
  // TODO(cjhopman): why is this derived differently than nativeLibAssetsDirectories?
  @AddToRuleKey private Optional<ImmutableSortedMap<APKModule, SourcePath>> nativeLibsAssetsDirs;

  @AddToRuleKey
  private final ImmutableSortedMap<APKModule, ImmutableList<SourcePath>> nativeLibAssetsDirectories;

  @AddToRuleKey private final Optional<SourcePath> appModularityResult;

  @AddToRuleKey
  private final Optional<ImmutableSortedMap<APKModule, ImmutableList<SourcePath>>>
      moduleMappedClasspathEntriesForConsistency;

  @AddToRuleKey private final Optional<NonPreDexedDexBuildable> nonPreDexedDexBuildable;

  // These should be the only things not added to the rulekey.
  private final ProjectFilesystem filesystem;
  private final BuildTarget buildTarget;

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
      ImmutableSet<TargetCpuType> cpuFilters,
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
    this.redexOptions = redexOptions;
    this.cpuFilters = cpuFilters;
    this.exopackageModes = exopackageModes;
    this.xzCompressionLevel = xzCompressionLevel;
    this.packageAssetLibraries = packageAssetLibraries;
    this.compressAssetLibraries = compressAssetLibraries;
    this.skipProguard = skipProguard;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.androidManifestPath = androidManifestPath;
    this.resourcesApkPath = resourcesApkPath;
    this.primaryApkAssetsZips = primaryApkAssetsZips;
    this.isCompressResources = isCompressResources;
    ImmutableSortedSet<SourcePath> additionalJarsForProguard =
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
    ImmutableSortedMap<APKModule, ImmutableSortedSet<APKModule>> apkModuleMap =
        apkModuleGraph.toOutgoingEdgesMap();
    APKModule rootAPKModule = apkModuleGraph.getRootAPKModule();

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

    this.shouldProguard =
        proguardConfig.isPresent()
            || !ProGuardObfuscateStep.SdkProguardType.NONE.equals(sdkProguardConfig);
    this.predexedPrimaryDexPath =
        enhancementResult.getPreDexMerge().map(PreDexMerge::getSourcePathToPrimaryDex);
    Optional<ImmutableSet<SourcePath>> classpathEntriesToDexSourcePaths;
    Optional<ImmutableSortedMap<APKModule, ImmutableList<SourcePath>>>
        moduleMappedClasspathEntriesToDex;
    if (isPreDexed) {
      Preconditions.checkState(!preprocessJavaClassesBash.isPresent());
      this.nonPreDexedDexBuildable = Optional.empty();
    } else {
      classpathEntriesToDexSourcePaths =
          Optional.of(
              RichStream.from(enhancementResult.getClasspathEntriesToDex())
                  .concat(
                      RichStream.of(
                          enhancementResult.getCompiledUberRDotJava().getSourcePathToOutput()))
                  .collect(MoreCollectors.toImmutableSet()));
      moduleMappedClasspathEntriesToDex =
          Optional.of(
              convertToMapOfLists(packageableCollection.getModuleMappedClasspathEntriesToDex()));
      this.nonPreDexedDexBuildable =
          Optional.of(
              new NonPreDexedDexBuildable(
                  aaptGeneratedProguardConfigFile,
                  additionalJarsForProguard,
                  apkModuleMap,
                  classpathEntriesToDexSourcePaths,
                  dexReorderDataDumpFile,
                  dexReorderToolFile,
                  dexSplitMode,
                  dxExecutorService,
                  dxMaxHeapSize,
                  javaRuntimeLauncher,
                  moduleMappedClasspathEntriesToDex,
                  optimizationPasses,
                  preprocessJavaClassesBash,
                  shouldProguard,
                  proguardAgentPath,
                  proguardConfig,
                  proguardConfigs,
                  proguardJarOverride,
                  proguardJvmArgs,
                  proguardMaxHeapSize,
                  reorderClassesIntraDex,
                  rootAPKModule,
                  sdkProguardConfig,
                  skipProguard,
                  xzCompressionLevel,
                  filesystem,
                  buildTarget,
                  dexSplitMode.isShouldSplitDex()));
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

    if (dexFilesInfo.proguardTextFilesPath.isPresent()) {
      steps.add(
          CopyStep.forDirectory(
              getProjectFilesystem(),
              dexFilesInfo.proguardTextFilesPath.get(),
              getProguardTextFilesPath(),
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    }

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
    if (shouldProguard) {
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
          secondaryDexDirs,
          Optional.empty());
    }

    return nonPreDexedDexBuildable.get().addDxSteps(buildableContext, buildContext, steps);
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

  public NonPreDexedDexBuildable getNonPredexedBuildableForTests() {
    return nonPreDexedDexBuildable.get();
  }

  /** Encapsulates the information about dexing output that must be passed to ApkBuilder. */
  static class DexFilesInfo {
    final Path primaryDexPath;
    final ImmutableSet<Path> secondaryDexDirs;
    final Optional<Path> proguardTextFilesPath;

    DexFilesInfo(
        Path primaryDexPath,
        ImmutableSet<Path> secondaryDexDirs,
        Optional<Path> proguardTextFilesPath) {
      this.primaryDexPath = primaryDexPath;
      this.secondaryDexDirs = secondaryDexDirs;
      this.proguardTextFilesPath = proguardTextFilesPath;
    }
  }

  /** All native-libs-as-assets are copied to this directory before running apkbuilder. */
  private Path getPathForNativeLibsAsAssets() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "__native_libs_as_assets_%s__");
  }

  Path getMergedThirdPartyJarsPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.java.resources");
  }

  /** The APK at this path will be signed, but not zipaligned. */
  private Path getSignedApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".signed.apk"));
  }

  Path getFinalApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".apk"));
  }

  /** The APK at this path will have compressed resources, but will not be zipaligned. */
  private Path getCompressedResourcesApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".compressed.apk"));
  }

  private String getUnsignedApkPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.unsigned.apk")
        .toString();
  }

  private Path getRedexedApkPath() {
    Path path = BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s__redex");
    return path.resolve(getBuildTarget().getShortName() + ".redex.apk");
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
