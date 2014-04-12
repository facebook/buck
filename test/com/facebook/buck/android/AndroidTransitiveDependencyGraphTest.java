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

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.java.PrebuiltJarBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.util.BuckConstant;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Paths;

public class AndroidTransitiveDependencyGraphTest {

  /**
   * This is a regression test to ensure that an additional 1 second startup cost is not
   * re-introduced to fb4a.
   */
  @Test
  public void testFindTransitiveDependencies() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    // Create an AndroidBinaryRule that transitively depends on two prebuilt JARs. One of the two
    // prebuilt JARs will be listed in the AndroidBinaryRule's no_dx list.
    BuildTarget guavaTarget = BuildTargetFactory.newInstance("//third_party/guava:guava");
    BuildRule guavaRule =
        PrebuiltJarBuilder.createBuilder(guavaTarget)
        .setBinaryJar(Paths.get("third_party/guava/guava-10.0.1.jar"))
        .build(ruleResolver);

    BuildRule jsr305Rule = PrebuiltJarBuilder
        .createBuilder(BuildTargetFactory.newInstance("//third_party/jsr-305:jsr-305"))
        .setBinaryJar(Paths.get("third_party/jsr-305/jsr305.jar"))
        .build(ruleResolver);

    BuildRule ndkLibrary =
        NdkLibraryBuilder.createNdkLibrary(BuildTargetFactory.newInstance(
                "//java/com/facebook/native_library:library"))
            .addSrc(Paths.get("Android.mk"))
            .setIsAsset(false).build();
    ruleResolver.addToIndex(ndkLibrary.getBuildTarget(), ndkLibrary);

    BuildTarget prebuiltNativeLibraryTarget =
        BuildTargetFactory.newInstance("//java/com/facebook/prebuilt_native_library:library");
    BuildRule prebuiltNativeLibraryBuild =
        PrebuiltNativeLibraryBuilder.newBuilder(prebuiltNativeLibraryTarget)
        .setNativeLibs(Paths.get("/java/com/facebook/prebuilt_native_library/libs"))
        .setIsAsset(true)
        .build();
    ruleResolver.addToIndex(prebuiltNativeLibraryTarget, prebuiltNativeLibraryBuild);

    BuildRule libraryRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//java/src/com/facebook:example"))
        .addDep(guavaRule)
        .addDep(jsr305Rule)
        .addDep(prebuiltNativeLibraryBuild)
        .addDep(ndkLibrary)
        .build(ruleResolver);

    BuildRule manifestRule = ruleResolver.addToIndex(
        AndroidResourceRuleBuilder.newBuilder()
            .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook:res"))
            .setManifest(Paths.get("java/src/com/facebook/module/AndroidManifest.xml"))
            .setAssets(Paths.get("assets"))
            .build());

    BuildTarget keystoreTarget = BuildTargetFactory.newInstance("//keystore:debug");
    Keystore keystore = (Keystore) KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(Paths.get("keystore/debug.keystore"))
        .setProperties(Paths.get("keystore/debug.keystore.properties"))
        .build(ruleResolver)
        .getBuildable();

