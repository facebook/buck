/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.java.FakeJavaLibraryRule;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FileSourcePath;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.Map;

public class AndroidInstrumentationApkTest {

  @Test
  public void testAndroidInstrumentationApkExcludesClassesFromInstrumentedApk() {
    final JavaLibraryRule javaLibrary1 = new FakeJavaLibraryRule(
        new BuildTarget("//java/com/example", "lib1"));

    JavaLibraryRule javaLibrary2 = new FakeJavaLibraryRule(
        new BuildTarget("//java/com/example", "lib2"),
        /* deps */ ImmutableSortedSet.of((BuildRule) javaLibrary1)) {
      @Override
      public ImmutableSetMultimap<JavaLibraryRule, String> getTransitiveClasspathEntries() {
        ImmutableSetMultimap.Builder<JavaLibraryRule, String> builder =
            ImmutableSetMultimap.builder();
        builder.put(javaLibrary1, javaLibrary1.getPathToOutputFile().toString());
        builder.put(this, this.getPathToOutputFile().toString());
        return builder.build();
      }
    };

    final JavaLibraryRule javaLibrary3 = new FakeJavaLibraryRule(
        new BuildTarget("//java/com/example", "lib3"));

    JavaLibraryRule javaLibrary4 = new FakeJavaLibraryRule(
        new BuildTarget("//java/com/example", "lib4"),
        /* deps */ ImmutableSortedSet.of((BuildRule) javaLibrary3)) {
      @Override
      public ImmutableSetMultimap<JavaLibraryRule, String> getTransitiveClasspathEntries() {
        ImmutableSetMultimap.Builder<JavaLibraryRule, String> builder =
            ImmutableSetMultimap.builder();
        builder.put(javaLibrary3, javaLibrary3.getPathToOutputFile().toString());
        builder.put(this, this.getPathToOutputFile().toString());
        return builder.build();
      }
    };

    Map<BuildTarget, BuildRule> buildRuleIndex = Maps.newHashMap();
    buildRuleIndex.put(javaLibrary1.getBuildTarget(), javaLibrary1);
    buildRuleIndex.put(javaLibrary2.getBuildTarget(), javaLibrary2);
    buildRuleIndex.put(javaLibrary3.getBuildTarget(), javaLibrary3);
    buildRuleIndex.put(javaLibrary4.getBuildTarget(), javaLibrary4);
    BuildRuleResolver ruleResolver = new BuildRuleResolver(buildRuleIndex);

    BuildRule keystore = ruleResolver.buildAndAddToIndex(
        Keystore.newKeystoreBuilder(
            new FakeAbstractBuildRuleBuilderParams())
            .setBuildTarget(new BuildTarget("//keystores", "debug"))
            .setProperties(Paths.get("keystores/debug.properties"))
            .setStore(Paths.get("keystores/debug.keystore"))
            .addVisibilityPattern(BuildTargetPattern.MATCH_ALL));

    // AndroidBinaryRule transitively depends on :lib1, :lib2, and :lib3.
    AndroidBinaryRule.Builder androidBinaryBuilder = AndroidBinaryRule.newAndroidBinaryRuleBuilder(
        new FakeAbstractBuildRuleBuilderParams());
    androidBinaryBuilder
        .setBuildTarget(new BuildTarget("//apps", "app"))
        .setManifest(new FileSourcePath("apps/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:18")
        .setKeystore(keystore.getBuildTarget())
        .addClasspathDep(javaLibrary2.getBuildTarget())
        .addClasspathDep(javaLibrary3.getBuildTarget());
    AndroidBinaryRule androidBinary = ruleResolver.buildAndAddToIndex(androidBinaryBuilder);

    // AndroidInstrumentationApk transitively depends on :lib1, :lib2, :lib3, and :lib4.
    AndroidInstrumentationApk.Builder androidInstrumentationApkBuilder = AndroidInstrumentationApk
        .newAndroidInstrumentationApkRuleBuilder(new FakeAbstractBuildRuleBuilderParams());
    androidInstrumentationApkBuilder
        .setBuildTarget(new BuildTarget("//apps", "instrumentation"))
        .setManifest(new FileSourcePath("apps/InstrumentationAndroidManifest.xml"))
        .setApk(androidBinary.getBuildTarget())
        .addClasspathDep(javaLibrary2.getBuildTarget())
        .addClasspathDep(javaLibrary4.getBuildTarget());
    AndroidInstrumentationApk androidInstrumentationApk = ruleResolver.buildAndAddToIndex(
        androidInstrumentationApkBuilder);

    assertEquals(BuildRuleType.ANDROID_INSTRUMENTATION_APK, androidInstrumentationApk.getType());
    assertEquals(
        "//apps:app should have three JAR files to dex.",
        ImmutableSet.of(
            "buck-out/gen/java/com/example/lib1.jar",
            "buck-out/gen/java/com/example/lib2.jar",
            "buck-out/gen/java/com/example/lib3.jar"),
        androidBinary.findDexTransitiveDependencies().classpathEntriesToDex);
    assertEquals(
        "//apps:instrumentation should have one JAR file to dex.",
        ImmutableSet.of("buck-out/gen/java/com/example/lib4.jar"),
        androidInstrumentationApk.findDexTransitiveDependencies().classpathEntriesToDex);
  }
}
