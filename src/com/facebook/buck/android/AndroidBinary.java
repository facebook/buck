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
import com.facebook.buck.android.redex.ReDexStep;
import com.facebook.buck.android.redex.RedexOptions;
import com.facebook.buck.android.resources.ResourcesZipBuilder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.AccumulateClassNamesStep;
import com.facebook.buck.jvm.java.HasClasspathEntries;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryClasspathProvider;
import com.facebook.buck.jvm.java.JavaRuntimeLauncher;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExopackageInfo;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.shell.AbstractGenruleStep;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.XzStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.zip.RepackZipEntriesStep;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;

/**
 *
 *
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
public class AndroidBinary extends AbstractBuildRule
    implements SupportsInputBasedRuleKey, HasClasspathEntries, HasRuntimeDeps, HasInstallableApk {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, PACKAGING);

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

  static final String SECONDARY_DEX_SUBDIR = "assets/secondary-program-dex-jars";

  /**
   * This list of package types is taken from the set of targets that the default build.xml provides
   * for Android projects.
   *
   * <p>Note: not all package types are supported. If unsupported, will be treated as "DEBUG".
   */
  enum PackageType {
    DEBUG,
    INSTRUMENTED,
    RELEASE,
    TEST,
    ;

    /** @return true if ProGuard should be used to obfuscate the output */
    boolean isBuildWithObfuscation() {
      return this == RELEASE;
    }
  }

  enum ExopackageMode {
    SECONDARY_DEX(1),
    NATIVE_LIBRARY(2),
    RESOURCES(4),
    ;

    private final int code;

    ExopackageMode(int code) {
      this.code = code;
    }

    public static boolean enabledForSecondaryDexes(EnumSet<ExopackageMode> modes) {
      return modes.contains(SECONDARY_DEX);
    }

    public static boolean enabledForNativeLibraries(EnumSet<ExopackageMode> modes) {
      return modes.contains(NATIVE_LIBRARY);
    }

    public static boolean enabledForResources(EnumSet<ExopackageMode> modes) {
      return modes.contains(RESOURCES);
    }

    public static int toBitmask(EnumSet<ExopackageMode> modes) {
      int bitmask = 0;
      for (ExopackageMode mode : modes) {
        bitmask |= mode.code;
      }
      return bitmask;
    }
  }

  enum RelinkerMode {
    ENABLED,
    DISABLED,
    ;
  }

  enum AaptMode {
    AAPT1,
    AAPT2,
    ;
  }

  private final Keystore keystore;
  @AddToRuleKey private final SourcePath keystorePath;
  @AddToRuleKey private final SourcePath keystorePropertiesPath;
  @AddToRuleKey private final PackageType packageType;
  @AddToRuleKey private DexSplitMode dexSplitMode;
  private final ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex;
  @AddToRuleKey private final ProGuardObfuscateStep.SdkProguardType sdkProguardConfig;
  @AddToRuleKey private final Optional<Integer> optimizationPasses;
  @AddToRuleKey private final Optional<SourcePath> proguardConfig;
  private final SourcePathRuleFinder ruleFinder;
  @AddToRuleKey private final Optional<SourcePath> proguardJarOverride;
  @AddToRuleKey private final Optional<RedexOptions> redexOptions;

  private final String proguardMaxHeapSize;
  @AddToRuleKey private final Optional<List<String>> proguardJvmArgs;
  private final Optional<String> proguardAgentPath;
  @AddToRuleKey private final ResourceCompressionMode resourceCompressionMode;
  @AddToRuleKey private final ImmutableSet<NdkCxxPlatforms.TargetCpuType> cpuFilters;
  private final ResourceFilter resourceFilter;
  private final Path primaryDexPath;
  @AddToRuleKey private final EnumSet<ExopackageMode> exopackageModes;
  private final Function<String, String> macroExpander;
  @AddToRuleKey private final Optional<String> preprocessJavaClassesBash;
  private final boolean reorderClassesIntraDex;
  private final Optional<SourcePath> dexReorderToolFile;
  private final Optional<SourcePath> dexReorderDataDumpFile;
  protected final ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex;

  @AddToRuleKey
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final String ruleNamesToExcludeFromDex;

  protected final AndroidGraphEnhancementResult enhancementResult;
  private final ListeningExecutorService dxExecutorService;
  @AddToRuleKey private final Optional<Integer> xzCompressionLevel;
  @AddToRuleKey private final boolean packageAssetLibraries;
  @AddToRuleKey private final boolean compressAssetLibraries;
  @AddToRuleKey private final ManifestEntries manifestEntries;
  @AddToRuleKey private final boolean skipProguard;
  @AddToRuleKey private final JavaRuntimeLauncher javaRuntimeLauncher;
  @AddToRuleKey private final SourcePath androidManifestPath;
  @AddToRuleKey private final SourcePath resourcesApkPath;
  @AddToRuleKey private ImmutableList<SourcePath> primaryApkAssetsZips;
  @AddToRuleKey private SourcePath aaptGeneratedProguardConfigFile;
  @AddToRuleKey private Optional<String> dxMaxHeapSize;
  @AddToRuleKey private ImmutableList<SourcePath> proguardConfigs;

  @AddToRuleKey
  @Nullable
  @SuppressWarnings("PMD.UnusedPrivateField")
  private final SourcePath abiPath;

  AndroidBinary(
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      Optional<SourcePath> proguardJarOverride,
      String proguardMaxHeapSize,
      Optional<List<String>> proguardJvmArgs,
      Optional<String> proguardAgentPath,
      Keystore keystore,
      PackageType packageType,
      DexSplitMode dexSplitMode,
      Set<BuildTarget> buildTargetsToExcludeFromDex,
      ProGuardObfuscateStep.SdkProguardType sdkProguardConfig,
      Optional<Integer> proguardOptimizationPasses,
      Optional<SourcePath> proguardConfig,
      boolean skipProguard,
      Optional<RedexOptions> redexOptions,
      ResourceCompressionMode resourceCompressionMode,
      Set<NdkCxxPlatforms.TargetCpuType> cpuFilters,
      ResourceFilter resourceFilter,
      EnumSet<ExopackageMode> exopackageModes,
      Function<String, String> macroExpander,
      Optional<String> preprocessJavaClassesBash,
      ImmutableSortedSet<JavaLibrary> rulesToExcludeFromDex,
      AndroidGraphEnhancementResult enhancementResult,
      boolean reorderClassesIntraDex,
      Optional<SourcePath> dexReorderToolFile,
      Optional<SourcePath> dexReorderDataDumpFile,
      Optional<Integer> xzCompressionLevel,
      ListeningExecutorService dxExecutorService,
      boolean packageAssetLibraries,
      boolean compressAssetLibraries,
      ManifestEntries manifestEntries,
      JavaRuntimeLauncher javaRuntimeLauncher,
      Optional<String> dxMaxHeapSize) {
    super(params);
    this.ruleFinder = ruleFinder;
    this.proguardJarOverride = proguardJarOverride;
    this.proguardMaxHeapSize = proguardMaxHeapSize;
    this.proguardJvmArgs = proguardJvmArgs;
    this.redexOptions = redexOptions;
    this.proguardAgentPath = proguardAgentPath;
    this.keystore = keystore;
    this.keystorePath = keystore.getPathToStore();
    this.keystorePropertiesPath = keystore.getPathToPropertiesFile();
    this.packageType = packageType;
    this.dexSplitMode = dexSplitMode;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
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
    this.ruleNamesToExcludeFromDex =
        Joiner.on(":")
            .join(FluentIterable.from(rulesToExcludeFromDex).transform(BuildRule::toString));
    this.enhancementResult = enhancementResult;
    this.primaryDexPath = getPrimaryDexPath(params.getBuildTarget(), getProjectFilesystem());
    this.reorderClassesIntraDex = reorderClassesIntraDex;
    this.dexReorderToolFile = dexReorderToolFile;
    this.dexReorderDataDumpFile = dexReorderDataDumpFile;
    this.dxExecutorService = dxExecutorService;
    this.xzCompressionLevel = xzCompressionLevel;
    this.packageAssetLibraries = packageAssetLibraries;
    this.compressAssetLibraries = compressAssetLibraries;
    this.skipProguard = skipProguard;
    this.manifestEntries = manifestEntries;
    this.androidManifestPath = enhancementResult.getAndroidManifestPath();
    this.resourcesApkPath = enhancementResult.getPrimaryResourcesApkPath();
    this.primaryApkAssetsZips = enhancementResult.getPrimaryApkAssetZips();
    this.aaptGeneratedProguardConfigFile =
        enhancementResult.getSourcePathToAaptGeneratedProguardConfigFile();
    this.dxMaxHeapSize = dxMaxHeapSize;
    this.proguardConfigs = enhancementResult.getProguardConfigs();

    if (exopackageModes.isEmpty()) {
      this.abiPath = null;
    } else {
      this.abiPath = enhancementResult.getComputeExopackageDepsAbi().get().getAbiPath();
    }

    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      Preconditions.checkArgument(
          enhancementResult.getPreDexMerge().isPresent(),
          "%s specified exopackage without pre-dexing, which is invalid.",
          getBuildTarget());
      Preconditions.checkArgument(
          dexSplitMode.getDexStore() == DexStore.JAR,
          "%s specified exopackage with secondary dex mode %s, "
              + "which is invalid.  (Only JAR is allowed.)",
          getBuildTarget(),
          dexSplitMode.getDexStore());
      Preconditions.checkArgument(
          enhancementResult.getComputeExopackageDepsAbi().isPresent(),
          "computeExopackageDepsAbi must be set if exopackage is true.");
    }

    if (ExopackageMode.enabledForResources(exopackageModes)
        && !(ExopackageMode.enabledForSecondaryDexes(exopackageModes)
            && ExopackageMode.enabledForNativeLibraries(exopackageModes))) {
      throw new HumanReadableException(
          "Invalid exopackage_modes for android_binary %s. %s requires %s and %s",
          getBuildTarget().getUnflavoredBuildTarget(),
          ExopackageMode.RESOURCES,
          ExopackageMode.NATIVE_LIBRARY,
          ExopackageMode.SECONDARY_DEX);
    }
  }

  public static Path getPrimaryDexPath(BuildTarget buildTarget, ProjectFilesystem filesystem) {
    return BuildTargets.getScratchPath(filesystem, buildTarget, ".dex/%s/classes.dex");
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  public ImmutableSortedSet<JavaLibrary> getRulesToExcludeFromDex() {
    return rulesToExcludeFromDex;
  }

  public ImmutableSet<BuildTarget> getBuildTargetsToExcludeFromDex() {
    return buildTargetsToExcludeFromDex;
  }

  public Optional<SourcePath> getProguardConfig() {
    return proguardConfig;
  }

  public boolean getSkipProguard() {
    return skipProguard;
  }

  private boolean isCompressResources() {
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

  public Optional<List<String>> getProguardJvmArgs() {
    return proguardJvmArgs;
  }

  public ManifestEntries getManifestEntries() {
    return manifestEntries;
  }

  JavaRuntimeLauncher getJavaRuntimeLauncher() {
    return javaRuntimeLauncher;
  }

  @VisibleForTesting
  AndroidGraphEnhancementResult getEnhancementResult() {
    return enhancementResult;
  }

  /** The APK at this path is the final one that points to an APK that a user should install. */
  @Override
  public ApkInfo getApkInfo() {
    return ApkInfo.builder()
        .setApkPath(getSourcePathToOutput())
        .setManifestPath(getManifestPath())
        .setExopackageInfo(getExopackageInfo())
        .build();
  }

  @Override
  public boolean inputBasedRuleKeyIsEnabled() {
    return !exopackageModes.isEmpty();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(), Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".apk")));
  }

  @SuppressWarnings("PMD.PrematureDeclaration")
  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // The `HasInstallableApk` interface needs access to the manifest, so make sure we create our
    // own copy of this so that we don't have a runtime dep on the `AaptPackageResources` step.
    Path manifestPath = context.getSourcePathResolver().getRelativePath(getManifestPath());
    steps.add(MkdirStep.of(getProjectFilesystem(), manifestPath.getParent()));
    steps.add(
        CopyStep.forFile(
            getProjectFilesystem(),
            context.getSourcePathResolver().getRelativePath(androidManifestPath),
            manifestPath));
    buildableContext.recordArtifact(manifestPath);

    // Create the .dex files if we aren't doing pre-dexing.
    DexFilesInfo dexFilesInfo =
        addFinalDxSteps(buildableContext, context.getSourcePathResolver(), steps);

    ////
    // BE VERY CAREFUL adding any code below here.
    // Any inputs to apkbuilder must be reflected in the hash returned by getAbiKeyForDeps.
    ////

    AndroidPackageableCollection packageableCollection =
        enhancementResult.getPackageableCollection();

    ImmutableSet.Builder<Path> nativeLibraryDirectoriesBuilder = ImmutableSet.builder();
    // Copy the transitive closure of native-libs-as-assets to a single directory, if any.
    ImmutableSet.Builder<Path> nativeLibraryAsAssetDirectories = ImmutableSet.builder();

    for (final APKModule module : enhancementResult.getAPKModuleGraph().getAPKModules()) {
      boolean shouldPackageAssetLibraries = packageAssetLibraries || !module.isRootModule();

      if (!ExopackageMode.enabledForNativeLibraries(exopackageModes)
          && enhancementResult.getCopyNativeLibraries().isPresent()
          && enhancementResult.getCopyNativeLibraries().get().containsKey(module)) {
        CopyNativeLibraries copyNativeLibraries =
            enhancementResult.getCopyNativeLibraries().get().get(module);
        if (shouldPackageAssetLibraries) {
          nativeLibraryDirectoriesBuilder.add(copyNativeLibraries.getPathToNativeLibsDir());
        } else {
          nativeLibraryDirectoriesBuilder.add(copyNativeLibraries.getPathToNativeLibsDir());
          nativeLibraryDirectoriesBuilder.add(copyNativeLibraries.getPathToNativeLibsAssetsDir());
        }
      }

      if ((!packageableCollection.getNativeLibAssetsDirectories().isEmpty())
          || (!packageableCollection.getNativeLinkablesAssets().isEmpty()
              && shouldPackageAssetLibraries)) {
        Preconditions.checkState(!ExopackageMode.enabledForResources(exopackageModes));
        Path pathForNativeLibsAsAssets = getPathForNativeLibsAsAssets();

        final Path libSubdirectory =
            pathForNativeLibsAsAssets
                .resolve("assets")
                .resolve(module.isRootModule() ? "lib" : module.getName());
        ImmutableCollection<SourcePath> nativeLibDirs =
            packageableCollection.getNativeLibAssetsDirectories().get(module);

        getStepsForNativeAssets(
            context.getSourcePathResolver(),
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
    RichStream.from(primaryApkAssetsZips)
        .map(context.getSourcePathResolver()::getRelativePath)
        .forEach(zipFiles::add);

    if (ExopackageMode.enabledForNativeLibraries(exopackageModes)) {
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
        packageableCollection
            .getPathsToThirdPartyJars()
            .stream()
            .map(resolver::getAbsolutePath)
            .collect(MoreCollectors.toImmutableSet());

    if (ExopackageMode.enabledForResources(exopackageModes)) {
      steps.add(createMergedThirdPartyJarsStep(thirdPartyJars));
      buildableContext.recordArtifact(getMergedThirdPartyJarsPath());
    }

    ApkBuilderStep apkBuilderCommand =
        new ApkBuilderStep(
            getProjectFilesystem(),
            context.getSourcePathResolver().getAbsolutePath(resourcesApkPath),
            getSignedApkPath(),
            dexFilesInfo.primaryDexPath,
            allAssetDirectories,
            nativeLibraryDirectoriesBuilder.build(),
            zipFiles.build(),
            thirdPartyJars,
            pathToKeystore,
            keystoreProperties,
            /* debugMode */ false,
            javaRuntimeLauncher);
    steps.add(apkBuilderCommand);

    // The `ApkBuilderStep` delegates to android tools to build a ZIP with timestamps in it, making
    // the output non-deterministic.  So use an additional scrubbing step to zero these out.
    steps.add(ZipScrubberStep.of(getProjectFilesystem().resolve(signedApkPath)));

    Path apkToRedexAndAlign;
    // Optionally, compress the resources file in the .apk.
    if (this.isCompressResources()) {
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
    Path apkPath = context.getSourcePathResolver().getRelativePath(getSourcePathToOutput());
    Path apkToAlign = apkToRedexAndAlign;

    // redex
    if (applyRedex) {
      Path proguardConfigDir = getProguardTextFilesPath();
      Path redexedApk = getRedexedApkPath();
      apkToAlign = redexedApk;
      steps.add(MkdirStep.of(getProjectFilesystem(), redexedApk.getParent()));
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
      SourcePathResolver resolver,
      ImmutableList.Builder<Step> steps,
      Optional<ImmutableCollection<SourcePath>> nativeLibDirs,
      final Path libSubdirectory,
      final String metadataFilename,
      final APKModule module) {
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), libSubdirectory));

    // Filter, rename and copy the ndk libraries marked as assets.
    if (nativeLibDirs.isPresent()) {
      for (SourcePath nativeLibDir : nativeLibDirs.get()) {
        CopyNativeLibraries.copyNativeLibrary(
            getProjectFilesystem(),
            resolver.getAbsolutePath(nativeLibDir),
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
      if (enhancementResult.getCopyNativeLibraries().isPresent()
          && enhancementResult.getCopyNativeLibraries().get().containsKey(module)) {
        // Copy in cxx libraries marked as assets. Filtering and renaming was already done
        // in CopyNativeLibraries.getBuildSteps().
        Path cxxNativeLibsSrc =
            enhancementResult
                .getCopyNativeLibraries()
                .get()
                .get(module)
                .getPathToNativeLibsAssetsDir();
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
            public StepExecutionResult execute(ExecutionContext context) {
              ProjectFilesystem filesystem = getProjectFilesystem();
              try {
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
              } catch (IOException e) {
                context.logError(e, "Writing metadata for asset libraries failed.");
                return StepExecutionResult.ERROR;
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
            public StepExecutionResult execute(ExecutionContext context) {
              try {
                ProjectFilesystem filesystem = getProjectFilesystem();
                for (Path libPath : inputAssetLibrariesBuilder.build()) {
                  Path tempPath = libPath.resolveSibling(libPath.getFileName() + "~");
                  filesystem.move(libPath, tempPath);
                  outputAssetLibrariesBuilder.add(tempPath);
                }
                return StepExecutionResult.SUCCESS;
              } catch (IOException e) {
                context.logError(e, "Renaming asset libraries failed");
                return StepExecutionResult.ERROR;
              }
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
      SourcePathResolver resolver,
      ImmutableList.Builder<Step> steps) {

    ImmutableSet<Path> classpathEntriesToDex =
        RichStream.from(enhancementResult.getClasspathEntriesToDex())
            .concat(
                RichStream.of(enhancementResult.getCompiledUberRDotJava().getSourcePathToOutput()))
            .map(input -> getProjectFilesystem().relativize(resolver.getAbsolutePath(input)))
            .toImmutableSet();

    ImmutableMultimap.Builder<APKModule, Path> additionalDexStoreToJarPathMapBuilder =
        ImmutableMultimap.builder();
    additionalDexStoreToJarPathMapBuilder.putAll(
        enhancementResult
            .getPackageableCollection()
            .getModuleMappedClasspathEntriesToDex()
            .entries()
            .stream()
            .map(
                input ->
                    new AbstractMap.SimpleEntry<>(
                        input.getKey(),
                        getProjectFilesystem()
                            .relativize(resolver.getAbsolutePath(input.getValue()))))
            .collect(MoreCollectors.toImmutableSet()));
    ImmutableMultimap<APKModule, Path> additionalDexStoreToJarPathMap =
        additionalDexStoreToJarPathMapBuilder.build();

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

      steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), preprocessJavaClassesInDir));
      steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), preprocessJavaClassesOutDir));

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
              /* bash */ preprocessJavaClassesBash.map(macroExpander::apply),
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
    if (packageType.isBuildWithObfuscation()) {
      classpathEntriesToDex =
          addProguardCommands(
              classpathEntriesToDex,
              proguardConfigs
                  .stream()
                  .map(resolver::getAbsolutePath)
                  .collect(MoreCollectors.toImmutableSet()),
              skipProguard,
              steps,
              buildableContext,
              resolver);
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
      Supplier<ImmutableMap<String, HashCode>> classNamesToHashesSupplier =
          addAccumulateClassNamesStep(classpathEntriesToDex, steps);

      steps.add(MkdirStep.of(getProjectFilesystem(), primaryDexPath.getParent()));

      addDexingSteps(
          classpathEntriesToDex,
          classNamesToHashesSupplier,
          secondaryDexDirectoriesBuilder,
          steps,
          primaryDexPath,
          dexReorderToolFile,
          dexReorderDataDumpFile,
          additionalDexStoreToJarPathMap,
          resolver);
    } else if (!ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      secondaryDexDirectoriesBuilder.addAll(preDexMerge.get().getSecondaryDexDirectories());
    }

    return new DexFilesInfo(primaryDexPath, secondaryDexDirectoriesBuilder.build());
  }

  public Supplier<ImmutableMap<String, HashCode>> addAccumulateClassNamesStep(
      final ImmutableSet<Path> classPathEntriesToDex, ImmutableList.Builder<Step> steps) {
    final ImmutableMap.Builder<String, HashCode> builder = ImmutableMap.builder();

    steps.add(
        new AbstractExecutionStep("collect_all_class_names") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) {
            for (Path path : classPathEntriesToDex) {
              Optional<ImmutableSortedMap<String, HashCode>> hashes =
                  AccumulateClassNamesStep.calculateClassHashes(
                      context, getProjectFilesystem(), path);
              if (!hashes.isPresent()) {
                return StepExecutionResult.ERROR;
              }
              builder.putAll(hashes.get());
            }
            return StepExecutionResult.SUCCESS;
          }
        });

    return Suppliers.memoize(builder::build);
  }

  public AndroidPackageableCollection getAndroidPackageableCollection() {
    return enhancementResult.getPackageableCollection();
  }

  /** All native-libs-as-assets are copied to this directory before running apkbuilder. */
  private Path getPathForNativeLibsAsAssets() {
    return getBinPath("__native_libs_as_assets_%s__");
  }

  public Keystore getKeystore() {
    return keystore;
  }

  public String getUnsignedApkPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.unsigned.apk")
        .toString();
  }

  private Path getMergedThirdPartyJarsPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.java.resources");
  }

  /** The APK at this path will be signed, but not zipaligned. */
  private Path getSignedApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".signed.apk"));
  }

  /** The APK at this path will have compressed resources, but will not be zipaligned. */
  private Path getCompressedResourcesApkPath() {
    return Paths.get(getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".compressed.apk"));
  }

  private Path getRedexedApkPath() {
    Path path = BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s__redex");
    return path.resolve(getBuildTarget().getShortName() + ".redex.apk");
  }

  private Path getBinPath(String format) {
    return BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), format);
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

  /** @return the resulting set of ProGuarded classpath entries to dex. */
  @VisibleForTesting
  ImmutableSet<Path> addProguardCommands(
      Set<Path> classpathEntriesToDex,
      Set<Path> depsProguardConfigs,
      boolean skipProguard,
      ImmutableList.Builder<Step> steps,
      BuildableContext buildableContext,
      SourcePathResolver resolver) {
    ImmutableSet.Builder<Path> additionalLibraryJarsForProguardBuilder = ImmutableSet.builder();

    for (JavaLibrary buildRule : rulesToExcludeFromDex) {
      additionalLibraryJarsForProguardBuilder.addAll(
          buildRule
              .getImmediateClasspaths()
              .stream()
              .map(resolver::getAbsolutePath)
              .collect(MoreCollectors.toImmutableSet()));
    }

    // Create list of proguard Configs for the app project and its dependencies
    ImmutableSet.Builder<Path> proguardConfigsBuilder = ImmutableSet.builder();
    proguardConfigsBuilder.addAll(depsProguardConfigs);
    if (proguardConfig.isPresent()) {
      proguardConfigsBuilder.add(resolver.getAbsolutePath(proguardConfig.get()));
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
        javaRuntimeLauncher,
        getProjectFilesystem(),
        proguardJarOverride.isPresent()
            ? Optional.of(resolver.getAbsolutePath(proguardJarOverride.get()))
            : Optional.empty(),
        proguardMaxHeapSize,
        proguardAgentPath,
        resolver.getRelativePath(aaptGeneratedProguardConfigFile),
        proguardConfigsBuilder.build(),
        sdkProguardConfig,
        optimizationPasses,
        proguardJvmArgs,
        inputOutputEntries,
        additionalLibraryJarsForProguardBuilder.build(),
        proguardConfigDir,
        buildableContext,
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
   *     PreDexMerge#getSecondaryDexDirectories()}.
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
      SourcePathResolver resolver) {
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
      if (packageType.isBuildWithObfuscation()) {
        Path proguardConfigDir = getProguardTextFilesPath();
        proguardFullConfigFile = Optional.of(proguardConfigDir.resolve("configuration.txt"));
        proguardMappingFile = Optional.of(proguardConfigDir.resolve("mapping.txt"));
      }

      // DexLibLoader expects that metadata.txt and secondary jar files are under this dir
      // in assets.

      // Intermediate directory holding the primary split-zip jar.
      Path splitZipDir = getBinPath("__%s_split_zip__");
      steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), splitZipDir));
      Path primaryJarPath = splitZipDir.resolve("primary.jar");

      Path secondaryJarMetaDirParent = splitZipDir.resolve("secondary_meta");
      Path secondaryJarMetaDir = secondaryJarMetaDirParent.resolve(SECONDARY_DEX_SUBDIR);
      steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), secondaryJarMetaDir));
      Path secondaryJarMeta = secondaryJarMetaDir.resolve("metadata.txt");

      // Intermediate directory holding _ONLY_ the secondary split-zip jar files.  This is
      // important because SmartDexingCommand will try to dx every entry in this directory.  It
      // does this because it's impossible to know what outputs split-zip will generate until it
      // runs.
      final Path secondaryZipDir = getBinPath("__%s_secondary_zip__");
      steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), secondaryZipDir));

      // Intermediate directory holding the directories holding _ONLY_ the additional split-zip
      // jar files that are intended for that dex store.
      final Path additionalDexStoresZipDir = getBinPath("__%s_dex_stores_zip__");
      steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), additionalDexStoresZipDir));
      for (APKModule dexStore : additionalDexStoreToJarPathMap.keySet()) {
        steps.addAll(
            MakeCleanDirectoryStep.of(
                getProjectFilesystem(), additionalDexStoresZipDir.resolve(dexStore.getName())));
        steps.addAll(
            MakeCleanDirectoryStep.of(
                getProjectFilesystem(),
                secondaryJarMetaDirParent.resolve("assets").resolve(dexStore.getName())));
      }

      // Run the split-zip command which is responsible for dividing the large set of input
      // classpaths into a more compact set of jar files such that no one jar file when dexed will
      // yield a dex artifact too large for dexopt or the dx method limit to handle.
      Path zipSplitReportDir = getBinPath("__%s_split_zip_report__");
      steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), zipSplitReportDir));
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
              enhancementResult.getAPKModuleGraph(),
              zipSplitReportDir);
      steps.add(splitZipCommand);

      // Add the secondary dex directory that has yet to be created, but will be by the
      // smart dexing command.  Smart dex will handle "cleaning" this directory properly.
      if (reorderClassesIntraDex) {
        secondaryDexDir =
            Optional.of(secondaryDexParentDir.resolve(SMART_DEX_SECONDARY_DEX_SUBDIR));
        Path intraDexReorderSecondaryDexDir = secondaryDexParentDir.resolve(SECONDARY_DEX_SUBDIR);
        steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), secondaryDexDir.get()));
        steps.addAll(
            MakeCleanDirectoryStep.of(getProjectFilesystem(), intraDexReorderSecondaryDexDir));
      } else {
        secondaryDexDir = Optional.of(secondaryDexParentDir.resolve(SECONDARY_DEX_SUBDIR));
        steps.add(MkdirStep.of(getProjectFilesystem(), secondaryDexDir.get()));
      }

      if (additionalDexStoreToJarPathMap.isEmpty()) {
        additionalDexDirs = Optional.empty();
      } else {
        ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
        for (APKModule dexStore : additionalDexStoreToJarPathMap.keySet()) {
          Path dexStorePath = additionalDexAssetsDir.resolve(dexStore.getName());
          builder.add(dexStorePath);
          steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), dexStorePath));
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
    steps.add(MkdirStep.of(getProjectFilesystem(), successDir));

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
        PackageType.RELEASE.equals(packageType)
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
              getProjectFilesystem(),
              resolver.getAbsolutePath(dexReorderToolFile.get()),
              resolver.getAbsolutePath(dexReorderDataDumpFile.get()),
              getBuildTarget(),
              selectedPrimaryDexPath,
              primaryDexPath,
              secondaryOutputToInputs,
              SMART_DEX_SECONDARY_DEX_SUBDIR,
              SECONDARY_DEX_SUBDIR);
      steps.add(intraDexReorderStep);
    }
  }

  private SourcePath getManifestPath() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(),
        BuildTargets.getGenPath(
            getProjectFilesystem(), getBuildTarget(), "%s/AndroidManifest.xml"));
  }

  boolean shouldSplitDex() {
    return dexSplitMode.isShouldSplitDex();
  }

  private Optional<ExopackageInfo> getExopackageInfo() {
    boolean shouldInstall = false;

    ExopackageInfo.Builder builder = ExopackageInfo.builder();
    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      PreDexMerge preDexMerge = enhancementResult.getPreDexMerge().get();
      builder.setDexInfo(
          ExopackageInfo.DexInfo.of(
              preDexMerge.getMetadataTxtPath(), preDexMerge.getDexDirectory()));
      shouldInstall = true;
    }

    if (ExopackageMode.enabledForNativeLibraries(exopackageModes)
        && enhancementResult.getCopyNativeLibraries().isPresent()) {
      CopyNativeLibraries copyNativeLibraries =
          Preconditions.checkNotNull(
              enhancementResult
                  .getCopyNativeLibraries()
                  .get()
                  .get(enhancementResult.getAPKModuleGraph().getRootAPKModule()));
      builder.setNativeLibsInfo(
          ExopackageInfo.NativeLibsInfo.of(
              copyNativeLibraries.getPathToMetadataTxt(),
              copyNativeLibraries.getPathToAllLibsDir()));
      shouldInstall = true;
    }

    if (ExopackageMode.enabledForResources(exopackageModes)) {
      Preconditions.checkState(!enhancementResult.getExoResources().isEmpty());
      builder.setResourcesInfo(
          ExopackageInfo.ResourcesInfo.of(
              ImmutableList.<SourcePath>builder()
                  .addAll(enhancementResult.getExoResources())
                  .add(
                      new ExplicitBuildTargetSourcePath(
                          getBuildTarget(), getMergedThirdPartyJarsPath()))
                  .build()));
      shouldInstall = true;
    } else {
      Preconditions.checkState(enhancementResult.getExoResources().isEmpty());
    }

    if (!shouldInstall) {
      return Optional.empty();
    }

    ExopackageInfo exopackageInfo = builder.build();
    return Optional.of(exopackageInfo);
  }

  public ImmutableSortedSet<BuildRule> getClasspathDeps() {
    return getDeclaredDeps();
  }

  @Override
  public ImmutableSet<SourcePath> getTransitiveClasspaths() {
    // This is used primarily for buck audit classpath.
    return JavaLibraryClasspathProvider.getClasspathsFromLibraries(getTransitiveClasspathDeps());
  }

  @Override
  public ImmutableSet<JavaLibrary> getTransitiveClasspathDeps() {
    return JavaLibraryClasspathProvider.getClasspathDeps(
        ImmutableSet.copyOf(
            ruleFinder.filterBuildRuleInputs(enhancementResult.getClasspathEntriesToDex())));
  }

  @Override
  public ImmutableSet<SourcePath> getImmediateClasspaths() {
    return ImmutableSet.of();
  }

  @Override
  public ImmutableSet<SourcePath> getOutputClasspaths() {
    // The apk has no exported deps or classpath contributions of its own
    return ImmutableSet.of();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    Stream.Builder<Stream<BuildTarget>> deps = Stream.builder();
    if (ExopackageMode.enabledForNativeLibraries(exopackageModes)
        && enhancementResult.getCopyNativeLibraries().isPresent()) {
      deps.add(
          enhancementResult
              .getCopyNativeLibraries()
              .get()
              .values()
              .stream()
              .map(BuildRule::getBuildTarget));
    }
    if (ExopackageMode.enabledForSecondaryDexes(exopackageModes)) {
      deps.add(
          OptionalCompat.asSet(enhancementResult.getPreDexMerge())
              .stream()
              .map(BuildRule::getBuildTarget));
    }
    return deps.build().reduce(Stream.empty(), Stream::concat);
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
}
