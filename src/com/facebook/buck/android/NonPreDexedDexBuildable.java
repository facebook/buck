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

import static com.facebook.buck.android.AndroidBinaryBuildable.SMART_DEX_SECONDARY_DEX_SUBDIR;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.AccumulateClassNamesStep;
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
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

class NonPreDexedDexBuildable implements AddsToRuleKey {
  @AddToRuleKey private final SourcePath aaptGeneratedProguardConfigFile;
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> additionalJarsForProguard;

  @AddToRuleKey
  private final ImmutableSortedMap<APKModule, ImmutableSortedSet<APKModule>> apkModuleMap;

  @AddToRuleKey private final Optional<ImmutableSet<SourcePath>> classpathEntriesToDexSourcePaths;
  @AddToRuleKey private final Optional<SourcePath> dexReorderDataDumpFile;
  @AddToRuleKey private final Optional<SourcePath> dexReorderToolFile;
  @AddToRuleKey private final DexSplitMode dexSplitMode;
  @AddToRuleKey private final Optional<String> dxMaxHeapSize;
  @AddToRuleKey private final Tool javaRuntimeLauncher;

  @AddToRuleKey
  private final Optional<ImmutableSortedMap<APKModule, ImmutableList<SourcePath>>>
      moduleMappedClasspathEntriesToDex;

  @AddToRuleKey private final Optional<Integer> optimizationPasses;
  @AddToRuleKey private final boolean shouldProguard;
  @AddToRuleKey private final Optional<Arg> preprocessJavaClassesBash;
  @AddToRuleKey private final Optional<String> proguardAgentPath;
  @AddToRuleKey private final Optional<SourcePath> proguardConfig;
  @AddToRuleKey private final ImmutableList<SourcePath> proguardConfigs;
  @AddToRuleKey private final Optional<SourcePath> proguardJarOverride;
  @AddToRuleKey private final Optional<List<String>> proguardJvmArgs;
  @AddToRuleKey private final String proguardMaxHeapSize;
  @AddToRuleKey private final boolean reorderClassesIntraDex;
  @AddToRuleKey private final APKModule rootAPKModule;
  @AddToRuleKey private final ProGuardObfuscateStep.SdkProguardType sdkProguardConfig;
  @AddToRuleKey private final boolean skipProguard;
  @AddToRuleKey private final Optional<Integer> xzCompressionLevel;
  @AddToRuleKey private final boolean shouldSplitDex;

  // Only these three fields should not be added to the rulekey.
  private final ListeningExecutorService dxExecutorService;
  private final ProjectFilesystem filesystem;
  private final BuildTarget buildTarget;

