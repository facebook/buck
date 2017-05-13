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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.FilterResourcesStep.ResourceFilter;
import com.facebook.buck.android.ResourcesFilter.ResourceCompressionMode;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.jvm.java.JavaCompilationConstants;
import com.facebook.buck.jvm.java.Keystore;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.shell.BashStep;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidBinaryTest {

  @Test
  public void testAndroidBinaryNoDx() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));

    // Two android_library deps, neither with an assets directory.
    BuildRule libraryOne =
        createAndroidLibraryRule(
            "//java/src/com/facebook/base:libraryOne",
            ruleResolver,
            null, /* resDirectory */
            null, /* assetDirectory */
            null /* nativeLibsDirectory */);
    BuildRule libraryOneRule = ruleResolver.getRule(libraryOne.getBuildTarget());
    BuildRule libraryTwo =
        createAndroidLibraryRule(
            "//java/src/com/facebook/base:libraryTwo",
            ruleResolver,
            null, /* resDirectory */
            null, /* assetDirectory */
            null /* nativeLibsDirectory */);
    BuildRule libraryTwoRule = ruleResolver.getRule(libraryTwo.getBuildTarget());

    // One android_binary rule that depends on the two android_library rules.
    BuildTarget binaryBuildTarget =
        BuildTargetFactory.newInstance("//java/src/com/facebook/base:apk");
    ImmutableSortedSet<BuildTarget> originalDepsTargets =
        ImmutableSortedSet.of(libraryOneRule.getBuildTarget(), libraryTwoRule.getBuildTarget());
    BuildRule keystoreRule = addKeystoreRule(ruleResolver);
    AndroidBinary androidBinary =
        AndroidBinaryBuilder.createBuilder(binaryBuildTarget)
            .setOriginalDeps(originalDepsTargets)
            .setBuildTargetsToExcludeFromDex(ImmutableSet.of(libraryTwoRule.getBuildTarget()))
            .setManifest(new FakeSourcePath("java/src/com/facebook/base/AndroidManifest.xml"))
            .setKeystore(keystoreRule.getBuildTarget())
            .setPackageType("release")
            .build(ruleResolver);

    AndroidPackageableCollection packageableCollection =
        androidBinary.getAndroidPackageableCollection();
    ImmutableList.Builder<Step> commands = ImmutableList.builder();

    FakeBuildableContext buildableContext = new FakeBuildableContext();

    androidBinary.addProguardCommands(
        packageableCollection
            .getClasspathEntriesToDex()
            .stream()
            .map(pathResolver::getRelativePath)
            .collect(MoreCollectors.toImmutableSet()),
        pathResolver.getAllAbsolutePaths(packageableCollection.getProguardConfigs()),
        false,
        commands,
        buildableContext,
        pathResolver);

    BuildTarget aaptPackageTarget =
        binaryBuildTarget.withFlavors(AndroidBinaryResourcesGraphEnhancer.AAPT_PACKAGE_FLAVOR);
    Path aaptProguardDir =
        BuildTargets.getGenPath(
            androidBinary.getProjectFilesystem(), aaptPackageTarget, "%s/proguard/");

    Path proguardOutputDir =
        BuildTargets.getGenPath(
            androidBinary.getProjectFilesystem(), binaryBuildTarget, "%s/proguard/");
    ImmutableSet<Path> expectedRecordedArtifacts =
        ImmutableSet.of(
            proguardOutputDir.resolve("configuration.txt"),
            proguardOutputDir.resolve("mapping.txt"),
            proguardOutputDir.resolve("seeds.txt"));

    assertEquals(expectedRecordedArtifacts, buildableContext.getRecordedArtifacts());

    buildableContext = new FakeBuildableContext();

    ImmutableList.Builder<Step> expectedSteps = ImmutableList.builder();

    ProGuardObfuscateStep.create(
        JavaCompilationConstants.DEFAULT_JAVA_OPTIONS.getJavaRuntimeLauncher(),
        new FakeProjectFilesystem(),
        /* proguardJarOverride */ Optional.empty(),
        /* proguardMaxHeapSize */ "1024M",
        /* proguardAgentPath */ Optional.empty(),
        aaptProguardDir.resolve("proguard.txt"),
        /* customProguardConfigs */ ImmutableSet.of(),
        ProGuardObfuscateStep.SdkProguardType.DEFAULT,
        /* optimizationPasses */ Optional.empty(),
        /* proguardJvmArgs */ Optional.empty(),
        ImmutableMap.of(
            BuildTargets.getGenPath(
                    libraryOneRule.getProjectFilesystem(),
                    libraryOneRule.getBuildTarget(),
                    "lib__%s__output")
                .resolve(libraryOneRule.getBuildTarget().getShortName() + ".jar"),
            proguardOutputDir.resolve(
                BuildTargets.getGenPath(
                        libraryOneRule.getProjectFilesystem(),
                        libraryOneRule.getBuildTarget(),
                        "lib__%s__output/")
                    .resolve(
                        libraryOne.getBuildTarget().getShortNameAndFlavorPostfix()
                            + "-obfuscated.jar"))),
        ImmutableSet.of(
            libraryTwo
                .getBuildTarget()
                .getUnflavoredBuildTarget()
                .getCellPath()
                .resolve(
                    BuildTargets.getGenPath(
                            libraryTwoRule.getProjectFilesystem(),
                            libraryTwoRule.getBuildTarget(),
                            "lib__%s__output")
                        .resolve(
                            libraryTwoRule.getBuildTarget().getShortNameAndFlavorPostfix()
                                + ".jar"))),
        proguardOutputDir,
        buildableContext,
        false,
        expectedSteps);

    assertEquals(expectedSteps.build(), commands.build());

    assertEquals(expectedRecordedArtifacts, buildableContext.getRecordedArtifacts());
  }

  static BuildRule createAndroidLibraryRule(
      String buildTarget,
      BuildRuleResolver ruleResolver,
      String resDirectory,
      String assetDirectory,
      String nativeLibsDirectory)
      throws Exception {
    BuildTarget libraryOnebuildTarget = BuildTargetFactory.newInstance(buildTarget);
    AndroidLibraryBuilder androidLibraryRuleBuilder =
        AndroidLibraryBuilder.createBuilder(libraryOnebuildTarget)
            .addSrc(Paths.get(buildTarget.split(":")[1] + ".java"));

    if (!Strings.isNullOrEmpty(resDirectory) || !Strings.isNullOrEmpty(assetDirectory)) {
      BuildTarget resourceOnebuildTarget =
          BuildTargetFactory.newInstance(buildTarget + "_resources");
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
      BuildRule androidResourceRule =
          ruleResolver.addToIndex(
              AndroidResourceRuleBuilder.newBuilder()
                  .setRuleFinder(ruleFinder)
                  .setAssets(new FakeSourcePath(assetDirectory))
                  .setRes(resDirectory == null ? null : new FakeSourcePath(resDirectory))
                  .setBuildTarget(resourceOnebuildTarget)
                  .build());

      androidLibraryRuleBuilder.addDep(androidResourceRule.getBuildTarget());
    }

    if (!Strings.isNullOrEmpty(nativeLibsDirectory)) {
      BuildTarget nativeLibOnebuildTarget =
          BuildTargetFactory.newInstance(buildTarget + "_native_libs");
      BuildRule nativeLibsRule =
          PrebuiltNativeLibraryBuilder.newBuilder(nativeLibOnebuildTarget)
              .setNativeLibs(Paths.get(nativeLibsDirectory))
              .build(ruleResolver);
      ruleResolver.addToIndex(nativeLibsRule);
      androidLibraryRuleBuilder.addDep(nativeLibsRule.getBuildTarget());
    }

    return androidLibraryRuleBuilder.build(ruleResolver);
  }

  @Test
  public void testGetUnsignedApkPath() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver =
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));
    Keystore keystore = addKeystoreRule(ruleResolver);

    BuildTarget targetInRootDirectory = BuildTargetFactory.newInstance("//:fb4a");
    AndroidBinary ruleInRootDirectory =
        AndroidBinaryBuilder.createBuilder(targetInRootDirectory)
            .setManifest(new FakeSourcePath("AndroidManifest.xml"))
            .setKeystore(keystore.getBuildTarget())
            .build(ruleResolver);
    assertEquals(
        BuildTargets.getGenPath(
            ruleInRootDirectory.getProjectFilesystem(), targetInRootDirectory, "%s.apk"),
        pathResolver.getRelativePath(ruleInRootDirectory.getApkInfo().getApkPath()));

    BuildTarget targetInNonRootDirectory =
        BuildTargetFactory.newInstance("//java/com/example:fb4a");
    AndroidBinary ruleInNonRootDirectory =
        AndroidBinaryBuilder.createBuilder(targetInNonRootDirectory)
            .setManifest(new FakeSourcePath("AndroidManifest.xml"))
            .setKeystore(keystore.getBuildTarget())
            .build(ruleResolver);
    assertEquals(
        BuildTargets.getGenPath(
            ruleInNonRootDirectory.getProjectFilesystem(), targetInNonRootDirectory, "%s.apk"),
        pathResolver.getRelativePath(ruleInNonRootDirectory.getApkInfo().getApkPath()));
  }

  @Test
  public void testGetProguardOutputFromInputClasspath() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    BuildTarget target = BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign");
    AndroidBinary rule =
        AndroidBinaryBuilder.createBuilder(target)
            .setManifest(new FakeSourcePath("AndroidManifest.xml"))
            .setKeystore(addKeystoreRule(ruleResolver).getBuildTarget())
            .build(ruleResolver);

    BuildTarget libBaseTarget =
        BuildTargetFactory.newInstance("//first-party/orca/lib-base:lib-base");
    Path proguardConfigDir =
        BuildTargets.getGenPath(
            rule.getProjectFilesystem(),
            target.withFlavors(AndroidBinaryResourcesGraphEnhancer.AAPT_PACKAGE_FLAVOR),
            "__%s__proguard__/.proguard");
    Path proguardDir =
        AndroidBinary.getProguardOutputFromInputClasspath(
            proguardConfigDir,
            BuildTargets.getScratchPath(
                rule.getProjectFilesystem(), libBaseTarget, "lib__%s__classes"));
    assertEquals(
        proguardConfigDir.resolve(
            BuildTargets.getScratchPath(
                rule.getProjectFilesystem(), libBaseTarget, "lib__%s__classes-obfuscated.jar")),
        proguardDir);
  }

  private void assertCommandsInOrder(List<Step> steps, List<Class<?>> expectedCommands)
      throws Exception {
    Iterable<Class<?>> filteredObservedCommands =
        FluentIterable.from(steps)
            .transform((Function<Step, Class<?>>) Step::getClass)
            .filter(Sets.newHashSet(expectedCommands)::contains);
    MoreAsserts.assertIterablesEquals(expectedCommands, filteredObservedCommands);
  }

  @Test
  public void testDexingCommand() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    AndroidBinary splitDexRule =
        AndroidBinaryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
            .setManifest(new FakeSourcePath("AndroidManifest.xml"))
            .setKeystore(addKeystoreRule(ruleResolver).getBuildTarget())
            .setShouldSplitDex(true)
            .setLinearAllocHardLimit(0)
            .setPrimaryDexScenarioOverflowAllowed(true)
            .setDexCompression(DexStore.JAR)
            .build(ruleResolver);

    Set<Path> classpath = Sets.newHashSet();
    ImmutableSet.Builder<Path> secondaryDexDirectories = ImmutableSet.builder();
    ImmutableList.Builder<Step> commandsBuilder = ImmutableList.builder();
    Path primaryDexPath =
        splitDexRule
            .getProjectFilesystem()
            .getBuckPaths()
            .getScratchDir()
            .resolve(".dex/classes.dex");
    splitDexRule.addDexingSteps(
        classpath,
        Suppliers.ofInstance(ImmutableMap.of()),
        secondaryDexDirectories,
        commandsBuilder,
        primaryDexPath,
        Optional.empty(),
        Optional.empty(),
        /*  additionalDexStoreToJarPathMap */ ImmutableMultimap.of(),
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver)));

    assertEquals(
        "Expected 2 new assets paths (one for metadata.txt and the other for the "
            + "secondary zips)",
        2,
        secondaryDexDirectories.build().size());

    List<Step> steps = commandsBuilder.build();
    assertCommandsInOrder(steps, ImmutableList.of(SplitZipStep.class, SmartDexingStep.class));
  }

  @Test
  public void testDexingCommandWithIntraDexReorder() throws Exception {
    SourcePath reorderTool = new FakeSourcePath("/tools#reorder_tool");
    SourcePath reorderData = new FakeSourcePath("/tools#reorder_data");
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    AndroidBinary splitDexRule =
        AndroidBinaryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//:fbandroid_with_dash_debug_fbsign"))
            .setManifest(new FakeSourcePath("AndroidManifest.xml"))
            .setKeystore(addKeystoreRule(ruleResolver).getBuildTarget())
            .setShouldSplitDex(true)
            .setLinearAllocHardLimit(0)
            .setPrimaryDexScenarioOverflowAllowed(true)
            .setDexCompression(DexStore.JAR)
            .setIntraDexReorderResources(true, reorderTool, reorderData)
            .build(ruleResolver);

    Set<Path> classpath = Sets.newHashSet();
    ImmutableSet.Builder<Path> secondaryDexDirectories = ImmutableSet.builder();
    ImmutableList.Builder<Step> commandsBuilder = ImmutableList.builder();
    Path primaryDexPath =
        splitDexRule
            .getProjectFilesystem()
            .getBuckPaths()
            .getScratchDir()
            .resolve(".dex/classes.dex");
    splitDexRule.addDexingSteps(
        classpath,
        Suppliers.ofInstance(ImmutableMap.of()),
        secondaryDexDirectories,
        commandsBuilder,
        primaryDexPath,
        Optional.of(reorderTool),
        Optional.of(reorderData),
        /*  additionalDexStoreToJarPathMap */ ImmutableMultimap.of(),
        new SourcePathResolver(new SourcePathRuleFinder(ruleResolver)));

    assertEquals(
        "Expected 2 new assets paths (one for metadata.txt and the other for the "
            + "secondary zips)",
        2,
        secondaryDexDirectories.build().size());

    List<Step> steps = commandsBuilder.build();
    assertCommandsInOrder(steps, ImmutableList.of(SplitZipStep.class, SmartDexingStep.class));
  }

  @Test
  public void testCreateFilterResourcesStep() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule keystoreRule = addKeystoreRule(resolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    AndroidBinaryBuilder builder =
        AndroidBinaryBuilder.createBuilder(target)
            .setManifest(new FakeSourcePath("AndroidManifest.xml"))
            .setKeystore(keystoreRule.getBuildTarget())
            .setResourceFilter(new ResourceFilter(ImmutableList.of("mdpi")))
            .setResourceCompressionMode(ResourceCompressionMode.ENABLED_WITH_STRINGS_AS_ASSETS);

    AndroidBinary buildRule = builder.build(resolver);
    ImmutableList<Path> resourceDirectories = ImmutableList.of(Paths.get("one"), Paths.get("two"));

    BuildRule aaptPackageRule =
        resolver.getRule(BuildTargetFactory.newInstance("//:target#aapt_package"));
    ResourcesFilter resourcesProvider =
        (ResourcesFilter) ((AaptPackageResources) aaptPackageRule).getFilteredResourcesProvider();
    ImmutableList.Builder<Path> filteredDirs = ImmutableList.builder();
    resourcesProvider.createFilterResourcesStep(
        /* whitelistedStringsDir */ ImmutableSet.of(),
        /* locales */ ImmutableSet.of(),
        resourcesProvider.createInResDirToOutResDirMap(resourceDirectories, filteredDirs));

    assertEquals(
        ImmutableList.of(
            BuildTargets.getScratchPath(
                buildRule.getProjectFilesystem(),
                target.withFlavors(AndroidBinaryResourcesGraphEnhancer.RESOURCES_FILTER_FLAVOR),
                "__filtered__%s__/0"),
            BuildTargets.getScratchPath(
                buildRule.getProjectFilesystem(),
                target.withFlavors(AndroidBinaryResourcesGraphEnhancer.RESOURCES_FILTER_FLAVOR),
                "__filtered__%s__/1")),
        filteredDirs.build());
  }

  @Test
  public void testAddPostFilterCommandSteps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    BuildRule keystoreRule = addKeystoreRule(resolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    AndroidBinaryBuilder builder =
        AndroidBinaryBuilder.createBuilder(target)
            .setPostFilterResourcesCmd(Optional.of("cmd"))
            .setResourceFilter(new ResourceFilter(ImmutableList.of("mdpi")))
            .setKeystore(keystoreRule.getBuildTarget())
            .setManifest(new FakeSourcePath("manifest"));
    builder.build(resolver);

    BuildRule aaptPackageRule =
        resolver.getRule(BuildTargetFactory.newInstance("//:target#aapt_package"));
    ResourcesFilter resourcesFilter =
        (ResourcesFilter) ((AaptPackageResources) aaptPackageRule).getFilteredResourcesProvider();
    ImmutableList.Builder<Step> stepsBuilder = new ImmutableList.Builder<>();
    resourcesFilter.addPostFilterCommandSteps(
        StringArg.of("cmd"), pathResolver, stepsBuilder, Paths.get("data.json"));
    ImmutableList<Step> steps = stepsBuilder.build();

    assertEquals(
        ImmutableList.of("bash", "-c", "cmd data.json"),
        ((BashStep) steps.get(0)).getShellCommand(null));
  }

  @Test
  public void testWriteFilterResourcesData() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule keystoreRule = addKeystoreRule(resolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    AndroidBinaryBuilder builder =
        AndroidBinaryBuilder.createBuilder(target)
            .setPostFilterResourcesCmd(Optional.of("cmd"))
            .setResourceFilter(new ResourceFilter(ImmutableList.of("mdpi")))
            .setKeystore(keystoreRule.getBuildTarget())
            .setManifest(new FakeSourcePath("manifest"));
    BuildRule buildRule = builder.build(resolver);

    BuildRule aaptPackageRule =
        resolver.getRule(BuildTargetFactory.newInstance("//:target#aapt_package"));
    ResourcesFilter resourcesFilter =
        (ResourcesFilter) ((AaptPackageResources) aaptPackageRule).getFilteredResourcesProvider();
    ByteArrayOutputStream dataOutputStream = new ByteArrayOutputStream();
    ImmutableList<Path> resourceDirectories = ImmutableList.of(Paths.get("one"), Paths.get("two"));
    ImmutableList.Builder<Path> filteredDirs = ImmutableList.builder();
    resourcesFilter.writeFilterResourcesData(
        dataOutputStream,
        resourcesFilter.createInResDirToOutResDirMap(resourceDirectories, filteredDirs));

    JsonNode jsonNode =
        ObjectMappers.READER.readTree(dataOutputStream.toString(StandardCharsets.UTF_8.name()));
    assertEquals(
        BuildTargets.getScratchPath(
                buildRule.getProjectFilesystem(),
                target.withFlavors(AndroidBinaryResourcesGraphEnhancer.RESOURCES_FILTER_FLAVOR),
                "__filtered__%s__/0")
            .toString(),
        jsonNode.get("res_dir_map").get("one").asText());
    assertEquals(
        BuildTargets.getScratchPath(
                buildRule.getProjectFilesystem(),
                target.withFlavors(AndroidBinaryResourcesGraphEnhancer.RESOURCES_FILTER_FLAVOR),
                "__filtered__%s__/1")
            .toString(),
        jsonNode.get("res_dir_map").get("two").asText());
  }

  @Test
  public void noDxParametersAreHintsAndNotHardDependencies() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule keystoreRule = addKeystoreRule(resolver);

    AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//:target"))
        .setBuildTargetsToExcludeFromDex(
            ImmutableSet.of(BuildTargetFactory.newInstance("//missing:dep")))
        .setKeystore(keystoreRule.getBuildTarget())
        .setManifest(new FakeSourcePath("AndroidManifest.xml"))
        .build(resolver);
  }

  @Test
  public void transitivePrebuiltJarsAreFirstOrderDeps() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    BuildRule keystoreRule = addKeystoreRule(resolver);

    BuildRule prebuiltJarGen =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:jar"))
            .setOut("foo.jar")
            .build(resolver);

    BuildRule transitivePrebuiltJarDep =
        PrebuiltJarBuilder.createBuilder(BuildTargetFactory.newInstance("//:transitive_dep"))
            .setBinaryJar(prebuiltJarGen.getSourcePathToOutput())
            .build(resolver);

    FakeJavaLibrary immediateDep =
        resolver.addToIndex(
            new FakeJavaLibrary(
                BuildTargetFactory.newInstance("//:immediate_dep"),
                pathResolver,
                ImmutableSortedSet.of(transitivePrebuiltJarDep)));

    BuildRule rule =
        AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//:target"))
            .setKeystore(keystoreRule.getBuildTarget())
            .setManifest(new FakeSourcePath("AndroidManifest.xml"))
            .setOriginalDeps(ImmutableSortedSet.of(immediateDep.getBuildTarget()))
            .build(resolver);

    assertThat(rule.getBuildDeps(), Matchers.hasItem(transitivePrebuiltJarDep));
  }

  private Keystore addKeystoreRule(BuildRuleResolver ruleResolver) throws Exception {
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    return KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(new FakeSourcePath("keystore/debug.keystore"))
        .setProperties(new FakeSourcePath("keystore/debug.keystore.properties"))
        .build(ruleResolver);
  }
}
