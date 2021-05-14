/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android.packageable;

import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVAC_OPTIONS;
import static com.facebook.buck.jvm.java.JavaCompilationConstants.DEFAULT_JAVA_OPTIONS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.AndroidBinary;
import com.facebook.buck.android.AndroidBinaryBuilder;
import com.facebook.buck.android.AndroidLibraryBuilder;
import com.facebook.buck.android.AndroidResource;
import com.facebook.buck.android.AndroidResourceBuilder;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.AndroidResourceRuleBuilder;
import com.facebook.buck.android.NativeLibraryBuildRule;
import com.facebook.buck.android.NdkLibrary;
import com.facebook.buck.android.NdkLibraryBuilder;
import com.facebook.buck.android.PrebuiltNativeLibraryBuilder;
import com.facebook.buck.android.TestAndroidPlatformTargetFactory;
import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.DxToolchain;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.android.toolchain.ndk.impl.TestNdkCxxPlatformsProviderFactory;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.jvm.java.toolchain.JavacOptionsProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidPackageableCollectorTest {

  /**
   * This is a regression test to ensure that an additional 1 second startup cost is not
   * re-introduced to fb4a.
   */
  @Test
  public void testFindTransitiveDependencies() throws Exception {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path prebuiltNativeLibraryPath = Paths.get("java/com/facebook/prebuilt_native_library/libs");
    projectFilesystem.mkdirs(prebuiltNativeLibraryPath);

    // Create an AndroidBinaryRule that transitively depends on two prebuilt JARs. One of the two
    // prebuilt JARs will be listed in the AndroidBinaryRule's no_dx list.
    BuildTarget guavaTarget = BuildTargetFactory.newInstance("//third_party/guava:guava");
    TargetNode<?> guava =
        PrebuiltJarBuilder.createBuilder(guavaTarget)
            .setBinaryJar(Paths.get("third_party/guava/guava-10.0.1.jar"))
            .build();

    BuildTarget jsr305Target = BuildTargetFactory.newInstance("//third_party/jsr-305:jsr-305");
    TargetNode<?> jsr =
        PrebuiltJarBuilder.createBuilder(jsr305Target)
            .setBinaryJar(Paths.get("third_party/jsr-305/jsr305.jar"))
            .build();

    TargetNode<?> ndkLibrary =
        new NdkLibraryBuilder(
                BuildTargetFactory.newInstance("//java/com/facebook/native_library:library"),
                projectFilesystem)
            .build();

    BuildTarget prebuiltNativeLibraryTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/prebuilt_native_library:library");
    TargetNode<?> prebuiltNativeLibraryBuild =
        PrebuiltNativeLibraryBuilder.newBuilder(prebuiltNativeLibraryTarget, projectFilesystem)
            .setNativeLibs(prebuiltNativeLibraryPath)
            .setIsAsset(true)
            .build();

    BuildTarget libraryRuleTarget =
        BuildTargetFactory.newInstance("//java/src/com/facebook:example");
    TargetNode<?> library =
        JavaLibraryBuilder.createBuilder(libraryRuleTarget)
            .setProguardConfig(FakeSourcePath.of("debug.pro"))
            .addSrc(Paths.get("Example.java"))
            .addDep(guavaTarget)
            .addDep(jsr305Target)
            .addDep(prebuiltNativeLibraryBuild.getBuildTarget())
            .addDep(ndkLibrary.getBuildTarget())
            .build();

    BuildTarget manifestTarget = BuildTargetFactory.newInstance("//java/src/com/facebook:res");
    TargetNode<?> manifest =
        AndroidResourceBuilder.createBuilder(manifestTarget)
            .setManifest(
                FakeSourcePath.of(
                    projectFilesystem, "java/src/com/facebook/module/AndroidManifest.xml"))
            .setAssets(FakeSourcePath.of("assets"))
            .build();

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    TargetNode<?> keystore =
        KeystoreBuilder.createBuilder(keystoreTarget)
            .setStore(FakeSourcePath.of(projectFilesystem, "keystore/debug.keystore"))
            .setProperties(
                FakeSourcePath.of(projectFilesystem, "keystore/debug.keystore.properties"))
            .build();

    ImmutableSortedSet<BuildTarget> originalDepsTargets =
        ImmutableSortedSet.of(libraryRuleTarget, manifestTarget);
    BuildTarget binaryTarget = BuildTargetFactory.newInstance("//java/src/com/facebook:app");
    TargetNode<?> binary =
        AndroidBinaryBuilder.createBuilder(binaryTarget)
            .setOriginalDeps(originalDepsTargets)
            .setBuildTargetsToExcludeFromDex(
                ImmutableSet.of(BuildTargetFactory.newInstance("//third_party/guava:guava")))
            .setManifest(FakeSourcePath.of("java/src/com/facebook/AndroidManifest.xml"))
            .setKeystore(keystoreTarget)
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            binary,
            library,
            manifest,
            keystore,
            ndkLibrary,
            prebuiltNativeLibraryBuild,
            guava,
            jsr);
    ToolchainProvider toolchainProvider =
        new ToolchainProviderBuilder()
            .withToolchain(
                NdkCxxPlatformsProvider.DEFAULT_NAME,
                NdkCxxPlatformsProvider.of(NdkLibraryBuilder.NDK_PLATFORMS))
            .withToolchain(
                AndroidNdk.DEFAULT_NAME,
                AndroidNdk.of("12b", Paths.get("/android/ndk"), false, new ExecutableFinder()))
            .withToolchain(
                AndroidPlatformTarget.DEFAULT_NAME, TestAndroidPlatformTargetFactory.create())
            .withToolchain(TestNdkCxxPlatformsProviderFactory.createDefaultNdkPlatformsProvider())
            .withToolchain(
                DxToolchain.DEFAULT_NAME, DxToolchain.of(MoreExecutors.newDirectExecutorService()))
            .withToolchain(
                JavaOptionsProvider.DEFAULT_NAME,
                JavaOptionsProvider.of(DEFAULT_JAVA_OPTIONS, DEFAULT_JAVA_OPTIONS))
            .withToolchain(
                JavacOptionsProvider.DEFAULT_NAME, JavacOptionsProvider.of(DEFAULT_JAVAC_OPTIONS))
            .build();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph, toolchainProvider);
    SourcePathResolverAdapter pathResolver = graphBuilder.getSourcePathResolver();

    AndroidBinary binaryRule = (AndroidBinary) graphBuilder.requireRule(binaryTarget);
    NdkLibrary ndkLibraryRule = (NdkLibrary) graphBuilder.requireRule(ndkLibrary.getBuildTarget());
    NativeLibraryBuildRule prebuildNativeLibraryRule =
        (NativeLibraryBuildRule) graphBuilder.requireRule(prebuiltNativeLibraryTarget);

    // Verify that the correct transitive dependencies are found.
    AndroidPackageableCollection packageableCollection =
        binaryRule.getAndroidPackageableCollection();

    assertResolvedEquals(
        "Because guava was passed to no_dx, it should not be in the classpathEntriesToDex list",
        pathResolver,
        ImmutableSet.of(
            graphBuilder.getRule(jsr305Target).getSourcePathToOutput(),
            graphBuilder.getRule(libraryRuleTarget).getSourcePathToOutput()),
        packageableCollection.getClasspathEntriesToDex());
    assertResolvedEquals(
        "Because guava was passed to no_dx, it should not be treated as a third-party JAR whose "
            + "resources need to be extracted and repacked in the APK. If this is done, then code "
            + "in the guava-10.0.1.dex.1.jar in the APK's assets/ tmp may try to load the resource "
            + "from the APK as a ZipFileEntry rather than as a resource within "
            + "guava-10.0.1.dex.1.jar. Loading a resource in this way could take substantially "
            + "longer. Specifically, this was observed to take over one second longer to load "
            + "the resource in fb4a. Because the resource was loaded on startup, this introduced a "
            + "substantial regression in the startup time for the fb4a app.",
        pathResolver,
        ImmutableSet.of(graphBuilder.getRule(jsr305Target).getSourcePathToOutput()),
        packageableCollection.getPathsToThirdPartyJars().values().stream()
            .collect(ImmutableSet.toImmutableSet()));
    assertResolvedEquals(
        "Because assets directory was passed an AndroidResourceRule it should be added to the "
            + "transitive dependencies",
        pathResolver,
        ImmutableSet.of(
            DefaultBuildTargetSourcePath.of(
                manifestTarget.withAppendedFlavors(
                    AndroidResourceDescription.ASSETS_SYMLINK_TREE_FLAVOR))),
        packageableCollection.getAssetsDirectories().values().stream()
            .collect(ImmutableSet.toImmutableSet()));
    assertResolvedEquals(
        "Because a native library was declared as a dependency, it should be added to the "
            + "transitive dependencies.",
        pathResolver,
        ImmutableSet.of(
            PathSourcePath.of(new FakeProjectFilesystem(), ndkLibraryRule.getLibraryPath())),
        ImmutableSet.copyOf(packageableCollection.getNativeLibsDirectories().values()));
    assertResolvedEquals(
        "Because a prebuilt native library  was declared as a dependency (and asset), it should "
            + "be added to the transitive dependecies.",
        pathResolver,
        ImmutableSet.of(FakeSourcePath.of(prebuildNativeLibraryRule.getLibraryPath())),
        ImmutableSet.copyOf(packageableCollection.getNativeLibAssetsDirectories().values()));
    assertEquals(
        ImmutableSet.of(FakeSourcePath.of("debug.pro")),
        packageableCollection.getProguardConfigs());
  }

  /**
   * Create the following dependency graph of {@link AndroidResource}s:
   *
   * <pre>
   *    A
   *  / | \
   * B  |  D
   *  \ | /
   *    C
   * </pre>
   *
   * Note that an ordinary breadth-first traversal would yield either {@code A B C D} or {@code A D
   * C B}. However, either of these would be <em>wrong</em> in this case because we need to be sure
   * that we perform a topological sort, the resulting traversal of which is either {@code A B D C}
   * or {@code A D B C}.
   *
   * <p>The reason for the correct result being reversed is because we want the resources with the
   * most dependencies listed first on the path, so that they're used in preference to the ones that
   * they depend on (presumably, the reason for extending the initial set of resources was to
   * override values).
   */
  @Test
  public void testGetAndroidResourceDeps() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildRule c =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//:c"))
                .setRes(FakeSourcePath.of("res_c"))
                .setRDotJavaPackage("com.facebook")
                .build());

    BuildRule b =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//:b"))
                .setRes(FakeSourcePath.of("res_b"))
                .setRDotJavaPackage("com.facebook")
                .setDeps(ImmutableSortedSet.of(c))
                .build());

    BuildRule d =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//:d"))
                .setRes(FakeSourcePath.of("res_d"))
                .setRDotJavaPackage("com.facebook")
                .setDeps(ImmutableSortedSet.of(c))
                .build());

    AndroidResource a =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//:a"))
                .setRes(FakeSourcePath.of("res_a"))
                .setRDotJavaPackage("com.facebook")
                .setDeps(ImmutableSortedSet.of(b, c, d))
                .build());

    AndroidPackageableCollector collector = new AndroidPackageableCollector(a.getBuildTarget());
    collector.addPackageables(ImmutableList.of(a), graphBuilder);

    // Note that a topological sort for a DAG is not guaranteed to be unique, but we order nodes
    // within the same depth of the search.
    ImmutableList<BuildTarget> result =
        ImmutableList.of(a, d, b, c).stream()
            .map(BuildRule::getBuildTarget)
            .collect(ImmutableList.toImmutableList());

    APKModule rootModule = APKModule.of(APKModuleGraph.ROOT_APKMODULE_NAME, true, true);

    assertEquals(
        "Android resources should be topologically sorted.",
        result,
        collector.build().getResourceDetails().get(rootModule).getResourcesWithNonEmptyResDir());

    // Introduce an AndroidBinaryRule that depends on A and C and verify that the same topological
    // sort results. This verifies that both AndroidResourceRule.getAndroidResourceDeps does the
    // right thing when it gets a non-AndroidResourceRule as well as an AndroidResourceRule.
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(FakeSourcePath.of("keystore/debug.keystore"))
        .setProperties(FakeSourcePath.of("keystore/debug.keystore.properties"))
        .build(graphBuilder);

    ImmutableSortedSet<BuildTarget> declaredDepsTargets =
        ImmutableSortedSet.of(a.getBuildTarget(), c.getBuildTarget());
    AndroidBinary androidBinary =
        AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//:e"))
            .setManifest(FakeSourcePath.of("AndroidManfiest.xml"))
            .setKeystore(keystoreTarget)
            .setOriginalDeps(declaredDepsTargets)
            .build(graphBuilder);

    assertEquals(
        "Android resources should be topologically sorted.",
        result,
        androidBinary
            .getAndroidPackageableCollection()
            .getResourceDetails()
            .get(rootModule)
            .getResourcesWithNonEmptyResDir());
  }

  @Test
  public void testGetAndroidResourceDepsWithDuplicateResourcePaths() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    PathSourcePath resPath = FakeSourcePath.of("res");
    AndroidResource res1 =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//:res1"))
                .setRes(resPath)
                .setRDotJavaPackage("com.facebook")
                .build());

    AndroidResource res2 =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//:res2"))
                .setRes(resPath)
                .setRDotJavaPackage("com.facebook")
                .build());

    PathSourcePath resBPath = FakeSourcePath.of("res_b");
    BuildRule b =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//:b"))
                .setRes(resBPath)
                .setRDotJavaPackage("com.facebook")
                .build());

    PathSourcePath resAPath = FakeSourcePath.of("res_a");
    AndroidResource a =
        graphBuilder.addToIndex(
            AndroidResourceRuleBuilder.newBuilder()
                .setRuleFinder(graphBuilder)
                .setBuildTarget(BuildTargetFactory.newInstance("//:a"))
                .setRes(resAPath)
                .setRDotJavaPackage("com.facebook")
                .setDeps(ImmutableSortedSet.of(res1, res2, b))
                .build());

    AndroidPackageableCollector collector = new AndroidPackageableCollector(a.getBuildTarget());
    collector.addPackageables(ImmutableList.of(a), graphBuilder);

    AndroidPackageableCollection androidPackageableCollection = collector.build();
    AndroidPackageableCollection.ResourceDetails resourceDetails =
        androidPackageableCollection
            .getResourceDetails()
            .get(APKModule.of(APKModuleGraph.ROOT_APKMODULE_NAME, true, true));
    assertThat(
        resourceDetails.getResourceDirectories(), Matchers.contains(resAPath, resPath, resBPath));
  }

  /**
   * If the keystore rule depends on an android_library, and an android_binary uses that keystore,
   * the keystore's android_library should not contribute to the classpath of the android_binary.
   */
  @Test
  public void testGraphForAndroidBinaryExcludesKeystoreDeps() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    BuildTarget androidLibraryKeystoreTarget =
        BuildTargetFactory.newInstance("//java/com/keystore/base:base");
    BuildRule androidLibraryKeystore =
        AndroidLibraryBuilder.createBuilder(androidLibraryKeystoreTarget)
            .addSrc(Paths.get("java/com/facebook/keystore/Base.java"))
            .build(graphBuilder);

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(FakeSourcePath.of("keystore/debug.keystore"))
        .setProperties(FakeSourcePath.of("keystore/debug.keystore.properties"))
        .addDep(androidLibraryKeystore.getBuildTarget())
        .build(graphBuilder);

    BuildTarget androidLibraryTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/base:base");
    BuildRule androidLibrary =
        AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
            .addSrc(Paths.get("java/com/facebook/base/Base.java"))
            .build(graphBuilder);

    ImmutableSortedSet<BuildTarget> originalDepsTargets =
        ImmutableSortedSet.of(androidLibrary.getBuildTarget());
    AndroidBinary androidBinary =
        AndroidBinaryBuilder.createBuilder(BuildTargetFactory.newInstance("//apps/sample:app"))
            .setManifest(FakeSourcePath.of("apps/sample/AndroidManifest.xml"))
            .setOriginalDeps(originalDepsTargets)
            .setKeystore(keystoreTarget)
            .build(graphBuilder);

    AndroidPackageableCollection packageableCollection =
        androidBinary.getAndroidPackageableCollection();
    assertEquals(
        "Classpath entries should include facebook/base but not keystore/base.",
        ImmutableSet.of(
            BuildTargetPaths.getGenPath(
                    androidBinary.getProjectFilesystem(), androidLibraryTarget, "lib__%s__output")
                .resolve(androidLibraryTarget.getShortNameAndFlavorPostfix() + ".jar")),
        packageableCollection.getClasspathEntriesToDex().stream()
            .map(graphBuilder.getSourcePathResolver()::getRelativePath)
            .collect(ImmutableSet.toImmutableSet()));
  }

  private void assertResolvedEquals(
      String message,
      SourcePathResolverAdapter pathResolver,
      ImmutableSet<SourcePath> expected,
      ImmutableSet<SourcePath> actual) {
    assertEquals(
        message,
        expected.stream().map(pathResolver::getRelativePath).collect(ImmutableSet.toImmutableSet()),
        actual.stream().map(pathResolver::getRelativePath).collect(ImmutableSet.toImmutableSet()));
  }
}