    AndroidBinary binaryRule = (AndroidBinary) AndroidBinaryBuilder.newBuilder()
        .setBuildTarget(BuildTargetFactory.newInstance("//java/src/com/facebook:app"))
        .setOriginalDeps(ImmutableSortedSet.of(libraryRule, manifestRule))
        .setBuildTargetsToExcludeFromDex(
            ImmutableSet.of(BuildTargetFactory.newInstance("//third_party/guava:guava")))
        .setManifest(new TestSourcePath("java/src/com/facebook/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(keystore)
        .build(ruleResolver)
        .getBuildable();
    binaryRule.getEnhancedDeps(ruleResolver);

    // Verify that the correct transitive dependencies are found.
    AndroidTransitiveDependencies transitiveDeps = binaryRule.findTransitiveDependencies();
    AndroidDexTransitiveDependencies dexTransitiveDeps =
        binaryRule.findDexTransitiveDependencies();
    assertEquals(
        "Because guava was passed to no_dx, it should not be in the classpathEntriesToDex list",
        ImmutableSet.of(Paths.get("third_party/jsr-305/jsr305.jar")),
        dexTransitiveDeps.classpathEntriesToDex);
    assertEquals(
        "Because guava was passed to no_dx, it should not be treated as a third-party JAR whose " +
            "resources need to be extracted and repacked in the APK. If this is done, then code " +
            "in the guava-10.0.1.dex.1.jar in the APK's assets/ tmp may try to load the resource " +
            "from the APK as a ZipFileEntry rather than as a resource within " +
            "guava-10.0.1.dex.1.jar. Loading a resource in this way could take substantially " +
            "longer. Specifically, this was observed to take over one second longer to load " +
            "the resource in fb4a. Because the resource was loaded on startup, this introduced a " +
            "substantial regression in the startup time for the fb4a app.",
        ImmutableSet.of(Paths.get("third_party/jsr-305/jsr305.jar")),
        dexTransitiveDeps.pathsToThirdPartyJars);
    assertEquals(
        "Because assets directory was passed an AndroidResourceRule it should be added to the " +
            "transitive dependencies",
        ImmutableSet.of(Paths.get("assets")),
        transitiveDeps.assetsDirectories);
    assertEquals(
        "There should be one NativeLibraryBuildable that is an asset.",
        ImmutableSet.of(new BuildTarget("//java/com/facebook/prebuilt_native_library", "library")),
        transitiveDeps.nativeTargetsWithAssets);
    assertEquals(
        "Because manifest file was passed an AndroidResourceRule it should be added to the " +
            "transitive dependencies",
        ImmutableSet.of(Paths.get("java/src/com/facebook/module/AndroidManifest.xml")),
        transitiveDeps.manifestFiles);
    assertEquals(
        "Because a native library was declared as a dependency, it should be added to the " +
            "transitive dependencies.",
        ImmutableSet.of(((NativeLibraryBuildable) ndkLibrary.getBuildable()).getLibraryPath()),
        transitiveDeps.nativeLibsDirectories);
    assertEquals(
        "Because a prebuilt native library  was declared as a dependency (and asset), it should " +
            "be added to the transitive dependecies.",
        ImmutableSet.of(((NativeLibraryBuildable) prebuiltNativeLibraryBuild.getBuildable())
            .getLibraryPath()),
        transitiveDeps.nativeLibAssetsDirectories);
  }

  /**
   * If the keystore rule depends on an android_library, and an android_binary uses that keystore,
   * the keystore's android_library should not contribute to the classpath of the android_binary.
   */
  @Test
  public void testGraphForAndroidBinaryExcludesKeystoreDeps() {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();

    BuildTarget androidLibraryKeystoreTarget = new BuildTarget("//java/com/keystore/base", "base");
    BuildRule androidLibraryKeystore = AndroidLibraryBuilder
        .createBuilder(androidLibraryKeystoreTarget)
        .addSrc(Paths.get("java/com/facebook/keystore/Base.java"))
        .build(ruleResolver);

    BuildTarget keystoreTarget = new BuildTarget("//keystore", "debug");
    Keystore keystore = (Keystore) KeystoreBuilder.createBuilder(keystoreTarget)
        .setStore(Paths.get("keystore/debug.keystore"))
        .setProperties(Paths.get("keystore/debug.keystore.properties"))
        .addDep(androidLibraryKeystore)
        .build(ruleResolver)
        .getBuildable();

    BuildTarget androidLibraryTarget = new BuildTarget("//java/com/facebook/base", "base");
    BuildRule androidLibrary = AndroidLibraryBuilder.createBuilder(androidLibraryTarget)
        .addSrc(Paths.get("java/com/facebook/base/Base.java"))
        .build(ruleResolver);

    AndroidBinary androidBinary = (AndroidBinary) AndroidBinaryBuilder.newBuilder()
        .setBuildTarget(new BuildTarget("//apps/sample", "app"))
        .setManifest(new TestSourcePath("apps/sample/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:16")
        .setKeystore(keystore)
        .setOriginalDeps(ImmutableSortedSet.of(androidLibrary))
        .build(ruleResolver)
        .getBuildable();
    androidBinary.getEnhancedDeps(ruleResolver);

    AndroidDexTransitiveDependencies androidTransitiveDeps = androidBinary
        .findDexTransitiveDependencies();
    assertEquals(
        "Classpath entries should include facebook/base but not keystore/base.",
        ImmutableSet.of(
            BuckConstant.GEN_PATH.resolve("java/com/facebook/base/lib__base__output/base.jar")),
        androidTransitiveDeps.classpathEntriesToDex);
  }
}
