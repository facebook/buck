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

import com.android.common.SdkConstants;
import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.UberRDotJavaBuildable.ResourceCompressionMode;
import com.facebook.buck.dalvik.EstimateLinearAllocStep;
import com.facebook.buck.dalvik.ZipSplitter;
import com.facebook.buck.java.AccumulateClassNamesStep;
import com.facebook.buck.java.Classpaths;
import com.facebook.buck.java.HasClasspathEntries;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.InstallableBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.shell.AbstractGenruleStep;
import com.facebook.buck.shell.EchoStep;
import com.facebook.buck.shell.SymlinkFilesIntoDirectoryStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.Optionals;
import com.facebook.buck.zip.RepackZipEntriesStep;
import com.facebook.buck.zip.ZipDirectoryWithMaxDeflateStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * <pre>
 * android_binary(
 *   name = 'messenger',
 *   manifest = 'AndroidManifest.xml',
 *   target = 'Google Inc.:Google APIs:16',
 *   deps = [
 *     '//src/com/facebook/messenger:messenger_library',
 *   ],
 * )
 * </pre>
 */
public class AndroidBinaryRule extends DoNotUseAbstractBuildable implements
    HasAndroidPlatformTarget, HasClasspathEntries, InstallableBuildRule {

  private static final BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, PACKAGING);

  /**
   * This is the path from the root of the APK that should contain the metadata.txt and
   * secondary-N.dex.jar files for secondary dexes.
   */
  private static final String SECONDARY_DEX_SUBDIR = "assets/secondary-program-dex-jars";

  /**
   * The largest file size Froyo will deflate.
   */
  private final long FROYO_DEFLATE_LIMIT_BYTES = 1 << 20;

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
    private final boolean isBuildWithObfuscation() {
      return this == RELEASE;
    }

    final boolean isCrunchPngFiles() {
      return this == RELEASE;
    }
  }

  static enum TargetCpuType {
    ARM,
    ARMV7,
    X86,
    MIPS,
  }

  private final SourcePath manifest;
  private final String target;
  private final ImmutableSortedSet<BuildRule> classpathDeps;
  private final Keystore keystore;
  private final PackageType packageType;
  private final ImmutableSortedSet<BuildRule> buildRulesToExcludeFromDex;
  private DexSplitMode dexSplitMode;
  private final boolean useAndroidProguardConfigWithOptimizations;
  private final Optional<SourcePath> proguardConfig;
  private final ResourceCompressionMode resourceCompressionMode;
  private final ImmutableSet<String> primaryDexSubstrings;
  private final long linearAllocHardLimit;

  /**
   * File that whitelists the class files that should be in the primary dex.
   * <p>
   * Values in this file must match JAR entries exactly, so they should contain path separators.
   * For example:
   * <pre>
   * com/google/common/collect/ImmutableSet.class
   * </pre>
   */
  private final Optional<SourcePath> primaryDexClassesFile;

  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final ImmutableSet<IntermediateDexRule> preDexDeps;
  private final UberRDotJavaBuildable uberRDotJavaBuildable;
  private final AaptPackageResources aaptPackageResourcesBuildable;
  private final ImmutableSortedSet<BuildRule> preprocessJavaClassesDeps;
  private final Optional<String> preprocessJavaClassesBash;
  private final AndroidResourceDepsFinder androidResourceDepsFinder;
  private final AndroidTransitiveDependencyGraph transitiveDependencyGraph;

  /** This path is guaranteed to end with a slash. */
  private final String outputGenDirectory;

  /**
   * @param target the Android platform version to target, e.g., "Google Inc.:Google APIs:16". You
   *     can find the list of valid values on your system by running
   *     {@code android list targets --compact}.
   */
  protected AndroidBinaryRule(
      BuildRuleParams buildRuleParams,
      SourcePath manifest,
      String target,
      ImmutableSortedSet<BuildRule> classpathDeps,
      Keystore keystore,
      PackageType packageType,
      Set<BuildRule> buildRulesToExcludeFromDex,
      DexSplitMode dexSplitMode,
      boolean useAndroidProguardConfigWithOptimizations,
      Optional<SourcePath> proguardConfig,
      ResourceCompressionMode resourceCompressionMode,
      Set<String> primaryDexSubstrings,
      long linearAllocHardLimit,
      Optional<SourcePath> primaryDexClassesFile,
      Set<TargetCpuType> cpuFilters,
      Set<IntermediateDexRule> preDexDeps,
      UberRDotJavaBuildable uberRDotJavaBuildable,
      AaptPackageResources aaptPackageResourcesBuildable,
      Set<BuildRule> preprocessJavaClassesDeps,
      Optional<String> preprocessJavaClassesBash,
      AndroidResourceDepsFinder androidResourceDepsFinder,
      AndroidTransitiveDependencyGraph transitiveDependencyGraph) {
    super(buildRuleParams);
    this.manifest = Preconditions.checkNotNull(manifest);
    this.target = Preconditions.checkNotNull(target);
    this.classpathDeps = ImmutableSortedSet.copyOf(classpathDeps);
    this.keystore = Preconditions.checkNotNull(keystore);
    this.packageType = Preconditions.checkNotNull(packageType);
    this.buildRulesToExcludeFromDex = ImmutableSortedSet.copyOf(buildRulesToExcludeFromDex);
    this.dexSplitMode = Preconditions.checkNotNull(dexSplitMode);
    this.useAndroidProguardConfigWithOptimizations = useAndroidProguardConfigWithOptimizations;
    this.proguardConfig = Preconditions.checkNotNull(proguardConfig);
    this.resourceCompressionMode = Preconditions.checkNotNull(resourceCompressionMode);
    this.primaryDexSubstrings = ImmutableSet.copyOf(primaryDexSubstrings);
    this.linearAllocHardLimit = linearAllocHardLimit;
    this.primaryDexClassesFile = Preconditions.checkNotNull(primaryDexClassesFile);
    this.outputGenDirectory = String.format("%s/%s",
        BuckConstant.GEN_DIR,
        getBuildTarget().getBasePathWithSlash());
    this.cpuFilters = ImmutableSet.copyOf(cpuFilters);
    this.preDexDeps = ImmutableSet.copyOf(preDexDeps);
    this.uberRDotJavaBuildable = Preconditions.checkNotNull(uberRDotJavaBuildable);
    this.aaptPackageResourcesBuildable = Preconditions.checkNotNull(aaptPackageResourcesBuildable);
    this.preprocessJavaClassesDeps = ImmutableSortedSet.copyOf(preprocessJavaClassesDeps);
    this.preprocessJavaClassesBash = Preconditions.checkNotNull(preprocessJavaClassesBash);
    this.androidResourceDepsFinder = Preconditions.checkNotNull(androidResourceDepsFinder);
    this.transitiveDependencyGraph = Preconditions.checkNotNull(transitiveDependencyGraph);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ANDROID_BINARY;
  }

  @Override
  public BuildableProperties getProperties() {
    return PROPERTIES;
  }

  @Override
  public String getAndroidPlatformTarget() {
    return target;
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException {
    super.appendToRuleKey(builder)
        .set("manifest", manifest.asReference())
        .set("target", target)
        .set("keystore", keystore.getBuildTarget().getFullyQualifiedName())
        .setRuleNames("classpathDeps", classpathDeps)
        .set("packageType", packageType.toString())
        .set("buildRulesToExcludeFromDex", buildRulesToExcludeFromDex)
        .set("useAndroidProguardConfigWithOptimizations", useAndroidProguardConfigWithOptimizations)
        .set("proguardConfig", proguardConfig.transform(SourcePath.TO_REFERENCE))
        .set("resourceCompressionMode", resourceCompressionMode.toString())
        .set("primaryDexSubstrings", primaryDexSubstrings)
        .set("linearAllocHardLimit", linearAllocHardLimit)
        .set("primaryDexClassesFile", primaryDexClassesFile.transform(SourcePath.TO_REFERENCE))
        .set("cpuFilters", ImmutableSortedSet.copyOf(cpuFilters).toString())
        .set("preprocessJavaClassesBash", preprocessJavaClassesBash)
        .set("preprocessJavaClassesDeps", preprocessJavaClassesDeps);
    return dexSplitMode.appendToRuleKey("dexSplitMode", builder);
  }

  public ImmutableSortedSet<BuildRule> getBuildRulesToExcludeFromDex() {
    return buildRulesToExcludeFromDex;
  }

  public AndroidTransitiveDependencyGraph getTransitiveDependencyGraph() {
    return transitiveDependencyGraph;
  }

  public Optional<SourcePath> getProguardConfig() {
    return proguardConfig;
  }

  public boolean isRelease() {
    return packageType == PackageType.RELEASE;
  }

  private boolean isCompressResources(){
    return resourceCompressionMode.isCompressResources();
  }

  public ResourceCompressionMode getResourceCompressionMode() {
    return resourceCompressionMode;
  }

  public ImmutableSet<TargetCpuType> getCpuFilters() {
    return this.cpuFilters;
  }

  public UberRDotJavaBuildable getUberRDotJavaBuildable() {
    return uberRDotJavaBuildable;
  }

  public ImmutableSortedSet<BuildRule> getPreprocessJavaClassesDeps() {
    return preprocessJavaClassesDeps;
  }

  public Optional<String> getPreprocessJavaClassesBash() {
    return preprocessJavaClassesBash;
  }

  /**
   * Native libraries compiled for different CPU architectures are placed in the
   * respective ABI subdirectories, such as 'armeabi', 'armeabi-v7a', 'x86' and 'mips'.
   * This looks at the cpu filter and returns the correct subdirectory. If cpu filter is
   * not present or not supported, returns Optional.absent();
   */
  private static Optional<String> getAbiDirectoryComponent(TargetCpuType cpuType) {
    String component = null;
    if (cpuType.equals(TargetCpuType.ARM)) {
      component = SdkConstants.ABI_ARMEABI;
    } else if (cpuType.equals(TargetCpuType.ARMV7)) {
      component = SdkConstants.ABI_ARMEABI_V7A;
    } else if (cpuType.equals(TargetCpuType.X86)) {
      component = SdkConstants.ABI_INTEL_ATOM;
    } else if (cpuType.equals(TargetCpuType.MIPS)) {
      component = SdkConstants.ABI_MIPS;
    }
    return Optional.fromNullable(component);

  }

  @VisibleForTesting
  static void copyNativeLibrary(String sourceDir,
      String destinationDir,
      ImmutableSet<TargetCpuType> cpuFilters,
      ImmutableList.Builder<Step> steps) {
    Path sourceDirPath = Paths.get(sourceDir);
    Path destinationDirPath = Paths.get(destinationDir);

    if (cpuFilters.isEmpty()) {
      steps.add(new CopyStep(sourceDirPath, destinationDirPath, true));
    } else {
      for (TargetCpuType cpuType : cpuFilters) {
        Optional<String> abiDirectoryComponent = getAbiDirectoryComponent(cpuType);
        Preconditions.checkState(abiDirectoryComponent.isPresent());

        final Path libSourceDir = sourceDirPath.resolve(abiDirectoryComponent.get());
        Path libDestinationDir = destinationDirPath.resolve(abiDirectoryComponent.get());

        final MkdirStep mkDirStep = new MkdirStep(libDestinationDir);
        final CopyStep copyStep = new CopyStep(libSourceDir, libDestinationDir, true);
        steps.add(new Step() {
          @Override
          public int execute(ExecutionContext context) {
            if (!context.getProjectFilesystem().exists(libSourceDir.toString())) {
              return 0;
            }
            if (mkDirStep.execute(context) == 0 && copyStep.execute(context) == 0) {
              return 0;
            }
            return 1;
          }

          @Override
          public String getShortName() {
            return "copy_native_libraries";
          }

          @Override
          public String getDescription(ExecutionContext context) {
            ImmutableList.Builder<String> stringBuilder = ImmutableList.builder();
            stringBuilder.add(String.format("[ -d %s ]", libSourceDir.toString()));
            stringBuilder.add(mkDirStep.getDescription(context));
            stringBuilder.add(copyStep.getDescription(context));
            return Joiner.on(" && ").join(stringBuilder.build());
          }
        });
      }
    }
  }

  /** The APK at this path is the final one that points to an APK that a user should install. */
  @Override
  public String getApkPath() {
    return getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".apk");
  }

  @Override
  public String getPathToOutputFile() {
    return getApkPath();
  }

  @Override
  public List<String> getInputsToCompareToOutput() {
    ImmutableList.Builder<SourcePath> sourcePaths = ImmutableList.builder();
    sourcePaths.add(manifest);

    Optionals.addIfPresent(proguardConfig, sourcePaths);

    ImmutableList.Builder<String> inputs = ImmutableList.builder();
    inputs.addAll(SourcePaths.filterInputsToCompareToOutput(sourcePaths.build()));
    return inputs.build();
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    final AndroidTransitiveDependencies transitiveDependencies = findTransitiveDependencies();

    // Copy the transitive closure of files in native_libs to a single directory, if any.
    ImmutableSet<String> nativeLibraryDirectories;
    if (!transitiveDependencies.nativeLibsDirectories.isEmpty()) {
      String pathForNativeLibs = getPathForNativeLibs();
      String libSubdirectory = pathForNativeLibs + "/lib";
      steps.add(new MakeCleanDirectoryStep(libSubdirectory));
      for (String nativeLibDir : transitiveDependencies.nativeLibsDirectories) {
        copyNativeLibrary(nativeLibDir, libSubdirectory, cpuFilters, steps);
      }
      nativeLibraryDirectories = ImmutableSet.of(libSubdirectory);
    } else {
      nativeLibraryDirectories = ImmutableSet.of();
    }

    // Create the .dex files and create the unsigned APK using ApkBuilder.
    AndroidDexTransitiveDependencies dexTransitiveDependencies =
        findDexTransitiveDependencies();
    String signedApkPath = getSignedApkPath();
    addDxAndApkBuilderSteps(context,
        steps,
        transitiveDependencies,
        dexTransitiveDependencies,
        uberRDotJavaBuildable.getResDirectories(),
        nativeLibraryDirectories,
        aaptPackageResourcesBuildable.getResourceApkPath(),
        signedApkPath);

    String apkToAlign;
    // Optionally, compress the resources file in the .apk.
    if (this.isCompressResources()) {
      String compressedApkPath = getCompressedResourcesApkPath();
      apkToAlign = compressedApkPath;
      RepackZipEntriesStep arscComp = new RepackZipEntriesStep(
          signedApkPath,
          compressedApkPath,
          ImmutableSet.of("resources.arsc"));
      steps.add(arscComp);
    } else {
      apkToAlign = signedApkPath;
    }

    String apkPath = getApkPath();
    ZipalignStep zipalign = new ZipalignStep(apkToAlign, apkPath);
    steps.add(zipalign);

    // Inform the user where the APK can be found.
    EchoStep success = new EchoStep(
        String.format("built APK for %s at %s", getFullyQualifiedName(), apkPath));
    steps.add(success);

    return steps.build();
  }

  private void addDxAndApkBuilderSteps(BuildContext context,
      ImmutableList.Builder<Step> steps,
      final AndroidTransitiveDependencies transitiveDependencies,
      final AndroidDexTransitiveDependencies dexTransitiveDependencies,
      ImmutableSet<String> resDirectories,
      ImmutableSet<String> nativeLibraryDirectories,
      String resourceApkPath,
      String unsignedApkPath) {
    // Execute preprocess_java_classes_binary, if appropriate.
    ImmutableSet<String> classpathEntriesToDex;
    if (preprocessJavaClassesBash.isPresent()) {
      // Symlink everything in dexTransitiveDependencies.classpathEntriesToDex to the input
      // directory. Expect parallel outputs in the output directory and update classpathEntriesToDex
      // to reflect that.
      final String preprocessJavaClassesInDir = getBinPath("java_classes_preprocess_in_%s");
      final String preprocessJavaClassesOutDir = getBinPath("java_classes_preprocess_out_%s");
      steps.add(new MakeCleanDirectoryStep(preprocessJavaClassesInDir));
      steps.add(new MakeCleanDirectoryStep(preprocessJavaClassesOutDir));
      steps.add(new SymlinkFilesIntoDirectoryStep(
          Paths.get("."),
          dexTransitiveDependencies.classpathEntriesToDex,
          Paths.get(preprocessJavaClassesInDir)
          ));
      classpathEntriesToDex = FluentIterable.from(dexTransitiveDependencies.classpathEntriesToDex)
          .transform(new Function<String, String>() {
            @Override
            public String apply(String classpathEntry) {
              return Paths.get(preprocessJavaClassesOutDir, classpathEntry).toString();
            }
          })
          .toSet();

      AbstractGenruleStep.CommandString commandString = new AbstractGenruleStep.CommandString(
          /* cmd */ Optional.<String>absent(),
          /* bash */ preprocessJavaClassesBash,
          /* cmdExe */ Optional.<String>absent());
      steps.add(new AbstractGenruleStep(
          this, commandString, preprocessJavaClassesDeps, new File(preprocessJavaClassesInDir)) {

        @Override
        protected void addEnvironmentVariables(
            ExecutionContext context,
            ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
          Function<String, Path> pathRelativizer =
              context.getProjectFilesystem().getPathRelativizer();
          environmentVariablesBuilder.put(
              "IN_JARS_DIR", pathRelativizer.apply(preprocessJavaClassesInDir).toString());
          environmentVariablesBuilder.put(
              "OUT_JARS_DIR", pathRelativizer.apply(preprocessJavaClassesOutDir).toString());
        }

      });

    } else {
      classpathEntriesToDex = dexTransitiveDependencies.classpathEntriesToDex;
    }

    // Execute proguard if desired (transforms input classpaths).
    if (packageType.isBuildWithObfuscation()) {
      classpathEntriesToDex = addProguardCommands(
          context,
          classpathEntriesToDex,
          transitiveDependencies.proguardConfigs,
          steps,
          resDirectories);
    }

    // Create the final DEX (or set of DEX files in the case of split dex).
    // The APK building command needs to take a directory of raw files, so we create a directory
    // that can only contain .dex files from this build rule.
    String dexDir = getBinPath(".dex/%s");
    steps.add(new MkdirStep(dexDir));
    String dexFile = String.format("%s/classes.dex", dexDir);

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
    ImmutableSet.Builder<String> secondaryDexDirectoriesBuilder = ImmutableSet.builder();
    if (preDexDeps.isEmpty()) {
      addDexingSteps(
          classpathEntriesToDex,
          secondaryDexDirectoriesBuilder,
          steps,
          dexFile,
          context.getSourcePathResolver());
    } else if (!dexSplitMode.isShouldSplitDex()) {
      Iterable<Path> filesToDex = FluentIterable.from(preDexDeps)
          .transform(
              new Function<IntermediateDexRule, Path>() {
                  @Override
                  @Nullable
                  public Path apply(IntermediateDexRule preDexDep) {
                    DexProducedFromJavaLibraryThatContainsClassFiles preDex = preDexDep
                        .getBuildable();
                    if (preDex.hasOutput()) {
                      return preDex.getPathToDex();
                    } else {
                      return null;
                    }
                  }
              })
          .filter(Predicates.notNull());

      // If this APK has Android resources, then the generated R.class files also need to be dexed.
      if (dexTransitiveDependencies.pathToCompiledRDotJavaFiles.isPresent()) {
        Path pathToCompiledRDotJavaFilesDirectory =
            dexTransitiveDependencies.pathToCompiledRDotJavaFiles.get();
        filesToDex = Iterables.concat(filesToDex,
            Collections.singleton(pathToCompiledRDotJavaFilesDirectory));
      }

      // This will combine the pre-dexed files and the R.class files into a single classes.dex file.
      steps.add(new DxStep(dexFile, filesToDex, PreDexMergeStep.DX_MERGE_OPTIONS));
    } else {
      // At least initially (and possibly always), the logic for merging pre-dexed artifacts will
      // not leverage SmartDexingStep, so mergePreDexedArtifactsIntoMultipleDexFiles() and
      // addDexingSteps() do not use a common implementation. We hope to use graph-enhancement and
      // its natural cacheability rather than SmartDexingStep to limit the number of secondary
      // dex files that we need to rebuild.
      mergePreDexedArtifactsIntoMultipleDexFiles(preDexDeps,
          dexTransitiveDependencies.pathToCompiledRDotJavaFiles,
          secondaryDexDirectoriesBuilder,
          steps,
          dexFile);
    }
    ImmutableSet<String> secondaryDexDirectories = secondaryDexDirectoriesBuilder.build();

    // Due to limitations of Froyo, we need to ensure that all secondary zip files are STORED in
    // the final APK, not DEFLATED.  The only way to ensure this with ApkBuilder is to zip up the
    // the files properly and then add the zip files to the apk.
    ImmutableSet.Builder<String> secondaryDexZips = ImmutableSet.builder();
    for (String secondaryDexDirectory : secondaryDexDirectories) {
      Preconditions.checkState(secondaryDexDirectory.endsWith("/"));
      // String the trailing slash from the directory name and add the zip extension.
      String zipFile = secondaryDexDirectory.replaceAll("/$", "") + ".zip";

      secondaryDexZips.add(zipFile);
      steps.add(new ZipDirectoryWithMaxDeflateStep(secondaryDexDirectory,
          zipFile,
          FROYO_DEFLATE_LIMIT_BYTES));
    }

    ApkBuilderStep apkBuilderCommand = new ApkBuilderStep(
        resourceApkPath,
        unsignedApkPath,
        dexFile,
        ImmutableSet.<String>of(),
        nativeLibraryDirectories,
        secondaryDexZips.build(),
        dexTransitiveDependencies.pathsToThirdPartyJars,
        keystore.getPathToStore(),
        keystore.getPathToPropertiesFile(),
        /* debugMode */ false);
    steps.add(apkBuilderCommand);
  }

  /**
   * @param preDexDeps The set of pre-dexed JAR files that should go into the final APK.
   * @param pathToCompiledRDotJavaFiles If present, the directory where the {@code R.class} files
   *     for the final APK.
   * @param secondaryDexDirectoriesBuilder The contract for updating this builder must match that
   *     of {@link #addDexingSteps}.
   * @param steps The collection of steps to which steps needed to produce the dex files should be
   *     added.
   * @param primaryDexPath The path where the primary {@code classes.dex} file should be written.
   */
  private void mergePreDexedArtifactsIntoMultipleDexFiles(
      ImmutableSet<IntermediateDexRule> preDexDeps,
      Optional<Path> pathToCompiledRDotJavaFiles,
      ImmutableSet.Builder<String> secondaryDexDirectoriesBuilder,
      ImmutableList.Builder<Step> steps,
      String primaryDexPath) {
    // Run dx on the R.class files, if they exist.
    Path rDotJavaScratchDir = Paths.get(getBinPath("__%s_r_dot_java_scratch__"));
    Optional<DexWithClasses> dexWithClassesForRDotJava = createDexWithClassesForRDotJava(
        pathToCompiledRDotJavaFiles, rDotJavaScratchDir, steps);

    // Collect all of the DexWithClasses objects to use for merging.
    ImmutableList<DexWithClasses> dexFilesToMerge = FluentIterable.from(preDexDeps)
        .transform(DexWithClasses.TO_DEX_WITH_CLASSES)
        .filter(Predicates.notNull())
        .toList();

    // Create all of the output paths needed for the PreDexMergeStep.
    String secondaryDexScratchDir = getBinPath("__%s_secondary_dex__/");
    String secondaryDexMetadataScratchDir = secondaryDexScratchDir + "metadata/";
    String secondaryDexJarFilesScratchDir = secondaryDexScratchDir + "jarfiles/";
    secondaryDexDirectoriesBuilder.add(secondaryDexMetadataScratchDir);
    secondaryDexDirectoriesBuilder.add(secondaryDexJarFilesScratchDir);

    String secondaryDexMetadataDir = secondaryDexMetadataScratchDir + SECONDARY_DEX_SUBDIR;
    String secondaryDexJarFilesDir = secondaryDexJarFilesScratchDir + SECONDARY_DEX_SUBDIR;
    steps.add(new MakeCleanDirectoryStep(secondaryDexMetadataDir));
    steps.add(new MakeCleanDirectoryStep(secondaryDexJarFilesDir));

    // Add a step to do the bucketing and dexing. The bucketing must be done at runtime because it
    // is not safe to invoke dexWithClassesForRDotJava.getLinearAllocEstimate() at this point, but
    // it will be safe by the time the PreDexMergeStep is executed. (By comparison, it is safe to
    // invoke getLinearAllocEstimate() on every element in dexFilesToMerge at this point.)
    Path preDexScratchDir = Paths.get(secondaryDexScratchDir, "__pre_dex_tmp__");
    steps.add(new MakeCleanDirectoryStep(preDexScratchDir));
    steps.add(new PreDexMergeStep(dexFilesToMerge,
        dexWithClassesForRDotJava,
        primaryDexPath,
        primaryDexSubstrings,
        Paths.get(secondaryDexMetadataDir, "metadata.txt"),
        secondaryDexJarFilesDir,
        dexSplitMode.getDexStore(),
        linearAllocHardLimit,
        preDexScratchDir));
  }

  @VisibleForTesting
  static Optional<DexWithClasses> createDexWithClassesForRDotJava(
      Optional<Path> pathToCompiledRDotJavaFiles,
      Path rDotJavaScratchDir,
      ImmutableList.Builder<Step> steps) {
    if (!pathToCompiledRDotJavaFiles.isPresent()) {
      return Optional.absent();
    }

    steps.add(new MakeCleanDirectoryStep(rDotJavaScratchDir));

    final AccumulateClassNamesStep accumulateClassNames = new AccumulateClassNamesStep(
        pathToCompiledRDotJavaFiles,
        rDotJavaScratchDir.resolve("classes.txt"));
    steps.add(accumulateClassNames);

    final Path rDotJavaDex = rDotJavaScratchDir.resolve("classes.dex.jar");

    steps.add(new DxStep(rDotJavaDex.toString(),
        /* filesToDex */ Collections.singleton(pathToCompiledRDotJavaFiles.get()),
        EnumSet.of(DxStep.Option.NO_OPTIMIZE)));

    final EstimateLinearAllocStep estimateLinearAllocStep = new EstimateLinearAllocStep(
        pathToCompiledRDotJavaFiles.get());
    steps.add(estimateLinearAllocStep);

    return Optional.<DexWithClasses>of(new DexWithClasses() {
      @Override
      public Path getPathToDexFile() {
        return rDotJavaDex;
      }

      @Override
      public ImmutableSet<String> getClassNames() {
        // By the time accumulateClassNames.get() is invoked, it should have already been built,
        // so it should not throw a "step was not executed yet" error.
        return accumulateClassNames.get().keySet();
      }

      @Override
      public int getSizeEstimate() {
        // By the time estimateLinearAllocStep.get() is invoked, it should have already been built,
        // so it should not throw a "step was not executed yet" error.
        return estimateLinearAllocStep.get();
      }
    });
  }

  public AndroidTransitiveDependencies findTransitiveDependencies() {
    return androidResourceDepsFinder.getAndroidTransitiveDependencies();
  }

  public AndroidDexTransitiveDependencies findDexTransitiveDependencies() {
    return androidResourceDepsFinder.getAndroidDexTransitiveDependencies(
        uberRDotJavaBuildable);
  }

  /**
   * This is the path to the directory for generated files related to ProGuard. Ultimately, it
   * should include:
   * <ul>
   *   <li>proguard.txt
   *   <li>dump.txt
   *   <li>seeds.txt
   *   <li>usage.txt
   *   <li>mapping.txt
   *   <li>obfuscated.jar
   * </ul>
   * @return path to directory (will not include trailing slash)
   */
  @VisibleForTesting
  Path getPathForProGuardDirectory() {
    return MorePaths.newPathInstance(
        String.format("%s/%s.proguard/%s",
        BuckConstant.GEN_DIR,
        getBuildTarget().getBasePathWithSlash(),
        getBuildTarget().getShortName()));
  }

  /**
   * All native libs are copied to this directory before running aapt.
   */
  private String getPathForNativeLibs() {
    return getBinPath("__native_libs_%s__");
  }

  public Keystore getKeystore() {
    return keystore;
  }

  public String getUnsignedApkPath() {
    return String.format("%s%s.unsigned.apk",
        outputGenDirectory,
        getBuildTarget().getShortName());
  }

  /** The APK at this path will be signed, but not zipaligned. */
  private String getSignedApkPath() {
    return getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".signed.apk");
  }

  /** The APK at this path will have compressed resources, but will not be zipaligned. */
  private String getCompressedResourcesApkPath() {
    return getUnsignedApkPath().replaceAll("\\.unsigned\\.apk$", ".compressed.apk");
  }

  /**
   * Return a path to a file in the buck-out/bin/ directory. {@code format} will be prepended with
   * the {@link BuckConstant#BIN_DIR} and the target base path, then formatted with the target
   * short name.
   * {@code format} should not start with a slash.
   */
  private String getBinPath(String format) {
    return String.format("%s/%s" + format,
        BuckConstant.BIN_DIR,
        getBuildTarget().getBasePathWithSlash(),
        getBuildTarget().getShortName());
  }

  @VisibleForTesting
  Path getProguardOutputFromInputClasspath(String classpathEntry) {
    // Hehe, this is so ridiculously fragile.
    Preconditions.checkArgument(classpathEntry.charAt(0) != '/',
        "Classpath entries should be relative rather than absolute paths: %s",
        classpathEntry);
    String obfuscatedName = Files.getNameWithoutExtension(classpathEntry) + "-obfuscated.jar";
    Path dirName = MorePaths.newPathInstance(new File(classpathEntry).getParent());
    Path outputJar = getPathForProGuardDirectory().resolve(dirName).resolve(obfuscatedName);
    return outputJar;
  }

  /**
   * @return the resulting set of ProGuarded classpath entries to dex.
   */
  @VisibleForTesting
  ImmutableSet<String> addProguardCommands(
      BuildContext context,
      Set<String> classpathEntriesToDex,
      Set<String> depsProguardConfigs,
      ImmutableList.Builder<Step> steps,
      Set<String> resDirectories) {
    final ImmutableSetMultimap<JavaLibraryRule, String> classpathEntriesMap =
        getTransitiveClasspathEntries();
    ImmutableSet.Builder<String> additionalLibraryJarsForProguardBuilder = ImmutableSet.builder();

    for (BuildRule buildRule : buildRulesToExcludeFromDex) {
      if (buildRule instanceof JavaLibraryRule) {
        additionalLibraryJarsForProguardBuilder.addAll(
            classpathEntriesMap.get((JavaLibraryRule)buildRule));
      }
    }

    // Clean out the directory for generated ProGuard files.
    Path proguardDirectory = getPathForProGuardDirectory();
    steps.add(new MakeCleanDirectoryStep(proguardDirectory));

    // Generate a file of ProGuard config options using aapt.
    String generatedProGuardConfig = proguardDirectory + "/proguard.txt";
    GenProGuardConfigStep genProGuardConfig = new GenProGuardConfigStep(
        aaptPackageResourcesBuildable.getAndroidManifestXml(),
        resDirectories,
        generatedProGuardConfig);
    steps.add(genProGuardConfig);

    // Create list of proguard Configs for the app project and its dependencies
    ImmutableSet.Builder<String> proguardConfigsBuilder = ImmutableSet.builder();
    proguardConfigsBuilder.addAll(depsProguardConfigs);
    if (proguardConfig.isPresent()) {
      proguardConfigsBuilder.add(proguardConfig.get().resolve(context).toString());
    }

    // Transform our input classpath to a set of output locations for each input classpath.
    // TODO(devjasta): the output path we choose is the result of a slicing function against
    // input classpath. This is fragile and should be replaced with knowledge of the BuildTarget.
    final ImmutableMap<String, String> inputOutputEntries = FluentIterable
        .from(classpathEntriesToDex)
        .toMap(new Function<String, String>() {
          @Override
          public String apply(String classpathEntry) {
            return getProguardOutputFromInputClasspath(classpathEntry).toString();
          }
        });

    // Run ProGuard on the classpath entries.
    // TODO: ProGuardObfuscateStep's final argument should be a Path
    Step obfuscateCommand = ProGuardObfuscateStep.create(
        generatedProGuardConfig,
        proguardConfigsBuilder.build(),
        useAndroidProguardConfigWithOptimizations,
        inputOutputEntries,
        additionalLibraryJarsForProguardBuilder.build(),
        proguardDirectory.toString());
    steps.add(obfuscateCommand);

    // Apply the transformed inputs to the classpath (this will modify deps.classpathEntriesToDex
    // so that we're now dexing the proguarded artifacts).
    return ImmutableSet.copyOf(inputOutputEntries.values());
  }

  /**
   * Create dex artifacts for all of the individual directories of compiled .class files (or
   * the obfuscated jar files if proguard is used).  If split dex is used, multiple dex artifacts
   * will be produced.
   *
   * @param classpathEntriesToDex Full set of classpath entries that must make
   *     their way into the final APK structure (but not necessarily into the
   *     primary dex).
   * @param secondaryDexDirectoriesBuilder The contract for updating this builder must match that
   *     of {@link #mergePreDexedArtifactsIntoMultipleDexFiles}.
   * @param steps List of steps to add to.
   * @param primaryDexPath Output path for the primary dex file.
   */
  @VisibleForTesting
  void addDexingSteps(
      Set<String> classpathEntriesToDex,
      ImmutableSet.Builder<String> secondaryDexDirectories,
      ImmutableList.Builder<Step> steps,
      String primaryDexPath,
      Function<SourcePath, Path> sourcePathResolver) {
    final Set<String> primaryInputsToDex;
    final Optional<String> secondaryDexDir;
    final Optional<String> secondaryInputsDir;

    if (shouldSplitDex()) {
      Optional<Path> proguardMappingFile = Optional.absent();
      if (packageType.isBuildWithObfuscation()) {
        proguardMappingFile = Optional.of(getPathForProGuardDirectory().resolve("mapping.txt"));
      }

      // DexLibLoader expects that metadata.txt and secondary jar files are under this dir
      // in assets.

      // Intermediate directory holding the primary split-zip jar.
      String splitZipDir = getBinPath("__%s_split_zip__");
      steps.add(new MakeCleanDirectoryStep(splitZipDir));
      String primaryJarPath = splitZipDir + "/primary.jar";

      String secondaryJarMetaDirParent = splitZipDir + "/secondary_meta/";
      String secondaryJarMetaDir = secondaryJarMetaDirParent + SECONDARY_DEX_SUBDIR;
      steps.add(new MakeCleanDirectoryStep(secondaryJarMetaDir));
      String secondaryJarMeta = secondaryJarMetaDir + "/metadata.txt";

      // Intermediate directory holding _ONLY_ the secondary split-zip jar files.  This is
      // important because SmartDexingCommand will try to dx every entry in this directory.  It
      // does this because it's impossible to know what outputs split-zip will generate until it
      // runs.
      String secondaryZipDir = getBinPath("__%s_secondary_zip__");
      steps.add(new MakeCleanDirectoryStep(secondaryZipDir));

      // Run the split-zip command which is responsible for dividing the large set of input
      // classpaths into a more compact set of jar files such that no one jar file when dexed will
      // yield a dex artifact too large for dexopt or the dx method limit to handle.
      String zipSplitReportDir = getBinPath("__%s_split_zip_report__");
      steps.add(new MakeCleanDirectoryStep(zipSplitReportDir));
      SplitZipStep splitZipCommand = new SplitZipStep(
          classpathEntriesToDex,
          secondaryJarMeta,
          primaryJarPath,
          secondaryZipDir,
          "secondary-%d.jar",
          proguardMappingFile,
          primaryDexSubstrings,
          primaryDexClassesFile.transform(sourcePathResolver),
          dexSplitMode.getDexSplitStrategy(),
          dexSplitMode.getDexStore(),
          zipSplitReportDir,
          dexSplitMode.useLinearAllocSplitDex(),
          linearAllocHardLimit);
      steps.add(splitZipCommand);

      // Add the secondary dex directory that has yet to be created, but will be by the
      // smart dexing command.  Smart dex will handle "cleaning" this directory properly.
      String secondaryDexParentDir = getBinPath("__%s_secondary_dex__/");
      secondaryDexDir = Optional.of(secondaryDexParentDir + SECONDARY_DEX_SUBDIR);
      steps.add(new MkdirStep(secondaryDexDir.get()));

      secondaryDexDirectories.add(secondaryJarMetaDirParent);
      secondaryDexDirectories.add(secondaryDexParentDir);

      // Adjust smart-dex inputs for the split-zip case.
      primaryInputsToDex = ImmutableSet.of(primaryJarPath);
      secondaryInputsDir = Optional.of(secondaryZipDir);
    } else {
      // Simple case where our inputs are the natural classpath directories and we don't have
      // to worry about secondary jar/dex files.
      primaryInputsToDex = classpathEntriesToDex;
      secondaryDexDir = Optional.absent();
      secondaryInputsDir = Optional.absent();
    }

    // Stores checksum information from each invocation to intelligently decide when dx needs
    // to be re-run.
    Path successDir = Paths.get(getBinPath("__%s_smart_dex__/.success"));
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
    SmartDexingStep smartDexingCommand = new SmartDexingStep(
        primaryDexPath,
        primaryInputsToDex,
        secondaryDexDir,
        secondaryInputsDir,
        successDir,
        Optional.<Integer>absent(),
        dexSplitMode.getDexStore(),
        /* optimize */ PackageType.RELEASE.equals(packageType));
    steps.add(smartDexingCommand);
  }

  /**
   * @return the path to the AndroidManifest.xml. Note that this file is not guaranteed to be named
   *     AndroidManifest.xml.
   */
  @Override
  public SourcePath getManifest() {
    return manifest;
  }

  String getTarget() {
    return target;
  }

  boolean shouldSplitDex() {
    return dexSplitMode.isShouldSplitDex();
  }

  boolean isUseAndroidProguardConfigWithOptimizations() {
    return useAndroidProguardConfigWithOptimizations;
  }

  ImmutableSet<String> getPrimaryDexSubstrings() {
    return primaryDexSubstrings;
  }

  long getLinearAllocHardLimit() {
    return linearAllocHardLimit;
  }

  Optional<SourcePath> getPrimaryDexClassesFile() {
    return primaryDexClassesFile;
  }

  public ImmutableSortedSet<BuildRule> getClasspathDeps() {
    return classpathDeps;
  }

  @Override
  public ImmutableSetMultimap<JavaLibraryRule, String> getTransitiveClasspathEntries() {
    // This is used primarily for buck audit classpath.
    return Classpaths.getClasspathEntries(classpathDeps);
  }

  public static Builder newAndroidBinaryRuleBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildRuleBuilder<AndroidBinaryRule> {
    private static final PackageType DEFAULT_PACKAGE_TYPE = PackageType.DEBUG;

    private SourcePath manifest;
    private String target;

    /** This should always be a subset of {@link #getDeps()}. */
    private ImmutableSet.Builder<BuildTarget> classpathDepsBuilder = ImmutableSet.builder();

    private BuildTarget keystoreTarget;
    private PackageType packageType = DEFAULT_PACKAGE_TYPE;
    private ImmutableSet.Builder<BuildTarget> buildTargetsToExcludeFromDexBuilder =
        ImmutableSet.builder();
    private boolean disablePreDex = false;
    private DexSplitMode dexSplitMode = new DexSplitMode(
        /* shouldSplitDex */ false,
        ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
        DexStore.JAR,
        /* useLinearAllocSplitDex */ false);
    private boolean useAndroidProguardConfigWithOptimizations = false;
    private Optional<SourcePath> proguardConfig = Optional.absent();
    private ResourceCompressionMode resourceCompressionMode = ResourceCompressionMode.DISABLED;
    private ImmutableSet.Builder<String> primaryDexSubstrings = ImmutableSet.builder();
    private long linearAllocHardLimit = 0;
    private Optional<SourcePath> primaryDexClassesFile = Optional.absent();
    private FilterResourcesStep.ResourceFilter resourceFilter = ResourceFilter.EMPTY_FILTER;
    private ImmutableSet.Builder<TargetCpuType> cpuFilters = ImmutableSet.builder();
    private ImmutableSet.Builder<BuildTarget> preprocessJavaClassesDeps = ImmutableSet.builder();
    private Optional<String> preprocessJavaClassesBash = Optional.absent();

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    public AndroidBinaryRule build(BuildRuleResolver ruleResolver) {
      // Make sure the "keystore" argument refers to a KeystoreRule.
      BuildRule rule = ruleResolver.get(keystoreTarget);

      Buildable keystore = rule.getBuildable();
      if (!(keystore instanceof Keystore)) {
        throw new HumanReadableException(
            "In %s, keystore='%s' must be a keystore() but was %s().",
            getBuildTarget(),
            rule.getFullyQualifiedName(),
            rule.getType().getName());
      }

      ImmutableSortedSet<BuildRule> classpathDeps = getBuildTargetsAsBuildRules(ruleResolver,
          classpathDepsBuilder.build());
      AndroidTransitiveDependencyGraph androidTransitiveDependencyGraph =
          new AndroidTransitiveDependencyGraph(classpathDeps);

      BuildRuleParams originalParams = createBuildRuleParams(ruleResolver);
      final ImmutableSortedSet<BuildRule> originalDeps = originalParams.getDeps();
      ImmutableSet<IntermediateDexRule> preDexDeps;
      ImmutableSet<BuildTarget> buildTargetsToExcludeFromDex =
          buildTargetsToExcludeFromDexBuilder.build();
      AndroidBinaryGraphEnhancer graphEnhancer = new AndroidBinaryGraphEnhancer(
          originalParams, buildTargetsToExcludeFromDex);
      if (!disablePreDex
          && PackageType.DEBUG.equals(packageType)
          && !preprocessJavaClassesBash.isPresent()
          ) {
        preDexDeps = graphEnhancer.createDepsForPreDexing(ruleResolver);
      } else {
        preDexDeps = ImmutableSet.of();
      }

      // Create the BuildRule and Buildable for UberRDotJava.
      boolean allowNonExistentRule =
          false;
      ImmutableSortedSet<BuildRule> buildRulesToExcludeFromDex = getBuildTargetsAsBuildRules(
          ruleResolver,
          buildTargetsToExcludeFromDex,
          allowNonExistentRule);
      AndroidResourceDepsFinder androidResourceDepsFinder = new AndroidResourceDepsFinder(
          androidTransitiveDependencyGraph,
          buildRulesToExcludeFromDex) {
        @Override
        protected ImmutableList<HasAndroidResourceDeps> findMyAndroidResourceDeps() {
          return UberRDotJavaUtil.getAndroidResourceDeps(originalDeps);
        }

        @Override
        protected Set<HasAndroidResourceDeps> findMyAndroidResourceDepsUnsorted() {
          return UberRDotJavaUtil.getAndroidResourceDepsUnsorted(originalDeps);
        }
      };

      AndroidBinaryGraphEnhancer.Result result = graphEnhancer.addBuildablesToCreateAaptResources(ruleResolver,
          resourceCompressionMode,
          resourceFilter,
          androidResourceDepsFinder,
          manifest,
          packageType,
          cpuFilters.build(),
          preDexDeps);

      return new AndroidBinaryRule(
          result.getParams(),
          manifest,
          target,
          getBuildTargetsAsBuildRules(ruleResolver, classpathDepsBuilder.build()),
          (Keystore)keystore,
          packageType,
          buildRulesToExcludeFromDex,
          dexSplitMode,
          useAndroidProguardConfigWithOptimizations,
          proguardConfig,
          resourceCompressionMode,
          primaryDexSubstrings.build(),
          linearAllocHardLimit,
          primaryDexClassesFile,
          cpuFilters.build(),
          preDexDeps,
          result.getUberRDotJavaBuildable(),
          result.getAaptPackageResources(),
          getBuildTargetsAsBuildRules(ruleResolver, preprocessJavaClassesDeps.build()),
          preprocessJavaClassesBash,
          androidResourceDepsFinder,
          androidTransitiveDependencyGraph);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(BuildTarget dep) {
      super.addDep(dep);
      return this;
    }

    @Override
    public Builder addVisibilityPattern(BuildTargetPattern visibilityPattern) {
      super.addVisibilityPattern(visibilityPattern);
      return this;
    }

    public Builder setManifest(SourcePath manifest) {
      this.manifest = manifest;
      return this;
    }

    public Builder setTarget(String target) {
      this.target = target;
      return this;
    }

    public Builder addClasspathDep(BuildTarget classpathDep) {
      this.classpathDepsBuilder.add(classpathDep);
      addDep(classpathDep);
      return this;
    }

    public Builder setKeystore(BuildTarget keystoreTarget) {
      this.keystoreTarget = keystoreTarget;
      addDep(keystoreTarget);
      return this;
    }

    public Builder setPackageType(String packageType) {
      if (packageType == null) {
        this.packageType = DEFAULT_PACKAGE_TYPE;
      } else {
        this.packageType = PackageType.valueOf(packageType.toUpperCase());
      }
      return this;
    }

    public Builder addBuildRuleToExcludeFromDex(BuildTarget entry) {
      this.buildTargetsToExcludeFromDexBuilder.add(entry);
      return this;
    }

    public Builder setDisablePreDex(boolean disablePreDex) {
      this.disablePreDex = disablePreDex;
      return this;
    }

    public Builder setDexSplitMode(DexSplitMode dexSplitMode) {
      this.dexSplitMode = dexSplitMode;
      return this;
    }

    public Builder setUseAndroidProguardConfigWithOptimizations(
        boolean useAndroidProguardConfigWithOptimizations) {
      this.useAndroidProguardConfigWithOptimizations = useAndroidProguardConfigWithOptimizations;
      return this;
    }

    public Builder setProguardConfig(Optional<SourcePath> proguardConfig) {
      this.proguardConfig = Preconditions.checkNotNull(proguardConfig);
      return this;
    }

    public Builder addPrimaryDexSubstrings(Iterable<String> primaryDexSubstrings) {
      this.primaryDexSubstrings.addAll(primaryDexSubstrings);
      return this;
    }

    public Builder setLinearAllocHardLimit(long linearAllocHardLimit) {
      this.linearAllocHardLimit = linearAllocHardLimit;
      return this;
    }

    public Builder setPrimaryDexClassesFile(Optional<SourcePath> primaryDexClassesFile) {
      this.primaryDexClassesFile = Preconditions.checkNotNull(primaryDexClassesFile);
      return this;
    }

    public Builder setResourceFilter(ResourceFilter resourceFilter) {
      this.resourceFilter = Preconditions.checkNotNull(resourceFilter);
      return this;
    }

    public Builder setResourceCompressionMode(String resourceCompressionMode) {
      Preconditions.checkNotNull(resourceCompressionMode);
      try {
        this.resourceCompressionMode = ResourceCompressionMode.valueOf(
            resourceCompressionMode.toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new HumanReadableException(String.format(
            "In %s, android_binary() was passed an invalid resource compression mode: %s",
            buildTarget.getFullyQualifiedName(),
            resourceCompressionMode));
      }
      return this;
    }

    public Builder addCpuFilter(String cpuFilter) {
      if (cpuFilter != null) {
        try {
          this.cpuFilters.add(TargetCpuType.valueOf(cpuFilter.toUpperCase()));
        } catch (IllegalArgumentException e) {
          throw new HumanReadableException(
              "android_binary() was passed an invalid cpu filter: " + cpuFilter);
        }
      }
      return this;
    }

    public Builder addPreprocessJavaClassesDep(BuildTarget preprocessJavaClassesDep) {
      this.preprocessJavaClassesDeps.add(preprocessJavaClassesDep);
      this.addDep(preprocessJavaClassesDep);
      return this;
    }

    public Builder setPreprocessJavaClassesBash(
        Optional<String> preprocessJavaClassesBash) {
      this.preprocessJavaClassesBash = Preconditions.checkNotNull(preprocessJavaClassesBash);
      return this;
    }
  }
}
