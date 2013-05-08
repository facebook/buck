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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.shell.AaptCommand;
import com.facebook.buck.shell.ApkBuilderCommand;
import com.facebook.buck.shell.BashCommand;
import com.facebook.buck.shell.Command;
import com.facebook.buck.shell.EchoCommand;
import com.facebook.buck.shell.ExecutionContext;
import com.facebook.buck.shell.ExtractResourcesCommand;
import com.facebook.buck.shell.FilterResourcesCommand;
import com.facebook.buck.shell.GenProGuardConfigCommand;
import com.facebook.buck.command.io.MakeCleanDirectoryCommand;
import com.facebook.buck.command.io.MkdirAndSymlinkFileCommand;
import com.facebook.buck.command.io.MkdirCommand;
import com.facebook.buck.shell.ProGuardObfuscateCommand;
import com.facebook.buck.shell.ReadKeystorePropertiesAndSignApkCommand;
import com.facebook.buck.command.io.RepackZipEntriesCommand;
import com.facebook.buck.shell.SmartDexingCommand;
import com.facebook.buck.shell.SplitZipCommand;
import com.facebook.buck.command.io.ZipDirectoryWithMaxDeflateCommand;
import com.facebook.buck.shell.ZipalignCommand;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.DefaultDirectoryTraverser;
import com.facebook.buck.util.DefaultFilteredDirectoryCopier;
import com.facebook.buck.util.DirectoryTraversal;
import com.facebook.buck.util.DirectoryTraverser;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Paths;
import com.facebook.buck.util.ZipSplitter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

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
public class AndroidBinaryRule extends AbstractCachingBuildRule implements
    HasAndroidPlatformTarget, HasClasspathEntries, InstallableBuildRule {

  /**
   * The largest file size Froyo will deflate.
   */
  private final long FROYO_DEFLATE_LIMIT_BYTES = 1 << 20;

  private final static Logger logger = Logger.getLogger(AndroidBinaryRule.class.getName());

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

  /**
   * Bundles together some information about whether and how we should split up dex files.
   */
  public static class DexSplitMode {
    final boolean shouldSplitDex;
    final ZipSplitter.DexSplitStrategy dexSplitStrategy;

    public DexSplitMode(boolean shouldSplitDex, ZipSplitter.DexSplitStrategy dexSplitStrategy) {
      this.shouldSplitDex = shouldSplitDex;
      this.dexSplitStrategy = dexSplitStrategy;
    }

    public boolean isShouldSplitDex() {
      return shouldSplitDex;
    }

    ZipSplitter.DexSplitStrategy getDexSplitStrategy() {
      Preconditions.checkState(isShouldSplitDex());
      return dexSplitStrategy;
    }
  }

  private final String manifest;
  private final String target;
  private final String keystorePropertiesPath;
  private final PackageType packageType;
  private final ImmutableSortedSet<BuildRule> buildRulesToExcludeFromDex;
  private DexSplitMode dexSplitMode;
  private final boolean useAndroidProguardConfigWithOptimizations;
  @Nullable private final String proguardConfig;
  private final boolean compressResources;
  private final ImmutableSet<String> primaryDexSubstrings;
  @Nullable private String resourceFilter;
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
      String keystorePropertiesPath,
      PackageType packageType,
      Set<BuildRule> buildRulesToExcludeFromDex,
      DexSplitMode dexSplitMode,
      boolean useAndroidProguardConfigWithOptimizations,
      @Nullable String proguardConfig,
      boolean compressResources,
      Set<String> primaryDexSubstrings,
      @Nullable String resourceFilter) {
    super(buildRuleParams);
    this.manifest = Preconditions.checkNotNull(manifest);
    this.target = Preconditions.checkNotNull(target);
    this.keystorePropertiesPath = Preconditions.checkNotNull(keystorePropertiesPath);
    this.packageType = Preconditions.checkNotNull(packageType);
    this.buildRulesToExcludeFromDex = ImmutableSortedSet.copyOf(buildRulesToExcludeFromDex);
    this.dexSplitMode = Preconditions.checkNotNull(dexSplitMode);
    this.useAndroidProguardConfigWithOptimizations = useAndroidProguardConfigWithOptimizations;
    this.proguardConfig = proguardConfig;
    this.compressResources = compressResources;
    this.primaryDexSubstrings = ImmutableSet.copyOf(primaryDexSubstrings);
    this.outputGenDirectory = String.format("%s/%s",
        BuckConstant.GEN_DIR,
        getBuildTarget().getBasePathWithSlash());
    this.resourceFilter = resourceFilter;
    this.transitiveDependencyGraph =
        new AndroidTransitiveDependencyGraph(this, this.buildRulesToExcludeFromDex);
  }

  @Override
  public BuildRuleType getType() {
    return BuildRuleType.ANDROID_BINARY;
  }

  @Override
  public boolean isAndroidRule() {
    return true;
  }

  @Override
  public String getAndroidPlatformTarget() {
    return target;
  }

  @Override
  protected RuleKey.Builder ruleKeyBuilder() {
    return super.ruleKeyBuilder()
        .set("manifest", manifest)
        .set("target", target)
        .set("keystorePropertiesPath", keystorePropertiesPath)
        .set("packageType", packageType.toString())
        .set("buildRulesToExcludeFromDex", buildRulesToExcludeFromDex)
        .set("useAndroidProguardConfigWithOptimizations", useAndroidProguardConfigWithOptimizations)
        .set("proguardConfig", proguardConfig)
        .set("compressResources", compressResources)
        .set("primaryDexSubstrings", primaryDexSubstrings)
        .set("outputGenDirectory", outputGenDirectory)
        .set("resourceFilter", resourceFilter);
   }

  public ImmutableSortedSet<BuildRule> getBuildRulesToExcludeFromDex() {
    return buildRulesToExcludeFromDex;
  }

  public AndroidTransitiveDependencyGraph getTransitiveDependencyGraph() {
    return transitiveDependencyGraph;
  }

  @Nullable
  public String getProguardConfig() {
    return proguardConfig;
  }

  public boolean isRelease() {
    return packageType == PackageType.RELEASE;
  }

  public boolean isCompressResources(){
    return this.compressResources;
  }

  @Nullable
  public String getResourceFilter() {
    return this.resourceFilter;
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
  public File getOutput() {
    return new File(getApkPath());
  }

  @Override
  protected List<String> getInputsToCompareToOutput(BuildContext context) {
    ImmutableList.Builder<String> inputs = ImmutableList.builder();
    inputs.add(manifest);
    inputs.add(keystorePropertiesPath);
    if (proguardConfig != null) {
      inputs.add(proguardConfig);
    }
    return inputs.build();
  }

  @Override
  protected List<Command> buildInternal(BuildContext context) {
    ImmutableList.Builder<Command> commands = ImmutableList.builder();
    // Map from asset name to pathname for extra files to be added to assets.
    ImmutableMap.Builder<String, File> extraAssetsBuilder = ImmutableMap.builder();

    // Symlink the manifest to a path named AndroidManifest.xml. Do this before running any other
    // commands to ensure that it is available at the desired path.
    commands.add(new MkdirAndSymlinkFileCommand(getManifest(), getAndroidManifestXml()));

    final AndroidTransitiveDependencies transitiveDependencies = findTransitiveDependencies(
        context.getDependencyGraph(), Optional.of(context));

    boolean resourcesCached =
        !transitiveDependencies.uncachedBuildRules.containsKey(BuildRuleType.ANDROID_RESOURCE);

    Set<String> resDirectories = transitiveDependencies.resDirectories;
    Set<String> rDotJavaPackages = transitiveDependencies.rDotJavaPackages;

    String resourceFilter = getResourceFilter();

    // If resource filtering was requested (currently only by dpi).
    if (resourceFilter != null) {
      FilterResourcesCommand filterResourcesCommand = new FilterResourcesCommand(
          resDirectories,
          new File(getBinPath("__filtered__%s__")),
          resourceFilter,
          DefaultFilteredDirectoryCopier.getInstance(),
          FilterResourcesCommand.DefaultDrawableFinder.getInstance()
      );
      commands.add(filterResourcesCommand);
      resDirectories = filterResourcesCommand.getFilteredResourceDirectories();
    }

    // Extract the resources from third-party jars.
    // TODO(mbolin): The results of this should be cached between runs.
    String extractedResourcesDir = getBinPath("__resources__%s__");
    commands.add(new MakeCleanDirectoryCommand(extractedResourcesDir));
    commands.add(new ExtractResourcesCommand(transitiveDependencies.pathsToThirdPartyJars,
        extractedResourcesDir));

    // Create the R.java files. Their compiled versions must be included in classes.dex.
    // TODO(mbolin): Skip this step if the transitive set of AndroidResourceRules is cached.
    if (!resDirectories.isEmpty()) {
      UberRDotJavaUtil.generateRDotJavaFiles(resDirectories, rDotJavaPackages, getBuildTarget(), commands);
    }

    // Execute proguard if desired (transforms input classpaths).
    if (packageType.isBuildWithObfuscation()) {
      addProguardCommands(
          transitiveDependencies,
          commands,
          resDirectories);
    }

    // Create the final DEX (or set of DEX files in the case of split dex).
    // The APK building command needs to take a directory of raw files, so we create a directory
    // that can only contain .dex files from this build rule.
    String dexDir = getBinPath(".dex/%s");
    commands.add(new MkdirCommand(dexDir));
    String dexFile = String.format("%s/classes.dex", dexDir);

    final ImmutableSet.Builder<String> secondaryDexDirectories = ImmutableSet.builder();

    // Create dex artifacts.  This may modify assetsDirectories.
    addDexingCommands(
        transitiveDependencies.classpathEntriesToDex,
        secondaryDexDirectories,
        commands,
        dexFile);

    // Copy the transitive closure of files in assets to a single directory, if any.
    final ImmutableMap<String, File> extraAssets = extraAssetsBuilder.build();
    Command collectAssets = new Command() {
      @Override
      public int execute(ExecutionContext context) {
        // This must be done in a Command because the files and directories that are specified may
        // not exist at the time this Command is created because the previous Commands have not run
        // yet.
        ImmutableList.Builder<Command> commands = ImmutableList.builder();
        createAllAssetsDirectory(
            transitiveDependencies.assetsDirectories,
            extraAssets,
            commands,
            new DefaultDirectoryTraverser());
        for (Command command : commands.build()) {
          int exitCode = command.execute(context);
          if (exitCode != 0) {
            throw new HumanReadableException("Error running " + command.getDescription(context));
          }
        }

        return 0;
      }

      @Override
      public String getShortName(ExecutionContext context) {
        return "symlink assets";
      }

      @Override
      public String getDescription(ExecutionContext context) {
        return getShortName(context);
      }
    };
    commands.add(collectAssets);

    // Copy the transitive closure of files in native_libs to a single directory, if any.
    ImmutableSet.Builder<String> nativeLibraryDirectories = ImmutableSet.builder();
    if (!transitiveDependencies.nativeLibsDirectories.isEmpty()) {
      String pathForNativeLibs = getPathForNativeLibs();
      String libSubdirectory = pathForNativeLibs + "/lib";
      nativeLibraryDirectories.add(libSubdirectory);
      commands.add(new MakeCleanDirectoryCommand(libSubdirectory));
      for (String nativeLibDir : transitiveDependencies.nativeLibsDirectories) {
        if (nativeLibDir.endsWith("/")) {
          nativeLibDir = nativeLibDir.substring(0, nativeLibDir.length() - 1);
        }
        commands.add(new BashCommand(String.format(
            "cp -r %s/* %s", nativeLibDir, libSubdirectory)));
      }
    }

    // Create the unsigned APK.
    String resourceApkPath = getResourceApkPath();
    String unsignedApkPath = getUnsignedApkPath();

    Optional<String> assetsDirectory;
    if (transitiveDependencies.assetsDirectories.isEmpty() && extraAssets.isEmpty()) {
      assetsDirectory = Optional.absent();
    } else {
      assetsDirectory = Optional.of(getPathToAllAssetsDirectory());
    }

    commands.add(new MkdirCommand(outputGenDirectory));

    boolean canSkipAapt = resourcesCached;
    if (canSkipAapt) {
      try {
        canSkipAapt = ruleInputsCached(context, logger);
      } catch (IOException e) {
        // If we hit an IO exception while checking if the inputs to this rule were cached eat it
        // and treat it as a cache miss.
        canSkipAapt = false;
      }
    }
    if (!canSkipAapt) {
      AaptCommand aaptCommand = new AaptCommand(
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
      commands.add(new ZipDirectoryWithMaxDeflateCommand(secondaryDexDirectory,
          zipFile,
          FROYO_DEFLATE_LIMIT_BYTES));
    }

    ApkBuilderCommand apkBuilderCommand = new ApkBuilderCommand(
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
    ReadKeystorePropertiesAndSignApkCommand signApk = new ReadKeystorePropertiesAndSignApkCommand(
        keystorePropertiesPath, unsignedApkPath, signedApkPath, context.getProjectFilesystem());
    commands.add(signApk);

    String apkToAlign;

    // Optionally, compress the resources file in the .apk.
    if (this.isCompressResources()) {
      String compressedApkPath = getCompressedResourcesApkPath();
      apkToAlign = compressedApkPath;
      RepackZipEntriesCommand arscComp = new RepackZipEntriesCommand(
          signedApkPath,
          compressedApkPath,
          ImmutableSet.of("resources.arsc"));
      commands.add(arscComp);
    } else {
      apkToAlign = signedApkPath;
    }

    String apkPath = getApkPath();
    ZipalignCommand zipalign = new ZipalignCommand(apkToAlign, apkPath);
    commands.add(zipalign);

    // Inform the user where the APK can be found.
    EchoCommand success = new EchoCommand(
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
      ImmutableList.Builder<Command> commands,
      DirectoryTraverser traverser) {
    if (assetsDirectories.isEmpty() && extraAssets.isEmpty()) {
      return Optional.absent();
    }

    // Due to a limitation of aapt, only one assets directory can be specified, so if multiple are
    // specified in Buck, then all of the contents must be symlinked to a single directory.
    String destination = getPathToAllAssetsDirectory();
    commands.add(new MakeCleanDirectoryCommand(destination));
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
      commands.add(new MkdirAndSymlinkFileCommand(
          entry.getValue(),
          destinationDirectory + "/" + entry.getKey()));
    }

    return Optional.of(destination);
  }

  public AndroidTransitiveDependencies findTransitiveDependencies(
      DependencyGraph graph,
      final Optional<BuildContext> context) {
    return getTransitiveDependencyGraph().findDependencies(
        getAndroidResourceDepsInternal(graph),
        context);
  }

  /**
   * @return a list of {@link AndroidResourceRule}s that should be passed, in order, to {@code aapt}
   *     when generating the {@code R.java} files for this APK.
   */
  protected ImmutableList<AndroidResourceRule> getAndroidResourceDepsInternal(
      DependencyGraph graph) {
    return AndroidResourceRule.getAndroidResourceDeps(this, graph);
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

  public String getPathToKeystoreProperties() {
    return keystorePropertiesPath;
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
    String obfuscatedName = Paths.getBasename(classpathEntry, ".jar") + "-obfuscated.jar";
    String dirName = new File(classpathEntry).getParent();
    String outputJar = getPathForProGuardDirectory() + "/" + dirName + "/" +
        obfuscatedName;
    return outputJar;
  }

  @VisibleForTesting
  void addProguardCommands(
      AndroidTransitiveDependencies deps,
      ImmutableList.Builder<Command> commands,
      Set<String> resDirectories) {
    final ImmutableSetMultimap<BuildRule, String> classpathEntriesMap =
        getTransitiveClasspathEntries();
    Set<String> additionalLibraryJarsForProguard = Sets.newHashSet();

    for (BuildRule buildRule : buildRulesToExcludeFromDex) {
      additionalLibraryJarsForProguard.addAll(classpathEntriesMap.get(buildRule));
    }

    Set<String> classpathEntries = deps.classpathEntriesToDex;

    // Clean out the directory for generated ProGuard files.
    String proguardDirectory = getPathForProGuardDirectory();
    commands.add(new MakeCleanDirectoryCommand(proguardDirectory));

    // Generate a file of ProGuard config options using aapt.
    String generatedProGuardConfig = proguardDirectory + "/proguard.txt";
    GenProGuardConfigCommand genProGuardConfig = new GenProGuardConfigCommand(
        getAndroidManifestXml(),
        resDirectories,
        generatedProGuardConfig);
    commands.add(genProGuardConfig);

    // Create list of proguard Configs for the app project and its dependencies
    Set<String> proguardConfigs = Sets.newHashSet();
    proguardConfigs.addAll(deps.proguardConfigs);
    if (proguardConfig != null) {
      proguardConfigs.add(proguardConfig);
    }

    // Transform our input classpath to a set of output locations for each input classpath.
    // TODO(devjasta): the output path we choose is the result of a slicing function against
    // input classpath.  This is fragile and should be replaced with knowledge of the BuildTarget.
    ImmutableMap.Builder<String, String> inputOutputEntriesBuilder = ImmutableMap.builder();
    for (String classpathEntry : classpathEntries) {
      inputOutputEntriesBuilder.put(classpathEntry,
          getProguardOutputFromInputClasspath(classpathEntry));
    }
    final Map<String, String> inputOutputEntries = inputOutputEntriesBuilder.build();

    // Run ProGuard on the classpath entries.
    ProGuardObfuscateCommand obfuscateCommand = new ProGuardObfuscateCommand(
        generatedProGuardConfig,
        proguardConfigs,
        useAndroidProguardConfigWithOptimizations,
        inputOutputEntries,
        additionalLibraryJarsForProguard,
        proguardDirectory);
    commands.add(obfuscateCommand);

    // Apply the transformed inputs to the classpath (this will modify deps.classpathEntriesToDex
    // so that we're now dexing the proguarded artifacts).
    deps.applyClasspathTransformation(new AndroidTransitiveDependencies.InputTransformation() {
      @Override
      public String apply(String originalClasspath) {
        return inputOutputEntries.get(originalClasspath);
      }
    });
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
      ImmutableList.Builder<Command> commands,
      String primaryDexPath) {
    final Set<String> primaryInputsToDex;
    final Optional<String> secondaryDexDir;
    final Optional<String> secondaryInputsDir;

    if (shouldSplitDex()) {
      // DexLibLoader expects that metadata.txt and secondary jar files are under this dir
      // in assets.
      String magicSecondaryDexSubdir = "assets/secondary-program-dex-jars";

      // Intermediate directory holding the primary split-zip jar.
      String splitZipDir = getBinPath("__split_zip__/%s");
      commands.add(new MakeCleanDirectoryCommand(splitZipDir));
      String primaryJarPath = splitZipDir + "/primary.jar";

      String secondaryJarMetaDirParent = splitZipDir + "/secondary_meta/";
      String secondaryJarMetaDir = secondaryJarMetaDirParent + magicSecondaryDexSubdir;
      commands.add(new MakeCleanDirectoryCommand(secondaryJarMetaDir));
      String secondaryJarMeta = secondaryJarMetaDir + "/metadata.txt";

      // Intermediate directory holding _ONLY_ the secondary split-zip jar files.  This is
      // important because SmartDexingCommand will try to dx every entry in this directory.  It
      // does this because it's impossible to know what outputs split-zip will generate until it
      // runs.
      String secondaryZipDir = getBinPath("__secondary_zip__/%s");
      commands.add(new MakeCleanDirectoryCommand(secondaryZipDir));

      // Run the split-zip command which is responsible for dividing the large set of input
      // classpaths into a more compact set of jar files such that no one jar file when dexed will
      // yield a dex artifact too large for dexopt or the dx method limit to handle.
      SplitZipCommand splitZipCommand = new SplitZipCommand(
          classpathEntriesToDex,
          secondaryJarMeta,
          primaryJarPath,
          secondaryZipDir,
          "secondary-%d.jar",
          primaryDexSubstrings,
          dexSplitMode.getDexSplitStrategy());
      commands.add(splitZipCommand);

      // Add the secondary dex directory that has yet to be created, but will be by the
      // smart dexing command.  Smart dex will handle "cleaning" this directory properly.
      String secondaryDexParentDir = getBinPath("__secondary_dex__/%s/");
      secondaryDexDir = Optional.of(secondaryDexParentDir + magicSecondaryDexSubdir);
      commands.add(new MkdirCommand(secondaryDexDir.get()));

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
    String successDir = getBinPath("__smart_dex__/%s/.success");
    commands.add(new MkdirCommand(successDir));

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
    SmartDexingCommand smartDexingCommand = new SmartDexingCommand(
        primaryDexPath,
        primaryInputsToDex,
        secondaryDexDir,
        secondaryInputsDir,
        successDir,
        Optional.<Integer>absent());
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

  String getKeystorePropertiesPath() {
    return keystorePropertiesPath;
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

  @Override
  public ImmutableSetMultimap<BuildRule, String> getTransitiveClasspathEntries() {
    // This is used primarily for buck audit classpath.
    return getClasspathEntriesForDeps();
  }

  public static Builder newAndroidBinaryRuleBuilder() {
    return new Builder();
  }

  public static class Builder extends AbstractBuildRuleBuilder {

    private static final PackageType DEFAULT_PACKAGE_TYPE = PackageType.DEBUG;

    private String manifest;
    private String target;
    private String keystorePropertiesPath;
    private PackageType packageType = DEFAULT_PACKAGE_TYPE;
    private Set<String> buildRulesToExcludeFromDex = Sets.newHashSet();
    private DexSplitMode dexSplitMode =
        new DexSplitMode(false, ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE);
    private boolean useAndroidProguardConfigWithOptimizations = false;
    @Nullable private String proguardConfig;
    private boolean compressResources = false;
    private ImmutableSet.Builder<String> primaryDexSubstrings = ImmutableSet.builder();
    @Nullable private String resourceFilter = null;

    private Builder() {}

    @Override
    public AndroidBinaryRule build(Map<String, BuildRule> buildRuleIndex) {
      boolean allowNonExistentRule =
        false;

      return new AndroidBinaryRule(
          createBuildRuleParams(buildRuleIndex),
          manifest,
          target,
          keystorePropertiesPath,
          packageType,
          getBuildTargetsAsBuildRules(buildRuleIndex,
              buildRulesToExcludeFromDex,
              allowNonExistentRule),
          dexSplitMode,
          useAndroidProguardConfigWithOptimizations,
          proguardConfig,
          compressResources,
          primaryDexSubstrings.build(),
          resourceFilter);
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(String dep) {
      super.addDep(dep);
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

    public Builder setKeystorePropertiesPath(String keystorePropertiesPath) {
      this.keystorePropertiesPath = keystorePropertiesPath;
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

    public Builder addBuildRuleToExcludeFromDex(String entry) {
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

    public Builder setProguardConfig(String proguardConfig) {
      this.proguardConfig = proguardConfig;
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

    public Builder setResourceFilter(@Nullable String resourceFilter) {
      this.resourceFilter = resourceFilter;
      return this;
    }
  }
}