  NonPreDexedDexBuildable(
      SourcePath aaptGeneratedProguardConfigFile,
      ImmutableSortedSet<SourcePath> additionalJarsForProguard,
      ImmutableSortedMap<APKModule, ImmutableSortedSet<APKModule>> apkModuleMap,
      Optional<ImmutableSet<SourcePath>> classpathEntriesToDexSourcePaths,
      Optional<SourcePath> dexReorderDataDumpFile,
      Optional<SourcePath> dexReorderToolFile,
      DexSplitMode dexSplitMode,
      ListeningExecutorService dxExecutorService,
      Optional<String> dxMaxHeapSize,
      Tool javaRuntimeLauncher,
      Optional<ImmutableSortedMap<APKModule, ImmutableList<SourcePath>>>
          moduleMappedClasspathEntriesToDex,
      Optional<Integer> optimizationPasses,
      Optional<Arg> preprocessJavaClassesBash,
      boolean shouldProguard,
      Optional<String> proguardAgentPath,
      Optional<SourcePath> proguardConfig,
      ImmutableList<SourcePath> proguardConfigs,
      Optional<SourcePath> proguardJarOverride,
      Optional<List<String>> proguardJvmArgs,
      String proguardMaxHeapSize,
      boolean reorderClassesIntraDex,
      APKModule rootAPKModule,
      ProGuardObfuscateStep.SdkProguardType sdkProguardConfig,
      boolean skipProguard,
      Optional<Integer> xzCompressionLevel,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      boolean shouldSplitDex) {
    this.aaptGeneratedProguardConfigFile = aaptGeneratedProguardConfigFile;
    this.additionalJarsForProguard = additionalJarsForProguard;
    this.apkModuleMap = apkModuleMap;
    this.classpathEntriesToDexSourcePaths = classpathEntriesToDexSourcePaths;
    this.dexReorderDataDumpFile = dexReorderDataDumpFile;
    this.dexReorderToolFile = dexReorderToolFile;
    this.dexSplitMode = dexSplitMode;
    this.dxExecutorService = dxExecutorService;
    this.dxMaxHeapSize = dxMaxHeapSize;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.moduleMappedClasspathEntriesToDex = moduleMappedClasspathEntriesToDex;
    this.optimizationPasses = optimizationPasses;
    this.shouldProguard = shouldProguard;
    this.preprocessJavaClassesBash = preprocessJavaClassesBash;
    this.proguardAgentPath = proguardAgentPath;
    this.proguardConfig = proguardConfig;
    this.proguardConfigs = proguardConfigs;
    this.proguardJarOverride = proguardJarOverride;
    this.proguardJvmArgs = proguardJvmArgs;
    this.proguardMaxHeapSize = proguardMaxHeapSize;
    this.reorderClassesIntraDex = reorderClassesIntraDex;
    this.rootAPKModule = rootAPKModule;
    this.sdkProguardConfig = sdkProguardConfig;
    this.skipProguard = skipProguard;
    this.xzCompressionLevel = xzCompressionLevel;
    this.filesystem = filesystem;
    this.buildTarget = buildTarget;
    this.shouldSplitDex = shouldSplitDex;
  }

  @VisibleForTesting
  Path getProguardConfigDir() {
    Preconditions.checkState(shouldProguard);
    return getRootScratchPath().resolve("proguard");
  }

  @VisibleForTesting
  Path getProguardInputsDir() {
    Preconditions.checkState(shouldProguard);
    return getRootScratchPath().resolve("proguard_inputs");
  }

