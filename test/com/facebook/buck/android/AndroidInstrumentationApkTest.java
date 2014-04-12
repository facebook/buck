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

import com.facebook.buck.java.FakeJavaLibrary;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.Keystore;
import com.facebook.buck.java.KeystoreBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class AndroidInstrumentationApkTest {

  @Test
  public void testAndroidInstrumentationApkExcludesClassesFromInstrumentedApk() {
    final FakeJavaLibrary javaLibrary1 = new FakeJavaLibrary(
        new BuildTarget("//java/com/example", "lib1"));

    FakeJavaLibrary javaLibrary2 = new FakeJavaLibrary(
        new BuildTarget("//java/com/example", "lib2"),
        /* deps */ ImmutableSortedSet.of((BuildRule) javaLibrary1)) {
      @Override
      public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
        ImmutableSetMultimap.Builder<JavaLibrary, Path> builder =
            ImmutableSetMultimap.builder();
        builder.put(javaLibrary1, javaLibrary1.getPathToOutputFile());
        builder.put(this, this.getPathToOutputFile());
        return builder.build();
      }
    };

    final FakeJavaLibrary javaLibrary3 = new FakeJavaLibrary(
        new BuildTarget("//java/com/example", "lib3"));

    FakeJavaLibrary javaLibrary4 = new FakeJavaLibrary(
        new BuildTarget("//java/com/example", "lib4"),
        /* deps */ ImmutableSortedSet.of((BuildRule) javaLibrary3)) {
      @Override
      public ImmutableSetMultimap<JavaLibrary, Path> getTransitiveClasspathEntries() {
        ImmutableSetMultimap.Builder<JavaLibrary, Path> builder =
            ImmutableSetMultimap.builder();
        builder.put(javaLibrary3, javaLibrary3.getPathToOutputFile());
        builder.put(this, this.getPathToOutputFile());
        return builder.build();
      }
    };

    Map<BuildTarget, BuildRule> buildRuleIndex = Maps.newHashMap();
    buildRuleIndex.put(javaLibrary1.getBuildTarget(), javaLibrary1);
    buildRuleIndex.put(javaLibrary2.getBuildTarget(), javaLibrary2);
    buildRuleIndex.put(javaLibrary3.getBuildTarget(), javaLibrary3);
    buildRuleIndex.put(javaLibrary4.getBuildTarget(), javaLibrary4);
    BuildRuleResolver ruleResolver = new BuildRuleResolver(buildRuleIndex);

    Keystore keystore = (Keystore) KeystoreBuilder.createBuilder(
        new BuildTarget("//keystores", "debug"))
        .setProperties(Paths.get("keystores/debug.properties"))
        .setStore(Paths.get("keystores/debug.keystore"))
        .build(ruleResolver)
        .getBuildable();

    // AndroidBinaryRule transitively depends on :lib1, :lib2, and :lib3.
    AndroidBinaryBuilder.Builder androidBinaryBuilder = AndroidBinaryBuilder.newBuilder();
    androidBinaryBuilder
        .setBuildTarget(new BuildTarget("//apps", "app"))
        .setManifest(new TestSourcePath("apps/AndroidManifest.xml"))
        .setTarget("Google Inc.:Google APIs:18")
        .setKeystore(keystore)
        .setOriginalDeps(ImmutableSortedSet.<BuildRule>of(
                javaLibrary2,
                javaLibrary3));
    BuildRule androidBinaryRule = androidBinaryBuilder.build(ruleResolver);
    AndroidBinary androidBinary = (AndroidBinary) androidBinaryRule.getBuildable();
    androidBinary.getEnhancedDeps(ruleResolver);

    // AndroidInstrumentationApk transitively depends on :lib1, :lib2, :lib3, and :lib4.
    AndroidInstrumentationApk androidInstrumentationApk = new AndroidInstrumentationApk(
        new FakeBuildRuleParams(new BuildTarget("//apps", "instrumentation")),
        new TestSourcePath("apps/InstrumentationAndroidManifest.xml"),
        androidBinary,
        androidBinaryRule,
        ImmutableSortedSet.<BuildRule>of(
            javaLibrary2,
            javaLibrary4));
    androidInstrumentationApk.getEnhancedDeps(ruleResolver);

    assertEquals(
        "//apps:app should have three JAR files to dex.",
        ImmutableSet.of(
            Paths.get("buck-out/gen/java/com/example/lib1.jar"),
            Paths.get("buck-out/gen/java/com/example/lib2.jar"),
            Paths.get("buck-out/gen/java/com/example/lib3.jar")),
        androidBinary.findDexTransitiveDependencies().classpathEntriesToDex);
    assertEquals(
        "//apps:instrumentation should have one JAR file to dex.",
        ImmutableSet.of(Paths.get("buck-out/gen/java/com/example/lib4.jar")),
        androidInstrumentationApk.findDexTransitiveDependencies().classpathEntriesToDex);
  }
}
