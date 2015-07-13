/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.rules.BuildableProperties.Kind.ANDROID;
import static com.facebook.buck.rules.BuildableProperties.Kind.PACKAGING;

import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.java.AccumulateClassNamesStep;
import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.HasClasspathEntries;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExopackageInfo;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.AbstractGenruleStep;
import com.facebook.buck.shell.EchoStep;
import com.facebook.buck.shell.SymlinkFilesIntoDirectoryStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.zip.RepackZipEntriesStep;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.hash.HashCode;
import com.google.common.io.Files;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * <pre>
 * android_binary(
 *   name = 'messenger',
 *   manifest = 'AndroidManifest.xml',
 *   deps = [
 *     '//src/com/facebook/messenger:messenger_library',
 *   ],
 * )
 * </pre>
 */
public class AndroidBinary
    extends AbstractBuildRule
    implements AbiRule, HasClasspathEntries, HasRuntimeDeps, InstallableApk {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, PACKAGING);

  /**
   * This is the path from the root of the APK that should contain the metadata.txt and
   * secondary-N.dex.jar files for secondary dexes.
   */
  static final String SMART_DEX_SECONDARY_DEX_SUBDIR =
      "assets/smart-dex-secondary-program-dex-jars";
  static final String SECONDARY_DEX_SUBDIR = "assets/secondary-program-dex-jars";

  /**
   * This list of package types is taken from the set of targets that the default build.xml provides
   * for Android projects.
   * <p>
   * Note: not all package types are supported. If unsupported, will be treated as "DEBUG".
   */
  static enum PackageType {
    DEBUG,
    INSTRUMENTED,
    RELEASE,
    TEST,
    ;

    /**
     * @return true if ProGuard should be used to obfuscate the output
     */
    boolean isBuildWithObfuscation() {
      return this == RELEASE;
    }

    final boolean isCrunchPngFiles() {
      return this == RELEASE;
    }
  }

  static enum ExopackageMode {
    SECONDARY_DEX(1),
    NATIVE_LIBRARY(2),
    ;

    private final int code;

    private ExopackageMode(int code) {
      this.code = code;
    }

    public static boolean enabledForSecondaryDexes(EnumSet<ExopackageMode> modes) {
      return modes.contains(SECONDARY_DEX);
    }

    public static boolean enabledForNativeLibraries(EnumSet<ExopackageMode> modes) {
      return modes.contains(NATIVE_LIBRARY);
    }

    public static int toBitmask(EnumSet<ExopackageMode> modes) {
      int bitmask = 0;
      for (ExopackageMode mode : modes) {
        bitmask |= mode.code;
      }
      return bitmask;
    }
  }

  @AddToRuleKey
  private final Keystore keystore;
  @AddToRuleKey
  private final PackageType packageType;
  @AddToRuleKey
  private DexSplitMode dexSplitMode;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  @AddToRuleKey
  private final ProGuardObfuscateStep.SdkProguardType sdkProguardConfig;
  @AddToRuleKey
  private final Optional<Integer> optimizationPasses;
  @AddToRuleKey
  private final Optional<SourcePath> proguardConfig;
  @AddToRuleKey
  private final Optional<Path> proguardJarOverride;
  private final String proguardMaxHeapSize;
  @AddToRuleKey
  private final ResourceCompressionMode resourceCompressionMode;
  @AddToRuleKey
  private final ImmutableSet<NdkCxxPlatforms.TargetCpuType> cpuFilters;
  private final ResourceFilter resourceFilter;
  private final Path primaryDexPath;
  @AddToRuleKey
  private final EnumSet<ExopackageMode> exopackageModes;
  private final Function<String, String> macroExpander;
  @AddToRuleKey
  private final Optional<String> preprocessJavaClassesBash;
  private final Optional<Boolean> reorderClassesIntraDex;
  private final Optional<SourcePath> dexReorderToolFile;
  private final Optional<SourcePath> dexReorderDataDumpFile;
  @AddToRuleKey
  protected final ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex;
  protected final AndroidGraphEnhancementResult enhancementResult;
  private final ListeningExecutorService dxExecutorService;
  @AddToRuleKey
  private final Optional<Integer> xzCompressionLevel;

  AndroidBinary(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Optional<Path> proguardJarOverride,
      String proguardMaxHeapSize,
      Keystore keystore,
      PackageType packageType,
      DexSplitMode dexSplitMode,
      Set<BuildTarget> buildTargetsToExcludeFromDex,
      ProGuardObfuscateStep.SdkProguardType sdkProguardConfig,
      Optional<Integer> proguardOptimizationPasses,
      Optional<SourcePath> proguardConfig,
      ResourceCompressionMode resourceCompressionMode,
      Set<NdkCxxPlatforms.TargetCpuType> cpuFilters,
      ResourceFilter resourceFilter,
      EnumSet<ExopackageMode> exopackageModes,
      Function<String, String> macroExpander,
      Optional<String> preprocessJavaClassesBash,
      ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex,
      AndroidGraphEnhancementResult enhancementResult,
      Optional<Boolean> reorderClassesIntraDex,
      Optional<SourcePath> dexReorderToolFile,
      Optional<SourcePath> dexReorderDataDumpFile,
      Optional<Integer> xzCompressionLevel,
      ListeningExecutorService dxExecutorService) {
    super(params, resolver);
    this.proguardJarOverride = proguardJarOverride;
    this.proguardMaxHeapSize = proguardMaxHeapSize;
    this.keystore = keystore;
    this.packageType = packageType;
    this.dexSplitMode = dexSplitMode;
    this.buildTargetsToExcludeFromDex = ImmutableSet.copyOf(buildTargetsToExcludeFromDex);
    this.sdkProguardConfig = sdkProguardConfig;
    this.optimizationPasses = proguardOptimizationPasses;
    this.proguardConfig = proguardConfig;
    this.resourceCompressionMode = resourceCompressionMode;
    this.cpuFilters = ImmutableSet.copyOf(cpuFilters);
    this.resourceFilter = resourceFilter;
    this.exopackageModes = exopackageModes;
    this.macroExpander = macroExpander;
    this.preprocessJavaClassesBash = preprocessJavaClassesBash;
    this.rulesToExcludeFromDex = rulesToExcludeFromDex;
    this.enhancementResult = enhancementResult;
    this.primaryDexPath = getPrimaryDexPath(params.getBuildTarget());
    this.reorderClassesIntraDex = reorderClassesIntraDex;
    this.dexReorderToolFile = dexReorderToolFile;
    this.dexReorderDataDumpFile = dexReorderDataDumpFile;
    this.dxExecutorService = dxExecutorService;
    this.xzCompressionLevel = xzCompressionLevel;

    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      Preconditions.checkArgument(enhancementResult.getPreDexMerge().isPresent(),
          "%s specified exopackage without pre-dexing, which is invalid.",
          getBuildTarget());
      Preconditions.checkArgument(dexSplitMode.getDexStore() == DexStore.JAR,
          "%s specified exopackage with secondary dex mode %s, " +
              "which is invalid.  (Only JAR is allowed.)",
          getBuildTarget(), dexSplitMode.getDexStore());
      Preconditions.checkArgument(enhancementResult.getComputeExopackageDepsAbi().isPresent(),
          "computeExopackageDepsAbi must be set if exopackage is true.");
    }
  }

  public static Path getPrimaryDexPath(BuildTarget buildTarget) {
    return BuildTargets.getScratchPath(buildTarget, ".dex/%s/classes.dex");
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  public ImmutableSortedSet<JavaLibrary> getRulesToExcludeFromDex() {
    return rulesToExcludeFromDex;
  }

  public Set<BuildTarget> getBuildTargetsToExcludeFromDex() {
    return buildTargetsToExcludeFromDex;
  }

  public Optional<SourcePath> getProguardConfig() {
    return proguardConfig;
  }

  private boolean isCompressResources(){
    return resourceCompressionMode.isCompressResources();
  }

  public ResourceCompressionMode getResourceCompressionMode() {
    return resourceCompressionMode;
  }

  public ImmutableSet<NdkCxxPlatforms.TargetCpuType> getCpuFilters() {
    return this.cpuFilters;
  }

  public ResourceFilter getResourceFilter() {
    return resourceFilter;
  }

  public Function<String, String> getMacroExpander() {
    return macroExpander;
  }

  ProGuardObfuscateStep.SdkProguardType getSdkProguardConfig() {
    return sdkProguardConfig;
  }

  public Optional<Integer> getOptimizationPasses() {
    return optimizationPasses;
  }

  @VisibleForTesting
  AndroidGraphEnhancementResult getEnhancementResult() {
    return enhancementResult;
  }

  /** The APK at this path is the final one that points to an APK that a user should install. */
  @Override
  public Path getApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".apk"));
  }

  @Override
  public Path getPathToOutput() {
    return getApkPath();
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // The `InstallableApk` interface needs access to the manifest, so make sure we create our
    // own copy of this so that we don't have a runtime dep on the `AaptPackageResources` step.
    steps.add(new MkdirStep(getManifestPath().getParent()));
    steps.add(
        CopyStep.forFile(
            enhancementResult.getAaptPackageResources().getAndroidManifestXml(),
            getManifestPath()));
    buildableContext.recordArtifact(getManifestPath());

    // Create the .dex files if we aren't doing pre-dexing.
    Path signedApkPath = getSignedApkPath();
    DexFilesInfo dexFilesInfo = addFinalDxSteps(context, buildableContext, steps);

    ////
    // BE VERY CAREFUL adding any code below here.
    // Any inputs to apkbuilder must be reflected in the hash returned by getAbiKeyForDeps.
    ////

    AndroidPackageableCollection packageableCollection =
        enhancementResult.getPackageableCollection();
    ImmutableSet<Path> nativeLibraryDirectories = ImmutableSet.of();
    if (!ExopackageMode.enabledForNativeLibraries(exopackageModes) &&
        enhancementResult.getCopyNativeLibraries().isPresent()) {
      nativeLibraryDirectories = ImmutableSet.of(
          enhancementResult.getCopyNativeLibraries().get().getPathToNativeLibsDir());
    }

    // Copy the transitive closure of native-libs-as-assets to a single directory, if any.
    ImmutableSet<Path> nativeLibraryAsAssetDirectories;
    if (!packageableCollection.getNativeLibAssetsDirectories().isEmpty()) {
      Path pathForNativeLibsAsAssets = getPathForNativeLibsAsAssets();
      Path libSubdirectory = pathForNativeLibsAsAssets.resolve("assets").resolve("lib");
      steps.add(new MakeCleanDirectoryStep(libSubdirectory));
      for (SourcePath nativeLibDir : packageableCollection.getNativeLibAssetsDirectories()) {
        CopyNativeLibraries.copyNativeLibrary(
            getResolver().getPath(nativeLibDir),
            libSubdirectory,
            cpuFilters,
            steps);
      }
      nativeLibraryAsAssetDirectories = ImmutableSet.of(pathForNativeLibsAsAssets);
    } else {
      nativeLibraryAsAssetDirectories = ImmutableSet.of();
    }

    // If non-english strings are to be stored as assets, pass them to ApkBuilder.
    ImmutableSet.Builder<Path> zipFiles = ImmutableSet.builder();
    Optional<PackageStringAssets> packageStringAssets = enhancementResult.getPackageStringAssets();
    if (packageStringAssets.isPresent()) {
      final Path pathToStringAssetsZip = packageStringAssets.get().getPathToStringAssetsZip();
      zipFiles.add(pathToStringAssetsZip);
    }

    if (ExopackageMode.enabledForNativeLibraries(exopackageModes)) {
      // We need to include a few dummy native libraries with our application so that Android knows
      // to run it as 32-bit.  Android defaults to 64-bit when no libraries are provided at all,
      // causing us to fail to load our 32-bit exopackage native libraries later.
      String fakeNativeLibraryBundle =
          System.getProperty("buck.native_exopackage_fake_path");

      if (fakeNativeLibraryBundle == null) {
        throw new RuntimeException("fake native bundle not specified in properties");
      }

      zipFiles.add(Paths.get(fakeNativeLibraryBundle));
    }

    ImmutableSet<Path> allAssetDirectories = ImmutableSet.<Path>builder()
        .addAll(nativeLibraryAsAssetDirectories)
        .addAll(dexFilesInfo.secondaryDexDirs)
        .build();

    ApkBuilderStep apkBuilderCommand = new ApkBuilderStep(
        enhancementResult.getAaptPackageResources().getResourceApkPath(),
        getSignedApkPath(),
        dexFilesInfo.primaryDexPath,
        allAssetDirectories,
        nativeLibraryDirectories,
        zipFiles.build(),
        FluentIterable.from(packageableCollection.getPathsToThirdPartyJars())
            .transform(getResolver().getPathFunction())
            .toSet(),
        keystore.getPathToStore(),
        keystore.getPathToPropertiesFile(),
        /* debugMode */ false);
    steps.add(apkBuilderCommand);

    // The `ApkBuilderStep` delegates to android tools to build a ZIP with timestamps in it, making
    // the output non-deterministic.  So use an additional scrubbing step to zero these out.
    steps.add(new ZipScrubberStep(signedApkPath));

    Path apkToAlign;
    // Optionally, compress the resources file in the .apk.
    if (this.isCompressResources()) {
      Path compressedApkPath = getCompressedResourcesApkPath();
      apkToAlign = compressedApkPath;
      RepackZipEntriesStep arscComp = new RepackZipEntriesStep(
          signedApkPath,
          compressedApkPath,
          ImmutableSet.of("resources.arsc"));
      steps.add(arscComp);
    } else {
      apkToAlign = signedApkPath;
    }

    Path apkPath = getApkPath();
    ZipalignStep zipalign = new ZipalignStep(apkToAlign, apkPath);
    steps.add(zipalign);

    // Inform the user where the APK can be found.
    EchoStep success = new EchoStep(
        String.format("built APK for %s at %s",
            getBuildTarget().getFullyQualifiedName(),
            apkPath));
    steps.add(success);

    buildableContext.recordArtifact(getApkPath());
    return steps.build();
  }

  @Override
  public Sha1HashCode getAbiKeyForDeps() {
    // For non-exopackages, there is no benefit to the ABI optimization, so we want to disable it.
    // Returning our RuleKey has this effect because we will never get an ABI match after a
    // RuleKey miss.
    if (exopackageModes.isEmpty()) {
      return Sha1HashCode.of(getRuleKey().toString());
    }
    return enhancementResult.getComputeExopackageDepsAbi().get().getAndroidBinaryAbiHash();
  }

  /**
   * Adds steps to do the final dexing or dex merging before building the apk.
   */
  private DexFilesInfo addFinalDxSteps(
      BuildContext context,
      BuildableContext buildableContext,
      ImmutableList.Builder<Step> steps) {

    AndroidPackageableCollection packageableCollection =
        enhancementResult.getPackageableCollection();
    // Execute preprocess_java_classes_binary, if appropriate.
    ImmutableSet<Path> classpathEntriesToDex;
    if (preprocessJavaClassesBash.isPresent()) {
      // Symlink everything in dexTransitiveDependencies.classpathEntriesToDex to the input
      // directory. Expect parallel outputs in the output directory and update classpathEntriesToDex
      // to reflect that.
      final Path preprocessJavaClassesInDir = getBinPath("java_classes_preprocess_in_%s");
      final Path preprocessJavaClassesOutDir = getBinPath("java_classes_preprocess_out_%s");
      steps.add(new MakeCleanDirectoryStep(preprocessJavaClassesInDir));
      steps.add(new MakeCleanDirectoryStep(preprocessJavaClassesOutDir));
      steps.add(new SymlinkFilesIntoDirectoryStep(
          context.getProjectRoot(),
          enhancementResult.getClasspathEntriesToDex(),
          preprocessJavaClassesInDir));
      classpathEntriesToDex = FluentIterable.from(enhancementResult.getClasspathEntriesToDex())
          .transform(new Function<Path, Path>() {
            @Override
            public Path apply(Path classpathEntry) {
              return preprocessJavaClassesOutDir.resolve(classpathEntry);
            }
          })
          .toSet();

      AbstractGenruleStep.CommandString commandString = new AbstractGenruleStep.CommandString(
          /* cmd */ Optional.<String>absent(),
          /* bash */ preprocessJavaClassesBash.transform(macroExpander),
          /* cmdExe */ Optional.<String>absent());
      steps.add(new AbstractGenruleStep(
          this.getBuildTarget(),
          commandString,
          context.getProjectRoot().resolve(preprocessJavaClassesInDir).toFile()) {

        @Override
        protected void addEnvironmentVariables(
            ExecutionContext context,
            ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
          Function<Path, Path> absolutifier = context.getProjectFilesystem().getAbsolutifier();
          environmentVariablesBuilder.put(
              "IN_JARS_DIR", absolutifier.apply(preprocessJavaClassesInDir).toString());
          environmentVariablesBuilder.put(
              "OUT_JARS_DIR", absolutifier.apply(preprocessJavaClassesOutDir).toString());

          AndroidPlatformTarget platformTarget = context.getAndroidPlatformTarget();
          String bootclasspath = Joiner.on(':').join(
              Iterables.transform(
                  platformTarget.getBootclasspathEntries(),
                  absolutifier));

          environmentVariablesBuilder.put("ANDROID_BOOTCLASSPATH", bootclasspath);
        }
      });

    } else {
      classpathEntriesToDex = enhancementResult.getClasspathEntriesToDex();
    }

    // Execute proguard if desired (transforms input classpaths).
    if (packageType.isBuildWithObfuscation()) {
      classpathEntriesToDex = addProguardCommands(
          classpathEntriesToDex,
          ImmutableSet.copyOf(
              getResolver().getAllPaths(packageableCollection.getProguardConfigs())),
          steps,
          buildableContext);
    }

    Supplier<Map<String, HashCode>> classNamesToHashesSupplier;
    boolean classFilesHaveChanged = preprocessJavaClassesBash.isPresent() ||
        packageType.isBuildWithObfuscation();

    if (classFilesHaveChanged) {
      classNamesToHashesSupplier = addAccumulateClassNamesStep(classpathEntriesToDex, steps);
    } else {
      classNamesToHashesSupplier = packageableCollection.getClassNamesToHashesSupplier();
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
    Optional<PreDexMerge> preDexMerge = enhancementResult.getPreDexMerge();
    if (!preDexMerge.isPresent()) {
      steps.add(new MkdirStep(primaryDexPath.getParent()));

      addDexingSteps(
          classpathEntriesToDex,
          classNamesToHashesSupplier,
          secondaryDexDirectoriesBuilder,
          steps,
          primaryDexPath,
          dexReorderToolFile,
          dexReorderDataDumpFile);
    } else if (!ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      secondaryDexDirectoriesBuilder.addAll(preDexMerge.get().getSecondaryDexDirectories());
    }

    return new DexFilesInfo(primaryDexPath, secondaryDexDirectoriesBuilder.build());
  }

  public Supplier<Map<String, HashCode>> addAccumulateClassNamesStep(
      final ImmutableSet<Path> classPathEntriesToDex,
      ImmutableList.Builder<Step> steps) {
    final ImmutableMap.Builder<String, HashCode> builder = ImmutableMap.builder();

    steps.add(
        new AbstractExecutionStep("collect_all_class_names") {
          @Override
          public int execute(ExecutionContext context) {
            for (Path path : classPathEntriesToDex) {
              Optional<ImmutableSortedMap<String, HashCode>> hashes =
                  AccumulateClassNamesStep.calculateClassHashes(context, path);
              if (!hashes.isPresent()) {
                return 1;
              }
              builder.putAll(hashes.get());
            }
            return 0;
          }
        });

    return Suppliers.memoize(
        new Supplier<Map<String, HashCode>>() {
          @Override
          public Map<String, HashCode> get() {
            return builder.build();
          }
        });
  }

  public AndroidPackageableCollection getAndroidPackageableCollection() {
    return enhancementResult.getPackageableCollection();
  }

  /**
   * All native-libs-as-assets are copied to this directory before running apkbuilder.
   */
  private Path getPathForNativeLibsAsAssets() {
    return getBinPath("__native_libs_as_assets_%s__");
  }

  public Keystore getKeystore() {
    return keystore;
  }

  public String getUnsignedApkPath() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s.unsigned.apk").toString();
  }

  /** The APK at this path will be signed, but not zipaligned. */
  private Path getSignedApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".signed.apk"));
  }

  /** The APK at this path will have compressed resources, but will not be zipaligned. */
  private Path getCompressedResourcesApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".compressed.apk"));
  }

  private Path getBinPath(String format) {
    return BuildTargets.getScratchPath(getBuildTarget(), format);
  }

  @VisibleForTesting
  Path getProguardOutputFromInputClasspath(Path classpathEntry) {
    // Hehe, this is so ridiculously fragile.
    Preconditions.checkArgument(!classpathEntry.isAbsolute(),
        "Classpath entries should be relative rather than absolute paths: %s",
        classpathEntry);
    String obfuscatedName =
        Files.getNameWithoutExtension(classpathEntry.toString()) + "-obfuscated.jar";
    Path dirName = classpathEntry.getParent();
    Path proguardConfigDir = enhancementResult.getAaptPackageResources()
        .getPathToGeneratedProguardConfigDir();
    return proguardConfigDir.resolve(dirName).resolve(obfuscatedName);
  }

  /**
   * @return the resulting set of ProGuarded classpath entries to dex.
   */
  @VisibleForTesting
  ImmutableSet<Path> addProguardCommands(
      Set<Path> classpathEntriesToDex,
      Set<Path> depsProguardConfigs,
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext) {
    final ImmutableSetMultimap<JavaLibrary, Path> classpathEntriesMap =
        getTransitiveClasspathEntries();
    ImmutableSet.Builder<Path> additionalLibraryJarsForProguardBuilder = ImmutableSet.builder();

    for (JavaLibrary buildRule : rulesToExcludeFromDex) {
      additionalLibraryJarsForProguardBuilder.addAll(classpathEntriesMap.get(buildRule));
    }

    // Create list of proguard Configs for the app project and its dependencies
    ImmutableSet.Builder<Path> proguardConfigsBuilder = ImmutableSet.builder();
    proguardConfigsBuilder.addAll(depsProguardConfigs);
    if (proguardConfig.isPresent()) {
      proguardConfigsBuilder.add(getResolver().getPath(proguardConfig.get()));
    }

    // Transform our input classpath to a set of output locations for each input classpath.
    // TODO(devjasta): the output path we choose is the result of a slicing function against
    // input classpath. This is fragile and should be replaced with knowledge of the BuildTarget.
    final ImmutableMap<Path, Path> inputOutputEntries = FluentIterable
        .from(classpathEntriesToDex)
        .toMap(new Function<Path, Path>() {
          @Override
          public Path apply(Path classpathEntry) {
            return getProguardOutputFromInputClasspath(classpathEntry);
          }
        });

    Path proguardConfigDir = enhancementResult.getAaptPackageResources()
        .getPathToGeneratedProguardConfigDir();
    // Run ProGuard on the classpath entries.
    ProGuardObfuscateStep.create(
        proguardJarOverride,
        proguardMaxHeapSize,
        proguardConfigDir.resolve("proguard.txt"),
        proguardConfigsBuilder.build(),
        sdkProguardConfig,
        optimizationPasses,
        inputOutputEntries,
        additionalLibraryJarsForProguardBuilder.build(),
        proguardConfigDir,
        buildableContext,
        steps);

    // Apply the transformed inputs to the classpath (this will modify deps.classpathEntriesToDex
    // so that we're now dexing the proguarded artifacts).
    return ImmutableSet.copyOf(inputOutputEntries.values());
  }

  /** Helper method to check whether intra-dex reordering is enabled
   */
  private boolean isReorderingClasses() {
    return (reorderClassesIntraDex.isPresent() && reorderClassesIntraDex.get());
  }

  /**
   * Create dex artifacts for all of the individual directories of compiled .class files (or
   * the obfuscated jar files if proguard is used).  If split dex is used, multiple dex artifacts
   * will be produced.
   *  @param classpathEntriesToDex Full set of classpath entries that must make
   *     their way into the final APK structure (but not necessarily into the
   *     primary dex).
   * @param secondaryDexDirectories The contract for updating this builder must match that
   *     of {@link PreDexMerge#getSecondaryDexDirectories()}.
   * @param steps List of steps to add to.
   * @param primaryDexPath Output path for the primary dex file.
   */
  @VisibleForTesting
  void addDexingSteps(
      Set<Path> classpathEntriesToDex,
      Supplier<Map<String, HashCode>> classNamesToHashesSupplier,
      ImmutableSet.Builder<Path> secondaryDexDirectories,
      ImmutableList.Builder<Step> steps,
      Path primaryDexPath,
      Optional<SourcePath> dexReorderToolFile,
      Optional<SourcePath> dexReorderDataDumpFile) {
    final Supplier<Set<Path>> primaryInputsToDex;
    final Optional<Path> secondaryDexDir;
    final Optional<Supplier<Multimap<Path, Path>>> secondaryOutputToInputs;
    Path secondaryDexParentDir = getBinPath("__%s_secondary_dex__/");

    if (shouldSplitDex()) {
      Optional<Path> proguardFullConfigFile = Optional.absent();
      Optional<Path> proguardMappingFile = Optional.absent();
      if (packageType.isBuildWithObfuscation()) {
        Path proguardConfigDir = enhancementResult.getAaptPackageResources()
            .getPathToGeneratedProguardConfigDir();
        proguardFullConfigFile = Optional.of(proguardConfigDir.resolve("configuration.txt"));
        proguardMappingFile = Optional.of(proguardConfigDir.resolve("mapping.txt"));
      }

      // DexLibLoader expects that metadata.txt and secondary jar files are under this dir
      // in assets.

      // Intermediate directory holding the primary split-zip jar.
      Path splitZipDir = getBinPath("__%s_split_zip__");
      steps.add(new MakeCleanDirectoryStep(splitZipDir));
      Path primaryJarPath = splitZipDir.resolve("primary.jar");

      Path secondaryJarMetaDirParent = splitZipDir.resolve("secondary_meta");
      Path secondaryJarMetaDir = secondaryJarMetaDirParent.resolve(SECONDARY_DEX_SUBDIR);
      steps.add(new MakeCleanDirectoryStep(secondaryJarMetaDir));
      Path secondaryJarMeta = secondaryJarMetaDir.resolve("metadata.txt");

      // Intermediate directory holding _ONLY_ the secondary split-zip jar files.  This is
      // important because SmartDexingCommand will try to dx every entry in this directory.  It
      // does this because it's impossible to know what outputs split-zip will generate until it
      // runs.
      final Path secondaryZipDir = getBinPath("__%s_secondary_zip__");
      steps.add(new MakeCleanDirectoryStep(secondaryZipDir));

      // Run the split-zip command which is responsible for dividing the large set of input
      // classpaths into a more compact set of jar files such that no one jar file when dexed will
      // yield a dex artifact too large for dexopt or the dx method limit to handle.
      Path zipSplitReportDir = getBinPath("__%s_split_zip_report__");
      steps.add(new MakeCleanDirectoryStep(zipSplitReportDir));
      SplitZipStep splitZipCommand = new SplitZipStep(
          classpathEntriesToDex,
          secondaryJarMeta,
          primaryJarPath,
          secondaryZipDir,
          "secondary-%d.jar",
          proguardFullConfigFile,
          proguardMappingFile,
          dexSplitMode,
          dexSplitMode.getPrimaryDexScenarioFile().transform(getResolver().getPathFunction()),
          dexSplitMode.getPrimaryDexClassesFile().transform(getResolver().getPathFunction()),
          dexSplitMode.getSecondaryDexHeadClassesFile().transform(getResolver().getPathFunction()),
          dexSplitMode.getSecondaryDexTailClassesFile().transform(getResolver().getPathFunction()),
          zipSplitReportDir);
      steps.add(splitZipCommand);

      // Add the secondary dex directory that has yet to be created, but will be by the
      // smart dexing command.  Smart dex will handle "cleaning" this directory properly.
      if (isReorderingClasses()) {
        secondaryDexDir = Optional.of(secondaryDexParentDir.resolve(
              SMART_DEX_SECONDARY_DEX_SUBDIR));
        Path intraDexReorderSecondaryDexDir = secondaryDexParentDir.resolve(SECONDARY_DEX_SUBDIR);
        steps.add(new MakeCleanDirectoryStep(secondaryDexDir.get()));
        steps.add(new MakeCleanDirectoryStep(intraDexReorderSecondaryDexDir));
      } else {
        secondaryDexDir = Optional.of(secondaryDexParentDir.resolve(SECONDARY_DEX_SUBDIR));
        steps.add(new MkdirStep(secondaryDexDir.get()));
      }

      if (dexSplitMode.getDexStore() == DexStore.RAW) {
        secondaryDexDirectories.add(secondaryDexDir.get());
      } else {
        secondaryDexDirectories.add(secondaryJarMetaDirParent);
        secondaryDexDirectories.add(secondaryDexParentDir);
      }

      // Adjust smart-dex inputs for the split-zip case.
      primaryInputsToDex = Suppliers.<Set<Path>>ofInstance(ImmutableSet.of(primaryJarPath));
      Supplier<Multimap<Path, Path>> secondaryOutputToInputsMap =
          splitZipCommand.getOutputToInputsMapSupplier(secondaryDexDir.get());
      secondaryOutputToInputs = Optional.of(secondaryOutputToInputsMap);
    } else {
      // Simple case where our inputs are the natural classpath directories and we don't have
      // to worry about secondary jar/dex files.
      primaryInputsToDex = Suppliers.ofInstance(classpathEntriesToDex);
      secondaryDexDir = Optional.absent();
      secondaryOutputToInputs = Optional.absent();
    }

    HashInputJarsToDexStep hashInputJarsToDexStep = new HashInputJarsToDexStep(
        primaryInputsToDex,
        secondaryOutputToInputs,
        classNamesToHashesSupplier);
    steps.add(hashInputJarsToDexStep);

    // Stores checksum information from each invocation to intelligently decide when dx needs
    // to be re-run.
    Path successDir = getBinPath("__%s_smart_dex__/.success");
    steps.add(new MkdirStep(successDir));

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
    EnumSet<DxStep.Option> dxOptions = PackageType.RELEASE.equals(packageType)
        ? EnumSet.noneOf(DxStep.Option.class)
        : EnumSet.of(DxStep.Option.NO_OPTIMIZE);
    Path selectedPrimaryDexPath = primaryDexPath;
    if (isReorderingClasses()) {
      String primaryDexFileName = primaryDexPath.getFileName().toString();
      String smartDexPrimaryDexFileName = "smart-dex-" + primaryDexFileName;
      Path smartDexPrimaryDexPath = Paths.get(primaryDexPath.toString().replace(primaryDexFileName,
              smartDexPrimaryDexFileName));
      selectedPrimaryDexPath = smartDexPrimaryDexPath;
    }
    SmartDexingStep smartDexingCommand = new SmartDexingStep(
        selectedPrimaryDexPath,
        primaryInputsToDex,
        secondaryDexDir,
        secondaryOutputToInputs,
        hashInputJarsToDexStep,
        successDir,
        dxOptions,
        dxExecutorService,
        xzCompressionLevel);
    steps.add(smartDexingCommand);

    if (isReorderingClasses()) {
      IntraDexReorderStep intraDexReorderStep = new IntraDexReorderStep(
        dexReorderToolFile,
        dexReorderDataDumpFile,
        getResolver(),
        getBuildTarget(),
        selectedPrimaryDexPath,
        primaryDexPath,
        secondaryOutputToInputs,
        SMART_DEX_SECONDARY_DEX_SUBDIR,
        SECONDARY_DEX_SUBDIR);
      steps.add(intraDexReorderStep);
    }
  }

  @Override
  public Path getManifestPath() {
    return BuildTargets.getGenPath(getBuildTarget(), "%s/AndroidManifest.xml");
  }

  boolean shouldSplitDex() {
    return dexSplitMode.isShouldSplitDex();
  }

  @Override
  public Optional<ExopackageInfo> getExopackageInfo() {
    boolean shouldInstall = false;

    ExopackageInfo.Builder builder = ExopackageInfo.builder();
    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      PreDexMerge preDexMerge = enhancementResult.getPreDexMerge().get();
      builder.setDexInfo(
          ExopackageInfo.DexInfo.of(
              preDexMerge.getMetadataTxtPath(),
              preDexMerge.getDexDirectory()));
      shouldInstall = true;
    }

    if (ExopackageMode.enabledForNativeLibraries(exopackageModes) &&
        enhancementResult.getCopyNativeLibraries().isPresent()) {
      CopyNativeLibraries copyNativeLibraries = enhancementResult.getCopyNativeLibraries().get();
      builder.setNativeLibsInfo(
          ExopackageInfo.NativeLibsInfo.of(
              copyNativeLibraries.getPathToMetadataTxt(),
              copyNativeLibraries.getPathToNativeLibsDir()));
      shouldInstall = true;
    }

    if (!shouldInstall) {
      return Optional.absent();
    }

    ExopackageInfo exopackageInfo = builder.build();
    return Optional.of(exopackageInfo);
  }

  public ImmutableSortedSet<BuildRule> getClasspathDeps() {
    return getDeclaredDeps();
  }

  @Override
  public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
    // This is used primarily for buck audit classpath.
    return Classpaths.getClasspathEntries(getClasspathDeps());
  }

  @Override
  public ImmutableSortedSet<BuildRule> getRuntimeDeps() {
    ImmutableSortedSet.Builder<BuildRule> deps = ImmutableSortedSet.naturalOrder();
    if (ExopackageMode.enabledForNativeLibraries(exopackageModes)) {
      deps.addAll(enhancementResult.getCopyNativeLibraries().asSet());
    }
    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      deps.addAll(enhancementResult.getPreDexMerge().asSet());
    }
    return deps.build();
  }

  /**
   * Encapsulates the information about dexing output that must be passed to ApkBuilder.
   */
  private static class DexFilesInfo {
    final Path primaryDexPath;
    final ImmutableSet<Path> secondaryDexDirs;

    DexFilesInfo(Path primaryDexPath, ImmutableSet<Path> secondaryDexDirs) {
      this.primaryDexPath = primaryDexPath;
      this.secondaryDexDirs = secondaryDexDirs;
    }
  }
}