  private Path getRootScratchPath() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "%s/non_predexed_root");
  }

  private Path getRootGenPath() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/non_predexed_root");
  }

  private Path getSecondaryDexListing() {
    return getRootGenPath().resolve("secondary_dex.list");
  }

  private Path getSecondaryDexRoot() {
    return getBinPath("dexes");
  }

  ProjectFilesystem getProjectFilesystem() {
    return filesystem;
  }

  BuildTarget getBuildTarget() {
    return buildTarget;
  }

  Path getBinPath(String name) {
    return getRootScratchPath().resolve("bin").resolve(name);
  }

  private Path getNonPredexedPrimaryDexPath() {
    return BuildTargets.getScratchPath(
        getProjectFilesystem(), getBuildTarget(), "%s/.dex/classes.dex");
  }

  public AndroidBinaryBuildable.DexFilesInfo addDxSteps(
      BuildableContext buildableContext,
      BuildContext buildContext,
      ImmutableList.Builder<Step> steps) {
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
      Path preprocessJavaClassesInDir = getBinPath("java_classes_preprocess_in");
      Path preprocessJavaClassesOutDir = getBinPath("java_classes_preprocess_out");
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
              getBuildTarget(),
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
    if (shouldProguard) {
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
        path -> {
          Path secondaryDexRoot = getSecondaryDexRoot();
          Preconditions.checkState(
              path.startsWith(secondaryDexRoot),
              "Secondary dex directory %s is not a subdirectory of the secondary dex root %s.",
              path,
              secondaryDexRoot);
          secondaryDexDirectoriesBuilder.add(secondaryDexRoot.relativize(path));
        },
        steps,
        primaryDexPath,
        dexReorderToolFile,
        dexReorderDataDumpFile,
        additionalDexStoreToJarPathMap,
        buildContext);

    // TODO(cjhopman): This should be written in a step, but it's currently read by
    // AndroidBinaryBuildable before the step is run. When this is in its own BuildRule, it can be
    // a step.
    try {
      getProjectFilesystem().mkdirs(getSecondaryDexListing().getParent());
      getProjectFilesystem()
          .writeLinesToPath(
              secondaryDexDirectoriesBuilder.build().stream().map(t -> t.toString())::iterator,
              getSecondaryDexListing());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return new AndroidBinaryBuildable.DexFilesInfo(
        primaryDexPath,
        new AndroidBinaryBuildable.DexSecondaryDexDirView(
            getSecondaryDexRoot(), getSecondaryDexListing()),
        shouldProguard ? Optional.of(getProguardConfigDir()) : Optional.empty());
  }

  Supplier<ImmutableMap<String, HashCode>> addAccumulateClassNamesStep(
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

    // Transform our input classpath to a set of output locations for each input classpath.
    // TODO(devjasta): the output path we choose is the result of a slicing function against
    // input classpath. This is fragile and should be replaced with knowledge of the BuildTarget.
    final ImmutableMap<Path, Path> inputOutputEntries =
        classpathEntriesToDex
            .stream()
            .collect(
                MoreCollectors.toImmutableMap(
                    java.util.function.Function.identity(),
                    (path) ->
                        AndroidBinaryBuildable.getProguardOutputFromInputClasspath(
                            getProguardInputsDir(), path)));

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
        getProguardConfigDir(),
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
   */
  @VisibleForTesting
  void addDexingSteps(
      Set<Path> classpathEntriesToDex,
      Supplier<ImmutableMap<String, HashCode>> classNamesToHashesSupplier,
      Consumer<Path> secondaryDexDirectoriesConsumer,
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
    Path secondaryDexParentDir = getSecondaryDexRoot().resolve("__secondary_dex__/");
    Path additionalDexParentDir = getSecondaryDexRoot().resolve("__additional_dex__/");
    Path additionalDexAssetsDir = additionalDexParentDir.resolve("assets");
    final Optional<ImmutableSet<Path>> additionalDexDirs;

    if (shouldSplitDex) {
      Optional<Path> proguardFullConfigFile = Optional.empty();
      Optional<Path> proguardMappingFile = Optional.empty();
      if (shouldProguard) {
        proguardFullConfigFile = Optional.of(getProguardConfigDir().resolve("configuration.txt"));
        proguardMappingFile = Optional.of(getProguardConfigDir().resolve("mapping.txt"));
      }
      // DexLibLoader expects that metadata.txt and secondary jar files are under this dir
      // in assets.

      // Intermediate directory holding the primary split-zip jar.
      Path splitZipDir = getBinPath("__split_zip__");

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(), getProjectFilesystem(), splitZipDir)));
      Path primaryJarPath = splitZipDir.resolve("primary.jar");

      Path secondaryJarMetaDirParent = getSecondaryDexRoot().resolve("secondary_meta");
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
      final Path secondaryZipDir = getBinPath("__secondary_zip__");

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  buildContext.getBuildCellRootPath(), getProjectFilesystem(), secondaryZipDir)));

      // Intermediate directory holding the directories holding _ONLY_ the additional split-zip
      // jar files that are intended for that dex store.
      final Path additionalDexStoresZipDir = getBinPath("__dex_stores_zip__");

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
      Path zipSplitReportDir = getBinPath("__split_zip_report__");

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
        secondaryDexDirectoriesConsumer.accept(secondaryDexDir.get());
      } else {
        secondaryDexDirectoriesConsumer.accept(secondaryJarMetaDirParent);
        secondaryDexDirectoriesConsumer.accept(secondaryDexParentDir);
      }
      if (additionalDexDirs.isPresent()) {
        secondaryDexDirectoriesConsumer.accept(additionalDexParentDir);
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
    Path successDir = getBinPath("__smart_dex__/.success");
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
        shouldProguard
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
}
