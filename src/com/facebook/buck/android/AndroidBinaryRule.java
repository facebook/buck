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
import com.facebook.buck.dalvik.ZipSplitter;
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
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.DoNotUseAbstractBuildable;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.InstallableBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.shell.AbstractGenruleStep;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.shell.EchoStep;
import com.facebook.buck.shell.SymlinkFilesIntoDirectoryStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.facebook.buck.util.DefaultFilteredDirectoryCopier;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.Paths;
import com.facebook.buck.zip.RepackZipEntriesStep;
import com.facebook.buck.zip.ZipDirectoryWithMaxDeflateStep;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

  private final static BuildableProperties PROPERTIES = new BuildableProperties(ANDROID, PACKAGING);

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

    private final boolean isCrunchPngFiles() {
      return this == RELEASE;
    }
  }

  static enum TargetCpuType {
    ARM,
    ARMV7,
    X86,
    MIPS
  }

  private final String manifest;
  private final String target;
  private final ImmutableSortedSet<BuildRule> classpathDeps;
  private final Keystore keystore;
  private final PackageType packageType;
  private final ImmutableSortedSet<BuildRule> buildRulesToExcludeFromDex;
  private DexSplitMode dexSplitMode;
  private final boolean useAndroidProguardConfigWithOptimizations;
  private final Optional<SourcePath> proguardConfig;
  private final boolean compressResources;
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

  private final FilterResourcesStep.ResourceFilter resourceFilter;
  private final ImmutableSet<TargetCpuType> cpuFilters;
  private final ImmutableSortedSet<BuildRule> preprocessJavaClassesDeps;
  private final Optional<String> preprocessJavaClassesBash;
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
      String manifest,
      String target,
      ImmutableSortedSet<BuildRule> classpathDeps,
      Keystore keystore,
      PackageType packageType,
      Set<BuildRule> buildRulesToExcludeFromDex,
      DexSplitMode dexSplitMode,
      boolean useAndroidProguardConfigWithOptimizations,
      Optional<SourcePath> proguardConfig,
      boolean compressResources,
      Set<String> primaryDexSubstrings,
      long linearAllocHardLimit,
      Optional<SourcePath> primaryDexClassesFile,
      FilterResourcesStep.ResourceFilter resourceFilter,
      Set<TargetCpuType> cpuFilters,
      Set<BuildRule> preprocessJavaClassesDeps,
      Optional<String> preprocessJavaClassesBash) {
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
    this.compressResources = compressResources;
    this.primaryDexSubstrings = ImmutableSet.copyOf(primaryDexSubstrings);
    this.linearAllocHardLimit = linearAllocHardLimit;
    this.primaryDexClassesFile = Preconditions.checkNotNull(primaryDexClassesFile);
    this.outputGenDirectory = String.format("%s/%s",
        BuckConstant.GEN_DIR,
        getBuildTarget().getBasePathWithSlash());
    this.resourceFilter = Preconditions.checkNotNull(resourceFilter);
    this.cpuFilters = ImmutableSet.copyOf(cpuFilters);
    this.preprocessJavaClassesDeps = ImmutableSortedSet.copyOf(preprocessJavaClassesDeps);
    this.preprocessJavaClassesBash = Preconditions.checkNotNull(preprocessJavaClassesBash);
    this.transitiveDependencyGraph = new AndroidTransitiveDependencyGraph(this);
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
        .set("manifest", manifest)
        .set("target", target)
        .set("keystore", keystore.getBuildTarget().getFullyQualifiedName())
        .setRuleNames("classpathDeps", classpathDeps)
        .set("packageType", packageType.toString())
        .set("buildRulesToExcludeFromDex", buildRulesToExcludeFromDex)
        .set("useAndroidProguardConfigWithOptimizations", useAndroidProguardConfigWithOptimizations)
        .set("proguardConfig", proguardConfig.transform(SourcePath.TO_REFERENCE))
        .set("compressResources", compressResources)
        .set("primaryDexSubstrings", primaryDexSubstrings)
        .set("linearAllocHardLimit", linearAllocHardLimit)
        .set("primaryDexClassesFile", primaryDexClassesFile.transform(SourcePath.TO_REFERENCE))
        .set("resourceFilter", resourceFilter.getDescription())
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

  public boolean isCompressResources(){
    return this.compressResources;
  }

  public FilterResourcesStep.ResourceFilter getResourceFilter() {
    return this.resourceFilter;
  }

  public ImmutableSet<TargetCpuType> getCpuFilters() {
    return this.cpuFilters;
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

  // TODO(user): Replace the BashSteps in this class with Steps that are platform-agnostic.
  // You'll need to do this as part of making it possible to build an APK on Windows using Buck.

  @VisibleForTesting
  void copyNativeLibrary(String sourceDir,
      String destinationDir,
      ImmutableList.Builder<Step> commands) {
    if (getCpuFilters().isEmpty()) {
      commands.add(new BashStep(String.format("cp -R %s/* %s", sourceDir, destinationDir)));
    } else {
      for (TargetCpuType cpuType: getCpuFilters()) {
        Optional<String> abiDirectoryComponent = getAbiDirectoryComponent(cpuType);
        Preconditions.checkState(abiDirectoryComponent.isPresent());
        String libsDirectory = sourceDir + "/" + abiDirectoryComponent.get();
        commands.add(new BashStep(String.format(
            "[ -d %s ] && cp -R %s %s || exit 0", libsDirectory, libsDirectory, destinationDir)));
      }
    }
  }

  public DexSplitMode getDexSplitMode() {
    return dexSplitMode;
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
    ImmutableList.Builder<String> inputs = ImmutableList.builder();
    inputs.add(manifest);
    if (proguardConfig.isPresent()) {
      SourcePath sourcePath = proguardConfig.get();
      // Alternatively, if it is a BuildTargetSourcePath, then it should not be included.
      if (sourcePath instanceof FileSourcePath) {
        inputs.add(sourcePath.asReference());
      }
    }

    return inputs.build();
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> commands = ImmutableList.builder();
    // Map from asset name to pathname for extra files to be added to assets.
    ImmutableMap.Builder<String, File> extraAssetsBuilder = ImmutableMap.builder();

    // Symlink the manifest to a path named AndroidManifest.xml. Do this before running any other
    // commands to ensure that it is available at the desired path.
    commands.add(new MkdirAndSymlinkFileStep(getManifest(), getAndroidManifestXml()));

    final AndroidTransitiveDependencies transitiveDependencies = findTransitiveDependencies(
        context.getDependencyGraph());

    final AndroidDexTransitiveDependencies dexTransitiveDependencies =
        findDexTransitiveDependencies(context.getDependencyGraph());

    Set<String> resDirectories = transitiveDependencies.resDirectories;
    Set<String> rDotJavaPackages = transitiveDependencies.rDotJavaPackages;


    FilterResourcesStep.ResourceFilter resourceFilter = getResourceFilter();
    // If resource filtering was requested (currently only by dpi).
    if (resourceFilter.isEnabled()) {
      FilterResourcesStep filterResourcesCommand = new FilterResourcesStep(
          resDirectories,
          new File(getBinPath("__filtered__%s__")),
          resourceFilter.getDensity(),
          DefaultFilteredDirectoryCopier.getInstance(),
          FilterResourcesStep.DefaultDrawableFinder.getInstance(),
          resourceFilter.shouldDownscale() ? FilterResourcesStep.ImageMagickScaler.getInstance() : null
      );
      commands.add(filterResourcesCommand);
      resDirectories = filterResourcesCommand.getFilteredResourceDirectories();
    }

    // Extract the resources from third-party jars.
    // TODO(mbolin): The results of this should be cached between runs.
    String extractedResourcesDir = getBinPath("__resources__%s__");
    commands.add(new MakeCleanDirectoryStep(extractedResourcesDir));
    commands.add(new ExtractResourcesStep(dexTransitiveDependencies.pathsToThirdPartyJars,
        extractedResourcesDir));

    // Create the R.java files. Their compiled versions must be included in classes.dex.
    // TODO(mbolin): Skip this step if the transitive set of AndroidResourceRules is cached.
    if (!resDirectories.isEmpty()) {
      UberRDotJavaUtil.generateRDotJavaFiles(resDirectories,
          rDotJavaPackages,
          getBuildTarget(),
          commands);
    }

    // Execute preprocess_java_classes_binary, if appropriate.
    ImmutableSet<String> classpathEntriesToDex;
    if (preprocessJavaClassesBash.isPresent()) {
      // Symlink everything in dexTransitiveDependencies.classpathEntriesToDex to the input
      // directory. Expect parallel outputs in the output directory and update classpathEntriesToDex
      // to reflect that.
      final String preprocessJavaClassesInDir = getBinPath("java_classes_preprocess_in_%s");
      final String preprocessJavaClassesOutDir = getBinPath("java_classes_preprocess_out_%s");
      commands.add(new MakeCleanDirectoryStep(preprocessJavaClassesInDir));
      commands.add(new MakeCleanDirectoryStep(preprocessJavaClassesOutDir));
      commands.add(new SymlinkFilesIntoDirectoryStep(
          java.nio.file.Paths.get("."),
          dexTransitiveDependencies.classpathEntriesToDex,
          java.nio.file.Paths.get(preprocessJavaClassesInDir)
          ));
      classpathEntriesToDex = FluentIterable.from(dexTransitiveDependencies.classpathEntriesToDex)
          .transform(new Function<String, String>() {
            @Override
            public String apply(String classpathEntry) {
              return java.nio.file.Paths.get(preprocessJavaClassesOutDir, classpathEntry).toString();
            }
          })
          .toSet();

      AbstractGenruleStep.CommandString commandString = new AbstractGenruleStep.CommandString(
          /* cmd */ Optional.<String>absent(),
          /* bash */ preprocessJavaClassesBash,
          /* cmdExe */ Optional.<String>absent());
      commands.add(new AbstractGenruleStep(this, commandString, preprocessJavaClassesDeps) {

        @Override
        protected void addEnvironmentVariables(
            ExecutionContext context,
            ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
          environmentVariablesBuilder.put("IN_JARS_DIR", preprocessJavaClassesInDir);
          environmentVariablesBuilder.put("OUT_JARS_DIR", preprocessJavaClassesOutDir);
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
          commands,
          resDirectories);
    }

    // Create the final DEX (or set of DEX files in the case of split dex).
    // The APK building command needs to take a directory of raw files, so we create a directory
    // that can only contain .dex files from this build rule.
    String dexDir = getBinPath(".dex/%s");
    commands.add(new MkdirStep(dexDir));
    String dexFile = String.format("%s/classes.dex", dexDir);

    final ImmutableSet.Builder<String> secondaryDexDirectories = ImmutableSet.builder();

    // Create dex artifacts. This may modify assetsDirectories.
    addDexingCommands(
        classpathEntriesToDex,
        secondaryDexDirectories,
        commands,
        dexFile,
        context.getSourcePathResolver());

    // Copy the transitive closure of files in assets to a single directory, if any.
    final ImmutableMap<String, File> extraAssets = extraAssetsBuilder.build();
    Step collectAssets = new Step() {
      @Override
      public int execute(ExecutionContext context) {
        // This must be done in a Command because the files and directories that are specified may
        // not exist at the time this Command is created because the previous Commands have not run
        // yet.
        ImmutableList.Builder<Step> commands = ImmutableList.builder();
        try {
          createAllAssetsDirectory(
              transitiveDependencies.assetsDirectories,
              extraAssets,
              commands,
              new DefaultDirectoryTraverser());
        } catch (IOException e) {
          e.printStackTrace(context.getStdErr());
          return 1;
        }

        for (Step command : commands.build()) {
          int exitCode = command.execute(context);
          if (exitCode != 0) {
            throw new HumanReadableException("Error running " + command.getDescription(context));
          }
        }

        return 0;
      }

      @Override
      public String getShortName() {
        return "symlink_assets";
      }

      @Override
      public String getDescription(ExecutionContext context) {
        return getShortName();
      }
    };
    commands.add(collectAssets);

    // Copy the transitive closure of files in native_libs to a single directory, if any.
    ImmutableSet.Builder<String> nativeLibraryDirectories = ImmutableSet.builder();
    if (!transitiveDependencies.nativeLibsDirectories.isEmpty()) {
      String pathForNativeLibs = getPathForNativeLibs();
      String libSubdirectory = pathForNativeLibs + "/lib";
      nativeLibraryDirectories.add(libSubdirectory);
      commands.add(new MakeCleanDirectoryStep(libSubdirectory));
      for (String nativeLibDir : transitiveDependencies.nativeLibsDirectories) {
        // TODO(mbolin): Verify whether this check is actually necessary. If not, remove it.
        if (nativeLibDir.endsWith("/")) {
          nativeLibDir = nativeLibDir.substring(0, nativeLibDir.length() - 1);
        }
        copyNativeLibrary(nativeLibDir, libSubdirectory, commands);
      }
    }

    // Create the unsigned APK.
    String resourceApkPath = getResourceApkPath();
    String unsignedApkPath = getUnsignedApkPath();

    Optional<String> assetsDirectory;
    if (transitiveDependencies.assetsDirectories.isEmpty() && extraAssets.isEmpty()
        && transitiveDependencies.nativeLibAssetsDirectories.isEmpty()) {
      assetsDirectory = Optional.absent();
    } else {
      assetsDirectory = Optional.of(getPathToAllAssetsDirectory());
    }

    if (!transitiveDependencies.nativeLibAssetsDirectories.isEmpty()) {
      String nativeLibAssetsDir = assetsDirectory.get() + "/lib";
      commands.add(new MakeCleanDirectoryStep(nativeLibAssetsDir));
      for (String nativeLibDir : transitiveDependencies.nativeLibAssetsDirectories) {
        copyNativeLibrary(nativeLibDir, nativeLibAssetsDir, commands);
      }
    }

    commands.add(new MkdirStep(outputGenDirectory));

    if (!canSkipAaptResourcePackaging()) {
      AaptStep aaptCommand = new AaptStep(
          getAndroidManifestXml(),
          resDirectories,
          assetsDirectory,
          resourceApkPath,
          ImmutableSet.of(extractedResourcesDir),
          packageType.isCrunchPngFiles());
      commands.add(aaptCommand);
    }

    // Due to limitations of Froyo, we need to ensure that all secondary zip files are STORED in
    // the final APK, not DEFLATED.  The only way to ensure this with ApkBuilder is to zip up the
    // the files properly and then add the zip files to the apk.
    ImmutableSet.Builder<String> secondaryDexZips = ImmutableSet.builder();
    for (String secondaryDexDirectory : secondaryDexDirectories.build()) {
      // String the trailing slash from the directory name and add the zip extension.
      String zipFile = secondaryDexDirectory.replaceAll("/$", "") + ".zip";

      secondaryDexZips.add(zipFile);
      commands.add(new ZipDirectoryWithMaxDeflateStep(secondaryDexDirectory,
          zipFile,
          FROYO_DEFLATE_LIMIT_BYTES));
    }

    ApkBuilderStep apkBuilderCommand = new ApkBuilderStep(
        resourceApkPath,
        unsignedApkPath,
        dexFile,
        ImmutableSet.<String>of(),
        nativeLibraryDirectories.build(),
        secondaryDexZips.build(),
        false);
    commands.add(apkBuilderCommand);

    // Sign the APK.
    String signedApkPath = getSignedApkPath();
    SignApkStep signApkStep = new SignApkStep(
        keystore.getPathToStore(), keystore.getPathToPropertiesFile(), unsignedApkPath, signedApkPath);
    commands.add(signApkStep);

    String apkToAlign;

    // Optionally, compress the resources file in the .apk.
    if (this.isCompressResources()) {
      String compressedApkPath = getCompressedResourcesApkPath();
      apkToAlign = compressedApkPath;
      RepackZipEntriesStep arscComp = new RepackZipEntriesStep(
          signedApkPath,
          compressedApkPath,
          ImmutableSet.of("resources.arsc"));
      commands.add(arscComp);
    } else {
      apkToAlign = signedApkPath;
    }

    String apkPath = getApkPath();
    ZipalignStep zipalign = new ZipalignStep(apkToAlign, apkPath);
    commands.add(zipalign);

    // Inform the user where the APK can be found.
    EchoStep success = new EchoStep(
        String.format("built APK for %s at %s", getFullyQualifiedName(), apkPath));
    commands.add(success);

    return commands.build();
  }

  /**
   * Given a set of assets directories to include in the APK (which may be empty), return the path
   * to the directory that contains the union of all the assets. If any work needs to be done to
   * create such a directory, the appropriate commands should be added to the {@code commands}
   * list builder.
   * <p>
   * If there are no assets (i.e., {@code assetsDirectories} is empty), then the return value will
   * be an empty {@link Optional}.
   */
  @VisibleForTesting
  Optional<String> createAllAssetsDirectory(
      Set<String> assetsDirectories,
      ImmutableMap<String, File> extraAssets,
      ImmutableList.Builder<Step> commands,
      DirectoryTraverser traverser) throws IOException {
    if (assetsDirectories.isEmpty() && extraAssets.isEmpty()) {
      return Optional.absent();
    }

    // Due to a limitation of aapt, only one assets directory can be specified, so if multiple are
    // specified in Buck, then all of the contents must be symlinked to a single directory.
    String destination = getPathToAllAssetsDirectory();
    commands.add(new MakeCleanDirectoryStep(destination));
    final ImmutableMap.Builder<String, File> allAssets = ImmutableMap.builder();

    File destinationDirectory = new File(destination);
    for (String assetsDirectory : assetsDirectories) {
      traverser.traverse(new DirectoryTraversal(new File(assetsDirectory)) {
        @Override
        public void visit(File file, String relativePath) {
          allAssets.put(relativePath, file);
        }
      });
    }

    allAssets.putAll(extraAssets);

    for (Map.Entry<String, File> entry : allAssets.build().entrySet()) {
      commands.add(new MkdirAndSymlinkFileStep(
          MorePaths.newPathInstance(entry.getValue()).toString(),
          MorePaths.newPathInstance(destinationDirectory + "/" + entry.getKey()).toString()));
    }

    return Optional.of(destination);
  }

  public AndroidTransitiveDependencies findTransitiveDependencies(DependencyGraph graph) {
    return getTransitiveDependencyGraph().findDependencies(getAndroidResourceDepsInternal(graph));
  }

  public AndroidDexTransitiveDependencies findDexTransitiveDependencies(DependencyGraph graph) {
    return getTransitiveDependencyGraph().findDexDependencies(
        getAndroidResourceDepsInternal(graph),
        buildRulesToExcludeFromDex);
  }

  /**
   * @return a list of {@link HasAndroidResourceDeps}s that should be passed, in order, to {@code aapt}
   *     when generating the {@code R.java} files for this APK.
   */
  protected ImmutableList<HasAndroidResourceDeps> getAndroidResourceDepsInternal(
      DependencyGraph graph) {
    return UberRDotJavaUtil.getAndroidResourceDeps(this, graph);
  }

  private boolean canSkipAaptResourcePackaging() {
    // TODO(mbolin): Create a RuleKey for resources only and use it to determine the value of this
    // boolean. Whether the resources have not changed since the last build run is irrelevant
    // because this AndroidBinary may not have been written as part of the last build run.
    return false;
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
  String getPathForProGuardDirectory() {
    return String.format("%s/%s.proguard/%s",
        BuckConstant.GEN_DIR,
        getBuildTarget().getBasePathWithSlash(),
        getBuildTarget().getShortName());
  }

  @VisibleForTesting
  String getPathToAllAssetsDirectory() {
    String format = "__assets_%s__";
    return getBinPath(format);
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

  public String getResourceApkPath() {
    return String.format("%s%s.unsigned.ap_",
        outputGenDirectory,
        getBuildTarget().getShortName());
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
   * Buck does not require the manifest to be named AndroidManifest.xml, but commands such as aapt
   * do. For this reason, we symlink the path to {@link #getManifest()} to the path returned by
   * this method, whose name is always "AndroidManifest.xml".
   * <p>
   * Therefore, commands created by this method should use this method instead of
   * {@link #getManifest()}.
   */
  private String getAndroidManifestXml() {
    return getBinPath("__manifest_%s__/AndroidManifest.xml");
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
  String getProguardOutputFromInputClasspath(String classpathEntry) {
    // Hehe, this is so ridiculously fragile.
    Preconditions.checkArgument(classpathEntry.charAt(0) != '/',
        "Classpath entries should be relative rather than absolute paths: %s",
        classpathEntry);
    String obfuscatedName = Files.getNameWithoutExtension(classpathEntry) + "-obfuscated.jar";
    String dirName = Paths.normalizePathSeparator(new File(classpathEntry).getParent());
    String outputJar = getPathForProGuardDirectory() + "/" + dirName + "/" +
        obfuscatedName;
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
      ImmutableList.Builder<Step> commands,
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
    String proguardDirectory = getPathForProGuardDirectory();
    commands.add(new MakeCleanDirectoryStep(proguardDirectory));

    // Generate a file of ProGuard config options using aapt.
    String generatedProGuardConfig = proguardDirectory + "/proguard.txt";
    GenProGuardConfigStep genProGuardConfig = new GenProGuardConfigStep(
        getAndroidManifestXml(),
        resDirectories,
        generatedProGuardConfig);
    commands.add(genProGuardConfig);

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
            return getProguardOutputFromInputClasspath(classpathEntry);
          }
        });

    // Run ProGuard on the classpath entries.
    ProGuardObfuscateStep obfuscateCommand = new ProGuardObfuscateStep(
        generatedProGuardConfig,
        proguardConfigsBuilder.build(),
        useAndroidProguardConfigWithOptimizations,
        inputOutputEntries,
        additionalLibraryJarsForProguardBuilder.build(),
        proguardDirectory);
    commands.add(obfuscateCommand);

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
   * @param commands
   * @param primaryDexPath Output path for the primary dex file.
   */
  @VisibleForTesting
  void addDexingCommands(
      Set<String> classpathEntriesToDex,
      ImmutableSet.Builder<String> secondaryDexDirectories,
      ImmutableList.Builder<Step> commands,
      String primaryDexPath,
      Function<SourcePath, Path> sourcePathResolver) {
    final Set<String> primaryInputsToDex;
    final Optional<String> secondaryDexDir;
    final Optional<String> secondaryInputsDir;

    if (shouldSplitDex()) {
      // DexLibLoader expects that metadata.txt and secondary jar files are under this dir
      // in assets.
      String magicSecondaryDexSubdir = "assets/secondary-program-dex-jars";

      // Intermediate directory holding the primary split-zip jar.
      String splitZipDir = getBinPath("__%s_split_zip__");
      commands.add(new MakeCleanDirectoryStep(splitZipDir));
      String primaryJarPath = splitZipDir + "/primary.jar";

      String secondaryJarMetaDirParent = splitZipDir + "/secondary_meta/";
      String secondaryJarMetaDir = secondaryJarMetaDirParent + magicSecondaryDexSubdir;
      commands.add(new MakeCleanDirectoryStep(secondaryJarMetaDir));
      String secondaryJarMeta = secondaryJarMetaDir + "/metadata.txt";

      // Intermediate directory holding _ONLY_ the secondary split-zip jar files.  This is
      // important because SmartDexingCommand will try to dx every entry in this directory.  It
      // does this because it's impossible to know what outputs split-zip will generate until it
      // runs.
      String secondaryZipDir = getBinPath("__%s_secondary_zip__");
      commands.add(new MakeCleanDirectoryStep(secondaryZipDir));

      // Run the split-zip command which is responsible for dividing the large set of input
      // classpaths into a more compact set of jar files such that no one jar file when dexed will
      // yield a dex artifact too large for dexopt or the dx method limit to handle.
      String zipSplitReportDir = getBinPath("__%s_split_zip_report__");
      commands.add(new MakeCleanDirectoryStep(zipSplitReportDir));
      SplitZipStep splitZipCommand = new SplitZipStep(
          classpathEntriesToDex,
          secondaryJarMeta,
          primaryJarPath,
          secondaryZipDir,
          "secondary-%d.jar",
          primaryDexSubstrings,
          primaryDexClassesFile.transform(sourcePathResolver),
          dexSplitMode.getDexSplitStrategy(),
          dexSplitMode.getDexStore(),
          zipSplitReportDir,
          dexSplitMode.useLinearAllocSplitDex(),
          linearAllocHardLimit);
      commands.add(splitZipCommand);

      // Add the secondary dex directory that has yet to be created, but will be by the
      // smart dexing command.  Smart dex will handle "cleaning" this directory properly.
      String secondaryDexParentDir = getBinPath("__%s_secondary_dex__/");
      secondaryDexDir = Optional.of(secondaryDexParentDir + magicSecondaryDexSubdir);
      commands.add(new MkdirStep(secondaryDexDir.get()));

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
    String successDir = getBinPath("__%s_smart_dex__/.success");
    commands.add(new MkdirStep(successDir));

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
        dexSplitMode.getDexStore());
    commands.add(smartDexingCommand);
  }

  /**
   * @return the path to the AndroidManifest.xml. Note that this file is not guaranteed to be named
   *     AndroidManifest.xml.
   */
  @Override
  public String getManifest() {
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

    private String manifest;
    private String target;

    /** This should always be a subset of {@link #getDeps()}. */
    private ImmutableSet.Builder<BuildTarget> classpathDeps = ImmutableSet.builder();

    private BuildTarget keystoreTarget;
    private PackageType packageType = DEFAULT_PACKAGE_TYPE;
    private Set<BuildTarget> buildRulesToExcludeFromDex = Sets.newHashSet();
    private DexSplitMode dexSplitMode = new DexSplitMode(
        /* shouldSplitDex */ false,
        ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE,
        DexStore.JAR,
        /* useLinearAllocSplitDex */ false);
    private boolean useAndroidProguardConfigWithOptimizations = false;
    private Optional<SourcePath> proguardConfig = Optional.absent();
    private boolean compressResources = false;
    private ImmutableSet.Builder<String> primaryDexSubstrings = ImmutableSet.builder();
    private long linearAllocHardLimit = 0;
    private Optional<SourcePath> primaryDexClassesFile = Optional.absent();
    private FilterResourcesStep.ResourceFilter resourceFilter =
        new FilterResourcesStep.ResourceFilter(ImmutableList.<String>of());
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

      boolean allowNonExistentRule =
          false;
      return new AndroidBinaryRule(
          createBuildRuleParams(ruleResolver),
          manifest,
          target,
          getBuildTargetsAsBuildRules(ruleResolver, classpathDeps.build()),
          (Keystore)keystore,
          packageType,
          getBuildTargetsAsBuildRules(ruleResolver,
              buildRulesToExcludeFromDex,
              allowNonExistentRule),
          dexSplitMode,
          useAndroidProguardConfigWithOptimizations,
          proguardConfig,
          compressResources,
          primaryDexSubstrings.build(),
          linearAllocHardLimit,
          primaryDexClassesFile,
          resourceFilter,
          cpuFilters.build(),
          getBuildTargetsAsBuildRules(ruleResolver, preprocessJavaClassesDeps.build()),
          preprocessJavaClassesBash);
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

    public Builder setManifest(String manifest) {
      this.manifest = manifest;
      return this;
    }

    public Builder setTarget(String target) {
      this.target = target;
      return this;
    }

    public Builder addClasspathDep(BuildTarget classpathDep) {
      this.classpathDeps.add(classpathDep);
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
      this.buildRulesToExcludeFromDex.add(entry);
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

    public Builder setCompressResources(boolean compressResources) {
      this.compressResources = compressResources;
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
