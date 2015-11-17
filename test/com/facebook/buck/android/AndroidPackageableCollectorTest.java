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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.KeystoreBuilder;
import com.facebook.buck.jvm.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.BuckConstant;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AndroidPackageableCollectorTest {

  /**
   * This is a regression test to ensure that an additional 1 second startup cost is not
   * re-introduced to fb4a.
   */
  @Test
  public void testFindTransitiveDependencies() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path prebuiltNativeLibraryPath = Paths.get("java/com/facebook/prebuilt_native_library/libs");
    projectFilesystem.mkdirs(prebuiltNativeLibraryPath);

    // Create an AndroidBinaryRule that transitively depends on two prebuilt JARs. One of the two
    // prebuilt JARs will be listed in the AndroidBinaryRule's no_dx list.
    BuildTarget guavaTarget = BuildTargetFactory.newInstance("//third_party/guava:guava");
    PrebuiltJarBuilder
        .createBuilder(guavaTarget)
        .setBinaryJar(Paths.get("third_party/guava/guava-10.0.1.jar"))
        .build(ruleResolver);

    BuildTarget jsr305Target = BuildTargetFactory.newInstance("//third_party/jsr-305:jsr-305");
    PrebuiltJarBuilder
        .createBuilder(jsr305Target)
        .setBinaryJar(Paths.get("third_party/jsr-305/jsr305.jar"))
        .build(ruleResolver);

    BuildRule ndkLibrary =
        new NdkLibraryBuilder(
                BuildTargetFactory.newInstance("//java/com/facebook/native_library:library"))
            .build(ruleResolver, projectFilesystem);

    BuildTarget prebuiltNativeLibraryTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/prebuilt_native_library:library");
    BuildRule prebuiltNativeLibraryBuild =
        PrebuiltNativeLibraryBuilder.newBuilder(prebuiltNativeLibraryTarget)
        .setNativeLibs(prebuiltNativeLibraryPath)
        .setIsAsset(true)
        .build(ruleResolver, projectFilesystem);

    BuildTarget libraryRuleTarget =
        BuildTargetFactory.newInstance("//java/src/com/facebook:example");
    JavaLibraryBuilder
        .createBuilder(libraryRuleTarget)
        .setProguardConfig(Paths.get("debug.pro"))
        .addSrc(Paths.get("Example.java"))
        .addDep(guavaTarget)
        .addDep(jsr305Target)
        .addDep(prebuiltNativeLibraryBuild.getBuildTarget())
        .addDep(ndkLibrary.getBuildTarget())
        .build(ruleResolver);

    BuildTarget manifestTarget = BuildTargetFactory.newInstance("//java/src/com/facebook:res");
    AndroidResource manifestRule = AndroidResourceRuleBuilder
        .newBuilder()
        .setResolver(pathResolver)
        .setBuildTarget(manifestTarget)
        .setManifest(
            new PathSourcePath(
                projectFilesystem,
                Paths.get("java/src/com/facebook/module/AndroidManifest.xml")))
        .setAssets(new FakeSourcePath("assets"))
        .build();
    ruleResolver.addToIndex(manifestRule);

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(new FakeSourcePath(projectFilesystem, "keystore/debug.keystore"))
        .setProperties(new FakeSourcePath(projectFilesystem, "keystore/debug.keystore.properties"))
        .build(ruleResolver);

    ImmutableSortedSet<BuildTarget> originalDepsTargets =
        ImmutableSortedSet.of(libraryRuleTarget, manifestTarget);
    ruleResolver.getAllRules(originalDepsTargets);
    AndroidBinary binaryRule = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//java/src/com/facebook:app"))
        .setOriginalDeps(originalDepsTargets)
        .setBuildTargetsToExcludeFromDex(
            ImmutableSet.of(BuildTargetFactory.newInstance("//third_party/guava:guava")))
        .setManifest(new FakeSourcePath("java/src/com/facebook/AndroidManifest.xml"))
        .setKeystore(keystoreTarget)
        .build(ruleResolver);

    // Verify that the correct transitive dependencies are found.
    AndroidPackageableCollection packageableCollection =
        binaryRule.getAndroidPackageableCollection();
    assertEquals(
        "Because guava was passed to no_dx, it should not be in the classpathEntriesToDex list",
        ImmutableSet.of(
            Paths.get("buck-out/gen/third_party/jsr-305/jsr-305.jar"),
            BuckConstant.GEN_PATH.resolve(
                "java/src/com/facebook/lib__example__output/example.jar")),
        FluentIterable.from(packageableCollection.getClasspathEntriesToDex())
            .transform(pathResolver.deprecatedPathFunction())
            .toSet());
    assertEquals(
        "Because guava was passed to no_dx, it should not be treated as a third-party JAR whose " +
            "resources need to be extracted and repacked in the APK. If this is done, then code " +
            "in the guava-10.0.1.dex.1.jar in the APK's assets/ tmp may try to load the resource " +
            "from the APK as a ZipFileEntry rather than as a resource within " +
            "guava-10.0.1.dex.1.jar. Loading a resource in this way could take substantially " +
            "longer. Specifically, this was observed to take over one second longer to load " +
            "the resource in fb4a. Because the resource was loaded on startup, this introduced a " +
            "substantial regression in the startup time for the fb4a app.",
        ImmutableSet.of(Paths.get("buck-out/gen/third_party/jsr-305/jsr-305.jar")),
        FluentIterable.from(packageableCollection.getPathsToThirdPartyJars())
            .transform(pathResolver.deprecatedPathFunction())
            .toSet());
    assertEquals(
        "Because assets directory was passed an AndroidResourceRule it should be added to the " +
            "transitive dependencies",
        ImmutableSet.of(new FakeSourcePath("assets")),
        packageableCollection.getAssetsDirectories());
    assertEquals(
        "Because a native library was declared as a dependency, it should be added to the " +
            "transitive dependencies.",
        ImmutableSet.<SourcePath>of(
            new PathSourcePath(
                new FakeProjectFilesystem(),
                ((NativeLibraryBuildRule) ndkLibrary).getLibraryPath())),
        packageableCollection.getNativeLibsDirectories());
    assertEquals(
        "Because a prebuilt native library  was declared as a dependency (and asset), it should " +
            "be added to the transitive dependecies.",
        ImmutableSet.<SourcePath>of(
            new PathSourcePath(
                new FakeProjectFilesystem(),
                ((NativeLibraryBuildRule) prebuiltNativeLibraryBuild).getLibraryPath())),
        packageableCollection.getNativeLibAssetsDirectories());
    assertEquals(
        ImmutableSet.of(new FakeSourcePath("debug.pro")),
        packageableCollection.getProguardConfigs());
  }

  /**
   * Create the following dependency graph of {@link AndroidResource}s:
   * <pre>
   *    A
   *  / | \
   * B  |  D
   *  \ | /
   *    C
   * </pre>
   * Note that an ordinary breadth-first traversal would yield either {@code A B C D} or
   * {@code A D C B}. However, either of these would be <em>wrong</em> in this case because we need
   * to be sure that we perform a topological sort, the resulting traversal of which is either
   * {@code A B D C} or {@code A D B C}.
   * <p>
   * The reason for the correct result being reversed is because we want the resources with the most
   * dependencies listed first on the path, so that they're used in preference to the ones that they
   * depend on (presumably, the reason for extending the initial set of resources was to override
   * values).
   */
  @Test
  public void testGetAndroidResourceDeps() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    BuildRule c = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(pathResolver)
            .setBuildTarget(BuildTargetFactory.newInstance("//:c"))
            .setRes(new FakeSourcePath("res_c"))
            .setRDotJavaPackage("com.facebook")
            .build());

    BuildRule b = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(pathResolver)
            .setBuildTarget(BuildTargetFactory.newInstance("//:b"))
            .setRes(new FakeSourcePath("res_b"))
            .setRDotJavaPackage("com.facebook")
            .setDeps(ImmutableSortedSet.of(c))
            .build());

    BuildRule d = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(pathResolver)
            .setBuildTarget(BuildTargetFactory.newInstance("//:d"))
            .setRes(new FakeSourcePath("res_d"))
            .setRDotJavaPackage("com.facebook")
            .setDeps(ImmutableSortedSet.of(c))
            .build());

    AndroidResource a = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setResolver(pathResolver)
            .setBuildTarget(BuildTargetFactory.newInstance("//:a"))
            .setRes(new FakeSourcePath("res_a"))
            .setRDotJavaPackage("com.facebook")
            .setDeps(ImmutableSortedSet.of(b, c, d))
            .build());

    AndroidPackageableCollector collector = new AndroidPackageableCollector(a.getBuildTarget());
    collector.addPackageables(ImmutableList.<AndroidPackageable>of(a));

    // Note that a topological sort for a DAG is not guaranteed to be unique, but we order nodes
    // within the same depth of the search.
    ImmutableList<BuildTarget> result = FluentIterable.from(ImmutableList.of(a, d, b, c))
        .transform(BuildTarget.TO_TARGET)
        .toList();

    assertEquals(
        "Android resources should be topologically sorted.",
        result,
        collector.build().getResourceDetails().getResourcesWithNonEmptyResDir());

    // Introduce an AndroidBinaryRule that depends on A and C and verify that the same topological
    // sort results. This verifies that both AndroidResourceRule.getAndroidResourceDeps does the
    // right thing when it gets a non-AndroidResourceRule as well as an AndroidResourceRule.
    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(new FakeSourcePath("keystore/debug.keystore"))
        .setProperties(new FakeSourcePath("keystore/debug.keystore.properties"))
        .build(ruleResolver);

    ImmutableSortedSet<BuildTarget> declaredDepsTargets =
        ImmutableSortedSet.of(a.getBuildTarget(), c.getBuildTarget());
    AndroidBinary androidBinary = (AndroidBinary) AndroidBinaryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//:e"))
        .setManifest(new FakeSourcePath("AndroidManfiest.xml"))
        .setKeystore(keystoreTarget)
        .setOriginalDeps(declaredDepsTargets)
        .build(ruleResolver);

    assertEquals(
        "Android resources should be topologically sorted.",
        result,
        androidBinary
            .getAndroidPackageableCollection()
            .getResourceDetails()
            .getResourcesWithNonEmptyResDir());
  }

  /**
   * If the keystore rule depends on an android_library, and an android_binary uses that keystore,
   * the keystore's android_library should not contribute to the classpath of the android_binary.
   */
  @Test
  public void testGraphForAndroidBinaryExcludesKeystoreDeps() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    BuildTarget androidLibraryKeystoreTarget =
        BuildTargetFactory.newInstance("//java/com/keystore/base:base");
    BuildRule androidLibraryKeystore = AndroidLibraryBuilder
        .createBuilder(androidLibraryKeystoreTarget)
        .addSrc(Paths.get("java/com/facebook/keystore/Base.java"))
        .build(ruleResolver);

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(new FakeSourcePath("keystore/debug.keystore"))
        .setProperties(new FakeSourcePath("keystore/debug.keystore.properties"))
        .addDep(androidLibraryKeystore.getBuildTarget())
        .build(ruleResolver);

    BuildTarget androidLibraryTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/base:base");
    BuildRule androidLibrary = AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
        .addSrc(Paths.get("java/com/facebook/base/Base.java"))
        .build(ruleResolver);

    ImmutableSortedSet<BuildTarget> originalDepsTargets =
        ImmutableSortedSet.of(androidLibrary.getBuildTarget());
    AndroidBinary androidBinary = (AndroidBinary) AndroidBinaryBuilder.createBuilder(
        BuildTargetFactory.newInstance("//apps/sample:app"))
        .setManifest(new FakeSourcePath("apps/sample/AndroidManifest.xml"))
        .setOriginalDeps(originalDepsTargets)
        .setKeystore(keystoreTarget)
        .build(ruleResolver);

    AndroidPackageableCollection packageableCollection =
        androidBinary.getAndroidPackageableCollection();
    assertEquals(
        "Classpath entries should include facebook/base but not keystore/base.",
        ImmutableSet.of(
            BuckConstant.GEN_PATH.resolve("java/com/facebook/base/lib__base__output/base.jar")),
        FluentIterable.from(packageableCollection.getClasspathEntriesToDex())
            .transform(pathResolver.deprecatedPathFunction())
            .toSet());
  }
}
